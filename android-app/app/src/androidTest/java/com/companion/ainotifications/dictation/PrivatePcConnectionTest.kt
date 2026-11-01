package com.companion.ainotifications.dictation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in device smoke test. Uses synthetic text and silence; never opens the microphone. */
@RunWith(AndroidJUnit4::class)
class PrivatePcConnectionTest {
    @Test fun phoneCanReachBothConfiguredModelsOverTailscale() = runBlocking {
        val settings = DictationSettings(InstrumentationRegistry.getInstrumentation().targetContext)
        val client = DictationClient()
        assertTrue(client.test(settings.asr).startsWith("Connected:"))
        assertTrue(client.test(settings.cleanup).startsWith("Connected:"))
    }

    @Test fun phoneCanSendAudioAndCleanTextWithoutCloudServices() = runBlocking {
        val settings = DictationSettings(InstrumentationRegistry.getInstrumentation().targetContext)
        val client = DictationClient()
        // The silence response need not contain recognized speech. Rejection of blank speech
        // also establishes that a valid JSON completion was received from the audio server.
        try { client.transcribe(settings.asr, ByteArray(3200)) }
        catch (error: IllegalArgumentException) {
            assertEquals("No speech was recognized. Please try again.", error.message)
        }
        val polished = client.polish(settings.cleanup, "this is a private connection test the meeting starts at three thirty")
        assertTrue(polished.isNotBlank())
        assertTrue(polished.contains("meeting", ignoreCase = true))
    }
}
