package com.s0undplus.twitch.playback

import com.s0undplus.twitch.configFor
import com.s0undplus.twitch.json
import com.s0undplus.twitch.testHttp
import com.s0undplus.twitch.text
import java.io.IOException
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer

class PlaybackApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: PlaybackApi

    @BeforeTest
    fun setUp() {
        server = MockWebServer().also { it.start() }
        api = PlaybackApi(testHttp(), configFor(server), Random(1))
    }

    @AfterTest
    fun tearDown() = server.close()

    private val tokenResponse =
        """{"data":{"streamPlaybackAccessToken":{"value":"{\"channel\":\"alice\"}","signature":"sig123","__typename":"PlaybackAccessToken"}}}"""

    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="chunked",NAME="1080p60 (source)",AUTOSELECT=YES,DEFAULT=YES
        #EXT-X-STREAM-INF:BANDWIDTH=8446000,RESOLUTION=1920x1080,CODECS="avc1.64002A,mp4a.40.2",VIDEO="chunked",FRAME-RATE=60.000
        https://edge.example/chunked.m3u8
    """.trimIndent()

    @Test
    fun liveFlowPostsGqlThenFetchesSignedUsherPlaylist() = runTest {
        server.enqueue(json(tokenResponse))
        server.enqueue(text(master))

        val stream = api.live("Alice")

        val gql = server.takeRequest()
        assertEquals("/gql", gql.url.encodedPath)
        assertEquals("POST", gql.method)
        assertEquals("kimne78kx3ncx6brgo4mv6wki5h1ko", gql.headers["Client-ID"])
        assertEquals("device-1", gql.headers["X-Device-Id"])
        val body = Json.parseToJsonElement(gql.body!!.utf8()).jsonObject
        assertEquals("PlaybackAccessToken_Template", body["operationName"]?.jsonPrimitive?.content)
        val vars = body["variables"]!!.jsonObject
        assertEquals("alice", vars["login"]?.jsonPrimitive?.content) // lowercased
        assertEquals("true", vars["isLive"]?.jsonPrimitive?.content)
        assertEquals("false", vars["isVod"]?.jsonPrimitive?.content)
        assertTrue(body["query"]!!.jsonPrimitive.content.contains("streamPlaybackAccessToken"))

        val usher = server.takeRequest()
        assertEquals("/usher/api/v2/channel/hls/alice.m3u8", usher.url.encodedPath)
        assertEquals("sig123", usher.url.queryParameter("sig"))
        assertEquals("""{"channel":"alice"}""", usher.url.queryParameter("token"))
        assertEquals("true", usher.url.queryParameter("allow_source"))
        assertEquals("true", usher.url.queryParameter("allow_audio_only"))
        assertEquals("h264", usher.url.queryParameter("supported_codecs"))

        assertEquals(usher.url.toString(), stream.masterUrl)
        assertEquals(listOf("1080p60 (source)"), stream.variants.map { it.name })
    }

    @Test
    fun usher404MeansChannelOffline() = runTest {
        server.enqueue(json(tokenResponse))
        server.enqueue(text("""{"error":"Not Found"}""", 404))
        val e = assertFailsWith<ChannelOfflineException> { api.live("alice") }
        assertEquals("alice", e.login)
    }

    @Test
    fun vodUsesVideoTokenAndNauthParams() = runTest {
        server.enqueue(
            json("""{"data":{"videoPlaybackAccessToken":{"value":"vtoken","signature":"vsig"}}}"""),
        )
        server.enqueue(text(master))

        api.vod("12345")

        val gqlVars = Json.parseToJsonElement(server.takeRequest().body!!.utf8())
            .jsonObject["variables"]!!.jsonObject
        assertEquals("12345", gqlVars["vodID"]?.jsonPrimitive?.content)
        assertEquals("true", gqlVars["isVod"]?.jsonPrimitive?.content)

        val usher = server.takeRequest()
        assertEquals("/usher/vod/v2/12345.m3u8", usher.url.encodedPath)
        assertEquals("vsig", usher.url.queryParameter("nauthsig"))
        assertEquals("vtoken", usher.url.queryParameter("nauth"))
    }

    @Test
    fun fallsBackToPersistedQueryWhenFullQueryIsRejected() = runTest {
        server.enqueue(json("""{"errors":[{"message":"PersistedQueryNotFound"}]}""", 200))
        server.enqueue(json(tokenResponse))
        server.enqueue(text(master))

        api.live("alice")

        val first = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        val second = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("PlaybackAccessToken_Template", first["operationName"]?.jsonPrimitive?.content)
        assertEquals("PlaybackAccessToken", second["operationName"]?.jsonPrimitive?.content)
        assertTrue(second["extensions"]!!.jsonObject["persistedQuery"]!!.jsonObject.containsKey("sha256Hash"))
    }

    @Test
    fun reportsGqlErrorMessageWhenBothFormsFail() = runTest {
        repeat(2) { server.enqueue(json("""{"errors":[{"message":"failed integrity check"}]}""")) }
        val e = assertFailsWith<IOException> { api.live("alice") }
        assertTrue(e.message!!.contains("failed integrity check"), e.message)
    }
}
