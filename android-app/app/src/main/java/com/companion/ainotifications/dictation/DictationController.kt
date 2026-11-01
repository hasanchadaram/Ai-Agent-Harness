package com.companion.ainotifications.dictation

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class DictationPhase { IDLE, STARTING, RECORDING, TRANSCRIBING, CLEANING, READY, ERROR }

data class DictationState(
    val id: String = "",
    val phase: DictationPhase = DictationPhase.IDLE,
    val original: String = "",
    val result: String = "",
    val message: String = "Ready to dictate",
    val timings: String = "",
    val polished: Boolean = true,
    val inserted: Boolean = false
) {
    val busy get() = when (phase) {
        DictationPhase.STARTING, DictationPhase.RECORDING,
        DictationPhase.TRANSCRIBING, DictationPhase.CLEANING -> true
        else -> false
    }
}

/** Main-thread session owner. Dictation never passes through the notification agent or its tools. */
class DictationController private constructor(context: Context) {
    val settings = DictationSettings(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(DictationState(polished = settings.polished))
    val state = mutableState.asStateFlow()
    private val client = DictationClient()
    private var recorder: PcmRecorder? = null
    private var work: Job? = null
    private var finishService: (() -> Unit)? = null
    private var destination: InsertionTarget? = null

    interface InsertionTarget {
        /** Returns an explanation; must refuse insertion if the original destination changed. */
        suspend fun insert(text: String): InsertionOutcome
        fun release()
    }
    data class InsertionOutcome(val inserted: Boolean, val message: String)

    fun prepare(target: InsertionTarget?): String {
        check(!state.value.busy) { "A dictation is already running." }
        releaseDestination()
        destination = target
        val id = UUID.randomUUID().toString()
        mutableState.value = DictationState(id, DictationPhase.STARTING, polished = settings.polished,
            message = "Starting microphone…")
        return id
    }

    fun start(id: String, onFinished: () -> Unit) {
        if (id != state.value.id || state.value.phase != DictationPhase.STARTING) { onFinished(); return }
        finishService = onFinished
        try {
            val capture = PcmRecorder(scope)
            recorder = capture
            mutableState.value = state.value.copy(phase = DictationPhase.RECORDING,
                message = "Recording · tap Stop when finished (5 minute limit)")
            work = scope.launch {
                try {
                    capture.audio.await()
                    if (state.value.id == id && state.value.phase == DictationPhase.RECORDING) stop()
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { if (state.value.id == id) fail(error.message ?: "Microphone capture failed.") }
            }
        } catch (error: Exception) { fail(error.message ?: "Cannot start the microphone.") }
    }

    fun setPolished(enabled: Boolean) {
        if (state.value.polished == enabled) return
        settings.polished = enabled
        mutableState.value = state.value.copy(polished = enabled)
    }

    fun stop() {
        if (state.value.phase != DictationPhase.RECORDING) return
        val id = state.value.id
        val capture = recorder ?: return
        val polishedAtStop = state.value.polished
        val asr = settings.asr
        val cleanup = settings.cleanup
        val stopped = SystemClock.elapsedRealtime()
        mutableState.value = state.value.copy(phase = DictationPhase.TRANSCRIBING, message = "Transcribing on your PC…")
        work?.cancel()
        capture.stop()
        work = scope.launch {
            try {
                val pcm = capture.audio.await()
                recorder = null
                val audioReady = SystemClock.elapsedRealtime()
                val original = client.transcribe(asr, pcm)
                ensureActive()
                if (state.value.id != id) return@launch
                val asrDone = SystemClock.elapsedRealtime()
                mutableState.value = state.value.copy(original = original, result = original,
                    timings = "Audio ready ${audioReady - stopped} ms · ASR request ${asrDone - audioReady} ms")
                var result = original
                var cleanupFailure: String? = null
                if (polishedAtStop) {
                    mutableState.value = state.value.copy(phase = DictationPhase.CLEANING, message = "Cleaning up on your PC…")
                    try { result = client.polish(cleanup, original) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { cleanupFailure = error.message ?: "Cleanup failed." }
                }
                ensureActive()
                if (state.value.id != id) return@launch
                val cleanupDone = SystemClock.elapsedRealtime()
                // A failed cleanup leaves the original for explicit review/copy.
                val insertion = if (cleanupFailure == null) destination?.insert(result)
                    ?: InsertionOutcome(false, "Transcript ready. Use Copy to paste it.")
                    else InsertionOutcome(false, "$cleanupFailure Original transcript retained; use Copy.")
                val finished = SystemClock.elapsedRealtime()
                mutableState.value = state.value.copy(phase = DictationPhase.READY, result = result,
                    inserted = insertion.inserted, message = insertion.message,
                    timings = state.value.timings + (if (polishedAtStop) " · Cleanup request ${cleanupDone - asrDone} ms" else "") +
                        " · Insertion ${finished - cleanupDone} ms · Stop to result ${finished - stopped} ms")
                releaseDestination()
                work = null
                stopService()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (state.value.id == id) fail(error.message ?: "Dictation failed. Please try again.") }
        }
    }

    fun startupFailed(id: String, message: String) { if (state.value.id == id && state.value.phase == DictationPhase.STARTING) fail(message) }

    fun cancel() {
        // Invalidate before cancelling so late network/service callbacks cannot publish or insert.
        mutableState.value = DictationState(polished = settings.polished, message = "Cancelled")
        work?.cancel(); work = null
        recorder?.cancel(); recorder = null
        releaseDestination()
        stopService()
    }

    fun serviceDestroyed(id: String) {
        if (state.value.id == id && state.value.busy) {
            cancel()
            mutableState.value = state.value.copy(phase = DictationPhase.ERROR,
                message = "Dictation stopped because the recording service was closed. Please start again.")
        }
    }

    fun useOriginal() {
        if (!state.value.busy && state.value.original.isNotBlank()) mutableState.value = state.value.copy(
            result = state.value.original, inserted = false, message = "Original transcript selected. Copy it to replace the polished text.")
    }

    private fun fail(message: String) {
        work?.cancel(); work = null
        recorder?.cancel(); recorder = null
        mutableState.value = state.value.copy(phase = DictationPhase.ERROR, message = message)
        releaseDestination()
        stopService()
    }
    private fun stopService() { val stop = finishService; finishService = null; stop?.invoke() }
    private fun releaseDestination() { destination?.release(); destination = null }

    companion object {
        @Volatile private var instance: DictationController? = null
        fun get(context: Context): DictationController = instance ?: synchronized(this) {
            instance ?: DictationController(context).also { instance = it }
        }
    }
}
