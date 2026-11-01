package com.companion.ainotifications.dictation

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DictationProtocolTest {
    @Test fun wavIsMono16kPcmWithCompleteHeaderAndSamples() {
        val pcm = byteArrayOf(0, 0, -1, 127, 0, -128)
        val wav = DictationProtocol.wav(pcm)
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals(wav.size - 8, buffer.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals(1, buffer.getShort(20).toInt())
        assertEquals(1, buffer.getShort(22).toInt())
        assertEquals(16000, buffer.getInt(24))
        assertEquals(32000, buffer.getInt(28))
        assertEquals(16, buffer.getShort(34).toInt())
        assertEquals(pcm.size, buffer.getInt(40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }

    @Test fun incompleteOrEmptyAudioIsRejected() {
        for (pcm in listOf(byteArrayOf(), byteArrayOf(1))) {
            assertThrows(IllegalArgumentException::class.java) { DictationProtocol.wav(pcm) }
        }
    }

    @Test fun insertionPreservesBothSidesAndReplacesOnlySelection() {
        assertEquals("Hello wonderful world" to 16,
            DictationProtocol.insertAtSelection("Hello world", 6, 6, "wonderful "))
        assertEquals("Hello world!" to 11,
            DictationProtocol.insertAtSelection("Hello bad!", 6, 9, "world"))
        assertEquals("Hello world!" to 11,
            DictationProtocol.insertAtSelection("Hello bad!", 9, 6, "world"))
        assertEquals("very, very good" to 15,
            DictationProtocol.insertAtSelection("", 0, 0, "very, very good"))
    }

    @Test fun unknownOrStaleSelectionIsNeverGuessed() {
        assertThrows(IllegalArgumentException::class.java) { DictationProtocol.insertAtSelection("hello", -1, -1, "text") }
        assertThrows(IllegalArgumentException::class.java) { DictationProtocol.insertAtSelection("hello", 6, 6, "text") }
    }

    @Test fun placeholderTextDoesNotBecomeAnExistingDraft() {
        assertEquals("", DictationProtocol.editorText("Message", showingHint = true))
        assertEquals("", DictationProtocol.editorText(null, showingHint = true))
        assertEquals("Message", DictationProtocol.editorText("Message", showingHint = false))
        assertEquals("", DictationProtocol.editorText(null, showingHint = false))
    }

    @Test fun qwenMetadataIsRemovedButIntentionalRepetitionRemains() {
        assertEquals("Very, very good.", DictationProtocol.transcript("language English<asr_text>Very, very good.<|im_end|>"))
        assertEquals("Hello.", DictationProtocol.transcript(" Hello. "))
    }

    @Test fun onlyTailnetAddressesAreAccepted() {
        for (url in listOf("https://pc.tail123.ts.net/v1", "http://100.64.0.1:8081/v1/", "http://100.127.255.254/v1")) {
            assertTrue(DictationProtocol.baseUrl(url).encodedPath.endsWith("/v1/"))
        }
        for (url in listOf("http://localhost:8081/v1", "https://api.example.com/v1", "http://100.128.0.1/v1",
            "http://100.63.0.1/v1", "https://pc.tail123.ts.net", "https://pc.tail123.ts.net/v1?key=secret",
            "https://user:password@pc.tail123.ts.net/v1", "https://pc.tail123.ts.net.evil.com/v1")) {
            assertThrows(url, IllegalArgumentException::class.java) { DictationProtocol.baseUrl(url) }
        }
    }
}
