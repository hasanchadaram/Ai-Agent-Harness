package com.companion.ainotifications.data

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("ai_notifications_prefs", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "http://100.100.100.100:8080/v1") ?: "http://100.100.100.100:8080/v1"
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trim()).apply()

    var modelName: String
        get() = prefs.getString(KEY_MODEL_NAME, "qwen3.5:4b") ?: "qwen3.5:4b"
        set(value) = prefs.edit().putString(KEY_MODEL_NAME, value.trim()).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "llama") ?: "llama"
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    var isServiceActive: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_ACTIVE, true)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_ACTIVE, value).apply()

    var monitoredPackages: Set<String>
        get() = prefs.getStringSet(KEY_MONITORED_PACKAGES, DEFAULT_MONITORED_PACKAGES) ?: DEFAULT_MONITORED_PACKAGES
        set(value) = prefs.edit().putStringSet(KEY_MONITORED_PACKAGES, value).apply()

    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_MODEL_NAME = "model_name"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_SERVICE_ACTIVE = "service_active"
        private const val KEY_MONITORED_PACKAGES = "monitored_packages"

        val DEFAULT_MONITORED_PACKAGES = setOf(
            "com.whatsapp",
            "com.google.android.gm",
            "com.google.android.calendar",
            "org.telegram.messenger",
            "com.google.android.apps.messaging",
            "com.slack"
        )
    }
}
