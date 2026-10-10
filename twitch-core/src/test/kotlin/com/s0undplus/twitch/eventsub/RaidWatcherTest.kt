package com.s0undplus.twitch.eventsub

import com.s0undplus.twitch.TwitchApiException
import com.s0undplus.twitch.auth.AuthSession
import com.s0undplus.twitch.auth.AuthTokens
import com.s0undplus.twitch.auth.TokenStore
import com.s0undplus.twitch.auth.TwitchAuth
import com.s0undplus.twitch.configFor
import com.s0undplus.twitch.json
import com.s0undplus.twitch.testHttp
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class RaidWatcherTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @AfterTest
    fun tearDown() = server.close()

    private val welcome =
        """{"metadata":{"message_id":"1","message_type":"session_welcome"},"payload":{"session":{"id":"SESSION_1","status":"connected","keepalive_timeout_seconds":10}}}"""
    private val raidNotification =
        """{"metadata":{"message_type":"notification","subscription_type":"channel.raid"},"payload":{"subscription":{"type":"channel.raid"},"event":{"from_broadcaster_user_id":"10","from_broadcaster_user_login":"alice","to_broadcaster_user_id":"20","to_broadcaster_user_login":"bob","to_broadcaster_user_name":"Bob","viewers":321}}}"""

    @Test
    fun parsesEachMessageType() {
        assertEquals(EventSubMessage.Welcome("SESSION_1"), EventSubParser.parse(welcome))
        // The pre-release RFC called it `websocket`; tolerate both.
        assertEquals(
            EventSubMessage.Welcome("W"),
            EventSubParser.parse("""{"metadata":{"message_type":"session_welcome"},"payload":{"websocket":{"id":"W"}}}"""),
        )
        assertEquals(EventSubMessage.Keepalive, EventSubParser.parse("""{"metadata":{"message_type":"session_keepalive"},"payload":{}}"""))
        assertEquals(
            EventSubMessage.Reconnect("wss://new/ws"),
            EventSubParser.parse("""{"metadata":{"message_type":"session_reconnect"},"payload":{"session":{"reconnect_url":"wss://new/ws"}}}"""),
        )
        val raid = assertIs<EventSubMessage.Raid>(EventSubParser.parse(raidNotification))
        assertEquals(RaidEvent("alice", "20", "bob", "Bob", 321), raid.event)
        assertEquals(EventSubMessage.Revocation, EventSubParser.parse("""{"metadata":{"message_type":"revocation"},"payload":{}}"""))
    }

    @Test
    fun ignoresGarbageAndOtherSubscriptionTypes() {
        assertNull(EventSubParser.parse("not json"))
        assertNull(EventSubParser.parse("""{"metadata":{}}"""))
        assertNull(
            EventSubParser.parse("""{"metadata":{"message_type":"notification","subscription_type":"channel.follow"},"payload":{"event":{}}}"""),
        )
        assertNull(EventSubParser.parse("""{"metadata":{"message_type":"notification","subscription_type":"channel.raid"},"payload":{"event":{}}}"""))
    }

    private fun signedInSession(config: com.s0undplus.twitch.TwitchConfig): AuthSession = runBlocking {
        val store = object : TokenStore {
            override suspend fun load(): AuthTokens? = null
            override suspend fun save(tokens: AuthTokens?) = Unit
        }
        AuthSession(TwitchAuth(testHttp(), config), store).also {
            it.signIn(AuthTokens("access-1", "refresh-1", Long.MAX_VALUE / 2, "777", "viewer"))
        }
    }

    @Test
    fun subscribesAfterWelcomeThenEmitsRaids() = runBlocking {
        val config = configFor(server).copy(eventSubUrl = server.url("/eventsub").toString().replaceFirst("http", "ws"))
        val serverSocket = AtomicReference<WebSocket>()
        val subscribeBody = AtomicReference<String>()
        val subscribeAuth = AtomicReference<String>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/eventsub" -> MockResponse.Builder().webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            serverSocket.set(webSocket)
                            webSocket.send(welcome)
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(1000, null)
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = Unit
                    },
                ).build()
                "/helix/eventsub/subscriptions" -> {
                    subscribeBody.set(request.body!!.utf8())
                    subscribeAuth.set(request.headers["Authorization"])
                    serverSocket.get().send(raidNotification) // the subscription exists now: Twitch starts delivering
                    json("""{"data":[{"id":"sub1","status":"enabled"}]}""", 202)
                }
                else -> MockResponse.Builder().code(404).build()
            }
        }

        val watcher = RaidWatcher(testHttp(), config, signedInSession(config), Random(1))
        val raid = withTimeout(10_000) { watcher.raids("10").first() }

        assertEquals("bob", raid.toLogin)
        assertEquals(321, raid.viewers)
        assertEquals("Bearer access-1", subscribeAuth.get())
        val body = assertNotNull(subscribeBody.get())
        assertTrue(body.contains("\"type\":\"channel.raid\""), body)
        assertTrue(body.contains("\"from_broadcaster_user_id\":\"10\""), body)
        assertTrue(body.contains("\"session_id\":\"SESSION_1\""), body)
        assertTrue(body.contains("\"method\":\"websocket\""), body)
    }

    @Test
    fun givesUpQuietlyWhenTwitchRefusesTheSubscription() = runBlocking {
        val config = configFor(server).copy(eventSubUrl = server.url("/eventsub").toString().replaceFirst("http", "ws"))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/eventsub" -> MockResponse.Builder().webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send(welcome)
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(1000, null)
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = Unit
                    },
                ).build()
                "/helix/eventsub/subscriptions" -> json("""{"status":403,"message":"forbidden"}""", 403)
                else -> MockResponse.Builder().code(404).build()
            }
        }
        val watcher = RaidWatcher(testHttp(), config, signedInSession(config), Random(1))
        // A 4xx on subscribe is terminal: the flow completes (empty) instead of retrying forever.
        val collected = withTimeout(10_000) { mutableListOf<RaidEvent>().also { list -> watcher.raids("10").collect { list += it } } }
        assertTrue(collected.isEmpty())
        // ...and it did not retry: exactly one websocket connection and one subscribe attempt.
        assertEquals(2, server.requestCount)
        assertIs<TwitchApiException>(runCatching { throw TwitchApiException(403, "x") }.exceptionOrNull())
    }
}
