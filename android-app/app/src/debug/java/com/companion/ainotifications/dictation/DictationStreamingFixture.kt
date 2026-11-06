package com.companion.ainotifications.dictation

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Shell-protected replay: synthetic audio only, no microphone, clipboard or insertion. */
class DictationStreamingFixture : Activity() {
    private val checks = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val label = TextView(this).apply {
            text = "Testing background ASR using synthetic speech. Microphone is off."
            setPadding(24, 64, 24, 24)
        }
        setContentView(label)
        checks.launch {
            val report = File(filesDir, "dictation-streaming-check.txt")
            report.writeText("Synthetic speech replay; no microphone or editor insertion\n")
            var worker: TranscriptionWorker? = null
            var detector: SpeechDetector? = null
            val latest = AtomicReference(StreamingProgress())
            try {
                check(!DictationController.get(this@DictationStreamingFixture).state.value.busy) {
                    "Finish the current dictation before running this check."
                }
                val pcm = readPcm(File(getExternalFilesDir(null), "synthetic-streaming-check.wav"))
                val hesitation = intent.getStringExtra("scenario") == "hesitation"
                val growing = intent.getStringExtra("strategy") == "growing"
                val client = DictationClient()
                val endpoint = DictationSettings(this@DictationStreamingFixture).asr
                client.test(endpoint)
                val agreedBeforeStop = AtomicBoolean(false)
                withContext(Dispatchers.IO) { detector = SileroSpeechDetector(this@DictationStreamingFixture) }
                val forceRolling = intent.getBooleanExtra("force_rolling", false)
                val audio = StreamingAudio(silenceMs = if (forceRolling) 300_000 else 320, detector = detector)
                val publishProgress: (StreamingProgress) -> Unit = {
                        latest.set(it)
                        if (!audio.position().finished && it.history.any { update ->
                            (update.kind.startsWith("Provisional") || update.kind == "Growing from the same start" ||
                                update.kind == "Agreed prefix; timestamp advance") && update.stable.isNotBlank()
                        }) agreedBeforeStop.set(true)
                    }
                worker = if (growing) GrowingWindowTranscriber(checks, audio,
                    { data, timestamps -> client.transcribeAligned(endpoint, data, timestamps) },
                    inspect = true, onProgress = publishProgress)
                else StreamingTranscriber(checks, audio, { data, empty -> client.transcribe(endpoint, data, empty) },
                    inspect = true, onProgress = publishProgress)
                var offset = 0
                while (offset < pcm.size) {
                    val end = minOf(offset + 3200, pcm.size)
                    audio.append(pcm.copyOfRange(offset, end))
                    offset = end
                    if (offset % 320000 == 0) {
                        val now = latest.get()
                        val line = "replayedMs=${offset / 32} requests=${now.requests} pauses=${now.detectedPauses} rolls=${now.rolls}\n"
                        report.appendText(line)
                        label.text = "Microphone off; synthetic replay\n$line"
                    }
                    delay(100)
                }
                val stopped = android.os.SystemClock.elapsedRealtime()
                audio.finish()
                val text = withTimeout(60_000) { worker.result.await() }
                val stopMs = android.os.SystemClock.elapsedRealtime() - stopped
                val progress = latest.get()
                if (intent.getBooleanExtra("include_synthetic_text", false)) {
                    report.appendText("EXPLICIT SYNTHETIC TEXT DIAGNOSTIC\n$text\nEND SYNTHETIC TEXT\n")
                }
                check(progress.completed >= if (growing) 2 else 3) { "Expected multiple ASR responses." }
                check(progress.sections >= 1) { "Expected a completed transcript at Stop." }
                if (!growing || pcm.size >= 256000) check(agreedBeforeStop.get()) { "Expected agreement between updates while speaking." }
                if (hesitation) {
                    val normalized = text.lowercase().replace(Regex("[^a-z\\s]"), "").replace(Regex("\\s+"), " ").trim()
                    check(normalized == "because right now it is giving me some hindi words in the transcript output sometimes") {
                        "Hesitation replay changed or lost words."
                    }
                    check(Regex("[.!?]").findAll(text).count() <= 1) { "Hesitation became a sentence break." }
                    check(progress.detectedPauses >= 2) { "Expected real VAD hesitation detections." }
                } else check(text.contains("ticket", true) && text.contains("notebook", true)) { "Both synthetic clauses must survive." }
                check(progress.finalizedSamples == pcm.size / 2) { "Final sample coverage is incomplete." }
                check(progress.maxRequestSamples <= if (growing) 320000 else 128000) { "An ASR window exceeded its configured bound." }
                if (progress.recoveryRequests > 0) check(progress.recovered && progress.recoverySamples == pcm.size / 2) {
                    "Full-recording recovery did not cover all original samples."
                }
                if (pcm.size / 32 >= 60_000) {
                    for (marker in listOf("apple", "banana", "carrot", "flower", "garden", "jacket", "kitchen", "lemon")) {
                        // This checks section coverage, not exact WER. Qwen's
                        // bounded-window decode sometimes pluralizes this noun.
                        val pattern = if (marker == "banana") "bananas?" else Regex.escape(marker)
                        val occurrences = Regex("\\b$pattern\\b", RegexOption.IGNORE_CASE).findAll(text).count()
                        check(occurrences == 1) {
                            "Synthetic marker $marker occurs $occurrences times; expected once."
                        }
                    }
                    for (word in listOf("notebook", "meeting")) {
                        check(Regex("\\b$word\\b", RegexOption.IGNORE_CASE).findAll(text).count() == 8) {
                            "The repeated $word phrase must occur in all eight sections."
                        }
                    }
                    if (text.contains("bananas", true)) report.appendText("ASR variation: banana decoded as bananas; section coverage is intact\n")
                    if (forceRolling) check(progress.rolls >= 3) { "Continuous speech must roll the window." }
                    else check(progress.detectedPauses >= 6) { "Expected multiple speech-aware pauses despite background noise." }
                }
                report.appendText("PASS Multiple requests; agreement before Stop=${agreedBeforeStop.get()}\n" +
                    "PASS ASR windows respect strategy bounds; recovery coverage checked separately\n" +
                    "PASS Both clauses survive and all samples are covered\n" +
                    "strategy=${progress.strategy} requests=${progress.requests} sections=${progress.sections} failures=${progress.failures} " +
                    "audioMs=${pcm.size / 32} stopMs=$stopMs rolls=${progress.rolls} pauses=${progress.detectedPauses} " +
                    "maxRequestMs=${progress.maxRequestSamples / 16} detector=${progress.detector} " +
                    "recoveryRequests=${progress.recoveryRequests} recoveryMs=${progress.recoverySamples / 16} recovered=${progress.recovered}\n")
                progress.history.forEach {
                    report.appendText("#${it.number} ${it.rangeLabel} ${it.kind} requestMs=${it.durationMs} " +
                        "stableWords=${it.stable.split(' ').count { word -> word.isNotBlank() }}\n")
                }
                report.appendText("SUCCESS\n")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                report.appendText("FAIL ${error.javaClass.simpleName}: ${error.message}\n")
                val progress = latest.get()
                report.appendText("requests=${progress.requests} completed=${progress.completed} rolls=${progress.rolls} " +
                    "pauses=${progress.detectedPauses} samples=${progress.capturedSamples}\n")
                progress.history.forEach { report.appendText("#${it.number} ${it.rangeLabel} ${it.kind} requestMs=${it.durationMs}\n") }
            }
            finally { withContext(NonCancellable) { worker?.cancel(); worker?.result?.join(); detector?.close() } }
            label.text = report.readText()
        }
    }

    override fun onDestroy() { checks.cancel(); super.onDestroy() }

    private fun readPcm(file: File): ByteArray {
        val bytes = file.readBytes()
        require(bytes.size in 44..9_600_100 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "Use the synthetic PCM16 WAV fixture, at most five minutes." }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var checked = false
        while (offset + 8 <= bytes.size) {
            val name = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = input.getInt(offset + 4)
            require(size >= 0 && offset.toLong() + 8 + size <= bytes.size)
            val start = offset + 8
            if (name == "fmt ") {
                require(size >= 16 && input.getShort(start).toInt() == 1 && input.getShort(start + 2).toInt() == 1 &&
                    input.getInt(start + 4) == 16000 && input.getShort(start + 14).toInt() == 16)
                checked = true
            }
            if (name == "data") {
                require(checked && size > 0 && size % 2 == 0)
                return bytes.copyOfRange(start, start + size)
            }
            offset = start + size + size % 2
        }
        error("WAV contains no PCM data.")
    }
}
