package com.s0undplus.twitch.playback

import com.s0undplus.twitch.TwitchApiException
import com.s0undplus.twitch.TwitchConfig
import com.s0undplus.twitch.TwitchJson
import com.s0undplus.twitch.await
import java.io.IOException
import java.util.Locale
import kotlin.random.Random
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Signed, short-lived permission to fetch one stream/VOD playlist from Twitch's usher service. */
data class PlaybackToken(val value: String, val signature: String)

/** A resolved stream: the master playlist for adaptive playback plus the individual rungs for a quality picker. */
data class PlaybackStream(val masterUrl: String, val variants: List<Variant>)

/** The channel exists but is not broadcasting right now. */
class ChannelOfflineException(val login: String) : IOException("$login is offline")

/**
 * Turns a channel login / VOD id into HLS playlists.
 *
 * Helix has no playback endpoint, so this follows the same two steps the Twitch web player does:
 *  1. POST an anonymous `PlaybackAccessToken` GraphQL query to gql.twitch.tv → `{value, signature}`
 *  2. GET the usher playlist with that token → HLS master playlist.
 *
 * This is **unofficial and undocumented** — Twitch can change it at any time. Everything that touches it is in this
 * class so a breakage has exactly one place to be fixed. Playback is anonymous: no Turbo/subscriber perks.
 */
