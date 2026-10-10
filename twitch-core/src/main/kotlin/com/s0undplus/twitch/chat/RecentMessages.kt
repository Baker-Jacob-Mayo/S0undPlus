package com.s0undplus.twitch.chat

import com.s0undplus.twitch.TwitchConfig
import com.s0undplus.twitch.TwitchJson
import com.s0undplus.twitch.awaitText
import java.io.IOException
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
internal data class RecentMessagesResponse(val messages: List<String> = emptyList())

/**
 * Chat history from the community-run recent-messages.robotty.de service, so you see what was said *before* you
 * tuned in. Best-effort: any failure just means an empty backlog.
 */
class RecentMessagesApi(private val http: OkHttpClient, private val config: TwitchConfig) {
    suspend fun fetch(channel: String, limit: Int = 100): List<ChatMessage> {
        val url = "${config.recentMessagesUrl}/${channel.lowercase()}".toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.toString())
            .build()
        return try {
            val text = http.newCall(Request.Builder().url(url).build()).awaitText()
            TwitchJson.decodeFromString(RecentMessagesResponse.serializer(), text).messages.mapNotNull { line ->
                IrcParser.parse(line)?.let(ChatProtocol::parseMessage)
            }
        } catch (_: IOException) {
            emptyList()
        }
    }
}
