package com.companion.ainotifications.dictation

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.TimeUnit

class DictationClientTest {
    private lateinit var server: MockWebServer
    private lateinit var endpoint: ModelEndpoint
    private lateinit var client: DictationClient
    @Before fun setup() {
        server = MockWebServer(); server.start()
        endpoint = ModelEndpoint("http://test.tail123.ts.net:${server.port}/v1", "test-model", "private-key")
        client = DictationClient(OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
            })
            .followRedirects(false).retryOnConnectionFailure(false).callTimeout(5, TimeUnit.SECONDS).build())
    }
    @After fun teardown() { server.shutdown() }
    private fun completion(text: String, finish: String = "stop") = buildJsonObject {
        putJsonArray("choices") { addJsonObject {
            put("finish_reason", finish)
            putJsonObject("message") { put("content", text) }
        } }
    }.toString()

    @Test fun sendsWavAudioDirectlyToChatCompletions() = runBlocking {
        server.enqueue(MockResponse().setBody(completion("language English<asr_text>Very, very good.")))
        assertEquals("Very, very good.", client.transcribe(endpoint, byteArrayOf(0, 0, 1, 0)))
        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer private-key", request.getHeader("Authorization"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("English", body["language"]!!.jsonPrimitive.content)
        val audio = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject["input_audio"]!!.jsonObject
        assertEquals("wav", audio["format"]!!.jsonPrimitive.content)
        val decoded = Base64.getDecoder().decode(audio["data"]!!.jsonPrimitive.content)
        assertEquals("RIFF", String(decoded, 0, 4, Charsets.US_ASCII))
        assertFalse(body.containsKey("tools"))
    }

    @Test fun cleanupDisablesThinkingAndDoesNotSendToolsOrMemory() = runBlocking {
        server.enqueue(MockResponse().setBody(completion("Very, very good.")))
        assertEquals("Very, very good.", client.polish(endpoint, "very very good"))
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(false, body["chat_template_kwargs"]!!.jsonObject["enable_thinking"]!!.jsonPrimitive.boolean)
        assertEquals("none", body["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("very very good", body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("tools"))
    }

    @Test fun growingFlowRequestsOptionalWordTimesAndKeepsNegation() = runBlocking {
        val response = Json.parseToJsonElement(completion("<asr_text>Do not.<|im_end|>")).jsonObject
        server.enqueue(MockResponse().setBody(buildJsonObject {
            response.forEach { (key, value) -> put(key, value) }
            putJsonArray("timestamps") {
                addJsonObject { put("word", "Do"); put("start", 0.0); put("end", .2) }
                addJsonObject { put("word", "not."); put("start", .2); put("end", .5) }
            }
        }.toString()))
        val result = client.transcribeAligned(endpoint, ByteArray(32000))
        assertEquals("Do not.", result.text)
        assertEquals(listOf(TimedWord("Do", 0.0, .2), TimedWord("not.", .2, .5)), result.words)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertTrue(body["return_time_stamps"]!!.jsonPrimitive.boolean)
        server.enqueue(MockResponse().setBody(completion("Full original.")))
        assertTrue(client.transcribeAligned(endpoint, ByteArray(32000), timestamps = false).words.isEmpty())
        assertFalse(Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.containsKey("return_time_stamps"))
    }

    @Test fun checksActualConfiguredModel() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"wrong-model"}]}"""))
        try { client.test(endpoint); fail("Must reject mismatched model") }
        catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("wrong-model")) }
    }

    @Test fun streamingCanAcceptBlankButNeverTruncatedOutput() = runBlocking {
        server.enqueue(MockResponse().setBody(completion("")))
        assertEquals("", client.transcribe(endpoint, byteArrayOf(0, 0), allowEmpty = true))
        server.enqueue(MockResponse().setBody(completion("partial", "length")))
        try { client.transcribe(endpoint, byteArrayOf(0, 0), allowEmpty = true); fail("Must reject truncation") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsTruncatedAndBlankCompletions() = runBlocking {
        for ((text, finish) in listOf("partial transcript" to "length", "" to "stop")) {
            server.enqueue(MockResponse().setBody(completion(text, finish)))
            try { client.transcribe(endpoint, byteArrayOf(0, 0)); fail("Must not insert incomplete text") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun serverFailureDoesNotExposeResponseBodyOrApiKey() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503).setBody("sensitive server debug output")) }
        try { client.test(endpoint); fail("Expected failure") }
        catch (error: java.io.IOException) {
            assertTrue(error.message!!.contains("not ready"))
            assertFalse(error.message!!.contains("sensitive"))
            assertFalse(error.message!!.contains("private-key"))
        }
    }

    @Test fun transientModelFailureRetriesTheSameAudio() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("model traceback"))
        server.enqueue(MockResponse().setBody(completion("Recovered text.")))
        assertEquals("Recovered text.", client.transcribe(endpoint, byteArrayOf(0, 0)))
        assertEquals(server.takeRequest().body.readUtf8(), server.takeRequest().body.readUtf8())
        assertEquals(2, server.requestCount)
    }

    @Test fun interruptedResponseBodyRetriesWithoutReturningPartialText() = runBlocking {
        server.enqueue(MockResponse().setBody(completion("incomplete ".repeat(1000)))
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        server.enqueue(MockResponse().setBody(completion("Complete text.")))
        assertEquals("Complete text.", client.transcribe(endpoint, byteArrayOf(0, 0)))
        assertEquals(2, server.requestCount)
    }

    @Test fun permanentAuthorizationFailureIsNotRetried() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        try { client.polish(endpoint, "Draft."); fail("Expected failure") }
        catch (error: java.io.IOException) { assertTrue(error.message!!.contains("API key")) }
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellationDuringRetryBackoffDoesNotSendAnotherRequest() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val job = async { client.transcribe(endpoint, byteArrayOf(0, 0)) }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
        delay(50)
        job.cancelAndJoin()
        delay(300)
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellationCancelsAnInflightRequest() = runBlocking {
        server.enqueue(MockResponse().setBody(completion("late result")).setHeadersDelay(2, TimeUnit.SECONDS))
        val job = async { client.transcribe(endpoint, byteArrayOf(0, 0)) }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }
}
