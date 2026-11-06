package com.companion.ainotifications.dictation

import kotlinx.coroutines.*
import kotlin.math.roundToInt

interface TranscriptionWorker {
    val result: Deferred<String>
    fun cancel() { result.cancel() }
}

data class TimedWord(val text: String, val start: Double, val end: Double)
data class AlignedTranscript(val text: String, val words: List<TimedWord> = emptyList())

data class GrowingConfig(
    val initial: Double = 4.0, val growth: Double = 2.0,
    val softLimit: Double = 12.0, val hardLimit: Double = 20.0,
    val context: Double = 1.5, val tail: Double = 0.8,
    val holdWords: Int = 2, val minimumAdvance: Double = 2.0
) {
    init {
        require(listOf(initial, growth, softLimit, hardLimit, context, tail, minimumAdvance).all { it.isFinite() && it > 0 } &&
            initial <= softLimit && softLimit <= hardLimit && holdWords >= 1 && context < minimumAdvance)
    }
    internal fun samples(seconds: Double) = (seconds * 16000).roundToInt()
}

/** Separate opt-in strategy. Prefix agreement authorizes a timestamp cut; fixed hops never do. */
class GrowingWindowTranscriber(
    scope: CoroutineScope,
    private val audio: StreamingAudio,
    private val recognize: suspend (ByteArray, Boolean) -> AlignedTranscript,
    private val config: GrowingConfig = GrowingConfig(),
    private val inspect: Boolean = false,
    private val intervalMs: Long = 50,
    private val backgroundTimeoutMs: Long = 8_000,
    private val retryDelayMs: Long = 1_500,
    private val optimizeFinish: Boolean = true,
    private val onProgress: (StreamingProgress) -> Unit = {}
) : TranscriptionWorker {
    private val context = RecognitionAudio(audio)
    private var progress = StreamingProgress(strategy = "growing")
    private val sections = ArrayList<String>()
    private var start = 0
    private var resolved = 0
    private var stable = ""
    private var tentative = ""
    override val result = scope.async(Dispatchers.Default) { run() }

    private fun publish() {
        val raw = audio.position()
        progress = progress.copy(capturedSamples = raw.samples, finalizedSamples = context.rawOffset(resolved),
            windowStartSample = context.rawOffset(start), sections = sections.size,
            stable = (sections + stable).filter { it.isNotBlank() }.joinToString(" "), tentative = tentative,
            detectedPauses = raw.pauses, detector = raw.detector, speechProbability = raw.probability)
        onProgress(progress)
    }

    private fun clearPreview() { stable = ""; tentative = "" }

    private suspend fun run(): String {
        var previous: List<TimedWord>? = null
        var lastEnd = -1
        var cachedStart = -1
        var cachedEnd = -1
        var cachedPcm: ByteArray? = null
        var cachedResult: AlignedTranscript? = null
        val initial = config.samples(config.initial)
        val growth = config.samples(config.growth)
        val soft = config.samples(config.softLimit)
        val hard = config.samples(config.hardLimit)
        while (true) {
            currentCoroutineContext().ensureActive()
            if (progress.recoveryRequired) {
                publish()
                if (audio.position().finished) return recoverRecording()
                audio.updates.receive()
                continue
            }
            val position = context.position()
            if (position.finished && start >= position.samples) break
            val target = if (optimizeFinish && position.finished && position.samples - start <= hard) position.samples
                else if (lastEnd < 0) start + initial else lastEnd + growth
            val end = minOf(position.samples, start + hard, target)
            val final = position.finished && end == position.samples
            if (end <= start || (!position.finished &&
                    (end - start < initial || (lastEnd >= 0 && end - lastEnd < growth)))) {
                publish(); audio.updates.receive(); continue
            }
            if (!context.hasSpeech(start, end)) {
                if (final) { start = end; resolved = end; break }
                lastEnd = end
                if (end - start >= hard) progress = progress.copy(recoveryRequired = true)
                else audio.updates.receive()
                continue
            }
            val pcm = context.snapshot(start, end)
            val reusable = cachedResult?.takeIf { optimizeFinish && final && cachedStart == start && cachedEnd == end &&
                it.text.isNotBlank() && cachedPcm?.contentEquals(pcm) == true }
            progress = if (reusable != null) progress.copy(reused = progress.reused + 1)
                else progress.copy(requests = progress.requests + 1,
                    maxRequestSamples = maxOf(progress.maxRequestSamples, end - start))
            publish()
            val began = System.nanoTime()
            try {
                val timestamps = !(optimizeFinish && final && start == 0 && sections.isEmpty())
                val response = reusable ?: if (position.finished) recognize(pcm, timestamps)
                    else withTimeout(backgroundTimeoutMs) { recognize(pcm, timestamps) }
                currentCoroutineContext().ensureActive()
                if (reusable == null) {
                    progress = progress.copy(completed = progress.completed + 1)
                    cachedStart = start; cachedEnd = end; cachedPcm = pcm; cachedResult = response
                }
                if (optimizeFinish && final && start == 0 && sections.isEmpty() && response.text.isNotBlank()) {
                    sections.add(response.text.trim())
                    record(end, if (reusable != null) "Reused exact audio" else "Final complete text", began)
                    start = end; resolved = end; clearPreview(); break
                }
                val aligned = validatedWords(response, (end - start) / 16000.0)
                if (aligned == null || (aligned.isEmpty() && context.hasSpeech(start, end))) {
                    previous = null; clearPreview()
                    if (final && start == 0 && sections.isEmpty() && response.text.isNotBlank()) {
                        sections.add(response.text.trim()); start = end; resolved = end; break
                    }
                    if (final || end - start >= hard) progress = progress.copy(recoveryRequired = true)
                    lastEnd = end
                    record(end, "Alignment unavailable; retaining audio", began)
                    publish(); continue
                }
                val words = aligned.filter { start + (it.start + it.end) * 8000 > resolved }
                if (final) {
                    words.joinToString(" ") { it.text }.trim().takeIf { it.isNotBlank() }?.let { sections.add(it) }
                    record(end, if (reusable != null) "Reused exact audio" else "Final timestamp section", began)
                    start = end; resolved = end; clearPreview(); break
                }
                var count = 0
                val old = previous
                if (old != null) while (count < minOf(old.size, maxOf(0, words.size - config.holdWords))) {
                    if (wordKey(old[count].text) != wordKey(words[count].text)) break
                    if (end - start - config.samples(words[count].end) < config.samples(config.tail)) break
                    count++
                }
                while (count > 0 && (words[count - 1].end <= words[count - 1].start ||
                        (count < words.size && (words[count].start + words[count].end) / 2 <= words[count - 1].end))) count--
                stable = words.take(count).joinToString(" ") { it.text }
                tentative = words.drop(count).joinToString(" ") { it.text }
                if (count > 0 && end - start >= soft) {
                    val frontier = start + config.samples(words[count - 1].end)
                    if (frontier - resolved >= config.samples(config.minimumAdvance)) {
                        record(end, "Agreed prefix; timestamp advance", began)
                        sections.add(stable); resolved = frontier
                        start = maxOf(start, frontier - config.samples(config.context))
                        progress = progress.copy(rolls = progress.rolls + 1)
                        previous = null; lastEnd = -1; clearPreview(); publish(); continue
                    }
                }
                previous = words; lastEnd = end
                record(end, "Growing from the same start", began)
                if (end - start >= hard) { progress = progress.copy(recoveryRequired = true); clearPreview() }
                publish()
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                currentCoroutineContext().ensureActive()
                progress = progress.copy(failures = progress.failures + 1)
                previous = null; lastEnd = -1; clearPreview()
                record(end, "Failed; audio retained", began); publish()
                if (position.finished) throw error
                pace(retryDelayMs)
            }
            if (!audio.position().finished) pace(intervalMs)
        }
        val text = sections.joinToString(" ").trim()
        require(text.isNotBlank()) { "No speech was recognized. Please try again." }
        publish()
        return text
    }

    private suspend fun recoverRecording(): String {
        val position = audio.position()
        check(position.finished)
        progress = progress.copy(requests = progress.requests + 1, recoveryRequests = progress.recoveryRequests + 1,
            recoverySamples = position.samples)
        publish()
        val began = System.nanoTime()
        val text = try {
            val response = recognize(audio.snapshot(0, position.samples), false)
            currentCoroutineContext().ensureActive()
            response.text.trim().also { require(it.isNotBlank()) { "Full-recording recovery returned no text." } }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            progress = progress.copy(failures = progress.failures + 1); publish(); throw error
        }
        sections.clear(); sections.add(text); clearPreview()
        start = context.position().samples; resolved = start
        progress = progress.copy(completed = progress.completed + 1, recoveryRequired = false, recovered = true)
        if (inspect) progress = progress.copy(history = (progress.history + AsrUpdate(progress.requests, 0, position.samples,
            "Full recording recovery", (System.nanoTime() - began) / 1_000_000, text, "", position.samples)).takeLast(24))
        publish()
        return text
    }

    private fun record(end: Int, kind: String, began: Long) {
        if (inspect) progress = progress.copy(history = (progress.history + AsrUpdate(progress.requests,
            context.rawOffset(start), context.rawOffset(end), kind, (System.nanoTime() - began) / 1_000_000,
            stable, tentative, end - start)).takeLast(24))
    }
    private suspend fun pace(milliseconds: Long) {
        withTimeoutOrNull(milliseconds) { while (!audio.position().finished) audio.updates.receive() }
    }

    companion object {
        internal fun wordKey(text: String) = text.lowercase().filter { it.isLetterOrDigit() }
        internal fun validatedWords(result: AlignedTranscript, duration: Double): List<TimedWord>? {
            if (wordKey(result.text) != result.words.joinToString("") { wordKey(it.text) }) return null
            var previousStart = 0.0
            var previousEnd = 0.0
            for (word in result.words) {
                if (wordKey(word.text).isEmpty() || !word.start.isFinite() || !word.end.isFinite() ||
                    word.start < previousStart || word.end < previousEnd || word.end < word.start || word.end > duration + .05) return null
                previousStart = word.start; previousEnd = word.end
            }
            if (result.words.isNotEmpty() && result.words.none { it.end > it.start }) return null
            return result.words
        }
    }
}
