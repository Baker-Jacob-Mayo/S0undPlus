package com.s0undplus.tv.fake

import android.graphics.Bitmap
import com.s0undplus.twitch.TwitchConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okio.Buffer

/**
 * A stand-in for every service the app talks to, served from a MockWebServer on the emulator's own loopback.
 *
 * Tests register answers with [on] (matched by longest path prefix); anything unregistered is a 404, which the app
 * must cope with anyway. [httpClient] refuses to reach any other host, so a test can never touch the real internet,
 * and [blockedHosts] records every attempt so [HermeticRule] can fail the test that made one.
 */
object FakeTwitch {
    private val loopback: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val lock = Any()
    private var server: MockWebServer? = null
    private val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()

    /** Every request the fake received, as "METHOD /path?query", oldest first. */
    val requests = CopyOnWriteArrayList<String>()

    /** Hosts the app tried to reach that are not the fake server. */
    val blockedHosts = CopyOnWriteArrayList<String>()

    val baseUrl: String
        get() = "http://127.0.0.1:${requireNotNull(server) { "FakeTwitch.start() was not called" }.port}"

    fun start() {
        synchronized(lock) {
            if (server != null) return
            val created = MockWebServer()
            created.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
            }
            // Bind off the calling thread: the first caller can be the main thread, which must not touch the network.
            var failure: Throwable? = null
            thread(name = "fake-twitch-start") {
                try {
                    created.start(loopback, 0)
                } catch (t: Throwable) {
                    failure = t
                }
            }.join()
            failure?.let { throw IllegalStateException("Could not start the fake Twitch server", it) }
            server = created
            installDefaults()
        }
    }

    /** Back to the default answers, and forget what was requested. */
    fun reset() {
        routes.clear()
        requests.clear()
        blockedHosts.clear()
        installDefaults()
    }

    /** Answers requests whose path starts with [pathPrefix]; the longest matching prefix wins. */
    fun on(pathPrefix: String, answer: (RecordedRequest) -> MockResponse) {
        routes[pathPrefix] = answer
    }

    /** Requests the fake received whose path-and-query contains [fragment]. */
    fun requestsMatching(fragment: String): List<String> = requests.filter { fragment in it }

    fun config(): TwitchConfig {
        val base = baseUrl
        return TwitchConfig(
            clientId = "test-client-id",
            deviceId = "0".repeat(32),
            helixUrl = "$base/helix",
            oauthUrl = "$base/oauth2",
            gqlUrl = "$base/gql",
            usherUrl = "$base/usher",
            ircUrl = "ws://127.0.0.1:${requireNotNull(server).port}/irc",
            eventSubUrl = "ws://127.0.0.1:${requireNotNull(server).port}/eventsub",
            recentMessagesUrl = "$base/recent-messages",
            bttvUrl = "$base/bttv",
            sevenTvUrl = "$base/7tv",
            ffzUrl = "$base/ffz",
        )
    }

    fun httpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(Interceptor { chain ->
            val host = chain.request().url.host
            if (host == "127.0.0.1" || host == "localhost") {
                chain.proceed(chain.request())
            } else {
                blockedHosts.add(host)
                throw IOException("Blocked in the emulator tests (only the fake server is reachable): $host")
            }
        })
        .build()

    private fun answer(request: RecordedRequest): MockResponse {
        requests.add("${request.method} ${request.target}")
        val path = request.url.encodedPath
        val handler = routes.entries.filter { path.startsWith(it.key) }.maxByOrNull { it.key.length }?.value
        return handler?.invoke(request) ?: json("""{"error":"Not Found","status":404}""", 404)
    }

    fun json(body: String, code: Int = 200): MockResponse =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    fun text(body: String, code: Int = 200, contentType: String = "text/plain"): MockResponse =
        MockResponse.Builder().code(code).addHeader("Content-Type", contentType).body(body).build()

    /** A tiny solid-colour image, so thumbnails decode instead of erroring. */
    private fun thumbnail(): MockResponse {
        val bitmap = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF4B2E83.toInt()) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return MockResponse.Builder().code(200).addHeader("Content-Type", "image/png").body(Buffer().write(bytes)).build()
    }

    private fun installDefaults() {
        on("/thumb/") { thumbnail() }
        on("/helix/streams/followed") { json(streams(ALPHA, BETA)) }
        on("/helix/streams") { json(streams(ALPHA, BETA, GAMMA)) }
        on("/helix/channels/followed") {
            json(
                """{"data":[
                    {"broadcaster_id":"100","broadcaster_login":"alpha","broadcaster_name":"Alpha"},
                    {"broadcaster_id":"200","broadcaster_login":"beta","broadcaster_name":"Beta"},
                    {"broadcaster_id":"400","broadcaster_login":"delta","broadcaster_name":"Delta"}
                ],"pagination":{}}""",
            )
        }
        on("/helix/games/top") {
            json("""{"data":[{"id":"1","name":"Game One","box_art_url":"$baseUrl/thumb/game-{width}x{height}.png"}],"pagination":{}}""")
        }
        on("/helix/users") {
            json("""{"data":[{"id":"100","login":"alpha","display_name":"Alpha","profile_image_url":"","description":"","broadcaster_type":""}]}""")
        }
        on("/helix/chat/badges") { json("""{"data":[]}""") }
        on("/helix/search/channels") { json("""{"data":[],"pagination":{}}""") }
        on("/helix/search/categories") { json("""{"data":[],"pagination":{}}""") }
    }

    class FakeStream(val id: String, val login: String, val name: String, val title: String, val game: String, val viewers: Int)

    val ALPHA = FakeStream("100", "alpha", "Alpha", "Alpha stream title", "Game One", 1234)
    val BETA = FakeStream("200", "beta", "Beta", "Beta stream title", "Game Two", 56)
    val GAMMA = FakeStream("300", "gamma", "Gamma", "Gamma stream title", "Game One", 7)

    fun streams(vararg list: FakeStream): String = list.joinToString(prefix = """{"data":[""", postfix = """],"pagination":{}}""") {
        """{"id":"s${it.id}","user_id":"${it.id}","user_login":"${it.login}","user_name":"${it.name}","game_id":"1",""" +
            """"game_name":"${it.game}","type":"live","title":"${it.title}","viewer_count":${it.viewers},""" +
            """"started_at":"2026-10-11T10:00:00Z","language":"en","is_mature":false,""" +
            """"thumbnail_url":"$baseUrl/thumb/${it.login}-{width}x{height}.png"}"""
    }
}
