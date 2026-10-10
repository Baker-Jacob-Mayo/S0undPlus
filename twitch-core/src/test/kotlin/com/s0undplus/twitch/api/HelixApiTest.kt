package com.s0undplus.twitch.api

import com.s0undplus.twitch.TwitchApiException
import com.s0undplus.twitch.auth.AuthSession
import com.s0undplus.twitch.auth.AuthTokens
import com.s0undplus.twitch.auth.TokenStore
import com.s0undplus.twitch.auth.TwitchAuth
import com.s0undplus.twitch.chat.BadgeRef
import com.s0undplus.twitch.configFor
import com.s0undplus.twitch.json
import com.s0undplus.twitch.testHttp
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer

class HelixApiTest {
    private lateinit var server: MockWebServer
    private lateinit var session: AuthSession
    private lateinit var helix: HelixApi

    private class MemoryStore : TokenStore {
        override suspend fun load(): AuthTokens? = null
        override suspend fun save(tokens: AuthTokens?) = Unit
    }

    @BeforeTest
    fun setUp() = runTest {
        server = MockWebServer().also { it.start() }
        val config = configFor(server)
        session = AuthSession(TwitchAuth(testHttp(), config), MemoryStore())
        session.signIn(AuthTokens("access-1", "refresh-1", Long.MAX_VALUE / 2, userId = "777", login = "viewer"))
        helix = HelixApi(testHttp(), config, session)
    }

    @AfterTest
    fun tearDown() = server.close()

    private val streamJson = """
        {"data":[{"id":"1","user_id":"10","user_login":"alice","user_name":"Alice","game_id":"33","game_name":"Just Chatting",
        "type":"live","title":"hi","viewer_count":1234,"started_at":"2026-01-01T00:00:00Z","language":"en",
        "thumbnail_url":"https://cdn/live_user_alice-{width}x{height}.jpg","tags":["x"],"is_mature":false}],
        "pagination":{"cursor":"CUR"}}
    """.trimIndent()

    @Test
    fun followedStreamsSendsAuthHeadersAndUserIdAndPaginates() = runTest {
        server.enqueue(json(streamJson))
        val page = helix.followedStreams()

        val request = server.takeRequest()
        assertEquals("/helix/streams/followed", request.url.encodedPath)
        assertEquals("777", request.url.queryParameter("user_id"))
        assertEquals("Bearer access-1", request.headers["Authorization"])
        assertEquals("test-client", request.headers["Client-Id"])

        assertEquals("CUR", page.nextCursor)
        val stream = page.items.single()
        assertEquals("alice", stream.userLogin)
        assertEquals(1234, stream.viewerCount)
        assertEquals("https://cdn/live_user_alice-440x248.jpg", stream.thumbnail())
    }

    @Test
    fun omitsNullParamsAndHandlesMissingCursor() = runTest {
        server.enqueue(json("""{"data":[],"pagination":{}}"""))
        val page = helix.streams(gameId = "33", language = null)
        val url = server.takeRequest().url
        assertEquals("33", url.queryParameter("game_id"))
        assertNull(url.queryParameter("language"))
        assertNull(url.queryParameter("after"))
        assertNull(page.nextCursor)
    }

    @Test
    fun unauthorizedTriggersOneRefreshAndRetry() = runTest {
        server.enqueue(json("""{"status":401,"message":"Invalid OAuth token"}""", 401))
        server.enqueue(json("""{"access_token":"access-2","refresh_token":"refresh-2","expires_in":3600}"""))
        server.enqueue(json(streamJson))

        val page = helix.followedStreams()
        assertEquals(1, page.items.size)

        assertEquals("Bearer access-1", server.takeRequest().headers["Authorization"])
        assertEquals("/oauth2/token", server.takeRequest().url.encodedPath)
        assertEquals("Bearer access-2", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun secondUnauthorizedSurfacesAsException() = runTest {
        // On the wire: request → 401, token refresh → ok, retry → 401 again. No third attempt.
        server.enqueue(json("""{"status":401,"message":"nope"}""", 401))
        server.enqueue(json("""{"access_token":"access-2","expires_in":3600}"""))
        server.enqueue(json("""{"status":401,"message":"nope"}""", 401))
        val failure = assertFailsWith<TwitchApiException> { helix.followedStreams() }
        assertEquals(401, failure.status)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun serverErrorsBecomeTwitchApiException() = runTest {
        server.enqueue(json("""{"status":500,"message":"boom"}""", 500))
        val e = assertFailsWith<TwitchApiException> { helix.topCategories() }
        assertEquals(500, e.status)
    }

    @Test
    fun videosAskForArchives() = runTest {
        server.enqueue(
            json(
                """{"data":[{"id":"v1","user_id":"10","user_login":"alice","user_name":"Alice","title":"VOD",
                "created_at":"2026-01-01T00:00:00Z","thumbnail_url":"https://cdn/v-{width}x{height}.jpg","view_count":5,
                "type":"archive","duration":"1h2m3s"}],"pagination":{}}""",
            ),
        )
        val video = helix.videos("10").items.single()
        assertEquals(3723L, video.durationSeconds)
        assertEquals("archive", server.takeRequest().url.queryParameter("type"))
    }

    @Test
    fun usersByIdChunksAtOneHundred() = runTest {
        val ids = (1..150).map { it.toString() }
        server.enqueue(json("""{"data":[]}"""))
        server.enqueue(json("""{"data":[]}"""))
        helix.usersById(ids)
        assertEquals(100, server.takeRequest().url.queryParameterValues("id").size)
        assertEquals(50, server.takeRequest().url.queryParameterValues("id").size)
    }

    @Test
    fun streamsByLoginsSendsRepeatedUserLoginParamsAndChunks() = runTest {
        val logins = (1..130).map { "chan$it" }
        server.enqueue(json(streamJson))
        server.enqueue(json("""{"data":[]}"""))
        val live = helix.streamsByLogins(logins + logins.take(5)) // duplicates are dropped
        assertEquals(1, live.size)
        val first = server.takeRequest().url
        assertEquals(100, first.queryParameterValues("user_login").size)
        assertEquals("100", first.queryParameter("first"))
        assertEquals(30, server.takeRequest().url.queryParameterValues("user_login").size)
    }

    @Test
    fun channelBadgesOverrideGlobalOnes() = runTest {
        server.enqueue(
            json("""{"data":[{"set_id":"subscriber","versions":[{"id":"0","image_url_1x":"g1","image_url_2x":"g2"}]},{"set_id":"vip","versions":[{"id":"1","image_url_1x":"v1","image_url_2x":"v2"}]}]}"""),
        )
        server.enqueue(
            json("""{"data":[{"set_id":"subscriber","versions":[{"id":"0","image_url_1x":"c1","image_url_2x":"c2"}]}]}"""),
        )
        val badges = helix.badges("10")
        assertEquals("c2", badges.url(BadgeRef("subscriber", "0")))
        assertEquals("v2", badges.url(BadgeRef("vip", "1")))
        assertNull(badges.url(BadgeRef("nope", "1")))
        assertEquals("/helix/chat/badges/global", server.takeRequest().url.encodedPath)
        assertEquals("10", server.takeRequest().url.queryParameter("broadcaster_id"))
    }
}
