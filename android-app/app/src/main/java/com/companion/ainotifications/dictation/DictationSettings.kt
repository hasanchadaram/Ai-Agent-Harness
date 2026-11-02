package com.companion.ainotifications.dictation

import android.content.Context

data class ModelEndpoint(val url: String, val model: String, val apiKey: String = "")

class DictationSettings(context: Context) {
    // Separate from notification memory/settings. No audio or transcript is persisted.
    private val prefs = context.getSharedPreferences("private_dictation", Context.MODE_PRIVATE)
    var asr: ModelEndpoint
        get() = endpoint("asr", "https://hasan-pc.tail99bd0f.ts.net:8443/v1",
            "Qwen/Qwen3-ASR-1.7B")
        set(value) = save("asr", value)
    var cleanup: ModelEndpoint
        get() = endpoint("cleanup", "https://hasan-pc.tail99bd0f.ts.net/v1",
            "C:/Users/hasan/.lmstudio/models/Unsloth-Fine-Tuned/Qwen3.5-4B-Q4_K_M/Qwen3.5-4B.Q4_K_M.gguf")
        set(value) = save("cleanup", value)
    var polished: Boolean
        get() = prefs.getBoolean("polished", true)
        set(value) { prefs.edit().putBoolean("polished", value).apply() }
    var backgroundAsr: Boolean
        get() = prefs.getBoolean("background_asr", true)
        set(value) { prefs.edit().putBoolean("background_asr", value).apply() }
    var inspectTranscription: Boolean
        get() = prefs.getBoolean("inspect_transcription", false)
        set(value) { prefs.edit().putBoolean("inspect_transcription", value).apply() }
    var overlayEnabled: Boolean
        get() = prefs.getBoolean("overlay", false)
        set(value) { prefs.edit().putBoolean("overlay", value).apply() }
    var overlaySide: String
        get() = prefs.getString("overlay_side", "right").let { if (it == "left") "left" else "right" }
        set(value) { prefs.edit().putString("overlay_side", if (value == "left") "left" else "right").apply() }
    var overlayTopDp: Int
        get() = prefs.getInt("overlay_top_dp", -1)
        set(value) { prefs.edit().putInt("overlay_top_dp", value).apply() }
    var overlayOpacity: Int
        get() = prefs.getInt("overlay_opacity", 50).coerceIn(20, 100)
        set(value) { prefs.edit().putInt("overlay_opacity", value.coerceIn(20, 100)).apply() }

    fun saveOverlayPosition(side: String, topDp: Int) {
        prefs.edit().putString("overlay_side", if (side == "left") "left" else "right")
            .putInt("overlay_top_dp", topDp).apply()
    }

    private fun endpoint(key: String, url: String, model: String) = ModelEndpoint(
        prefs.getString("${key}_url", url) ?: url,
        prefs.getString("${key}_model", model) ?: model,
        prefs.getString("${key}_key", "") ?: ""
    )
    private fun save(key: String, value: ModelEndpoint) {
        prefs.edit().putString("${key}_url", value.url.trim().trimEnd('/'))
            .putString("${key}_model", value.model.trim())
            .putString("${key}_key", value.apiKey.trim()).apply()
    }
}
