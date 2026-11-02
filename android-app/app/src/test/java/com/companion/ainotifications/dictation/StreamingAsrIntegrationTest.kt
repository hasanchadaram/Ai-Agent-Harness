package com.companion.ainotifications.dictation

import kotlinx.coroutines.*
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** Opt-in replay against the PC's real ASR. No microphone or phone is used. */
class StreamingAsrIntegrationTest {
    @Test fun replaySyntheticSpeechThroughTheRealStreamingWorker() = runBlocking {
        val path = System.getenv("DICTATION_LIVE_ASR_WAV")
        assumeTrue("Set DICTATION_LIVE_ASR_WAV to a synthetic 16 kHz mono PCM16 WAV to opt in", !path.isNullOrBlank())
        val pcm = readPcm(File(checkNotNull(path)))
        val fast = System.getenv("DICTATION_LIVE_ASR_FAST_REPLAY") == "true"
        val showSynthetic = System.getenv("DICTATION_LIVE_ASR_SYNTHETIC_TEXT") == "true"
        val client = DictationClient(OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
            }).callTimeout(60, TimeUnit.SECONDS).build())
        // Only this injected test DNS resolves a private-shaped hostname to loopback.
        val endpoint = ModelEndpoint("http://live-asr.tailtest.ts.net:8000/v1", "Qwen/Qwen3-ASR-1.7B")
        assertTrue(client.test(endpoint).startsWith("Connected:"))
        val audio = StreamingAudio()
        val progress = ArrayList<StreamingProgress>()
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val worker = StreamingTranscriber(workerScope, audio,
                { data, allowEmpty -> client.transcribe(endpoint, data, allowEmpty).also {
                    if (showSynthetic) println("SYNTHETIC WINDOW ${data.size / 32} ms: $it")
                } }, inspect = true,
                onProgress = { synchronized(progress) { progress.add(it) } })
            var offset = 0
            while (offset < pcm.size) {
                val end = minOf(offset + 3200, pcm.size)
                audio.append(pcm.copyOfRange(offset, end))
                offset = end
                if (!fast) delay(100) // Fast mode deliberately queues a complete backlog.
            }
            val stopped = System.nanoTime()
            audio.finish()
            val result = withTimeout(60_000) { worker.result.await() }
            val stopMs = (System.nanoTime() - stopped) / 1_000_000
            val states = synchronized(progress) { progress.toList() }
            val done = states.last()
            assertTrue("Expected several successful audio requests", done.completed >= 3)
            assertTrue("Expected a completed transcript at Stop", done.sections >= 1)
            if (!fast) assertTrue("Expected two-pass word agreement", states.any { state -> state.history.any {
                it.kind.startsWith("Provisional") && it.stable.isNotBlank()
            } })
            assertTrue("Expected the first clause to survive", result.contains("ticket", ignoreCase = true))
            assertTrue("Expected the second clause to survive", result.contains("notebook", ignoreCase = true))
            if (showSynthetic) println("SYNTHETIC ASSEMBLED: $result")
            if (pcm.size / 32 >= 60_000) {
                for (marker in listOf("apple", "banana", "carrot", "flower", "garden", "jacket", "kitchen", "lemon")) {
                    val pattern = if (marker == "banana") "bananas?" else marker
                    assertEquals("Synthetic section marker $marker", 1,
                        Regex("\\b$pattern\\b", RegexOption.IGNORE_CASE).findAll(result).count())
                }
                for (word in listOf("notebook", "meeting")) assertEquals(8,
                    Regex("\\b$word\\b", RegexOption.IGNORE_CASE).findAll(result).count())
            }
            assertEquals(pcm.size / 2, done.finalizedSamples)
            assertTrue("Every request must be bounded", done.maxRequestSamples <= 128000)
            println("Live ASR replay: requests=${done.requests}, sections=${done.sections}, failures=${done.failures}, " +
                "audioMs=${pcm.size / 32}, stopMs=$stopMs, stableBeforeStop=true")
            done.history.forEach { update ->
                println("#${update.number} ${update.rangeLabel} ${update.kind} durationMs=${update.durationMs} " +
                    "stableWords=${update.stable.split(' ').count { it.isNotBlank() }}")
            }
        } finally { workerScope.cancel() }
    }

    private fun readPcm(file: File): ByteArray {
        val bytes = file.readBytes()
        require(bytes.size >= 44 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE")
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var formatChecked = false
        while (offset + 8 <= bytes.size) {
            val name = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = input.getInt(offset + 4)
            require(size >= 0 && offset.toLong() + 8 + size <= bytes.size)
            val start = offset + 8
            if (name == "fmt ") {
                require(size >= 16 && input.getShort(start).toInt() == 1 &&
                    input.getShort(start + 2).toInt() == 1 && input.getInt(start + 4) == 16000 &&
                    input.getShort(start + 14).toInt() == 16) { "Use 16 kHz mono PCM16 WAV." }
                formatChecked = true
            }
            if (name == "data") {
                require(formatChecked && size > 0 && size % 2 == 0)
                return bytes.copyOfRange(start, start + size)
            }
            offset = start + size + size % 2
        }
        error("WAV has no PCM data.")
    }
}
