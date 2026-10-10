package com.s0undplus.twitch.playback

import com.s0undplus.twitch.json
import com.s0undplus.twitch.testHttp
import com.s0undplus.twitch.text
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Credentials
import okhttp3.Request

class ProxyUrlsTest {
    @Test
    fun parseListKeepsUsableHttpsEntriesInOrderAndDropsTheRest() {
        val raw = """
            eu.luminous.dev/live/${'$'}channel
            https://proxy.example/play/${'$'}channel.m3u8?x=1   http://insecure.example/live/${'$'}channel
            no-placeholder.example/live/alice
            eu.luminous.dev/live/${'$'}channel
            not a url
        """.trimIndent()
        assertEquals(
            listOf("eu.luminous.dev/live/\$channel", "https://proxy.example/play/\$channel.m3u8?x=1"),
            ProxyUrls.parseList(raw),
        )
    }

    @Test
    fun expandAddsHttpsWhenTheSchemeIsMissingAndFillsInTheChannel() {
        assertEquals(
            "https://eu.luminous.dev/live/alice",
            ProxyUrls.expand("eu.luminous.dev/live/\$channel", "alice").toString(),
        )
        assertEquals(
            "https://p.example/alice.m3u8?x=1",
            ProxyUrls.expand("https://p.example/\$channel.m3u8?x=1", "alice").toString(),
        )
        assertNull(ProxyUrls.expand("http://bad host/\$channel", "alice"))
    }

    @Test
    fun pingUrlReplacesPathAndQuery() {
        assertEquals(
            "https://eu.luminous.dev/ping",
            ProxyUrls.pingUrl("eu.luminous.dev/live/\$channel?x=1").toString(),
        )
    }

    @Test
    fun shippedDefaultsAreAllUsableAndMirrorXtrasEnabledSet() {
        val defaults = ProxyDefaults.customProxies
        assertEquals(12, defaults.size)
        assertTrue(defaults.all { ProxyUrls.isUsable(it.template) }, "every default template parses")
        assertEquals(9, defaults.count { it.enabled })
        assertEquals(listOf(false, false, false), defaults.takeLast(3).map { it.enabled })
        assertEquals(defaults.size, defaults.map { it.template }.toSet().size)
        assertTrue(ProxyDefaults.httpProxy.isValid)
        assertEquals(Triple(false, false, true), ProxyDefaults.httpProxy.let { Triple(it.proxyToken, it.proxyMultivariant, it.proxyMedia) })
        assertEquals("android", ProxyDefaults.videoSwap.first().platform)
    }

    @Test
    fun playerTypesFallBackToTheDefaultForUnknownValues() {
        assertEquals("embed", PlayerTypes.normalize("embed"))
        assertEquals("site", PlayerTypes.normalize("nonsense"))
        assertEquals("site", PlayerTypes.normalize(null))
        assertEquals("android", PlayerTypes.normalizePlatform("android"))
        assertEquals("web", PlayerTypes.normalizePlatform("toaster"))
    }
}

class ProxyRoutingTest {
    private val routing = ProxyRouting.TWITCH
    private fun proxy(token: Boolean = false, multi: Boolean = false, media: Boolean = false) =
        HttpProxy("proxy.example", 8080, proxyToken = token, proxyMultivariant = multi, proxyMedia = media)

    @Test
    fun eachKindOfRequestFollowsItsOwnToggle() {
        assertTrue(routing.shouldProxy("gql.twitch.tv", proxy(token = true)))
        assertFalse(routing.shouldProxy("gql.twitch.tv", proxy(multi = true, media = true)))
        assertTrue(routing.shouldProxy("usher.ttvnw.net", proxy(multi = true)))
        assertFalse(routing.shouldProxy("usher.ttvnw.net", proxy(token = true, media = true)))
        assertTrue(routing.shouldProxy("video-weaver.sea02.hls.ttvnw.net", proxy(media = true)))
        assertTrue(routing.shouldProxy("abc123.playlist.live-video.net", proxy(media = true)))
        assertTrue(routing.shouldProxy("abc123.playlist.ttvnw.net", proxy(media = true)))
        assertFalse(routing.shouldProxy("video-weaver.sea02.hls.ttvnw.net", proxy(token = true, multi = true)))
    }

    @Test
    fun videoSegmentsAndEverythingElseAlwaysGoDirect() {
        val all = proxy(token = true, multi = true, media = true)
        for (host in listOf("video-edge-abc.abs.hls.ttvnw.net", "api.twitch.tv", "7tv.io", "usher.ttvnw.net.evil.example", "localhost")) {
            assertFalse(routing.shouldProxy(host, all), host)
        }
    }

