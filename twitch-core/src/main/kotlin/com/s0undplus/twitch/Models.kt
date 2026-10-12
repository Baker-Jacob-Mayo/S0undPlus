package com.s0undplus.twitch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Helix list envelope: `{ "data": [...], "pagination": { "cursor": "..." } }`. */
@Serializable
internal data class HelixList<T>(
    val data: List<T> = emptyList(),
    val pagination: HelixPagination? = null,
)

@Serializable
internal data class HelixPagination(val cursor: String? = null)

/** One page of results plus the cursor for the next page (null when exhausted). */
data class Page<T>(val items: List<T>, val nextCursor: String?)

@Serializable
data class Stream(
    val id: String = "",
    @SerialName("user_id") val userId: String,
    @SerialName("user_login") val userLogin: String,
    @SerialName("user_name") val userName: String,
    @SerialName("game_id") val gameId: String = "",
    @SerialName("game_name") val gameName: String = "",
    val type: String = "live",
    val title: String = "",
    @SerialName("viewer_count") val viewerCount: Int = 0,
    @SerialName("started_at") val startedAt: String = "",
    val language: String = "",
    @SerialName("thumbnail_url") val thumbnailUrl: String = "",
    @SerialName("is_mature") val isMature: Boolean = false,
) {
    /** Live preview image at the requested size. */
    fun thumbnail(width: Int = 440, height: Int = 248): String = thumbnailUrl.sized(width, height)
}

@Serializable
data class Category(
    val id: String,
    val name: String,
    @SerialName("box_art_url") val boxArtUrl: String = "",
) {
    fun boxArt(width: Int = 285, height: Int = 380): String = boxArtUrl.sized(width, height)
}

/** A `search/channels` hit. */
@Serializable
data class ChannelResult(
    val id: String,
    @SerialName("broadcaster_login") val login: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("game_name") val gameName: String = "",
    @SerialName("is_live") val isLive: Boolean = false,
    @SerialName("thumbnail_url") val thumbnailUrl: String = "",
    val title: String = "",
)

/** A channel the user follows (live or not). */
@Serializable
data class FollowedChannel(
    @SerialName("broadcaster_id") val id: String,
    @SerialName("broadcaster_login") val login: String,
    @SerialName("broadcaster_name") val displayName: String,
)

@Serializable
data class User(
    val id: String,
    val login: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("profile_image_url") val profileImageUrl: String = "",
    val description: String = "",
    @SerialName("broadcaster_type") val broadcasterType: String = "",
    /** The picture Twitch shows on the channel while it is offline; often empty. */
    @SerialName("offline_image_url") val offlineImageUrl: String = "",
)

/** The kinds of saved video Helix can list for a channel. */
enum class VideoType(val apiValue: String, val label: String) {
    ARCHIVE("archive", "Past broadcasts"),
    HIGHLIGHT("highlight", "Highlights"),
    UPLOAD("upload", "Uploads"),
}

@Serializable
data class Video(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("user_login") val userLogin: String,
    @SerialName("user_name") val userName: String,
    val title: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("thumbnail_url") val thumbnailUrl: String = "",
    @SerialName("view_count") val viewCount: Int = 0,
    val type: String = "archive",
    /** Twitch duration string such as `3h2m1s`; see [durationSeconds]. */
    val duration: String = "",
) {
    val durationSeconds: Long get() = parseTwitchDuration(duration)

    fun thumbnail(width: Int = 440, height: Int = 248): String = thumbnailUrl.sized(width, height)
}

@Serializable
internal data class BadgeSetDto(
    @SerialName("set_id") val setId: String,
    val versions: List<BadgeVersionDto> = emptyList(),
)

@Serializable
internal data class BadgeVersionDto(
    val id: String,
    @SerialName("image_url_1x") val image1x: String = "",
    @SerialName("image_url_2x") val image2x: String = "",
    @SerialName("image_url_4x") val image4x: String = "",
    val title: String = "",
)

/**
 * Helix thumbnails/box art contain `{width}x{height}` placeholders; video thumbnails use `%{width}x%{height}`, with
 * percent signs. The percent forms go first, otherwise `%{width}` would become `%440`.
 */
internal fun String.sized(width: Int, height: Int): String =
    replace("%{width}", width.toString())
        .replace("%{height}", height.toString())
        .replace("{width}", width.toString())
        .replace("{height}", height.toString())

private val DURATION = Regex("""(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?""")

/** Parses Twitch's `1h2m3s` style durations into seconds; unknown input yields 0. */
fun parseTwitchDuration(text: String): Long {
    val m = DURATION.matchEntire(text.trim()) ?: return 0
    val (h, min, s) = m.destructured
    return (h.toLongOrNull() ?: 0) * 3600 + (min.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)
}
