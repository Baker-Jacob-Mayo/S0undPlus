package com.s0undplus.twitch

/** Where an incoming link wants to go. */
sealed interface DeepLink {
    data class Live(val login: String) : DeepLink
    data class Vod(val id: String) : DeepLink
    data object Home : DeepLink
}

/**
 * Understands `s0undplus://live/<login>`, `s0undplus://vod/<id>`, `s0undplus://home` (what notifications and the TV
 * home row use) and ordinary `twitch.tv/<login>` and `twitch.tv/videos/<id>` links.
 */
object DeepLinkParser {
    private val TWITCH_HOSTS = setOf("twitch.tv", "www.twitch.tv", "m.twitch.tv")
    private val LOGIN = Regex("^[A-Za-z0-9_]{2,25}$")
    private val VIDEO_ID = Regex("^[0-9]{1,15}$")

    /** Twitch URL paths that look like a login but are site pages. */
    private val RESERVED = setOf(
        "directory", "videos", "settings", "downloads", "login", "signup", "p", "subscriptions", "wallet", "turbo",
        "jobs", "store", "friends", "inventory", "drops", "moderator", "popout", "embed", "prime", "search", "team",
        "broadcast", "dashboard", "payments", "messages", "following", "about", "annual-recap",
    )

    fun parse(scheme: String?, host: String?, pathSegments: List<String>): DeepLink? {
        val segments = pathSegments.filter { it.isNotEmpty() }
        return when (scheme?.lowercase()) {
            "s0undplus" -> when (host?.lowercase()) {
                "live" -> segments.firstOrNull()?.takeIf { LOGIN.matches(it) }?.let { DeepLink.Live(it.lowercase()) }
                "vod" -> segments.firstOrNull()?.takeIf { VIDEO_ID.matches(it) }?.let { DeepLink.Vod(it) }
                "home" -> DeepLink.Home
                else -> null
            }
            "http", "https" -> {
                if (host?.lowercase() !in TWITCH_HOSTS) return null
                val first = segments.firstOrNull() ?: return null
                when {
                    first.equals("videos", ignoreCase = true) ->
                        segments.getOrNull(1)?.takeIf { VIDEO_ID.matches(it) }?.let { DeepLink.Vod(it) }
                    first.lowercase() in RESERVED -> null
                    LOGIN.matches(first) -> DeepLink.Live(first.lowercase())
                    else -> null
                }
            }
            else -> null
        }
    }
}
