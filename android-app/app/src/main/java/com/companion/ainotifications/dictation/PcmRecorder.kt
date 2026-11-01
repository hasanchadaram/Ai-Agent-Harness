package com.companion.ainotifications.dictation

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Whole-utterance capture, bounded to five minutes, entirely in memory. */
class PcmRecorder(scope: CoroutineScope) {
    private val running = AtomicBoolean(true)
    private val released = AtomicBoolean(false)
    private val record: AudioRecord
    val audio: Deferred<ByteArray>

    init {
        val minimum = AudioRecord.getMinBufferSize(DictationProtocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0) { "This phone does not support 16 kHz microphone recording." }
        @SuppressLint("MissingPermission")
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
            DictationProtocol.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 6400))
        record = recorder
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "The microphone could not be initialized." }
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "The microphone is unavailable. Close other recording apps and try again." }
        } catch (error: Exception) {
            record.release()
            throw error
        }
        audio = scope.async(Dispatchers.IO) {
            try {
                val output = ByteArrayOutputStream(DictationProtocol.SAMPLE_RATE * 2)
                val buffer = ByteArray(3200)
                val limit = DictationProtocol.SAMPLE_RATE * 2 * DictationProtocol.MAX_SECONDS
                while (running.get() && isActive && output.size() < limit) {
                    val count = record.read(buffer, 0, minOf(buffer.size, limit - output.size()), AudioRecord.READ_BLOCKING)
                    if (count > 0) output.write(buffer, 0, count)
                    else if (running.get()) throw IOException("Microphone capture failed. Please try again.")
                }
                output.toByteArray()
            } finally {
                release()
            }
        }
        // Cancellation before Dispatchers.IO starts otherwise skips the body's finally.
        audio.invokeOnCompletion { release() }
    }

    fun stop() {
        if (running.getAndSet(false)) runCatching { record.stop() }
    }

    fun cancel() { stop(); audio.cancel() }

    private fun release() {
        if (released.compareAndSet(false, true)) {
            running.set(false)
            runCatching { record.stop() }
            record.release()
        }
    }
}
