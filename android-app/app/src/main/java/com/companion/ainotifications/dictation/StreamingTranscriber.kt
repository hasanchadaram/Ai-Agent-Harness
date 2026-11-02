package com.companion.ainotifications.dictation

import kotlinx.coroutines.*
import java.io.IOException

data class AsrUpdate(
    val number: Int, val startSample: Int, val endSample: Int, val kind: String,
    val durationMs: Long, val stable: String = "", val tentative: String = "",
    val audioSamples: Int = endSample - startSample
) {
    val rangeLabel: String get() = "${startSample / 16}–${endSample / 16} ms · audio ${audioSamples / 16} ms"
}

data class StreamingProgress(
    val requests: Int = 0, val completed: Int = 0, val failures: Int = 0, val reused: Int = 0,
    val sections: Int = 0, val finalizedSamples: Int = 0, val capturedSamples: Int = 0,
    val stable: String = "", val tentative: String = "", val waitingForPause: Boolean = false,
    val history: List<AsrUpdate> = emptyList(),
    val windowStartSample: Int = 0, val rolls: Int = 0, val detectedPauses: Int = 0,
    val detector: String = "", val speechProbability: Float = 0f, val overlapping: Boolean = false,
    val maxRequestSamples: Int = 0
)

/** Serial bounded-window ASR; a missed pause never creates a full-recording request. */
class StreamingTranscriber(
    scope: CoroutineScope,
    private val audio: StreamingAudio,
    private val transcribe: suspend (ByteArray, Boolean) -> String,
    private val inspect: Boolean = false,
    private val intervalMs: Long = 1_000,
    private val minimumSamples: Int = 32_000,
    private val speculativeLimitSamples: Int = 128_000,
    private val backgroundTimeoutMs: Long = 4_000,
    private val retryDelayMs: Long = 1_500,
    private val onProgress: (StreamingProgress) -> Unit = {}
) {
    private data class Bridge(val text: String, val oldStart: Int, val widened: Boolean = false)
    private var progress = StreamingProgress()
    private val context = RecognitionAudio(audio)
    private val agreement = LocalAgreement2()
    private val sections = ArrayList<String>()
    private var start = 0
    private var resolved = 0
    private var bridge: Bridge? = null
    private var pendingPrefix = ""
    private val overlap = minOf(48_000, speculativeLimitSamples / 2)
    val result: Deferred<String> = scope.async(Dispatchers.Default) { run() }

    fun cancel() { result.cancel() }

    private fun publish(stable: String = agreement.stable, tentative: String = agreement.tentative) {
        val position = audio.position()
        progress = progress.copy(sections = sections.size, finalizedSamples = context.rawOffset(resolved),
            capturedSamples = position.samples, windowStartSample = context.rawOffset(start),
            stable = (sections + pendingPrefix + stable).filter { it.isNotBlank() }.joinToString(" "), tentative = tentative,
            detectedPauses = position.pauses, detector = position.detector,
            speechProbability = position.probability, overlapping = bridge != null)
        onProgress(progress)
    }

    private suspend fun run(): String {
        var lastRequestedEnd = -1
        var lastText = ""
        while (true) {
            currentCoroutineContext().ensureActive()
            val position = context.position()
            val limit = minOf(position.samples, start + speculativeLimitSamples)
            val end = limit
            val final = position.finished && end == position.samples
            val checkpoint = !final && end - start == speculativeLimitSamples
            if (position.finished && start == position.samples) break
            if (bridge == null && end > start && !context.hasSpeech(start, end) &&
                (end < position.samples || position.pendingSamples == 0)) {
                // Retain pre-roll during recording; speech detection can lag
                // the first phoneme. Quiet audio is not a word-timestamp guess.
                start = if (position.finished) end else maxOf(start, end - 4_800)
                resolved = start
                lastRequestedEnd = -1; lastText = ""
                agreement.reset(); publish()
                pendingPrefix = ""
                if (!position.finished) audio.updates.receive()
                continue
            }
            if (end <= start || (!final && !checkpoint && (end - start < minimumSamples || end == lastRequestedEnd))) {
                publish(); audio.updates.receive(); continue
            }
            if (final && bridge == null && end == lastRequestedEnd && lastText.isNotBlank()) {
                sections.add(lastText.trim())
                progress = progress.copy(reused = progress.reused + 1)
                record(progress.requests, end, "Reused exact audio", System.nanoTime(), lastText.trim(), "")
                start = end; resolved = end; lastRequestedEnd = -1; lastText = ""
                agreement.reset(); publish(); continue
            }
            val number = progress.requests + 1
            progress = progress.copy(requests = number, maxRequestSamples = maxOf(progress.maxRequestSamples, end - start))
            publish()
            val began = System.nanoTime()
            try {
                val pcm = context.snapshot(start, end)
                var text = if (position.finished) transcribe(pcm, true)
                    else withTimeout(backgroundTimeoutMs) { transcribe(pcm, true) }
                currentCoroutineContext().ensureActive()
                require((!final && !checkpoint && bridge == null) || text.isNotBlank() || !context.hasSpeech(start, end)) {
                    "No speech was recognized in an audio section. Please try again."
                }
                progress = progress.copy(completed = progress.completed + 1)
                var joined = false
                var widened = false
                bridge?.let { pending ->
                    val join = OverlapAgreement.join(pending.text, text)
                    if (join == null) {
                        record(number, end, "Checking wider overlap", began, "", text)
                        if (pending.widened) throw IOException(
                            "ASR could not reliably join overlapping audio. Please try again.")
                        start = pending.oldStart + (start - pending.oldStart) / 2
                        bridge = pending.copy(widened = true)
                        pendingPrefix = ""
                        widened = true
                        agreement.reset(); lastRequestedEnd = -1; lastText = ""
                        publish(); return@let
                    }
                    pendingPrefix = join.committed
                    text = join.current
                    resolved = start
                    joined = true
                    // Both hypotheses can revise their shared boundary. Keep
                    // this prefix provisional until closing/rolling this window.
                    if (final || checkpoint) {
                        if (pendingPrefix.isNotBlank()) sections.add(pendingPrefix)
                        pendingPrefix = ""
                        bridge = null
                    }
                }
                if (widened) continue
                when {
                    final -> {
                        if (text.isNotBlank()) sections.add(text.trim())
                        record(number, end, if (joined) "Final section · overlap joined" else "Final section", began, text.trim(), "")
                        start = end; resolved = end; lastRequestedEnd = -1; lastText = ""
                        agreement.reset()
                        pendingPrefix = ""
                    }
                    checkpoint -> {
                        record(number, end, if (joined) "Window checkpoint · overlap joined" else "Window checkpoint", began, agreement.stable, text)
                        bridge = Bridge(text, start)
                        start = end - overlap
                        progress = progress.copy(rolls = progress.rolls + 1)
                        agreement.reset(); lastRequestedEnd = -1; lastText = ""
                        pendingPrefix = ""
                    }
                    else -> {
                        agreement.update(text)
                        record(number, end, if (joined) "Provisional · overlap joined" else "Provisional", began,
                            agreement.stable, agreement.tentative)
                        lastRequestedEnd = end; lastText = text
                    }
                }
                publish()
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                currentCoroutineContext().ensureActive()
                progress = progress.copy(failures = progress.failures + 1)
                record(number, end, "Failed; audio retained", began, "", "")
                agreement.reset(); lastRequestedEnd = -1; lastText = ""
                publish()
                if (position.finished || (bridge?.widened == true && error is IOException)) throw error
                pace(maxOf(intervalMs, retryDelayMs))
            }
            if (!audio.position().finished) pace(intervalMs)
        }
        val text = sections.joinToString(" ").trim()
        require(text.isNotBlank()) { "No speech was recognized. Please try again." }
        publish("", "")
        return text
    }

    private suspend fun pace(milliseconds: Long) {
        withTimeoutOrNull(milliseconds) { while (!audio.position().finished) audio.updates.receive() }
    }

    private fun record(number: Int, end: Int, kind: String, began: Long, stable: String, tentative: String) {
        if (inspect) progress = progress.copy(history = (progress.history + AsrUpdate(number, context.rawOffset(start), context.rawOffset(end),
            kind, (System.nanoTime() - began) / 1_000_000, stable, tentative, end - start)).takeLast(24))
    }
}
