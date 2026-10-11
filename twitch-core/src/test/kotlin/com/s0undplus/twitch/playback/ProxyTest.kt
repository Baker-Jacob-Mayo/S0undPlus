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
    fun aQueryStringContainingASchemeDoesNotConfuseTheSchemeDetection() {
        val template = "proxy.example/live/\$channel?upstream=https://usher.ttvnw.net/x"
        assertTrue(ProxyUrls.isUsable(template))
        assertEquals("https://proxy.example/live/alice?upstream=https://usher.ttvnw.net/x", ProxyUrls.expand(template, "alice").toString())
    }

    @Test
    fun aTemplateThatResolvesToTheSameAddressForEveryChannelIsRejected() {
        assertFalse(ProxyUrls.isUsable("x.example/live/\$channel/../../admin"))
        assertFalse(ProxyUrls.isUsable("x.example/\$channel/.."))
        assertTrue(ProxyUrls.isUsable("x.example/live/\$channel/index"))
    }

    @Test
    fun twoPastedAddressesOnOneLineAreNotOneUsableTemplate() {
        // the whole-field check in the settings screen runs parseList first; as a single template this has a space
        assertFalse(ProxyUrls.isUsable("a.example/live/\$channel b.example/live/\$channel"))
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

class HttpProxyValidityTest {
    @Test
    fun onlyABareHostAndARealPortAreValid() {
        assertTrue(HttpProxy("proxy.example", 8080).isValid)
        assertTrue(HttpProxy("10.0.0.5", 3128).isValid)
        assertFalse(HttpProxy("http://proxy.example", 8080).isValid)
        assertFalse(HttpProxy("proxy.example/path", 8080).isValid)
        assertFalse(HttpProxy("proxy example", 8080).isValid)
        assertFalse(HttpProxy("", 8080).isValid)
        assertFalse(HttpProxy("proxy.example", 0).isValid)
        assertFalse(HttpProxy("proxy.example", 65536).isValid)
    }
}

class SourceCooldownTest {
    private var now = 0L
    private val cooldown = SourceCooldown<String>(windowMs = 1_000, nowMs = { now })

    @Test
    fun aFailedSourceIsSkippedForTheWindowAndThenAvailableAgain() {
        cooldown.failed("a")
        assertTrue(cooldown.isCoolingDown("a"))
        assertFalse(cooldown.isCoolingDown("b"))
        now = 999
        assertEquals(listOf("b", "c"), cooldown.available(listOf("a", "b", "c")) { it })
        now = 1_000
        assertEquals(listOf("a", "b", "c"), cooldown.available(listOf("a", "b", "c")) { it })
    }

    @Test
    fun failingAgainRestartsTheWindowAndClearForgetsEverything() {
        cooldown.failed("a")
        now = 900
        cooldown.failed("a")
        now = 1_500
        assertTrue(cooldown.isCoolingDown("a"))
        cooldown.clear()
        assertFalse(cooldown.isCoolingDown("a"))
    }
}

class ProxyEntryCodecTest {
    @Test
    fun defaultsSurviveAnEncodeDecodeRoundTripWithTheirEnabledFlags() {
        val decoded = ProxyEntryCodec.decode(ProxyEntryCodec.encode(ProxyDefaults.customProxies))
        assertEquals(ProxyDefaults.customProxies, decoded)
        assertEquals(ProxyDefaults.videoSwap, ProxyEntryCodec.decodeSwap(ProxyEntryCodec.encodeSwap(ProxyDefaults.videoSwap)))
    }

    @Test
    fun junkLinesAreDroppedAndDuplicatesCollapsed() {
        val raw = "#a.example/live/\$channel\n\n  b.example/live/\$channel  \nhttp://c.example/\$channel\nno-channel.example\nb.example/live/\$channel"
        assertEquals(
            listOf(ProxyEntry("a.example/live/\$channel", false), ProxyEntry("b.example/live/\$channel", true)),
            ProxyEntryCodec.decode(raw),
        )
    }

    @Test
    fun theSameSwapIdentityOnTwoLinesKeepsOnlyTheFirstWhateverItsState() {
        assertEquals(listOf(VideoSwapEntry("web", "embed", true)), ProxyEntryCodec.decodeSwap("web/embed\n#web/embed"))
        assertEquals(listOf(VideoSwapEntry("web", "embed", false)), ProxyEntryCodec.decodeSwap("#web/embed\nweb/embed"))
    }

    @Test
    fun swapEntriesAreValidatedAgainstKnownPlatformsAndPlayerTypes() {
        assertEquals(
            listOf(VideoSwapEntry("web", "embed", false), VideoSwapEntry("android", "autoplay", true)),
            ProxyEntryCodec.decodeSwap("#web/embed\nandroid/autoplay\nweb/nonsense\nplatform/embed\nweb\n"),
        )
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
