package com.companion.ainotifications.agent

import android.content.Context
import java.io.File

class MemoryManager internal constructor(private val memoryFile: File) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "memory.md"))
    private var cachedMemory: String? = null

    private fun ensureMemoryExists() {
        // Pre-seed memory if it doesn't exist yet
        if (!memoryFile.exists()) {
            memoryFile.writeText(
                "- The user's name is Hasan and they are 24 years old.\n",
                Charsets.UTF_8
            )
        }
    }

    @Synchronized
    fun loadMemory(): String {
        cachedMemory?.let { return it }
        ensureMemoryExists()
        return (if (memoryFile.length() > 0) {
            memoryFile.readText(Charsets.UTF_8)
        } else {
            "(nothing saved yet)"
        }).also { cachedMemory = it }
    }

    @Synchronized
    fun saveMemory(fact: String): String {
        val trimmed = fact.trim()
        if (trimmed.isNotEmpty()) {
            ensureMemoryExists()
            memoryFile.appendText("- $trimmed\n", Charsets.UTF_8)
            cachedMemory = null
        }
        return "saved: $trimmed"
    }

    @Synchronized
    fun updateMemory(newContent: String) {
        memoryFile.writeText(newContent, Charsets.UTF_8)
        cachedMemory = newContent.ifEmpty { "(nothing saved yet)" }
    }
}