class PlaybackApi(
    private val http: OkHttpClient,
    private val config: TwitchConfig,
    private val random: Random = Random.Default,
    private val customUrlTimeoutMs: Long = ProxyClients.CUSTOM_URL_TIMEOUT_MS,
) {
    /**
     * Resolves a live channel. [options] only matter for ad handling (see [PlaybackRoute]); the default is the plain,
     * direct request.
     */
    suspend fun live(login: String, options: PlaybackOptions = PlaybackOptions()): PlaybackStream {
        val channel = login.lowercase(Locale.ROOT)
        val route = options.route
        if (route is PlaybackRoute.CustomUrl) {
            // The proxy service fetches the stream itself, so no token is created here and none is sent to it.
            val url = customMasterUrl(route, channel) ?: throw IOException("That proxy URL is not valid")
            return load(route.clientFor(http, customUrlTimeoutMs), url, onNotFound = { ChannelOfflineException(channel) })
        }
        val client = route.clientFor(http)
        val platform = PlayerTypes.normalizePlatform(options.platform)
        val token = requestToken(
            client, isLive = true, login = channel, vodId = "",
            playerType = options.playerType, platform = platform,
        )
        return load(client, liveMasterUrl(channel, token, platform), onNotFound = { ChannelOfflineException(channel) })
    }

    suspend fun vod(videoId: String): PlaybackStream {
        val token = requestToken(
            http, isLive = false, login = "", vodId = videoId,
            playerType = PlayerTypes.SITE, platform = PlayerTypes.WEB,
        )
        return load(http, vodMasterUrl(videoId, token), onNotFound = { IOException("VOD $videoId is not available") })
    }

    internal fun liveMasterUrl(login: String, token: PlaybackToken, platform: String = PlayerTypes.WEB): HttpUrl =
        "${config.usherUrl}/api/v2/channel/hls/$login.m3u8".toHttpUrl().newBuilder()
            .commonParams(platform)
            .addQueryParameter("fast_bread", "true") // low-latency
            .addQueryParameter("sig", token.signature)
            .addQueryParameter("token", token.value)
            .build()

    internal fun vodMasterUrl(videoId: String, token: PlaybackToken): HttpUrl =
        "${config.usherUrl}/vod/v2/$videoId.m3u8".toHttpUrl().newBuilder()
            .commonParams(PlayerTypes.WEB)
            .addQueryParameter("nauthsig", token.signature)
            .addQueryParameter("nauth", token.value)
            .build()

    /**
     * The user's proxy URL with `$channel` filled in and, if asked, the playlist options usher is normally sent.
     * Parameters the user already put in the URL win, so a proxy that wants its own `fast_bread` value keeps it.
     */
    internal fun customMasterUrl(route: PlaybackRoute.CustomUrl, login: String): HttpUrl? {
        val base = ProxyUrls.expand(route.template, login) ?: return null
        if (!route.addQueryParams) return base
        val builder = base.newBuilder()
        fun addIfAbsent(name: String, value: String) {
            if (base.queryParameter(name) == null) builder.addQueryParameter(name, value)
        }
        addIfAbsent("allow_source", "true")
        addIfAbsent("allow_audio_only", "true")
        addIfAbsent("fast_bread", "true")
        addIfAbsent("include_unavailable", "true")
        addIfAbsent("supported_codecs", "h264")
        return builder.build()
    }

    private fun HttpUrl.Builder.commonParams(platform: String): HttpUrl.Builder = this
        .addQueryParameter("allow_source", "true")
        .addQueryParameter("allow_audio_only", "true")
        .addQueryParameter("playlist_include_framerate", "true")
        .addQueryParameter("platform", platform)
        // H.264 only: every TV decodes it; HEVC/AV1 rungs are a coin-flip on cheap Google TV boxes.
        .addQueryParameter("supported_codecs", "h264")
        .addQueryParameter("p", random.nextInt(0, 9_999_999).toString())

    private suspend fun load(client: OkHttpClient, url: HttpUrl, onNotFound: () -> IOException): PlaybackStream {
        val request = Request.Builder().url(url).build()
        client.newCall(request).await().use { response ->
            val text = response.body.string()
            if (response.code == 404) throw onNotFound()
            if (!response.isSuccessful) throw TwitchApiException(response.code, "Playlist request failed", text)
            // A rewriting proxy may hand back relative rung URLs; make them absolute so each plays on its own.
            val variants = MasterPlaylist.parse(text).map { it.copy(url = url.resolve(it.url)?.toString() ?: it.url) }
            if (variants.isEmpty()) throw IOException("Twitch returned a playlist with no qualities")
            return PlaybackStream(url.toString(), variants)
        }
    }

    /**
     * Asks for a token with the full query text first (robust to Twitch rotating persisted-query hashes) and falls
     * back to the persisted-query form the web player itself sends.
     */
    internal suspend fun requestToken(
        client: OkHttpClient,
        isLive: Boolean,
        login: String,
        vodId: String,
        playerType: String,
        platform: String,
    ): PlaybackToken {
        val variables = buildJsonObject {
            put("isLive", isLive)
            put("login", login)
            put("isVod", !isLive)
            put("vodID", vodId)
            put("playerType", PlayerTypes.normalize(playerType))
            put("platform", platform)
        }
        val attempts = listOf(
            buildJsonObject {
                put("operationName", "PlaybackAccessToken_Template")
                put("query", TOKEN_QUERY)
                put("variables", variables)
            },
            buildJsonObject {
                put("operationName", "PlaybackAccessToken")
                putJsonObject("extensions") {
                    putJsonObject("persistedQuery") {
                        put("version", 1)
                        put("sha256Hash", PERSISTED_HASH)
                    }
                }
                put("variables", variables)
            },
        )
        var lastFailure: IOException? = null
        for (body in attempts) {
            try {
                return postGql(client, body, isLive)
            } catch (e: TwitchApiException) {
                lastFailure = e
            } catch (e: GqlTokenException) {
                lastFailure = e
            }
        }
        throw lastFailure ?: IOException("Could not get a playback token")
    }

    private class GqlTokenException(message: String) : IOException(message)

    private suspend fun postGql(client: OkHttpClient, body: JsonObject, isLive: Boolean): PlaybackToken {
        val request = Request.Builder()
            .url(config.gqlUrl)
            .header("Client-ID", config.webClientId)
            .header("X-Device-Id", config.deviceId)
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).await().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw TwitchApiException(response.code, "GQL request failed", text)
            val root = TwitchJson.parseToJsonElement(text)
            // The persisted-query form answers with an *array* of results when sent as a batch; we send a single op.
            val obj = (root as? JsonArray)?.firstOrNull()?.jsonObject ?: root.jsonObject
            val field = if (isLive) "streamPlaybackAccessToken" else "videoPlaybackAccessToken"
            val token = (obj["data"] as? JsonObject)?.get(field) as? JsonObject
            val value = token?.get("value")?.jsonPrimitive?.contentOrNull
            val signature = token?.get("signature")?.jsonPrimitive?.contentOrNull
            if (value == null || signature == null) {
                val message = (obj["errors"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("message")
                    ?.let { (it as? JsonPrimitive)?.contentOrNull }
                throw GqlTokenException(message ?: "No playback token in response")
            }
            return PlaybackToken(value, signature)
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        const val PERSISTED_HASH = "ed230aa1e33e07eebb8928504583da78a5173989fadfb1ac94be06a04f3cdbe9"

        val TOKEN_QUERY = """
            query PlaybackAccessToken_Template(${'$'}login: String!, ${'$'}isLive: Boolean!, ${'$'}vodID: ID!, ${'$'}isVod: Boolean!, ${'$'}playerType: String!, ${'$'}platform: String!) {
              streamPlaybackAccessToken(channelName: ${'$'}login, params: {platform: ${'$'}platform, playerBackend: "mediaplayer", playerType: ${'$'}playerType}) @include(if: ${'$'}isLive) {
                value
                signature
              }
              videoPlaybackAccessToken(id: ${'$'}vodID, params: {platform: ${'$'}platform, playerBackend: "mediaplayer", playerType: ${'$'}playerType}) @include(if: ${'$'}isVod) {
                value
                signature
              }
            }
        """.trimIndent()
    }
}
