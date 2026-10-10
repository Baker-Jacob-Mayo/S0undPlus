package com.s0undplus.twitch.library

import com.s0undplus.twitch.TwitchJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class FavoriteChannel(val login: String, val displayName: String, val userId: String? = null)

@Serializable
data class HistoryEntry(
    val videoId: String,
    val title: String,
    val channelName: String,
    val channelLogin: String,
    val durationSeconds: Long,
    val thumbnailUrl: String,
    val positionMs: Long,
    val watchedAtMillis: Long,
)

/** Pure, bounded list operations behind favorites and watch history; the app only persists the results. */
object LibraryOps {
    const val MAX_HISTORY = 50
    const val MAX_FAVORITES = 200

    fun isFavorite(favorites: List<FavoriteChannel>, login: String): Boolean =
        favorites.any { it.login.equals(login, ignoreCase = true) }

    /** Adds [channel] (to the end) or removes it if already present. */
    fun toggleFavorite(favorites: List<FavoriteChannel>, channel: FavoriteChannel): List<FavoriteChannel> =
        if (isFavorite(favorites, channel.login)) {
            favorites.filterNot { it.login.equals(channel.login, ignoreCase = true) }
        } else {
            (favorites + channel).takeLast(MAX_FAVORITES)
        }

    /** Newest first, one entry per video, capped at [MAX_HISTORY]. Re-watching moves a video to the front. */
    fun record(history: List<HistoryEntry>, entry: HistoryEntry): List<HistoryEntry> =
        (listOf(entry) + history.filterNot { it.videoId == entry.videoId }).take(MAX_HISTORY)

    fun remove(history: List<HistoryEntry>, videoId: String): List<HistoryEntry> =
        history.filterNot { it.videoId == videoId }

    fun encodeFavorites(list: List<FavoriteChannel>): String =
        TwitchJson.encodeToString(ListSerializer(FavoriteChannel.serializer()), list)

    fun decodeFavorites(json: String?): List<FavoriteChannel> = decode(json, FavoriteChannel.serializer())

    fun encodeHistory(list: List<HistoryEntry>): String =
        TwitchJson.encodeToString(ListSerializer(HistoryEntry.serializer()), list)

    fun decodeHistory(json: String?): List<HistoryEntry> = decode(json, HistoryEntry.serializer())

    /** Corrupt or missing data is treated as an empty library rather than crashing the app at launch. */
    private fun <T> decode(json: String?, serializer: kotlinx.serialization.KSerializer<T>): List<T> =
        if (json.isNullOrBlank()) {
            emptyList()
        } else {
            try {
                TwitchJson.decodeFromString(ListSerializer(serializer), json)
            } catch (_: Exception) {
                emptyList()
            }
        }
}
