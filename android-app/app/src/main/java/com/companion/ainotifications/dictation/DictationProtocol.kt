package com.companion.ainotifications.dictation

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder

object DictationProtocol {
    const val SAMPLE_RATE = 16_000
    const val MAX_SECONDS = 300

    fun baseUrl(value: String): HttpUrl {
        val url = value.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Enter a valid Tailscale server URL ending in /v1.")
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "Put authentication in the API key field, and remove URL query/fragment values."
        }
        val host = url.host.lowercase()
        val octets = host.split('.').mapNotNull { it.toIntOrNull() }
        val tailnetIp = octets.size == 4 && octets[0] == 100 && octets[1] in 64..127 && octets.all { it in 0..255 }
        val tailnetDns = host.endsWith(".ts.net")
        val tailnetV6 = host.startsWith("fd7a:115c:a1e0:")
        require(tailnetIp || tailnetDns || tailnetV6) {
            "Use your PC's Tailscale IP or full .ts.net name. localhost on the phone is the phone."
        }
        require(url.encodedPath.trimEnd('/') == "/v1") { "The server URL must end in /v1." }
        return url.newBuilder().encodedPath("/v1/").build()
    }

    fun wav(pcm: ByteArray): ByteArray {
        require(pcm.isNotEmpty() && pcm.size % 2 == 0) { "No complete audio samples were captured. Please try again." }
        return ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + pcm.size)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(1); putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2)
            putShort(2); putShort(16); put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcm.size); put(pcm)
        }.array()
    }

    fun transcript(value: String): String = value.substringAfter("<asr_text>", value)
        .substringBefore("<|im_end|>").trim()

    // Android exposes placeholder text through node.text on empty editors. A hint
    // is not a draft; its missing selection is safe to normalize to the only empty
    // position (0). Never infer emptiness by comparing the text with a hint string.
    fun editorText(exposedText: String?, showingHint: Boolean): String =
        if (showingHint) "" else exposedText.orEmpty()

    // Accessibility SET_TEXT replaces the field. Construct the replacement explicitly.
    fun insertAtSelection(existing: String, start: Int, end: Int, dictated: String): Pair<String, Int> {
        require(start in 0..existing.length && end in 0..existing.length) { "The cursor position is unavailable. Use Copy instead." }
        val left = minOf(start, end)
        val right = maxOf(start, end)
        return (existing.take(left) + dictated + existing.substring(right)) to (left + dictated.length)
    }
}
