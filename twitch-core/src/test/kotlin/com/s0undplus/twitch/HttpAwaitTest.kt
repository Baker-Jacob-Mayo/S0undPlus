package com.s0undplus.twitch

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `await()` is what every API call goes through, and the callers are usually on Android's main thread, where reading
 * a response body that is still arriving throws NetworkOnMainThreadException. These pin the guarantee that makes that
 * impossible: when `await()` returns, the whole body is already in memory.
 */
class HttpAwaitTest {
    private val server = MockWebServer()

    @BeforeTest
    fun setUp() = server.start()

    @AfterTest
    fun tearDown() = server.close()

    private fun call(path: String = "/x") = testHttp().newCall(Request.Builder().url(server.url(path)).build())

    @Test
    fun theBodyHasFullyArrivedBeforeAwaitReturns() = runBlocking {
        val body = "x".repeat(2_000)
        server.enqueue(MockResponse.Builder().body(body).bodyDelay(500, TimeUnit.MILLISECONDS).build())
        val startedAt = System.nanoTime()
        val response = call().await()
        val waitedMs = (System.nanoTime() - startedAt) / 1_000_000
        // Headers come first and the body half a second later: returning at the headers would leave the read to the caller.
        assertTrue(waitedMs >= 400, "await() returned after ${waitedMs}ms, before the body could have arrived")
        val readStartedAt = System.nanoTime()
        assertEquals(body, response.body.string())
        assertTrue((System.nanoTime() - readStartedAt) / 1_000_000 < 100, "reading the body should be instant")
    }

    @Test
    fun statusHeadersAndTheRequestSurviveBuffering() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).addHeader("X-Test", "yes").body("nope").build())
        val response = call("/missing").await()
        response.use {
            assertEquals(404, it.code)
            assertEquals("yes", it.header("X-Test"))
            assertEquals("/missing", it.request.url.encodedPath)
            assertEquals("nope", it.body.string())
        }
    }

    @Test
    fun anAnswerWithNoBodyIsFine() = runBlocking {
        server.enqueue(MockResponse.Builder().code(204).build())
        call().await().use { assertEquals("", it.body.string()) }
    }

    @Test
    fun anAnswerLargerThanTheLimitFailsInsteadOfFillingMemory() {
        server.enqueue(MockResponse.Builder().body("y".repeat(500)).build())
        assertFailsWith<IOException> { runBlocking { call().await(maxBodyBytes = 100) } }
    }

    @Test
    fun anAnswerExactlyAtTheLimitIsAccepted() = runBlocking {
        server.enqueue(MockResponse.Builder().body("z".repeat(100)).build())
        call().await(maxBodyBytes = 100).use { assertEquals(100, it.body.string().length) }
    }
}
