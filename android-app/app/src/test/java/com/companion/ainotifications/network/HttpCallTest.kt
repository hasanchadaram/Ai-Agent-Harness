package com.companion.ainotifications.network

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class HttpCallTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()
    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun teardown() { server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }

    @Test fun successAndErrorResponsesAreConsumedAndConnectionsCanBeReused() = runBlocking {
        server.enqueue(MockResponse().setBody("first"))
        server.enqueue(MockResponse().setResponseCode(503).setBody("not ready"))
        val first = client.newCall(Request.Builder().url(server.url("/first")).build()).awaitResult()
        val second = client.newCall(Request.Builder().url(server.url("/second")).build()).awaitResult()
        assertTrue(first.isSuccessful); assertEquals("first", first.body)
        assertFalse(second.isSuccessful); assertEquals(503, second.code)
        assertEquals("not ready", second.body)
        assertEquals(0, server.takeRequest().sequenceNumber)
        assertEquals(1, server.takeRequest().sequenceNumber)
    }

    @Test fun stoppingTheOwningCoroutineCancelsTheActualSocketRequest() = runBlocking {
        server.enqueue(MockResponse().setBody("late result").setHeadersDelay(2, TimeUnit.SECONDS))
        val call = client.newCall(Request.Builder().url(server.url("/slow")).build())
        val task = async { call.awaitResult() }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertTrue(call.isCanceled())
    }
}
