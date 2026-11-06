package com.companion.ainotifications.dictation

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GrowingWindowTranscriberTest {
    private fun pcm(samples: Int) = ByteArray(samples * 2).apply {
        for (i in 0 until samples) {
            val value = i % 30000 + 1
            this[i * 2] = value.toByte(); this[i * 2 + 1] = (value shr 8).toByte()
        }
    }
    private fun w(text: String, start: Double, end: Double) = TimedWord(text, start, end)
    private fun r(vararg words: TimedWord) = AlignedTranscript(words.joinToString(" ") { it.text }, words.toList())
    private fun example() = listOf(
        r(w("I", .1, .4), w("am", .5, 1.2), w("try", 2.0, 2.9)),
        r(w("I", .1, .4), w("am", .5, 1.2), w("trying", 2.0, 3.0), w("to", 3.2, 3.5), w("get", 3.7, 4.0)),
        r(w("am", 0.0, .5), w("trying", 1.3, 2.3), w("to", 2.5, 2.9)),
        r(w("am", 0.0, .5), w("trying", 1.3, 2.3), w("to", 2.5, 2.9), w("get", 3.0, 3.3), w("there", 4.0, 4.7)),
        r(w("to", 0.0, .5), w("get", .6, 1.0), w("there", 1.2, 1.7), w("safely.", 2.0, 2.7)))
    private val exampleConfig = GrowingConfig(initial = 3.0, growth = 2.0, softLimit = 5.0, hardLimit = 10.0,
        context = .5, tail = .3, holdWords = 1, minimumAdvance = 1.0)

    private class Harness(config: GrowingConfig = GrowingConfig(), optimizeFinish: Boolean = false) : AutoCloseable {
        data class Call(val pcm: ByteArray, val timestamps: Boolean, val answer: CompletableDeferred<AlignedTranscript> = CompletableDeferred())
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val audio = StreamingAudio(detector = object : SpeechDetector {
            override val frameSize = 512
            override val name = "Test speech"
            override fun probability(frame: FloatArray) = 1f
            override fun close() = Unit
        })
        val calls = Channel<Call>(Channel.UNLIMITED)
        val progress = Channel<StreamingProgress>(Channel.UNLIMITED)
        val worker = GrowingWindowTranscriber(scope, audio, { pcm, timestamps ->
            val call = Call(pcm, timestamps); calls.send(call); call.answer.await()
        }, config = config, inspect = true, optimizeFinish = optimizeFinish, onProgress = { progress.trySend(it) })
        suspend fun next() = withTimeout(3000) { calls.receive() }
        suspend fun state(test: (StreamingProgress) -> Boolean): StreamingProgress = withTimeout(3000) {
            var value = progress.receive()
            while (!test(value)) value = progress.receive()
            value
        }
        suspend fun result() = withTimeout(3000) { worker.result.await() }
        override fun close() { scope.cancel() }
    }

    @Test fun repairsCutWordThenAdvancesByConfirmedWordTimeAndMatchesPythonTrace() = runBlocking {
        Harness(exampleConfig).use { h ->
            val raw = pcm(96000); h.audio.append(raw); h.audio.finish()
            val calls = mutableListOf<Harness.Call>()
            for (response in example()) {
                val call = h.next(); calls.add(call); assertTrue(call.timestamps); call.answer.complete(response)
            }
            val text = h.result()
            assertEquals("I am trying to get there safely.", text)
            assertArrayEquals(raw.copyOfRange(0, 96000), calls[0].pcm)
            assertArrayEquals(raw.copyOfRange(0, 160000), calls[1].pcm)
            assertArrayEquals(raw.copyOfRange(22400, 118400), calls[2].pcm)
            val done = h.state { it.finalizedSamples == 96000 }
            assertEquals(2, done.rolls); assertFalse(done.recovered)
            println("GROWING_PARITY=" + buildJsonObject {
                put("text", text)
                putJsonArray("ranges") { calls.forEach {
                    addJsonArray {
                        add((it.pcm[0].toInt() and 255) or ((it.pcm[1].toInt() and 255) shl 8))
                        add(it.pcm.size / 2)
                    }
                } }
            })
        }
    }

    @Test fun disagreementKeepsTheStartUntilFinalDecodeResolvesIt() = runBlocking {
        Harness(GrowingConfig(initial = 2.0, growth = 2.0, softLimit = 4.0, hardLimit = 10.0)).use { h ->
            val raw = pcm(128000); h.audio.append(raw); h.audio.finish()
            for ((seconds, word) in listOf(2 to "Alpha", 4 to "Beta", 6 to "Gamma", 8 to "Final.")) {
                val call = h.next(); assertArrayEquals(raw.copyOfRange(0, seconds * 32000), call.pcm)
                call.answer.complete(r(w(word, .1, 1.0)))
            }
            assertEquals("Final.", h.result())
            assertEquals(0, h.state { it.finalizedSamples == 128000 }.rolls)
        }
    }

    @Test fun hardLimitWaitsForStopAndFullRecoveryIncludesLaterSpeech() = runBlocking {
        Harness(GrowingConfig(initial = 2.0, growth = 2.0, softLimit = 4.0, hardLimit = 4.0)).use { h ->
            val raw = pcm(64000); h.audio.append(raw)
            h.next().answer.complete(r(w("Alpha", .1, 1.0)))
            h.next().answer.complete(r(w("Beta", .1, 1.0)))
            h.state { it.recoveryRequired }
            assertFalse(h.worker.result.isCompleted)
            assertNull(withTimeoutOrNull(100) { h.calls.receive() })
            val tail = pcm(16000); h.audio.append(tail); h.audio.finish()
            h.next().also {
                assertFalse(it.timestamps); assertArrayEquals(raw + tail, it.pcm)
                it.answer.complete(AlignedTranscript("Entire recording including later speech."))
            }
            assertEquals("Entire recording including later speech.", h.result())
            assertEquals(80000, h.state { it.recovered }.recoverySamples)
        }
    }

    @Test fun missingFinalAlignmentReplacesAllPriorCommitsWithFullRecovery() = runBlocking {
        Harness(exampleConfig).use { h ->
            val raw = pcm(96000); h.audio.append(raw); h.audio.finish()
            for (response in example().dropLast(1)) h.next().answer.complete(response)
            h.next().answer.complete(AlignedTranscript("Unaligned remaining text."))
            h.next().also {
                assertFalse(it.timestamps); assertArrayEquals(raw, it.pcm)
                it.answer.complete(AlignedTranscript("Authoritative original, including not."))
            }
            assertEquals("Authoritative original, including not.", h.result())
            assertEquals(1, h.state { it.recovered }.sections)
        }
    }

    @Test fun failedOrBlankRecoveryKeepsOriginalAudioAndDoesNotAcceptPartialText() = runBlocking {
        for (blank in listOf(false, true)) Harness(exampleConfig).use { h ->
            val raw = pcm(96000); h.audio.append(raw); h.audio.finish()
            for (response in example().dropLast(1)) h.next().answer.complete(response)
            h.next().answer.complete(AlignedTranscript("Unaligned."))
            val recovery = h.next()
            if (blank) recovery.answer.complete(AlignedTranscript(""))
            else recovery.answer.completeExceptionally(IOException("offline"))
            try { h.result(); fail("Must not return partial text") }
            catch (error: Exception) { if (blank) assertTrue(error is IllegalArgumentException) else assertTrue(error is IOException) }
            val failed = h.state { it.failures == 1 }
            assertEquals("I am trying to", failed.stable)
            assertTrue(failed.recoveryRequired); assertFalse(failed.recovered)
            assertArrayEquals(raw, h.audio.snapshot(0, 96000))
        }
    }

    @Test fun cancellationDuringAlignmentAcceptsNoLateResponse() = runBlocking {
        Harness().use { h ->
            h.audio.append(pcm(48000)); h.audio.finish()
            val call = h.next(); h.worker.cancel(); h.worker.result.join()
            call.answer.complete(AlignedTranscript("Must never appear."))
            assertTrue(h.worker.result.isCancelled)
            assertNull(withTimeoutOrNull(100) { h.calls.receive() })
        }
    }

    @Test fun shortFinalCommandDoesNotNeedTimestamps() = runBlocking {
        Harness(optimizeFinish = true).use { h ->
            h.audio.append(pcm(3200)); h.audio.finish()
            h.next().also { assertFalse(it.timestamps); it.answer.complete(AlignedTranscript("Yes.")) }
            assertEquals("Yes.", h.result())
        }
    }

    @Test fun stopFlushesTheWholePendingTailOnceAndPreservesCommaAndNegation() = runBlocking {
        Harness(optimizeFinish = true).use { h ->
            val raw = pcm(144000); h.audio.append(raw); h.audio.finish()
            val text = "Because right now, it is giving me some Hindi words. Do not remove this comma."
            h.next().also { assertArrayEquals(raw, it.pcm); assertFalse(it.timestamps); it.answer.complete(AlignedTranscript(text)) }
            assertEquals(text, h.result())
            assertEquals(1, h.state { it.finalizedSamples == 144000 }.requests)
            assertNull(withTimeoutOrNull(100) { h.calls.receive() })
        }
    }

    @Test fun stopReusesOnlyByteIdenticalSuccessfulAudio() = runBlocking {
        Harness(optimizeFinish = true).use { h ->
            val raw = pcm(64000); h.audio.append(raw)
            val first = h.next(); assertTrue(first.timestamps); assertArrayEquals(raw, first.pcm)
            h.audio.finish()
            first.answer.complete(r(w("Please,", .1, .4), w("do", .4, .6), w("not", .6, .8), w("stop.", .8, 1.2)))
            assertEquals("Please, do not stop.", h.result())
            val done = h.state { it.finalizedSamples == 64000 }
            assertEquals(1, done.requests); assertEquals(1, done.reused)
            assertNull(withTimeoutOrNull(100) { h.calls.receive() })
        }
    }

    @Test fun aShortNewTailRequiresAFreshResponse() = runBlocking {
        Harness(optimizeFinish = true).use { h ->
            val raw = pcm(64000); h.audio.append(raw)
            val first = h.next()
            val tail = pcm(100); h.audio.append(tail); h.audio.finish()
            first.answer.complete(r(w("Do", .1, .4), w("drop", .4, .8)))
            h.next().also {
                assertArrayEquals(raw + tail, it.pcm); assertFalse(it.timestamps)
                it.answer.complete(AlignedTranscript("Do not drop the tail."))
            }
            assertEquals("Do not drop the tail.", h.result())
            assertEquals(0, h.state { it.finalizedSamples == 64100 }.reused)
        }
    }

    @Test fun optimizedFinishAfterLiveCommitsStillAlignsTheRetainedContext() = runBlocking {
        Harness(exampleConfig, optimizeFinish = true).use { h ->
            h.audio.append(pcm(96000))
            val responses = example()
            for (response in responses.take(3)) h.next().answer.complete(response)
            val fourth = h.next(); h.audio.finish(); fourth.answer.complete(responses[3])
            h.next().also { assertTrue(it.timestamps); it.answer.complete(responses[4]) }
            assertEquals("I am trying to get there safely.", h.result())
            assertEquals(2, h.state { it.finalizedSamples == 96000 }.rolls)
        }
    }

    @Test fun collapsedWordAtTheBoundaryRemainsPendingInsteadOfBeingCutAway() = runBlocking {
        Harness(GrowingConfig(initial = 2.0, growth = 2.0, softLimit = 4.0, hardLimit = 6.0,
            context = .2, tail = .2, minimumAdvance = .3)).use { h ->
            h.audio.append(pcm(64000))
            val response = r(w("I", .1, .4), w("am", .4, 1.0), w("to", 1.0, 1.0), w("the", 1.0, 1.4), w("end", 1.4, 1.8))
            h.next().answer.complete(response)
            h.next().answer.complete(response)
            val rolled = h.state { it.rolls == 1 }
            assertEquals("I", rolled.stable)
            assertEquals(6400, rolled.finalizedSamples)
            h.worker.cancel(); h.worker.result.join()
        }
    }

    @Test fun cancellingRecoveryRejectsEvenACallbackThatSuppressesCancellation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val audio = StreamingAudio()
        audio.append(ByteArray(192000) { if (it % 2 == 0) 127 else 31 }); audio.finish()
        val answers = (example().dropLast(1) + AlignedTranscript("Unaligned.")).iterator()
        val entered = CompletableDeferred<Unit>()
        var latest = StreamingProgress()
        try {
            val worker = GrowingWindowTranscriber(scope, audio, { _, timestamps ->
                if (timestamps) answers.next()
                else {
                    entered.complete(Unit)
                    try { delay(30000); AlignedTranscript("Late.") }
                    catch (_: CancellationException) { AlignedTranscript("Late response must never appear.") }
                }
            }, config = exampleConfig, optimizeFinish = false, onProgress = { latest = it })
            withTimeout(3000) { entered.await() }
            worker.cancel(); worker.result.join()
            assertTrue(worker.result.isCancelled)
            assertFalse(latest.recovered)
            assertEquals("I am trying to", latest.stable)
        } finally { scope.cancel() }
    }

    @Test fun invalidOrIncompleteAlignmentCannotAuthorizeACutAndRepeatsRemainDistinct() {
        for (words in listOf(emptyList(), listOf(w("Please", .1, 1.0)), listOf(w("Please not", Double.NaN, 1.0)),
                listOf(w("Please not", -.1, 1.0)), listOf(w("Please not", .1, 100.0)))) {
            assertNull(GrowingWindowTranscriber.validatedWords(AlignedTranscript("Please not", words), 3.0))
        }
        assertEquals(2, GrowingWindowTranscriber.validatedWords(r(w("very", .1, 1.0), w("very", 1.0, 2.0)), 3.0)!!.size)
    }
}
