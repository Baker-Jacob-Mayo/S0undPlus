package com.s0undplus.twitch.api

import com.s0undplus.twitch.BadgeSetDto
import com.s0undplus.twitch.Category
import com.s0undplus.twitch.ChannelResult
import com.s0undplus.twitch.FollowedChannel
import com.s0undplus.twitch.HelixList
import com.s0undplus.twitch.Page
import com.s0undplus.twitch.Stream
import com.s0undplus.twitch.TwitchApiException
import com.s0undplus.twitch.TwitchConfig
import com.s0undplus.twitch.TwitchJson
import com.s0undplus.twitch.User
import com.s0undplus.twitch.Video
import com.s0undplus.twitch.auth.AuthSession
import com.s0undplus.twitch.await
import com.s0undplus.twitch.chat.BadgeDirectory
import kotlinx.serialization.KSerializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The official Twitch Helix API. Every call needs a signed-in viewer (device-code login); an expired token is
 * refreshed transparently and a 401 is retried once with a refreshed token.
 */
class HelixApi(
    private val http: OkHttpClient,
    private val config: TwitchConfig,
    private val session: AuthSession,
) {
    /** Live streams from channels the signed-in viewer follows. Needs scope `user:read:follows`. */
    suspend fun followedStreams(after: String? = null, first: Int = 100): Page<Stream> =
        page(
            "streams/followed",
            Stream.serializer(),
            "user_id" to requireUserId(),
            "first" to first,
            "after" to after,
        )

    /** Every channel the viewer follows, live or not. Needs scope `user:read:follows`. */
    suspend fun followedChannels(after: String? = null, first: Int = 100): Page<FollowedChannel> =
        page(
            "channels/followed",
            FollowedChannel.serializer(),
            "user_id" to requireUserId(),
            "first" to first,
            "after" to after,
        )

    /** Most-watched live streams, optionally limited to one category and/or language (ISO 639-1). */
    suspend fun streams(
        gameId: String? = null,
        language: String? = null,
        after: String? = null,
        first: Int = 24,
    ): Page<Stream> = page(
        "streams",
        Stream.serializer(),
        "game_id" to gameId,
        "language" to language,
        "first" to first,
        "after" to after,
    )

    /** The live stream of one channel, or null when it is offline. */
    suspend fun streamByLogin(login: String): Stream? =
        page("streams", Stream.serializer(), "user_login" to login, "first" to 1).items.firstOrNull()

    /** Live streams among the given channel logins (offline channels are simply absent). Chunked at 100 per call. */
    suspend fun streamsByLogins(logins: List<String>): List<Stream> =
        logins.distinct().chunked(MAX_IDS_PER_REQUEST).flatMap { chunk ->
            pageOf("streams", Stream.serializer(), chunk.map { "user_login" to it } + ("first" to MAX_IDS_PER_REQUEST)).items
        }

    suspend fun topCategories(after: String? = null, first: Int = 30): Page<Category> =
        page("games/top", Category.serializer(), "first" to first, "after" to after)

    suspend fun searchChannels(query: String, liveOnly: Boolean = false, first: Int = 20): Page<ChannelResult> =
        page(
            "search/channels",
            ChannelResult.serializer(),
            "query" to query,
            "live_only" to liveOnly,
            "first" to first,
        )

    suspend fun searchCategories(query: String, first: Int = 20): Page<Category> =
        page("search/categories", Category.serializer(), "query" to query, "first" to first)

    suspend fun userByLogin(login: String): User? =
        page("users", User.serializer(), "login" to login).items.firstOrNull()

    /** Looks up profiles for [ids]; Helix allows 100 per request so larger lists are chunked. */
    suspend fun usersById(ids: List<String>): List<User> =
        ids.distinct().chunked(MAX_IDS_PER_REQUEST).flatMap { chunk ->
            pageOf("users", User.serializer(), chunk.map { "id" to it }).items
        }

    /** Past broadcasts (VODs) of a channel, newest first. */
    suspend fun videos(userId: String, after: String? = null, first: Int = 20): Page<Video> =
        page(
            "videos",
            Video.serializer(),
            "user_id" to userId,
            "type" to "archive",
            "first" to first,
            "after" to after,
        )

    /** Global chat badges plus [broadcasterId]'s custom ones (channel sets override global ones). */
    suspend fun badges(broadcasterId: String?): BadgeDirectory {
        val global = pageOf("chat/badges/global", BadgeSetDto.serializer(), emptyList()).items
        val channel = if (broadcasterId == null) {
            emptyList()
        } else {
            pageOf("chat/badges", BadgeSetDto.serializer(), listOf("broadcaster_id" to broadcasterId)).items
        }
        return BadgeDirectory.from(global, channel)
    }

    private suspend fun requireUserId(): String =
        session.freshTokens()?.userId ?: throw TwitchApiException(401, "Not signed in")

    private suspend fun <T> page(path: String, serializer: KSerializer<T>, vararg params: Pair<String, Any?>): Page<T> =
        pageOf(path, serializer, params.toList())

    /** Null-valued params are omitted; repeated keys (`id=1&id=2`) are allowed. */
    private suspend fun <T> pageOf(
        path: String,
        serializer: KSerializer<T>,
        params: List<Pair<String, Any?>>,
    ): Page<T> {
        val builder = "${config.helixUrl}/$path".toHttpUrl().newBuilder()
        for ((key, value) in params) {
            if (value != null) builder.addQueryParameter(key, value.toString())
        }
        val envelope = execute(builder.build(), HelixList.serializer(serializer))
        return Page(envelope.data, envelope.pagination?.cursor?.takeIf { it.isNotEmpty() })
    }

    private suspend fun <T> execute(url: HttpUrl, serializer: KSerializer<T>): T {
        var tokens = session.freshTokens() ?: throw TwitchApiException(401, "Not signed in")
        var retried = false
        while (true) {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${tokens.accessToken}")
                .header("Client-Id", config.clientId)
                .build()
            http.newCall(request).await().use { response ->
                val text = response.body.string()
                if (response.isSuccessful) return TwitchJson.decodeFromString(serializer, text)
                if (response.code == 401 && !retried) {
                    retried = true
                    tokens = session.refreshAfterUnauthorized(tokens)
                        ?: throw TwitchApiException(401, "Session expired, please sign in again", text)
                } else {
                    throw TwitchApiException(response.code, "Helix ${url.encodedPath} failed (${response.code})", text)
                }
            }
        }
    }

    private companion object {
        const val MAX_IDS_PER_REQUEST = 100
    }
}
