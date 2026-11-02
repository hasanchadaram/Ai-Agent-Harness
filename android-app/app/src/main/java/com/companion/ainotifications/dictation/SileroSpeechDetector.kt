package com.companion.ainotifications.dictation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import java.nio.LongBuffer

interface SpeechDetector : AutoCloseable {
    val frameSize: Int
    val name: String
    fun probability(frame: FloatArray): Float
}

/** Stateful 16 kHz Silero VAD. One instance per recording; never shared across sessions. */
class SileroSpeechDetector(context: Context) : SpeechDetector {
    override val frameSize = 512
    override val name = "Silero VAD"
    private val environment = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(1)
        options.setInterOpNumThreads(1)
        environment.createSession(context.assets.open("vad/silero_vad_16k_op15.onnx").use { it.readBytes() }, options)
    }
    private var state = FloatArray(256)
    private var contextSamples = FloatArray(64)

    override fun probability(frame: FloatArray): Float {
        require(frame.size == frameSize)
        val input = contextSamples + frame
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1, 576)).use { samples ->
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { recurrent ->
                OnnxTensor.createTensor(environment, LongBuffer.wrap(longArrayOf(16000)), longArrayOf()).use { rate ->
                    session.run(mapOf("input" to samples, "state" to recurrent, "sr" to rate)).use { outputs ->
                        val probability = (outputs[0] as OnnxTensor).floatBuffer.get(0)
                        (outputs[1] as OnnxTensor).floatBuffer.get(state)
                        contextSamples = frame.copyOfRange(frameSize - 64, frameSize)
                        return probability
                    }
                }
            }
        }
    }

    override fun close() { session.close() }
}
