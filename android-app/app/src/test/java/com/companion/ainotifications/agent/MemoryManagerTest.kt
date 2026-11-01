package com.companion.ainotifications.agent

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MemoryManagerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun constructingTheHarnessDoesNotReadOrCreateTheMemoryFile() {
        val file = File(temporary.root, "memory.md")
        val manager = MemoryManager(file)
        assertFalse(file.exists())
        assertTrue(manager.loadMemory().contains("Hasan"))
        assertTrue(file.exists())
    }

    @Test fun editsAndAppendsInvalidateTheReadCacheWithoutLosingFacts() {
        val file = temporary.newFile("memory.md").apply { writeText("Existing memory.\n") }
        val manager = MemoryManager(file)
        assertEquals("Existing memory.\n", manager.loadMemory())
        manager.saveMemory("A new fact.")
        assertEquals("Existing memory.\n- A new fact.\n", manager.loadMemory())
        manager.updateMemory("Replacement memory.")
        assertEquals("Replacement memory.", manager.loadMemory())
        manager.updateMemory("")
        assertEquals("(nothing saved yet)", manager.loadMemory())
    }

    @Test fun concurrentTasksCannotLoseMemoryWrites() {
        val manager = MemoryManager(temporary.newFile("memory.md"))
        val executor = Executors.newFixedThreadPool(4)
        try {
            val writes = (1..40).map { fact -> executor.submit { manager.saveMemory("Fact $fact") } }
            writes.forEach { it.get(5, TimeUnit.SECONDS) }
            val facts = manager.loadMemory().lineSequence().filter { it.isNotBlank() }.toSet()
            assertEquals(40, facts.size)
            (1..40).forEach { assertTrue("Missing Fact $it", "- Fact $it" in facts) }
        } finally { executor.shutdownNow() }
    }
}
