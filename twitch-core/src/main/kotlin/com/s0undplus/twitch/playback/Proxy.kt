package com.s0undplus.twitch.playback

import com.s0undplus.twitch.TwitchJson
import com.s0undplus.twitch.await
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The `playerType` and `platform` Twitch's web player sends with its playback-token request. The normal player sends
 * `site`/`web`; the other values are other embedding surfaces. Whether a different pair changes which ads are served is
 * Twitch's call and changes over time, so they are exposed as settings rather than assumed to work.
 */
object PlayerTypes {
    const val SITE = "site"
    const val EMBED = "embed"
    const val POPOUT = "popout"
    const val AUTOPLAY = "autoplay"
    const val PICTURE_BY_PICTURE = "picture-by-picture"

    const val WEB = "web"
    const val ANDROID = "android"

    val ALL: List<String> = listOf(SITE, EMBED, POPOUT, AUTOPLAY, PICTURE_BY_PICTURE)
    val PLATFORMS: List<String> = listOf(WEB, ANDROID)

    /** Anything unrecognised (e.g. a hand-edited setting) falls back to the default instead of being sent to Twitch. */
    fun normalize(value: String?): String = ALL.firstOrNull { it == value } ?: SITE

    fun normalizePlatform(value: String?): String = PLATFORMS.firstOrNull { it == value } ?: WEB
}

/**
 * A plain HTTP proxy the user runs or rents (Xtra calls this the "stream proxy"). Which of Twitch's hosts go through it
 * is chosen per kind of request, because proxies differ in what they serve:
 *  - [proxyToken]: the anonymous playback-token request (gql.twitch.tv)
 *  - [proxyMultivariant]: the master-playlist request (usher.ttvnw.net)
 *  - [proxyMedia]: the per-quality *media playlist* requests (not the video segments, which stay direct). The defaults
 *    match Xtra's: only the media playlists go through the proxy.
 */
data class HttpProxy(
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
    val proxyToken: Boolean = false,
    val proxyMultivariant: Boolean = false,
    val proxyMedia: Boolean = true,
) {
    val isValid: Boolean get() = host.isNotBlank() && port in 1..65535
}

/** Which request hosts count as "token", "master playlist" and "media playlist" when deciding what an [HttpProxy] carries. */
data class ProxyRouting(val token: Regex, val multivariant: Regex, val media: Regex) {
    fun shouldProxy(host: String, proxy: HttpProxy): Boolean {
        val h = host.lowercase()
        return when {
            token.matches(h) -> proxy.proxyToken
            multivariant.matches(h) -> proxy.proxyMultivariant
            media.matches(h) -> proxy.proxyMedia
            else -> false
        }
    }

    companion object {
        /** The same host patterns Xtra routes by; video segment hosts match none of them and always go direct. */
        val TWITCH = ProxyRouting(
            token = Regex("""gql\.twitch\.tv"""),
            multivariant = Regex("""usher\.ttvnw\.net"""),
            media = Regex("""(?:[a-z0-9-]+\.playlist\.(?:live-video|ttvnw)\.net|video-weaver\.[a-z0-9-]+\.hls\.ttvnw\.net)"""),
        )
    }
}

/** How a playlist request reaches Twitch. */
sealed interface PlaybackRoute {
    /** Straight to Twitch. */
    data object Direct : PlaybackRoute

    /**
     * The master playlist comes from a user-supplied URL instead of Twitch. [template] must contain `$channel` (see
     * [ProxyUrls]). Nothing is requested from Twitch first: such a service fetches the stream on its own, so no
     * playback token is created or sent. With [addQueryParams] the usual playlist options are appended unless the URL
     * already sets them.
     */
    data class CustomUrl(val template: String, val addQueryParams: Boolean = true) : PlaybackRoute

    /** Requests go to Twitch through [proxy], limited to the hosts [proxy] opts into. */
    data class Http(val proxy: HttpProxy, val routing: ProxyRouting = ProxyRouting.TWITCH) : PlaybackRoute
}