    @Test
    fun hostMatchingIsCaseInsensitive() {
        assertTrue(routing.shouldProxy("USHER.ttvnw.NET", proxy(multi = true)))
    }
}

class ProxyClientsTest {
    private lateinit var proxy: MockWebServer
    private lateinit var origin: MockWebServer

    @BeforeTest
    fun setUp() {
        proxy = MockWebServer().also { it.start() }
        origin = MockWebServer().also { it.start() }
    }

    @AfterTest
    fun tearDown() {
        proxy.close()
        origin.close()
    }

    private fun httpProxy(user: String? = null, password: String? = null) = HttpProxy(
        host = proxy.hostName, port = proxy.port, username = user, password = password,
        proxyToken = true, proxyMultivariant = true, proxyMedia = true,
    )

    private fun get(client: okhttp3.OkHttpClient, url: String) =
        client.newCall(Request.Builder().url(url).build()).execute()

    @Test
    fun matchingHostsGoThroughTheProxyInAbsoluteForm() {
        proxy.enqueue(text("via proxy"))
        val client = ProxyClients.build(testHttp(), httpProxy())
        get(client, "http://usher.ttvnw.net/api/v2/channel/hls/alice.m3u8?x=1").use {
            assertEquals("via proxy", it.body.string())
        }
        assertEquals("GET http://usher.ttvnw.net/api/v2/channel/hls/alice.m3u8?x=1 HTTP/1.1", proxy.takeRequest().requestLine)
    }

    @Test
    fun nonMatchingHostsBypassTheProxyEntirely() {
        origin.enqueue(text("direct"))
        val client = ProxyClients.build(testHttp(), httpProxy())
        get(client, origin.url("/segment").toString()).use { assertEquals("direct", it.body.string()) }
        assertEquals(0, proxy.requestCount)
        assertEquals("/segment", origin.takeRequest().url.encodedPath)
    }

    @Test
    fun answersAProxyAuthenticationChallengeWithBasicCredentials() {
        proxy.enqueue(MockResponse.Builder().code(407).addHeader("Proxy-Authenticate", "Basic realm=\"p\"").build())
        proxy.enqueue(text("ok"))
        val client = ProxyClients.build(testHttp(), httpProxy("user", "pw"))
        get(client, "http://usher.ttvnw.net/x").use { assertEquals("ok", it.body.string()) }
        assertNull(proxy.takeRequest().headers["Proxy-Authorization"])
        assertEquals(Credentials.basic("user", "pw"), proxy.takeRequest().headers["Proxy-Authorization"])
    }

    @Test
    fun wrongCredentialsStopAfterOneRetryInsteadOfLooping() {
        repeat(5) {
            proxy.enqueue(MockResponse.Builder().code(407).addHeader("Proxy-Authenticate", "Basic realm=\"p\"").build())
        }
        val client = ProxyClients.build(testHttp(), httpProxy("user", "wrong"))
        get(client, "http://usher.ttvnw.net/x").use { assertEquals(407, it.code) }
        assertEquals(2, proxy.requestCount)
    }

    @Test
    fun anIncompleteProxyIsRefusedUpFront() {
        assertFailsWith<IOException> { ProxyClients.build(testHttp(), HttpProxy("", 8080)) }
        assertFailsWith<IOException> { ProxyClients.build(testHttp(), HttpProxy("proxy.example", 0)) }
    }
}

class ProxyHealthTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @AfterTest
    fun tearDown() = server.close()

    private val template get() = server.url("/live/\$channel").toString()

    @Test
    fun anyBooleanOnlineFieldMeansTheServiceIsUp() = runTest {
        server.enqueue(json("""{"online":true,"region":"eu"}"""))
        server.enqueue(json("""{"online":false}""")) // one service reports this while working
        assertTrue(ProxyHealth.responds(testHttp(), template))
        assertTrue(ProxyHealth.responds(testHttp(), template))
        assertEquals("/ping", server.takeRequest().url.encodedPath)
    }

    @Test
    fun otherAnswersMeanItIsNot() = runTest {
        server.enqueue(json("""{"status":"ok"}"""))
        server.enqueue(text("<html>nope</html>"))
        server.enqueue(json("""{"online":true}""", code = 503))
        repeat(3) { assertFalse(ProxyHealth.responds(testHttp(), template)) }
    }

    @Test
    fun unreachableMeansNotUpRatherThanAnError() = runTest {
        val gone = MockWebServer().also { it.start() }
        val dead = gone.url("/live/\$channel").toString()
        gone.close()
        assertFalse(ProxyHealth.responds(testHttp(), dead))
        assertFalse(ProxyHealth.responds(testHttp(), "not a url"))
    }
}
