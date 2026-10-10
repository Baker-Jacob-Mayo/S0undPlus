package com.s0undplus.twitch.chat

import com.s0undplus.twitch.configFor
import com.s0undplus.twitch.json
import com.s0undplus.twitch.testHttp
import com.s0undplus.twitch.text
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer

class RecentMessagesTest {
    private lateinit var server: MockWebServer
    private lateinit var api: RecentMessagesApi

    @BeforeTest
    fun setUp() {
        server = MockWebServer().also { it.start() }
        api = RecentMessagesApi(testHttp(), configFor(server))
    }

    @AfterTest
    fun tearDown() = server.close()

    @Test
    fun parsesRawIrcLinesFromTheServiceAndAsksForTheLimit() = runTest {
        server.enqueue(
            json(
                """{"messages":["@display-name=Alice;id=m1;tmi-sent-ts=1700000000000 :alice!alice@alice.tmi.twitch.tv PRIVMSG #chan :hello chat","not irc at all"]}""",
            ),
        )
        val messages = api.fetch("Chan", limit = 25)
        assertEquals(1, messages.size)
        assertEquals("hello chat", messages.single().text)
        val request = server.takeRequest()
        assertEquals("/recent/chan", request.url.encodedPath)
        assertEquals("25", request.url.queryParameter("limit"))
    }

    @Test
    fun aSuccessfulReplyThatIsNotJsonYieldsNoHistoryInsteadOfCrashing() = runTest {
        server.enqueue(text("<html>Please sign in to the Wi-Fi</html>"))
        assertTrue(api.fetch("chan").isEmpty())
    }

    @Test
    fun httpErrorsAndEmptyBodiesYieldNoHistory() = runTest {
        server.enqueue(text("""{"error":"channel is ignored"}""", 403))
        server.enqueue(text(""))
        server.enqueue(json("""{}"""))
        repeat(3) { assertTrue(api.fetch("chan").isEmpty()) }
    }
}