/** Per-request playback choices; the defaults are exactly what the app did before ad handling existed. */
data class PlaybackOptions(
    val route: PlaybackRoute = PlaybackRoute.Direct,
    val playerType: String = PlayerTypes.SITE,
    val platform: String = PlayerTypes.WEB,
)

/** The client that carries this route's requests, derived from [base] so connection pools and settings are shared. */
fun PlaybackRoute.clientFor(base: OkHttpClient): OkHttpClient = when (this) {
    PlaybackRoute.Direct, is PlaybackRoute.CustomUrl -> base
    is PlaybackRoute.Http -> ProxyClients.build(base, proxy, routing)
}

object ProxyClients {
    /** Short on purpose: a dead proxy should hand over to the next one (or to hiding the ad) in seconds, not minutes. */
    private const val CONNECT_TIMEOUT_SECONDS = 8L

    fun build(base: OkHttpClient, proxy: HttpProxy, routing: ProxyRouting = ProxyRouting.TWITCH): OkHttpClient {
        if (!proxy.isValid) throw IOException("The HTTP proxy needs a host and a port")
        val address = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(proxy.host.trim(), proxy.port))
        val selector = object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> {
                val host = uri?.host ?: return listOf(Proxy.NO_PROXY)
                return listOf(if (routing.shouldProxy(host, proxy)) address else Proxy.NO_PROXY)
            }

            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
        }
        val builder = base.newBuilder()
            .proxySelector(selector)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!proxy.username.isNullOrEmpty()) {
            val credential = Credentials.basic(proxy.username, proxy.password.orEmpty())
            builder.proxyAuthenticator { _, response ->
                // Answer a 407 once; a second 407 means the credentials are wrong, and looping would just hammer the proxy.
                if (response.request.header("Proxy-Authorization") != null) {
                    null
                } else {
                    response.request.newBuilder().header("Proxy-Authorization", credential).build()
                }
            }
        }
        return builder.build()
    }
}

/**
 * Custom proxy playlist URLs: whatever the user pastes, with `$channel` standing in for the channel login, e.g.
 * `proxy.example/live/$channel`. A missing scheme means `https`, as in Xtra.
 */
object ProxyUrls {
    const val CHANNEL_PLACEHOLDER = "\$channel"

    /**
     * Splits a pasted list on whitespace and keeps the entries that can actually be used: `https` (Android blocks
     * cleartext by default and the viewing traffic should not travel unencrypted anyway) and containing `$channel`
     * (otherwise every channel would be sent to the same playlist).
     */
    fun parseList(raw: String): List<String> =
        raw.split(Regex("\\s+"))
            .filter { it.isNotEmpty() && isUsable(it) }
            .distinct()

    fun isUsable(template: String): Boolean =
        template.contains(CHANNEL_PLACEHOLDER) && expand(template, "channel")?.scheme == "https"

    fun expand(template: String, channel: String): HttpUrl? {
        val withScheme = if (template.contains("://")) template else "https://$template"
        return withScheme.replace(CHANNEL_PLACEHOLDER, channel).toHttpUrlOrNull()
    }

    /** The liveness endpoint these services expose next to the playlist: `https://host/ping`. */
    fun pingUrl(template: String): HttpUrl? =
        expand(template, "channel")?.newBuilder()?.encodedPath("/ping")?.query(null)?.build()
}

/** One entry of the custom-proxy list: where, and whether it is switched on. */
data class ProxyEntry(val template: String, val enabled: Boolean = true)

/** One entry of the video-swap list: a token identity to try while an ad plays. */
data class VideoSwapEntry(val platform: String, val playerType: String, val enabled: Boolean = true)

/**
 * Starting values copied from the lists Xtra ships (as of this writing). They are third-party services that the app's
 * author does not run and that can disappear or change behaviour at any time, which is why using any of them is an
 * explicit opt-in in the app.
 */
