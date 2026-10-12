package com.s0undplus.twitch.library

import com.s0undplus.twitch.TwitchJson
import com.s0undplus.twitch.User
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class FavoriteChannel(val login: String, val displayName: String, val userId: String? = null)

/**
 * A channel followed in this app. Twitch stopped letting other apps follow channels on the viewer's account in 2021,
 * so these are kept on the device and shown alongside the account's own follows.
 */
@Serializable
data class AppFollow(val login: String, val displayName: String, val userId: String? = null)

/** A category followed in this app. [boxArtUrl] keeps Twitch's `{width}x{height}` template. */
@Serializable
data class FollowedCategory(val id: String, val name: String, val boxArtUrl: String = "")

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
    const val MAX_FOLLOWS = 500
    const val MAX_CATEGORIES = 100

    fun isFavorite(favorites: List<FavoriteChannel>, login: String): Boolean =
        favorites.any { it.login.equals(login, ignoreCase = true) }

    /** Adds [channel] (to the end) or removes it if already present. */
    fun toggleFavorite(favorites: List<FavoriteChannel>, channel: FavoriteChannel): List<FavoriteChannel> =
        if (isFavorite(favorites, channel.login)) {
            favorites.filterNot { it.login.equals(channel.login, ignoreCase = true) }
        } else {
            (favorites + channel).takeLast(MAX_FAVORITES)
        }

    /**
     * Whether [follow] is the channel [login] / [userId]. The id decides when both sides have one, since a channel
     * keeps its id when it changes its name; the login otherwise.
     */
    fun isSameChannel(follow: AppFollow, login: String, userId: String?): Boolean =
        if (follow.userId != null && userId != null) follow.userId == userId else follow.login.equals(login, ignoreCase = true)

    fun isFollowed(follows: List<AppFollow>, login: String, userId: String? = null): Boolean =
        follows.any { isSameChannel(it, login, userId) }

    /** Follows [channel] (added to the end) or unfollows it if it is already followed, even under an older name. */
    fun toggleFollow(follows: List<AppFollow>, channel: AppFollow): List<AppFollow> =
        if (isFollowed(follows, channel.login, channel.userId)) {
            follows.filterNot { isSameChannel(it, channel.login, channel.userId) }
        } else {
            (follows + channel).takeLast(MAX_FOLLOWS)
        }

    /**
     * Brings the stored names up to date from [users] (looked up by id): a channel that renamed itself keeps its follow
     * under its new login. Returns [follows] itself when nothing changed.
     */
    fun renamed(follows: List<AppFollow>, users: List<User>): List<AppFollow> {
        val byId = users.associateBy { it.id }
        var changed = false
        val updated = follows.map { f ->
            val u = f.userId?.let { byId[it] } ?: return@map f
            if (u.login == f.login && u.displayName == f.displayName) {
                f
            } else {
                changed = true
                f.copy(login = u.login, displayName = u.displayName)
            }
        }
        return if (changed) updated else follows
    }

    /** Follows [category] (added to the end) or unfollows it if it is already followed. */
    fun toggleCategory(categories: List<FollowedCategory>, category: FollowedCategory): List<FollowedCategory> =
        if (categories.any { it.id == category.id }) {
            categories.filterNot { it.id == category.id }
        } else {
            (categories + category).takeLast(MAX_CATEGORIES)
        }

    /** Newest first, one entry per video, capped at [MAX_HISTORY]. Re-watching moves a video to the front. */
    fun record(history: List<HistoryEntry>, entry: HistoryEntry): List<HistoryEntry> =
        (listOf(entry) + history.filterNot { it.videoId == entry.videoId }).take(MAX_HISTORY)

    fun remove(history: List<HistoryEntry>, videoId: String): List<HistoryEntry> =
        history.filterNot { it.videoId == videoId }

    fun encodeFavorites(list: List<FavoriteChannel>): String =
        TwitchJson.encodeToString(ListSerializer(FavoriteChannel.serializer()), list)

    fun decodeFavorites(json: String?): List<FavoriteChannel> = decode(json, FavoriteChannel.serializer())

    fun encodeFollows(list: List<AppFollow>): String =
        TwitchJson.encodeToString(ListSerializer(AppFollow.serializer()), list)

    fun decodeFollows(json: String?): List<AppFollow> = decode(json, AppFollow.serializer())

    fun encodeCategories(list: List<FollowedCategory>): String =
        TwitchJson.encodeToString(ListSerializer(FollowedCategory.serializer()), list)

    fun decodeCategories(json: String?): List<FollowedCategory> = decode(json, FollowedCategory.serializer())

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
