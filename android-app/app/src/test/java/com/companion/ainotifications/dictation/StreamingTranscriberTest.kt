package com.companion.ainotifications.dictation

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class StreamingTranscriberTest {
    @Test fun finalOverlapKeepsOneSentenceWithMidSentenceLowercase() = runBlocking {
        Harness(limit = 16000).use { h ->
            h.audio.append(speech(24000)); h.audio.finish()
            h.next().answer.complete("Because right now it is giving me some Hindi words")
            h.next().answer.complete("Some Hindi words in the transcript output sometimes.")
            assertEquals("Because right now it is giving me some Hindi words in the transcript output sometimes.", h.result())
        }
    }

    @Test fun overlapRepairsFreshWindowCapitalizationInsideTheSameSentence() {
        val some = checkNotNull(OverlapAgreement.join("Because right now it is giving me some Hindi words",
            "Some Hindi words in the transcript output sometimes."))
        assertEquals("Because right now it is giving me", some.committed)
        assertEquals("some Hindi words in the transcript output sometimes.", some.current)
        val inside = checkNotNull(OverlapAgreement.join("Some Hindi words in the transcript",
            "In the transcript output sometimes."))
        assertEquals("in the transcript output sometimes.", inside.current)
    }

    @Test fun overlapPreservesSentenceStartsNamesAcronymsQuotesAndPronounI() {
        for ((previous, current) in listOf(
            "End of sentence. Some Hindi words" to "Some Hindi words remain.",
            "Ask Alice about the transcript" to "Alice about the transcript output.",
            "Ask NASA about the transcript" to "NASA about the transcript output.",
            "Yesterday I checked the transcript" to "I checked the transcript output.",
            "He said \" In the transcript" to "In the transcript output."
        )) assertEquals(current, checkNotNull(OverlapAgreement.join(previous, current)).current)
    }

    @Test fun longThinkingPauseKeepsSpeechAndOriginalPcmWhileBoundingRecognitionAudio() = runBlocking {
        val audio = StreamingAudio()
        val original = speech(32000) + ByteArray(640000) + speech(8000, 2000)
        audio.append(original); audio.finish()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var done = StreamingProgress()
        var calls = 0
        try {
            val worker = StreamingTranscriber(scope, audio, { data, _ ->
                calls++
                assertTrue(data.size <= 256000)
                assertArrayEquals(speech(32000), data.copyOfRange(0, 64000))
                assertArrayEquals(speech(8000, 2000), data.takeLast(16000).toByteArray())
                "I paused while thinking and continued."
            }, onProgress = { done = it })
            assertEquals("I paused while thinking and continued.", withTimeout(3000) { worker.result.await() })
            assertEquals(1, calls)
            assertEquals(original.size / 2, done.finalizedSamples)
            assertArrayEquals(original, audio.snapshot(0, original.size / 2))
        } finally { scope.cancel() }
    }

    @Test fun shortCommandDoesNotDecodeOnAHesitationButFlushesAtStop() = runBlocking {
        val audio = StreamingAudio()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var calls = 0
        try {
            val worker = StreamingTranscriber(scope, audio, { _, _ -> calls++; "Yes." })
            audio.append(speech(12800) + ByteArray(10240))
            delay(50)
            assertEquals(0, calls)
            audio.finish()
            assertEquals("Yes.", withTimeout(1000) { worker.result.await() })
            assertEquals(1, calls)
        } finally { scope.cancel() }
    }

    private fun speech(samples: Int, amplitude: Int = 1000) = ByteArray(samples * 2).apply {
        for (i in 0 until samples) {
            this[i * 2] = amplitude.toByte()
            this[i * 2 + 1] = (amplitude shr 8).toByte()
        }
    }

    private class Harness(limit: Int = 192_000, timeout: Long = 4_000, interval: Long = 1) : AutoCloseable {
        data class Call(val pcm: ByteArray, val allowEmpty: Boolean, val answer: CompletableDeferred<String> = CompletableDeferred())
        val audio = StreamingAudio(silenceMs = 660)
        val calls = Channel<Call>(Channel.UNLIMITED)
        val updates = Channel<StreamingProgress>(Channel.UNLIMITED)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val worker = StreamingTranscriber(scope, audio, { pcm, allowEmpty ->
            val call = Call(pcm, allowEmpty)
            calls.send(call)
            call.answer.await()
        }, inspect = true, intervalMs = interval, speculativeLimitSamples = limit,
            minimumSamples = 12800,
            backgroundTimeoutMs = timeout, retryDelayMs = 1,
            onProgress = { updates.trySend(it) })
        suspend fun next() = withTimeout(3_000) { calls.receive() }
        suspend fun progress(test: (StreamingProgress) -> Boolean): StreamingProgress = withTimeout(3_000) {
            var value = updates.receive()
            while (!test(value)) value = updates.receive()
            value
        }
        suspend fun result() = withTimeout(3_000) { worker.result.await() }
        override fun close() { scope.cancel() }
    }

    @Test fun agreementConfirmsOnlyTheCommonPrefixAndKeepsAnEndingRevisable() {
        val agreement = LocalAgreement2()
        agreement.update("I am try")
        assertEquals("", agreement.stable)
        agreement.update("I am trying to")
        assertEquals("I", agreement.stable)
        assertEquals("am trying to", agreement.tentative)
        agreement.update("I am trying to book")
        assertEquals("I am", agreement.stable)
        assertEquals("trying to book", agreement.tentative)
        agreement.update("I was trying to book")
        assertEquals("I", agreement.stable) // Stability is advisory, not an irreversible edit.
        agreement.reset()
        agreement.update("Very very")
        agreement.update("Very very")
        assertEquals("", agreement.stable)
        assertEquals("Very very", agreement.tentative)
    }

    @Test fun pcmSnapshotsPreserveSamplesAcrossReadBoundariesAndSilenceIsNotACutTimer() {
        val audio = StreamingAudio()
        val first = speech(333, -2000)
        val second = speech(14000)
        audio.append(first); audio.append(second)
        assertArrayEquals(first + second, audio.snapshot(0, 14333))
        assertArrayEquals((first + second).copyOfRange(600, 1200), audio.snapshot(300, 600))
        assertEquals(0, audio.position().pause)
        audio.append(ByteArray(3200)) // 100 ms is not a pause boundary.
        assertEquals(0, audio.position().pause)
        audio.append(ByteArray(24000))
        assertTrue(audio.position().pause > 14333)
    }

    @Test fun growingWindowsRepairPartialWordsAndFinalPassCanCorrectStablePreview() = runBlocking {
        Harness().use { h ->
            h.audio.append(speech(12800))
            h.next().also { assertEquals(25600, it.pcm.size); it.answer.complete("I am try") }
            h.progress { it.completed == 1 }
            h.audio.append(speech(12800))
            h.next().also { assertEquals(51200, it.pcm.size); it.answer.complete("I am trying to") }
            assertEquals("I", h.progress { it.completed == 2 }.stable)
            h.audio.append(speech(12800))
            h.next().answer.complete("I am trying to book")
            assertEquals("I am", h.progress { it.completed == 3 }.stable)
            h.audio.append(speech(100))
            h.audio.finish()
            h.next().also { assertEquals(77000, it.pcm.size); it.answer.complete("I was trying to book a ticket.") }
            assertEquals("I was trying to book a ticket.", h.result())
            val done = h.progress { it.finalizedSamples == 38500 }
            assertEquals(listOf(0, 0, 0, 0), done.history.map { it.startSample })
            assertEquals("I", done.history[1].stable)
        }
    }

    @Test fun pauseKeepsContextAndRepairsPunctuationWithSpeechCapturedDuringInference() = runBlocking {
        Harness().use { h ->
            val first = speech(16000) + ByteArray(21120)
            val tail = speech(6400, -3000)
            h.audio.append(first)
            val closing = h.next()
            assertArrayEquals(first, closing.pcm)
            h.audio.append(tail) // Microphone keeps capturing while ASR is busy.
            h.audio.finish()
            closing.answer.complete("Because right now it is giving me.")
            h.next().also { assertArrayEquals(first + tail, it.pcm)
                it.answer.complete("Because right now it is giving me some Hindi words.") }
            assertEquals("Because right now it is giving me some Hindi words.", h.result())
            val done = h.progress { it.sections == 1 }
            assertEquals(listOf(0, 0), done.history.map { it.startSample })
        }
    }

    @Test fun slowInferenceCoalescesUpdatesInsteadOfQueuingEveryTick() = runBlocking {
        Harness().use { h ->
            h.audio.append(speech(12800))
            val first = h.next()
            repeat(5) { h.audio.append(speech(3200)) }
            assertNull(withTimeoutOrNull(100) { h.calls.receive() })
            first.answer.complete("Please")
            val latest = h.next()
            assertEquals(57600, latest.pcm.size)
            h.audio.finish()
            latest.answer.complete("Please keep this")
            assertEquals("Please keep this", h.result())
            assertEquals(1, h.progress { it.reused == 1 }.reused)
        }
    }

    @Test fun failedBackgroundRequestRetainsAllAudioForStop() = runBlocking {
        Harness().use { h ->
            val initial = speech(12800)
            h.audio.append(initial)
            val first = h.next()
            val tail = speech(100)
            h.audio.append(tail); h.audio.finish()
            first.answer.completeExceptionally(IOException("offline"))
            h.next().also { assertArrayEquals(initial + tail, it.pcm); it.answer.complete("Recovered.") }
            assertEquals("Recovered.", h.result())
            assertEquals(1, h.progress { it.sections == 1 }.failures)
        }
    }

    @Test fun failedPauseFinalizationDoesNotAdvanceAudioOrDuplicateSections() = runBlocking {
        Harness().use { h ->
            h.audio.append(speech(16000) + ByteArray(21120))
            val first = h.next()
            h.audio.finish()
            first.answer.completeExceptionally(IOException("retry"))
            h.next().also { assertArrayEquals(first.pcm, it.pcm); it.answer.complete("One section.") }
            assertEquals("One section.", h.result())
            assertEquals(1, h.progress { it.sections == 1 }.sections)
        }
    }

    @Test fun stopFlushesEvenATailShorterThanOneFrame() = runBlocking {
        Harness().use { h ->
            val tiny = speech(100)
            h.audio.append(tiny); h.audio.finish()
            h.next().also { assertArrayEquals(tiny, it.pcm); it.answer.complete("Yes.") }
            assertEquals("Yes.", h.result())
        }
    }

    @Test fun longSpeechMovesToAnOverlappingWindowWithoutWaitingForStop() = runBlocking {
        Harness(limit = 16000).use { h ->
            h.audio.append(speech(12800))
            val first = h.next()
            h.audio.append(speech(12800))
            first.answer.complete("Please book the train")
            h.next().also { assertEquals(32000, it.pcm.size); it.answer.complete("Please book the train ticket") }
            val rolling = h.progress { it.rolls == 1 }
            assertEquals(8000, rolling.windowStartSample)
            h.next().answer.complete("the train ticket for tomorrow")
            h.progress { it.rolls == 2 }
            h.audio.finish()
            h.next().also { assertTrue(it.pcm.size <= 32000); it.answer.complete("ticket for tomorrow morning") }
            assertEquals("Please book the train ticket for tomorrow morning", h.result())
        }
    }

    @Test fun minuteLongSpeechKeepsEveryRequestBoundedAndNeverRestartsFromZeroAtStop() = runBlocking {
        val audio = StreamingAudio()
        val states = Channel<StreamingProgress>(Channel.UNLIMITED)
        val ranges = ArrayList<Pair<Int, Int>>()
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val words = (1..256).map { "word$it" }
        try {
            val worker = StreamingTranscriber(workerScope, audio, { pcm, _ ->
                val ids = ArrayList<Int>()
                for (i in pcm.indices step 2) {
                    val id = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)) - 1000
                    if (ids.lastOrNull() != id) ids.add(id)
                }
                synchronized(ranges) { ranges.add(ids.first() to pcm.size / 2) }
                ids.joinToString(" ") { "word$it" }
            }, intervalMs = 1, inspect = true, onProgress = { states.trySend(it) })
            // Let the worker run while capture continues, without a single quiet pause.
            for (word in 1..256) {
                audio.append(speech(4000, 1000 + word))
                delay(2)
            }
            audio.finish()
            val text = withTimeout(5000) { worker.result.await() }
            assertEquals(words.joinToString(" "), text)
            val sent = synchronized(ranges) { ranges.toList() }
            assertTrue(sent.size > 12)
            assertTrue(sent.all { it.second <= 128000 })
            assertTrue("The final request must start near the tail, not at zero", sent.last().first > 220)
            assertTrue(sent.any { it.first > 1 })
        } finally { workerScope.cancel() }
    }

    @Test fun stopDrainsAServerBacklogInBoundedWindows() = runBlocking {
        val audio = StreamingAudio()
        for (word in 1..256) audio.append(speech(4000, 1000 + word))
        audio.finish()
        val sizes = ArrayList<Int>()
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val worker = StreamingTranscriber(workerScope, audio, { pcm, _ ->
                sizes.add(pcm.size)
                val ids = ArrayList<Int>()
                for (i in pcm.indices step 2) {
                    val id = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)) - 1000
                    if (ids.lastOrNull() != id) ids.add(id)
                }
                ids.joinToString(" ") { "word$it" }
            }, intervalMs = 1)
            assertEquals((1..256).joinToString(" ") { "word$it" }, withTimeout(5000) { worker.result.await() })
            assertTrue(sizes.all { it <= 256000 })
            assertTrue(sizes.size >= 12)
        } finally { workerScope.cancel() }
    }

    @Test fun overlappingHypothesesRepairClippedWordsAndPreserveIntentionalRepetition() {
        val join = checkNotNull(OverlapAgreement.join("Please please send the revised docu", "fragment send the revised document today"))
        assertEquals("Please please", join.committed)
        assertEquals("send the revised document today", join.current)
        assertEquals("send the revised document tomorrow", checkNotNull(OverlapAgreement.join(
            "Please please send the revised docu", "send the revised document tomorrow")).current)
        assertNull(OverlapAgreement.join("very very very", "very very"))
        assertNull(OverlapAgreement.join("Please send the revised", "An unrelated sentence entirely"))
    }

    @Test fun speechDetectorFindsPausesDespiteNoiseAboveTheOldLoudnessThreshold() {
        val detector = object : SpeechDetector {
            override val frameSize = 320
            override val name = "Test speech classifier"
            override fun probability(frame: FloatArray) = if (frame[0] > 0.02f) 0.95f else 0.02f
            override fun close() = Unit
        }
        val audio = StreamingAudio(detector = detector)
        audio.append(speech(16000, 1200))
        audio.append(speech(6400, 500)) // Loud background, but classified as non-speech.
        assertEquals(1, audio.position().pauses)
        assertNotNull(audio.pauseBetween(0, 22400))
        audio.append(speech(100, 500))
        assertFalse("Unclassified trailing noise must not override the speech detector", audio.hasSpeech(22400, 22500))
        val oldGate = StreamingAudio(silenceMs = 660)
        oldGate.append(speech(16000, 1200)); oldGate.append(speech(6400, 500))
        assertEquals(0, oldGate.position().pauses)
    }

    @Test fun blankEarlyPauseKeepsItsAudioUntilMoreSpeechProvidesContext() = runBlocking {
        Harness().use { h ->
            val first = speech(16000) + ByteArray(21120)
            h.audio.append(first)
            h.next().answer.complete("")
            h.progress { it.completed == 1 }
            val tail = speech(6400)
            h.audio.append(tail); h.audio.finish()
            h.next().also { assertArrayEquals(first + tail, it.pcm); it.answer.complete("The full sentence.") }
            assertEquals("The full sentence.", h.result())
        }
    }

    @Test fun uncertainOverlapRetriesWithMoreContextButEveryRequestRemainsBounded() = runBlocking {
        Harness(limit = 16000).use { h ->
            h.audio.append(speech(32000)); h.audio.finish()
            h.next().answer.complete("Please bring the blue notebook")
            h.next().answer.complete("Unrelated words here")
            val wider = h.next()
            assertEquals(32000, wider.pcm.size)
            wider.answer.complete("bring the blue notebook to the meeting")
            h.next().answer.complete("notebook to the meeting tomorrow morning")
            h.next().answer.complete("meeting tomorrow morning")
            assertEquals("Please bring the blue notebook to the meeting tomorrow morning", h.result())
            val done = h.progress { it.finalizedSamples == 32000 }
            assertTrue(done.maxRequestSamples <= 16000)
            assertTrue(done.history.any { it.kind == "Checking wider overlap" })
        }
    }

    @Test fun unresolvableOverlapRecoversTheFullOriginalRecordingAndReplacesPartialText() = runBlocking {
        Harness(limit = 16000).use { h ->
            val original = speech(32000)
            h.audio.append(original); h.audio.finish()
            h.next().answer.complete("Please bring the blue notebook")
            h.next().answer.complete("Entirely unrelated text")
            h.next().answer.complete("Still unrelated text")
            h.next().also {
                assertArrayEquals(original, it.pcm)
                assertFalse(it.allowEmpty)
                it.answer.complete("The complete original recording.")
            }
            assertEquals("The complete original recording.", h.result())
            val done = h.progress { it.recovered }
            assertEquals(1, done.sections)
            assertEquals(1, done.recoveryRequests)
            assertEquals(32000, done.recoverySamples)
            assertEquals(32000, done.finalizedSamples)
            assertTrue(done.maxRequestSamples <= 16000)
            assertTrue(done.history.any { it.kind == "Full recording recovery" })
            assertNull(withTimeoutOrNull(100) { h.calls.receive() })
        }
    }

    @Test fun uncertainJoinKeepsRecordingUntilStopAndIncludesLaterAudioWithoutCompactingIt() = runBlocking {
        Harness(limit = 16000).use { h ->
            val original = speech(32000)
            h.audio.append(original)
            h.next().answer.complete("Please bring the blue notebook")
            h.next().answer.complete("bring the blue notebook today")
            h.next().answer.complete("Entirely unrelated text")
            h.next().answer.complete("Still unrelated text")
            val pending = h.progress { it.recoveryRequired }
            assertEquals("Please", pending.stable)
            assertFalse(h.worker.result.isCompleted)
            assertNull(withTimeoutOrNull(100) { h.calls.receive() })
            val tail = ByteArray(64000) + speech(100, 2000)
            h.audio.append(tail); h.audio.finish()
            h.next().also {
                assertArrayEquals(original + tail, it.pcm)
                it.answer.complete("Final complete recording.")
            }
            assertEquals("Final complete recording.", h.result())
            val done = h.progress { it.recovered }
            assertEquals("Final complete recording.", done.stable)
            assertEquals(1, done.sections)
            assertEquals((original.size + tail.size) / 2, done.recoverySamples)
        }
    }

    @Test fun failedOrBlankFullRecoveryDoesNotAcceptPartialTextOrDiscardOriginalAudio() = runBlocking {
        for (blank in listOf(false, true)) Harness(limit = 16000).use { h ->
            val original = speech(32000)
            h.audio.append(original); h.audio.finish()
            h.next().answer.complete("Please bring the blue notebook")
            h.next().answer.complete("Entirely unrelated text")
            h.next().answer.complete("Still unrelated text")
            val recovery = h.next()
            if (blank) recovery.answer.complete("") else recovery.answer.completeExceptionally(IOException("Recovery offline"))
            try { h.result(); fail("Partial output must never be accepted") }
            catch (error: Exception) {
                if (blank) assertTrue(error is IllegalArgumentException) else assertTrue(error is IOException)
            }
            val failed = h.progress { it.failures == 1 }
            assertTrue(failed.recoveryRequired)
            assertFalse(failed.recovered)
            assertArrayEquals(original, h.audio.snapshot(0, 32000))
        }
    }

    @Test fun cancellingFullRecoveryRejectsLateText() = runBlocking {
        Harness(limit = 16000).use { h ->
            h.audio.append(speech(32000)); h.audio.finish()
            h.next().answer.complete("Please bring the blue notebook")
            h.next().answer.complete("Entirely unrelated text")
            h.next().answer.complete("Still unrelated text")
            val recovery = h.next()
            h.worker.cancel(); h.worker.result.join()
            recovery.answer.complete("Must never appear.")
            assertTrue(h.worker.result.isCancelled)
            assertNull(withTimeoutOrNull(100) { h.calls.receive() })
        }
    }

    @Test fun laterHypothesisCanReviseTheSharedPrefixWithoutDuplicatingOrLosingWords() = runBlocking {
        Harness(limit = 16000).use { h ->
            h.audio.append(speech(22000))
            h.next().answer.complete("Please bring the blue notebook today")
            h.next().answer.complete("fragment blue notebook today for Alice")
            h.progress { it.completed == 2 }
            h.audio.append(speech(3000))
            h.next().answer.complete("the blue notebook today for Alice")
            h.progress { it.rolls == 2 }
            h.audio.finish()
            h.next().answer.complete("today for Alice")
            assertEquals("Please bring the blue notebook today for Alice", h.result())
        }
    }

    @Test fun cancellationRejectsLateTextAndStopsFurtherRequests() = runBlocking {
        Harness().use { h ->
            h.audio.append(speech(12800))
            val first = h.next()
            h.worker.cancel(); h.worker.result.join()
            first.answer.complete("Must never appear.")
            h.audio.append(speech(12800)); h.audio.finish()
            assertTrue(h.worker.result.isCancelled)
            assertNull(withTimeoutOrNull(100) { h.calls.receive() })
        }
    }

    @Test fun provisionalBlankIsAllowedButBlankFinalSpeechFailsWithoutCoverage() = runBlocking {
        Harness().use { h ->
            h.audio.append(speech(12800))
            h.next().answer.complete("")
            assertEquals(0, h.progress { it.completed == 1 }.failures)
            h.audio.append(speech(100)); h.audio.finish()
            h.next().answer.complete("")
            try { h.result(); fail("Must not silently discard speech") }
            catch (_: IllegalArgumentException) { }
            assertEquals(0, h.progress { it.failures == 1 }.finalizedSamples)
        }
    }

    @Test fun backgroundTimeoutRecoversAtStopInsteadOfLosingAudio() = runBlocking {
        Harness(timeout = 100).use { h ->
            h.audio.append(speech(12800))
            val timedOut = h.next()
            h.audio.finish()
            val final = h.next()
            assertArrayEquals(timedOut.pcm, final.pcm)
            final.answer.complete("After timeout.")
            assertEquals("After timeout.", h.result())
        }
    }

    @Test fun longTrailingSilenceRetainsSpeechContextAndFinalizesOnlyAtStop() = runBlocking {
        Harness().use { h ->
            h.audio.append(speech(16000) + ByteArray(21120))
            h.next().answer.complete("Done.")
            assertEquals(0, h.progress { it.completed == 1 }.sections)
            h.audio.append(ByteArray(32000)); h.audio.finish()
            h.next().answer.complete("Done.")
            assertEquals("Done.", h.result())
            val done = h.progress { it.finalizedSamples == 42560 }
            assertEquals(2, done.requests)
        }
    }

    @Test fun shortTailAfterPauseIsRecognizedTogetherWithItsContext() = runBlocking {
        Harness().use { h ->
            h.audio.append(speech(16000) + ByteArray(21120))
            h.next().answer.complete("First.")
            h.progress { it.completed == 1 }
            val tail = speech(100)
            h.audio.append(tail); h.audio.finish()
            h.next().also { assertArrayEquals(speech(16000) + ByteArray(21120) + tail, it.pcm)
                it.answer.complete("First. Yes.") }
            assertEquals("First. Yes.", h.result())
        }
    }

    @Test fun stopReusesExactSuccessfulAudioWithoutAnExtraAsrRequest() = runBlocking {
        Harness().use { h ->
            h.audio.append(speech(12800))
            h.next().answer.complete("Already complete.")
            h.progress { it.completed == 1 }
            h.audio.finish()
            assertEquals("Already complete.", h.result())
            val done = h.progress { it.reused == 1 }
            assertEquals(1, done.requests)
            assertEquals("Reused exact audio", done.history.last().kind)
            assertNull(withTimeoutOrNull(100) { h.calls.receive() })
        }
    }

    @Test fun stopWakesTheWorkerWithoutWaitingForTheNextTick() = runBlocking {
        Harness(interval = 2_000).use { h ->
            h.audio.append(speech(12800))
            h.next().answer.complete("Ready.")
            h.progress { it.completed == 1 }
            h.audio.finish()
            assertEquals("Ready.", withTimeout(500) { h.worker.result.await() })
        }
    }
}
