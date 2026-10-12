package com.s0undplus.tv

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.s0undplus.tv.data.SettingsRepository
import com.s0undplus.tv.ui.util.CrashGuard
import com.s0undplus.twitch.TwitchConfig
import com.s0undplus.twitch.api.HelixApi
import com.s0undplus.twitch.auth.AuthSession
import com.s0undplus.twitch.auth.TwitchAuth
import com.s0undplus.twitch.chat.ChatClient
import com.s0undplus.twitch.chat.EmoteCache
import com.s0undplus.twitch.chat.RecentMessagesApi
import com.s0undplus.twitch.chat.VodChatApi
import com.s0undplus.twitch.chat.ThirdPartyEmotes
import com.s0undplus.twitch.eventsub.RaidWatcher
import com.s0undplus.twitch.playback.PlaybackApi
import com.s0undplus.twitch.playback.SeekPreviewApi
import com.s0undplus.twitch.update.UpdateChecker
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/**
 * Hand-rolled dependency container: one instance per process, created lazily by [S0undPlusApp].
 *
 * The parameters beyond [context] exist so instrumented tests can point every client at a local fake server; the app
 * itself always uses the defaults.
 */
class AppContainer(
    context: Context,
    val http: OkHttpClient = defaultHttpClient(),
    val config: TwitchConfig = TwitchConfig(
        clientId = BuildConfig.TWITCH_CLIENT_ID,
        deviceId = deviceId(context),
    ),
    updateApiUrl: String = "https://api.github.com",
) {
    /** For fire-and-forget work that must outlive a screen, e.g. saving a VOD resume point on exit. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CrashGuard)

    val settings = SettingsRepository(context)
    val auth = TwitchAuth(http, config)
    val session = AuthSession(auth, settings.tokenStore)
    val helix = HelixApi(http, config, session)
    val playback = PlaybackApi(http, config)
    val chat = ChatClient(http, config)
    val emotes = ThirdPartyEmotes(http, config)

    /** Global emote and badge sets are shared across streams, so surfing channels doesn't re-download them. */
    val emoteCache = EmoteCache(emotes)
    val recentMessages = RecentMessagesApi(http, config)
    val vodChat = VodChatApi(http, config)
    val seekPreviews = SeekPreviewApi(http, config)
    val raids = RaidWatcher(http, config, session)
    val updates = UpdateChecker(http, BuildConfig.UPDATE_REPO, updateApiUrl)

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS) // keeps the chat WebSocket alive through idle NATs
            .build()

        /** Stable random id sent with playback-token requests (what the Twitch web player does per browser). */
        private fun deviceId(context: Context): String {
            val prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE)
            return prefs.getString("id", null) ?: UUID.randomUUID().toString().replace("-", "").also {
                prefs.edit().putString("id", it).apply()
            }
        }
    }
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
