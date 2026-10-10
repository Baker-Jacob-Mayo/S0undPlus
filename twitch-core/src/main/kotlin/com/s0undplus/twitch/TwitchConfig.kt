package com.s0undplus.twitch

/**
 * Everything environment-specific lives here so tests can point the clients at a MockWebServer.
 *
 * @property clientId Your own application's client ID from https://dev.twitch.tv/console. Required for the
 * official Helix API and the device-code login.
 * @property webClientId Client ID used *only* for the unofficial anonymous playback-token request. See README
 * ("Playback and Twitch's terms").
 */
data class TwitchConfig(
    val clientId: String,
    val deviceId: String = "0".repeat(32),
    val helixUrl: String = "https://api.twitch.tv/helix",
    val oauthUrl: String = "https://id.twitch.tv/oauth2",
    val gqlUrl: String = "https://gql.twitch.tv/gql",
    val usherUrl: String = "https://usher.ttvnw.net",
    val webClientId: String = "kimne78kx3ncx6brgo4mv6wki5h1ko",
    val ircUrl: String = "wss://irc-ws.chat.twitch.tv:443",
    val recentMessagesUrl: String = "https://recent-messages.robotty.de/api/v2/recent-messages",
    val bttvUrl: String = "https://api.betterttv.net/3",
    val sevenTvUrl: String = "https://7tv.io/v3",
    val ffzUrl: String = "https://api.frankerfacez.com/v1",
)

/** OAuth scopes the app asks for. Read-only: chat is anonymous, the app never posts as the user. */
object TwitchScopes {
    const val READ_FOLLOWS = "user:read:follows"
    val DEFAULT: List<String> = listOf(READ_FOLLOWS)
}
