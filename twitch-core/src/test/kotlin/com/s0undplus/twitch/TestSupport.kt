package com.s0undplus.twitch

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

/** Points every endpoint at one MockWebServer, keeping the real path layout. */
fun configFor(server: MockWebServer, clientId: String = "test-client"): TwitchConfig {
    fun base(path: String) = server.url(path).toString().trimEnd('/')
    return TwitchConfig(
        clientId = clientId,
        deviceId = "device-1",
        helixUrl = base("/helix"),
        oauthUrl = base("/oauth2"),
        gqlUrl = base("/gql"),
        usherUrl = base("/usher"),
        ircUrl = server.url("/irc").toString().replaceFirst("http", "ws"),
        recentMessagesUrl = base("/recent"),
        bttvUrl = base("/bttv"),
        sevenTvUrl = base("/7tv"),
        ffzUrl = base("/ffz"),
    )
}

fun testHttp(): OkHttpClient = OkHttpClient()

fun json(body: String, code: Int = 200): MockResponse =
    MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

fun text(body: String, code: Int = 200): MockResponse = MockResponse.Builder().code(code).body(body).build()