object ProxyDefaults {
    val customProxies: List<ProxyEntry> = listOf(
        ProxyEntry("eu.luminous.dev/live/\$channel"),
        ProxyEntry("eu2.luminous.dev/live/\$channel"),
        ProxyEntry("eu3.luminous.dev/live/\$channel"),
        ProxyEntry("as.luminous.dev/live/\$channel"),
        ProxyEntry("lb-eu.cdn-perfprod.com/live/\$channel"),
        ProxyEntry("lb-eu2.cdn-perfprod.com/live/\$channel"),
        ProxyEntry("lb-eu3.cdn-perfprod.com/live/\$channel"),
        ProxyEntry("lb-eu4.cdn-perfprod.com/live/\$channel"),
        ProxyEntry("lb-eu5.cdn-perfprod.com/live/\$channel"),
        ProxyEntry("lb-na.cdn-perfprod.com/live/\$channel", enabled = false),
        ProxyEntry("lb-sa.cdn-perfprod.com/live/\$channel", enabled = false),
        ProxyEntry("lb-as.cdn-perfprod.com/live/\$channel", enabled = false),
    )

    val httpProxy = HttpProxy(host = "firefox.api.cdn-perfprod.com", port = 2023)

    val videoSwap: List<VideoSwapEntry> = listOf(
        VideoSwapEntry(PlayerTypes.ANDROID, PlayerTypes.AUTOPLAY),
        VideoSwapEntry(PlayerTypes.WEB, PlayerTypes.EMBED, enabled = false),
        VideoSwapEntry(PlayerTypes.WEB, PlayerTypes.POPOUT, enabled = false),
    )
}

/**
 * Stores the editable lists as plain text, one entry per line, a leading `#` marking an entry that is switched off.
 * Lines that are not usable are dropped on read, so a hand-edited or corrupted value can't poison playback.
 */
object ProxyEntryCodec {
    fun encode(entries: List<ProxyEntry>): String =
        entries.joinToString("\n") { (if (it.enabled) "" else "#") + it.template }

    fun decode(raw: String): List<ProxyEntry> =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val template = line.removePrefix("#").trim()
                if (ProxyUrls.isUsable(template)) ProxyEntry(template, enabled = !line.startsWith("#")) else null
            }
            .distinctBy { it.template }
            .toList()

    fun encodeSwap(entries: List<VideoSwapEntry>): String =
        entries.joinToString("\n") { (if (it.enabled) "" else "#") + "${it.platform}/${it.playerType}" }

    fun decodeSwap(raw: String): List<VideoSwapEntry> =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val body = line.removePrefix("#").trim()
                val platform = body.substringBefore('/', "")
                val playerType = body.substringAfter('/', "")
                if (platform in PlayerTypes.PLATFORMS && playerType in PlayerTypes.ALL) {
                    VideoSwapEntry(platform, playerType, enabled = !line.startsWith("#"))
                } else {
                    null
                }
            }
            .distinct()
            .toList()
}

/** Asks a custom proxy whether it is alive, the way Xtra's proxy list shows "Online" / "Offline". */
object ProxyHealth {
    private const val TIMEOUT_SECONDS = 6L

    /**
     * True when `/ping` answers with a JSON object that has a boolean `online` field. The *value* is deliberately not
     * checked: at least one of these services answers `{"online": false}` while serving streams fine, so only "it
     * answers sensibly" counts.
     */
    suspend fun responds(http: OkHttpClient, template: String): Boolean {
        val url = ProxyUrls.pingUrl(template) ?: return false
        val client = http.newBuilder().callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
        return try {
            client.newCall(Request.Builder().url(url).build()).await().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) return false
                val online = (TwitchJson.parseToJsonElement(body) as? JsonObject)?.get("online") as? JsonPrimitive
                online?.content == "true" || online?.content == "false"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }
}
