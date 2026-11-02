package com.companion.ainotifications.dictation

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Deterministic trace consumed by the PC parity check; no network or microphone. */
class MobilePcParityTest {
    @Test fun queuedLongThinkingPauseProducesReferenceTrace() = runBlocking {
        val detector = object : SpeechDetector {
            override val frameSize = 512
            override val name = "Deterministic parity detector"
            override fun probability(frame: FloatArray) = if (frame[0] == 0f) 0f else 1f
            override fun close() = Unit
        }
        val audio = StreamingAudio(detector = detector)
        for (id in 1..30) {
            val data = ByteArray(8000)
            for (i in data.indices step 2) data[i] = id.toByte()
            audio.append(data)
            if (id == 10 || id == 20) audio.append(ByteArray(640000))
        }
        audio.finish()
        val ranges = ArrayList<Pair<Int, Int>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val worker = StreamingTranscriber(scope, audio, { bytes, _ ->
                val ids = ArrayList<Int>()
                for (i in bytes.indices step 2) {
                    val id = (bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)
                    if (id != 0 && ids.lastOrNull() != id) ids.add(id)
                }
                ranges.add((bytes[0].toInt() and 255) to bytes.size / 2)
                ids.joinToString(" ") { "word$it" }
            }, intervalMs = 1)
            val text = withTimeout(5000) { worker.result.await() }
            assertEquals((1..30).joinToString(" ") { "word$it" }, text)
            println("MOBILE_PARITY_LONG_PAUSES=" + buildJsonObject {
                put("text", text)
                putJsonArray("ranges") { ranges.forEach { (first, size) -> addJsonArray { add(first); add(size) } } }
            })
        } finally { scope.cancel() }
    }

    @Test fun queuedPausesProduceReferenceTrace() = runBlocking {
        val detector = object : SpeechDetector {
            override val frameSize = 512
            override val name = "Deterministic parity detector"
            override fun probability(frame: FloatArray) = if (frame[0] == 0f) 0f else 1f
            override fun close() = Unit
        }
        val audio = StreamingAudio(detector = detector)
        for (id in 1..30) {
            val data = ByteArray(8000)
            for (i in data.indices step 2) data[i] = id.toByte()
            audio.append(data)
            if (id == 10 || id == 20) audio.append(ByteArray(12800))
        }
        audio.finish()
        val ranges = ArrayList<Pair<Int, Int>>()
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val worker = StreamingTranscriber(workerScope, audio, { bytes, _ ->
                val ids = ArrayList<Int>()
                for (i in bytes.indices step 2) {
                    val id = (bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)
                    if (id != 0 && ids.lastOrNull() != id) ids.add(id)
                }
                ranges.add((bytes[0].toInt() and 255) to bytes.size / 2)
                ids.joinToString(" ") { "word$it" }
            }, intervalMs = 1)
            val text = withTimeout(5000) { worker.result.await() }
            assertEquals((1..30).joinToString(" ") { "word$it" }, text)
            println("MOBILE_PARITY_PAUSES=" + buildJsonObject {
                put("text", text)
                putJsonArray("ranges") { ranges.forEach { (first, size) ->
                    addJsonArray { add(first); add(size) }
                } }
            })
        } finally { workerScope.cancel() }
    }

    @Test fun queuedContinuousAudioProducesReferenceTrace() = runBlocking {
        val detector = object : SpeechDetector {
            override val frameSize = 512
            override val name = "Deterministic parity detector"
            override fun probability(frame: FloatArray) = 1f
            override fun close() = Unit
        }
        val audio = StreamingAudio(detector = detector)
        for (id in 1..80) {
            val pcm = ByteArray(8000)
            for (i in pcm.indices step 2) {
                pcm[i] = id.toByte()
                pcm[i + 1] = 0
            }
            audio.append(pcm)
        }
        audio.finish()
        val ranges = ArrayList<Pair<Int, Int>>()
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val worker = StreamingTranscriber(workerScope, audio, { bytes, _ ->
                val ids = ArrayList<Int>()
                for (i in bytes.indices step 2) {
                    val id = (bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)
                    if (ids.lastOrNull() != id) ids.add(id)
                }
                ranges.add(ids.first() to bytes.size / 2)
                ids.joinToString(" ") { "word$it" }
            }, intervalMs = 1)
            val text = withTimeout(5000) { worker.result.await() }
            assertEquals((1..80).joinToString(" ") { "word$it" }, text)
            println("MOBILE_PARITY=" + buildJsonObject {
                put("text", text)
                putJsonArray("ranges") { ranges.forEach { (first, size) ->
                    addJsonArray { add(first); add(size) }
                } }
            })
        } finally { workerScope.cancel() }
    }
}
