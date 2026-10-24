package com.companion.ainotifications.agent

import android.content.Context
import java.io.File

class MemoryManager(context: Context) {
    private val memoryFile: File = File(context.filesDir, "memory.md")

    init {
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
        return if (memoryFile.exists() && memoryFile.length() > 0) {
            memoryFile.readText(Charsets.UTF_8)
        } else {
            "(nothing saved yet)"
        }
    }

    @Synchronized
    fun saveMemory(fact: String): String {
        val trimmed = fact.trim()
        if (trimmed.isNotEmpty()) {
            memoryFile.appendText("- $trimmed\n", Charsets.UTF_8)
        }
        return "saved: $trimmed"
    }

    @Synchronized
    fun updateMemory(newContent: String) {
        memoryFile.writeText(newContent, Charsets.UTF_8)
    }
}
