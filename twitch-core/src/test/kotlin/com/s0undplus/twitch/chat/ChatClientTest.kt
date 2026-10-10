package com.s0undplus.twitch.chat

import com.s0undplus.twitch.configFor
import com.s0undplus.twitch.json
import com.s0undplus.twitch.testHttp
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Server-side socket that completes the close handshake so MockWebServer can shut down cleanly. */
private open class ServerListener : WebSocketListener() {
    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = Unit
}

/** Exercises the real WebSocket path against MockWebServer's WebSocket support. */
class ChatClientTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @AfterTest
    fun tearDown() = server.close()

    private val privmsg = "@display-name=Alice;id=m1;room-id=42;user-id=1;tmi-sent-ts=5 " +
        ":alice!alice@alice.tmi.twitch.tv PRIVMSG #chan :hello chat"

    @Test
    fun logsInAnonymouslyAnswersPingAndStreamsMessages() = runBlocking {
        val received = LinkedBlockingQueue<String>()
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : ServerListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        received += text
                        if (text.startsWith("JOIN")) {
                            webSocket.send("@room-id=42 :tmi.twitch.tv ROOMSTATE #chan\r\n")
                            webSocket.send("PING :tmi.twitch.tv\r\n")
                            webSocket.send("$privmsg\r\n")
                        }
                    }
                },
            ).build(),
        )

        val client = ChatClient(testHttp(), configFor(server), Random(7))
        val events = withTimeout(10_000) { client.events("Chan").take(2).toList() }

        assertEquals(ChatEvent.Connected("42"), events[0])
        val message = assertIs<ChatEvent.Message>(events[1]).message
        assertEquals("hello chat", message.text)
        assertEquals("Alice", message.displayName)

        // Handshake lines the server saw, in order, then the PONG reply.
        val seen = generateSequence { received.poll(2, TimeUnit.SECONDS) }.take(5).toList()
        assertEquals("CAP REQ :twitch.tv/tags twitch.tv/commands", seen[0])
        assertEquals("PASS SCHMOOPIIE", seen[1])
        assertTrue(seen[2].startsWith("NICK justinfan"), seen[2])
        assertEquals("JOIN #chan", seen[3])
        assertEquals("PONG :tmi.twitch.tv", seen[4])
    }

    @Test
    fun reconnectsAfterServerDropsTheConnection() = runBlocking {
        // First connection: accept then close right away. Second: serve a ROOMSTATE.
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : ServerListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text.startsWith("JOIN")) webSocket.close(1001, "going away")
                    }
                },
            ).build(),
        )
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : ServerListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text.startsWith("JOIN")) webSocket.send("@room-id=9 :tmi.twitch.tv ROOMSTATE #chan\r\n")
                    }
                },
            ).build(),
        )

        val client = ChatClient(testHttp(), configFor(server), Random(7))
        val events = withTimeout(20_000) { client.events("chan").take(2).toList() }

        assertEquals(ChatEvent.Disconnected(willRetry = true), events[0])
        assertEquals(ChatEvent.Connected("9"), events[1])
        assertEquals(2, server.requestCount)
    }

    @Test
    fun backoffGrowsAndIsCapped() {
        val client = ChatClient(testHttp(), configFor(server), Random(1))
        val delays = (0..8).map { client.backoffMillis(it) }
        assertTrue(delays[1] > delays[0] && delays[2] > delays[1])
        assertTrue(delays.all { it in 1_000..30_499 }, delays.toString())
    }

    @Test
    fun recentMessagesParseHistoryAndIgnoreFailures() = runBlocking {
        server.enqueue(
            json("""{"messages":["${privmsg.replace("\"", "\\\"")}", "garbage line"],"error":null}"""),
        )
        val api = RecentMessagesApi(testHttp(), configFor(server))
        val history = api.fetch("Chan", limit = 50)
        assertEquals(listOf("hello chat"), history.map { it.text })
        val request = server.takeRequest()
        assertEquals("/recent/chan", request.url.encodedPath)
        assertEquals("50", request.url.queryParameter("limit"))

        server.enqueue(MockResponse.Builder().code(500).body("down").build())
        assertEquals(emptyList(), api.fetch("chan"))
    }
}
