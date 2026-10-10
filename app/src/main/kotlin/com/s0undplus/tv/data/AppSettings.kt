package com.s0undplus.tv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.s0undplus.twitch.auth.AuthTokens
import com.s0undplus.twitch.auth.TokenStore
import com.s0undplus.twitch.chat.ChatFilter
import com.s0undplus.twitch.playback.QualityPreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

enum class ChatLayout(val label: String) {
    OVERLAY("Overlay"),
    SIDE("Beside video"),
}

/** Everything the viewer can tweak. Plain data so the UI can copy-and-save it. */
data class AppSettings(
    val chatVisible: Boolean = true,
    val chatLayout: ChatLayout = ChatLayout.OVERLAY,
    /** 0 = small, 1 = medium, 2 = large. */
    val chatTextSize: Int = 1,
    val chatHistory: Boolean = true,
    val hideBots: Boolean = true,
    val hideCommands: Boolean = true,
    /** Comma- or newline-separated. */
    val blockedWords: String = "",
    val blockedUsers: String = "",
    val quality: String = QualityPreference.AUTO,
) {
    val chatFilter: ChatFilter
        get() = ChatFilter(
            hideBots = hideBots,
            hideCommands = hideCommands,
            blockedWords = blockedWords.splitList(),
            blockedUsers = blockedUsers.splitList().toSet(),
        )

    private fun String.splitList(): List<String> =
        split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "s0undplus")

/** DataStore-backed settings, login tokens and per-VOD resume points. */
class SettingsRepository(private val context: Context) {
    private object Keys {
        val tokens = stringPreferencesKey("auth_tokens")
        val chatVisible = booleanPreferencesKey("chat_visible")
        val chatLayout = stringPreferencesKey("chat_layout")
        val chatTextSize = intPreferencesKey("chat_text_size")
        val chatHistory = booleanPreferencesKey("chat_history")
        val hideBots = booleanPreferencesKey("hide_bots")
        val hideCommands = booleanPreferencesKey("hide_commands")
        val blockedWords = stringPreferencesKey("blocked_words")
        val blockedUsers = stringPreferencesKey("blocked_users")
        val quality = stringPreferencesKey("quality")

        fun vod(id: String) = longPreferencesKey("vod_$id")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs -> prefs.write(transform(prefs.toSettings())) }
    }

    /** Login tokens live in app-private storage (backups are disabled in the manifest). */
    val tokenStore: TokenStore = object : TokenStore {
        override suspend fun load(): AuthTokens? {
            val raw = context.dataStore.data.first()[Keys.tokens] ?: return null
            return try {
                Json.decodeFromString(AuthTokens.serializer(), raw)
            } catch (_: Exception) {
                null
            }
        }

        override suspend fun save(tokens: AuthTokens?) {
            context.dataStore.edit { prefs ->
                if (tokens == null) {
                    prefs.remove(Keys.tokens)
                } else {
                    prefs[Keys.tokens] = Json.encodeToString(AuthTokens.serializer(), tokens)
                }
            }
        }
    }

    suspend fun vodPosition(videoId: String): Long = context.dataStore.data.first()[Keys.vod(videoId)] ?: 0L

    /** Stores the resume point, or forgets it when the viewer is at the very start or has basically finished. */
    suspend fun saveVodPosition(videoId: String, positionMs: Long, durationMs: Long) {
        context.dataStore.edit { prefs ->
            val nearlyDone = durationMs > 0 && positionMs >= durationMs - FINISHED_MARGIN_MS
            if (positionMs < MIN_RESUME_MS || nearlyDone) {
                prefs.remove(Keys.vod(videoId))
            } else {
                prefs[Keys.vod(videoId)] = positionMs
            }
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            chatVisible = this[Keys.chatVisible] ?: d.chatVisible,
            chatLayout = this[Keys.chatLayout]?.let { name -> ChatLayout.entries.firstOrNull { it.name == name } }
                ?: d.chatLayout,
            chatTextSize = (this[Keys.chatTextSize] ?: d.chatTextSize).coerceIn(0, 2),
            chatHistory = this[Keys.chatHistory] ?: d.chatHistory,
            hideBots = this[Keys.hideBots] ?: d.hideBots,
            hideCommands = this[Keys.hideCommands] ?: d.hideCommands,
            blockedWords = this[Keys.blockedWords] ?: d.blockedWords,
            blockedUsers = this[Keys.blockedUsers] ?: d.blockedUsers,
            quality = this[Keys.quality] ?: d.quality,
        )
    }

    private fun MutablePreferences.write(s: AppSettings) {
        this[Keys.chatVisible] = s.chatVisible
        this[Keys.chatLayout] = s.chatLayout.name
        this[Keys.chatTextSize] = s.chatTextSize
        this[Keys.chatHistory] = s.chatHistory
        this[Keys.hideBots] = s.hideBots
        this[Keys.hideCommands] = s.hideCommands
        this[Keys.blockedWords] = s.blockedWords
        this[Keys.blockedUsers] = s.blockedUsers
        this[Keys.quality] = s.quality
    }

    private companion object {
        const val MIN_RESUME_MS = 15_000L
        const val FINISHED_MARGIN_MS = 30_000L
    }
}
