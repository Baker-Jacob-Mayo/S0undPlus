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
import com.s0undplus.twitch.TwitchLanguages
import com.s0undplus.twitch.api.StreamFilter
import com.s0undplus.twitch.auth.AuthTokens
import com.s0undplus.twitch.auth.TokenStore
import com.s0undplus.twitch.chat.ChatFilter
import com.s0undplus.twitch.library.AppFollow
import com.s0undplus.twitch.library.FavoriteChannel
import com.s0undplus.twitch.library.FilterOps
import com.s0undplus.twitch.library.FollowedCategory
import com.s0undplus.twitch.library.HistoryEntry
import com.s0undplus.twitch.library.LibraryOps
import com.s0undplus.twitch.playback.HttpProxy
import com.s0undplus.twitch.playback.PlayerTypes
import com.s0undplus.twitch.playback.ProxyDefaults
import com.s0undplus.twitch.playback.ProxyEntry
import com.s0undplus.twitch.playback.ProxyEntryCodec
import com.s0undplus.twitch.playback.QualityPreference
import com.s0undplus.twitch.playback.RefreshMode
import com.s0undplus.twitch.playback.VideoSwapEntry
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** Which channels the player offers in its channel list (peek, Channel +/-). */
enum class OtherChannels(val label: String) {
    FOLLOWED("Followed only"),
    FOLLOWED_AND_POPULAR("Followed, then popular"),
}

/** How chat down a side shares the screen with the video. */
enum class ChatLayout(val label: String) {
    OVERLAY("Over the video"),
    SIDE("Beside the video"),
}

enum class AppTheme(val label: String) {
    PURPLE("Purple"),
    MIDNIGHT("Midnight blue"),
    AMOLED("AMOLED black"),
    CRIMSON("Crimson"),
    FOREST("Forest"),
}

/** What to do with chat messages a moderator deleted. */
enum class DeletedStyle(val label: String) {
    HIDE("Hide"),
    GREY("Grey out"),
    SHOW("Show normally"),
}

/**
 * What to do about the ads Twitch stitches into live streams. [OFF] is the default and means the app behaves exactly as
 * if ad handling did not exist: nothing extra is requested and nothing is sent to any third party.
 */
enum class AdMode(val label: String) {
    OFF("Off"),
    HIDE("Hide ads (mute and black out)"),
    PROXY("Ad-free copy via proxy URLs"),
    HTTP_PROXY("Ad-free copy via HTTP proxy"),
    VIDEO_SWAP("Ad-free copy via video swap"),
}

/** Everything the viewer can tweak. Plain data so the UI can copy-and-save it. */
data class AppSettings(
    // Appearance
    val theme: AppTheme = AppTheme.PURPLE,
    val showClock: Boolean = false,
    /** Broadcast language for the Language tab. */
    val language: String = TwitchLanguages.defaultFor(Locale.getDefault().language),
    // Chat
    val chatVisible: Boolean = true,
    val chatLayout: ChatLayout = ChatLayout.OVERLAY,
    /** Where chat sits and how big it is; its style is always [chatLayout]. */
    val chatPlacement: ChatPlacement = ChatPlacement(),
    /** 0 = small, 1 = medium, 2 = large. */
    val chatTextSize: Int = 1,
    /** 0 = small, 1 = medium, 2 = large emotes. */
    val emoteSize: Int = 1,
    val chatTimestamps: Boolean = false,
    val deletedStyle: DeletedStyle = DeletedStyle.GREY,
    val showNotices: Boolean = true,
    val chatHistory: Boolean = true,
    val hideBots: Boolean = true,
    val hideCommands: Boolean = true,
    /** Comma- or newline-separated. */
    val blockedWords: String = "",
    val blockedUsers: String = "",
    // Playback
    val quality: String = QualityPreference.AUTO,
    val autoRefresh: Boolean = true,
    val refreshMode: RefreshMode = RefreshMode.NORMAL,
    val refreshAttempts: Int = 5,
    val refreshIntervalSeconds: Int = 5,
    val keepScreenOn: Boolean = true,
    val gestures: Boolean = true,
    val followRaids: Boolean = true,
    val pipOnHome: Boolean = true,
    val livePreviews: Boolean = false,
    /** Fill the screen with the video (cropping the edges of a 4:3 or letterboxed stream) instead of fitting it. */
    val zoomVideo: Boolean = false,
    /** Peek and Channel +/- list only followed channels unless this says otherwise. */
    val otherChannels: OtherChannels = OtherChannels.FOLLOWED,
    /**
     * Switch the TV's refresh rate to suit the stream (50 Hz for a 50 fps stream, say) instead of showing it on a 60 Hz
     * picture, which judders. The system's own "match frame rate" only does this when the switch is seamless, and on
     * most TV boxes it never is.
     */
    val matchFrameRate: Boolean = true,
    // Background / system
    val goLiveNotifications: Boolean = false,
    val homeChannel: Boolean = false,
    val autoUpdateCheck: Boolean = true,
    // Ads (see [AdMode]); the lists start as copies of Xtra's built-in defaults but are only used if a mode asks for them
    val adMode: AdMode = AdMode.OFF,
    /** One proxy URL template per line, `$channel` standing in for the login; a leading `#` marks a disabled entry. */
    val adProxies: String = ProxyEntryCodec.encode(ProxyDefaults.customProxies),
    val adHttpHost: String = ProxyDefaults.httpProxy.host,
    val adHttpPort: Int = ProxyDefaults.httpProxy.port,
    val adHttpUser: String = "",
    val adHttpPassword: String = "",
    val adHttpToken: Boolean = ProxyDefaults.httpProxy.proxyToken,
    val adHttpMultivariant: Boolean = ProxyDefaults.httpProxy.proxyMultivariant,
    val adHttpMedia: Boolean = ProxyDefaults.httpProxy.proxyMedia,
    /** `platform/playerType` per line; a leading `#` marks a disabled entry. */
    val adSwap: String = ProxyEntryCodec.encodeSwap(ProxyDefaults.videoSwap),
    val tokenPlayerType: String = PlayerTypes.SITE,
) {
    val proxyEntries: List<ProxyEntry> get() = ProxyEntryCodec.decode(adProxies)

    val swapEntries: List<VideoSwapEntry> get() = ProxyEntryCodec.decodeSwap(adSwap)

    /** The configured HTTP proxy, or null while the host/port are incomplete. */
    val httpProxy: HttpProxy?
        get() = HttpProxy(
            host = adHttpHost.trim(),
            port = adHttpPort,
            username = adHttpUser.ifBlank { null },
            password = adHttpPassword.ifBlank { null },
            proxyToken = adHttpToken,
            proxyMultivariant = adHttpMultivariant,
            proxyMedia = adHttpMedia,
        ).takeIf { it.isValid }

    /** The player type for the main stream's token: the default unless ad handling is switched on and says otherwise. */
    val effectivePlayerType: String
        get() = if (adMode == AdMode.OFF) PlayerTypes.SITE else PlayerTypes.normalize(tokenPlayerType)

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

/** DataStore-backed settings, login tokens, favorites, watch history and per-VOD resume points. */
class SettingsRepository(private val context: Context) {
    private object Keys {
        val tokens = stringPreferencesKey("auth_tokens")
        val viewerTokens = stringPreferencesKey("viewer_tokens")
        val favorites = stringPreferencesKey("favorites")
        val appFollows = stringPreferencesKey("app_follows")
        val followedCategories = stringPreferencesKey("followed_categories")
        val history = stringPreferencesKey("history")
        val lastLive = stringPreferencesKey("last_live_logins")
        val liveFilter = stringPreferencesKey("live_filter")
        val categoryTypes = stringPreferencesKey("category_types")
        val categoryFilters = stringPreferencesKey("category_filters")

        val theme = stringPreferencesKey("theme")
        val showClock = booleanPreferencesKey("show_clock")
        val language = stringPreferencesKey("language")
        val chatVisible = booleanPreferencesKey("chat_visible")
        val chatLayout = stringPreferencesKey("chat_layout")
        val chatPlacement = stringPreferencesKey("chat_placement")
        val chatTextSize = intPreferencesKey("chat_text_size")
        val emoteSize = intPreferencesKey("emote_size")
        val chatTimestamps = booleanPreferencesKey("chat_timestamps")
        val deletedStyle = stringPreferencesKey("deleted_style")
        val showNotices = booleanPreferencesKey("show_notices")
        val chatHistory = booleanPreferencesKey("chat_history")
        val hideBots = booleanPreferencesKey("hide_bots")
        val hideCommands = booleanPreferencesKey("hide_commands")
        val blockedWords = stringPreferencesKey("blocked_words")
        val blockedUsers = stringPreferencesKey("blocked_users")
        val quality = stringPreferencesKey("quality")
        val autoRefresh = booleanPreferencesKey("auto_refresh")
        val refreshMode = stringPreferencesKey("refresh_mode")
        val refreshAttempts = intPreferencesKey("refresh_attempts")
        val refreshInterval = intPreferencesKey("refresh_interval")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val gestures = booleanPreferencesKey("gestures")
        val followRaids = booleanPreferencesKey("follow_raids")
        val pipOnHome = booleanPreferencesKey("pip_on_home")
        val livePreviews = booleanPreferencesKey("live_previews")
        val zoomVideo = booleanPreferencesKey("zoom_video")
        val otherChannels = stringPreferencesKey("other_channels")
        val matchFrameRate = booleanPreferencesKey("match_frame_rate")
        val goLive = booleanPreferencesKey("go_live_notifications")
        val homeChannel = booleanPreferencesKey("home_channel")
        val autoUpdate = booleanPreferencesKey("auto_update_check")
        val adMode = stringPreferencesKey("ad_mode")
        val adProxies = stringPreferencesKey("ad_proxies")
        val adHttpHost = stringPreferencesKey("ad_http_host")
        val adHttpPort = intPreferencesKey("ad_http_port")
        val adHttpUser = stringPreferencesKey("ad_http_user")
        val adHttpPassword = stringPreferencesKey("ad_http_password")
        val adHttpToken = booleanPreferencesKey("ad_http_token")
        val adHttpMultivariant = booleanPreferencesKey("ad_http_multivariant")
        val adHttpMedia = booleanPreferencesKey("ad_http_media")
        val adSwap = stringPreferencesKey("ad_swap")
        val tokenPlayerType = stringPreferencesKey("token_player_type")

        fun vod(id: String) = longPreferencesKey("vod_$id")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs -> prefs.write(transform(prefs.toSettings())) }
    }

    // --- login ---

    /** Login tokens live in app-private storage (backups are disabled in the manifest). */
    val tokenStore: TokenStore = tokenStoreAt(Keys.tokens)

    /** The optional second sign-in, to Twitch's TV app, for 1440p playback tickets (see ViewerLogin). */
    val viewerTokenStore: TokenStore = tokenStoreAt(Keys.viewerTokens)

    private fun tokenStoreAt(key: Preferences.Key<String>): TokenStore = object : TokenStore {
        override suspend fun load(): AuthTokens? {
            val raw = context.dataStore.data.first()[key] ?: return null
            return try {
                Json.decodeFromString(AuthTokens.serializer(), raw)
            } catch (_: Exception) {
                null
            }
        }

        override suspend fun save(tokens: AuthTokens?) {
            context.dataStore.edit { prefs ->
                if (tokens == null) {
                    prefs.remove(key)
                } else {
                    prefs[key] = Json.encodeToString(AuthTokens.serializer(), tokens)
                }
            }
        }
    }

    // --- favorites & history ---

    val favorites: Flow<List<FavoriteChannel>> =
        context.dataStore.data.map { LibraryOps.decodeFavorites(it[Keys.favorites]) }

    val history: Flow<List<HistoryEntry>> =
        context.dataStore.data.map { LibraryOps.decodeHistory(it[Keys.history]) }

    /** Channels followed in this app (Twitch no longer lets apps follow on the account itself). */
    val appFollows: Flow<List<AppFollow>> =
        context.dataStore.data.map { LibraryOps.decodeFollows(it[Keys.appFollows]) }

    val followedCategories: Flow<List<FollowedCategory>> =
        context.dataStore.data.map { LibraryOps.decodeCategories(it[Keys.followedCategories]) }

    suspend fun toggleAppFollow(channel: AppFollow) {
        context.dataStore.edit { prefs ->
            val updated = LibraryOps.toggleFollow(LibraryOps.decodeFollows(prefs[Keys.appFollows]), channel)
            prefs[Keys.appFollows] = LibraryOps.encodeFollows(updated)
        }
    }

    // --- browse filters (kept until changed) ---

    /** Live's sort and filters. Until first changed: streams in the language the old Language page showed. */
    val liveFilter: Flow<StreamFilter> = context.dataStore.data.map { prefs ->
        FilterOps.decodeFilter(prefs[Keys.liveFilter]) ?: StreamFilter(languages = setOf(prefs[Keys.language] ?: AppSettings().language))
    }.distinctUntilChanged()

    suspend fun setLiveFilter(filter: StreamFilter) {
        context.dataStore.edit { it[Keys.liveFilter] = FilterOps.encodeFilter(filter) }
    }

    /** The game types ticked on the Categories page, by tag id. */
    val categoryTypes: Flow<Set<String>> =
        context.dataStore.data.map { FilterOps.decodeIds(it[Keys.categoryTypes]) }.distinctUntilChanged()

    suspend fun setCategoryTypes(ids: Set<String>) {
        context.dataStore.edit { it[Keys.categoryTypes] = FilterOps.encodeIds(ids) }
    }

    /** One category page's sort and filters (all languages, most viewers first, until changed). */
    fun categoryFilter(id: String): Flow<StreamFilter> = context.dataStore.data.map { prefs ->
        FilterOps.decodeCategoryFilters(prefs[Keys.categoryFilters]).firstOrNull { it.id == id }?.filter ?: StreamFilter()
    }.distinctUntilChanged()

    suspend fun setCategoryFilter(id: String, filter: StreamFilter) {
        context.dataStore.edit { prefs ->
            val updated = FilterOps.withCategoryFilter(FilterOps.decodeCategoryFilters(prefs[Keys.categoryFilters]), id, filter)
            prefs[Keys.categoryFilters] = FilterOps.encodeCategoryFilters(updated)
        }
    }

    /** Rewrites the app follows with [transform]; nothing is written when it hands back the same list. */
    suspend fun updateAppFollows(transform: (List<AppFollow>) -> List<AppFollow>) {
        context.dataStore.edit { prefs ->
            val current = LibraryOps.decodeFollows(prefs[Keys.appFollows])
            val updated = transform(current)
            if (updated !== current) prefs[Keys.appFollows] = LibraryOps.encodeFollows(updated)
        }
    }

    suspend fun toggleCategoryFollow(category: FollowedCategory) {
        context.dataStore.edit { prefs ->
            val updated = LibraryOps.toggleCategory(LibraryOps.decodeCategories(prefs[Keys.followedCategories]), category)
            prefs[Keys.followedCategories] = LibraryOps.encodeCategories(updated)
        }
    }

    suspend fun toggleFavorite(channel: FavoriteChannel) {
        context.dataStore.edit { prefs ->
            val updated = LibraryOps.toggleFavorite(LibraryOps.decodeFavorites(prefs[Keys.favorites]), channel)
            prefs[Keys.favorites] = LibraryOps.encodeFavorites(updated)
        }
    }

    suspend fun recordHistory(entry: HistoryEntry) {
        context.dataStore.edit { prefs ->
            val updated = LibraryOps.record(LibraryOps.decodeHistory(prefs[Keys.history]), entry)
            prefs[Keys.history] = LibraryOps.encodeHistory(updated)
        }
    }

    suspend fun removeHistory(videoId: String) {
        context.dataStore.edit { prefs ->
            val updated = LibraryOps.remove(LibraryOps.decodeHistory(prefs[Keys.history]), videoId)
            prefs[Keys.history] = LibraryOps.encodeHistory(updated)
        }
    }

    // --- go-live notification bookkeeping ---

    /** Logins that were live at the last background check, or null if there has been no check yet. */
    suspend fun lastLiveLogins(): Set<String>? =
        context.dataStore.data.first()[Keys.lastLive]?.split(',')?.filter { it.isNotEmpty() }?.toSet()

    suspend fun saveLastLiveLogins(logins: Set<String>) {
        context.dataStore.edit { it[Keys.lastLive] = logins.joinToString(",") }
    }

    // --- VOD resume ---

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
            theme = enumOf(this[Keys.theme], d.theme),
            showClock = this[Keys.showClock] ?: d.showClock,
            language = this[Keys.language] ?: d.language,
            chatVisible = this[Keys.chatVisible] ?: d.chatVisible,
            chatLayout = enumOf(this[Keys.chatLayout], d.chatLayout),
            chatPlacement = ChatPlacement.decode(this[Keys.chatPlacement], enumOf(this[Keys.chatLayout], d.chatLayout)),
            chatTextSize = (this[Keys.chatTextSize] ?: d.chatTextSize).coerceIn(0, 2),
            emoteSize = (this[Keys.emoteSize] ?: d.emoteSize).coerceIn(0, 2),
            chatTimestamps = this[Keys.chatTimestamps] ?: d.chatTimestamps,
            deletedStyle = enumOf(this[Keys.deletedStyle], d.deletedStyle),
            showNotices = this[Keys.showNotices] ?: d.showNotices,
            chatHistory = this[Keys.chatHistory] ?: d.chatHistory,
            hideBots = this[Keys.hideBots] ?: d.hideBots,
            hideCommands = this[Keys.hideCommands] ?: d.hideCommands,
            blockedWords = this[Keys.blockedWords] ?: d.blockedWords,
            blockedUsers = this[Keys.blockedUsers] ?: d.blockedUsers,
            quality = this[Keys.quality] ?: d.quality,
            autoRefresh = this[Keys.autoRefresh] ?: d.autoRefresh,
            refreshMode = enumOf(this[Keys.refreshMode], d.refreshMode),
            refreshAttempts = (this[Keys.refreshAttempts] ?: d.refreshAttempts).coerceIn(1, 20),
            refreshIntervalSeconds = (this[Keys.refreshInterval] ?: d.refreshIntervalSeconds).coerceIn(1, 60),
            keepScreenOn = this[Keys.keepScreenOn] ?: d.keepScreenOn,
            gestures = this[Keys.gestures] ?: d.gestures,
            followRaids = this[Keys.followRaids] ?: d.followRaids,
            pipOnHome = this[Keys.pipOnHome] ?: d.pipOnHome,
            livePreviews = this[Keys.livePreviews] ?: d.livePreviews,
            zoomVideo = this[Keys.zoomVideo] ?: d.zoomVideo,
            otherChannels = enumOf(this[Keys.otherChannels], d.otherChannels),
            matchFrameRate = this[Keys.matchFrameRate] ?: d.matchFrameRate,
            goLiveNotifications = this[Keys.goLive] ?: d.goLiveNotifications,
            homeChannel = this[Keys.homeChannel] ?: d.homeChannel,
            autoUpdateCheck = this[Keys.autoUpdate] ?: d.autoUpdateCheck,
            adMode = enumOf(this[Keys.adMode], d.adMode),
            adProxies = this[Keys.adProxies] ?: d.adProxies,
            adHttpHost = this[Keys.adHttpHost] ?: d.adHttpHost,
            adHttpPort = (this[Keys.adHttpPort] ?: d.adHttpPort).coerceIn(0, 65535),
            adHttpUser = this[Keys.adHttpUser] ?: d.adHttpUser,
            adHttpPassword = this[Keys.adHttpPassword] ?: d.adHttpPassword,
            adHttpToken = this[Keys.adHttpToken] ?: d.adHttpToken,
            adHttpMultivariant = this[Keys.adHttpMultivariant] ?: d.adHttpMultivariant,
            adHttpMedia = this[Keys.adHttpMedia] ?: d.adHttpMedia,
            adSwap = this[Keys.adSwap] ?: d.adSwap,
            tokenPlayerType = PlayerTypes.normalize(this[Keys.tokenPlayerType] ?: d.tokenPlayerType),
        )
    }

    private fun MutablePreferences.write(s: AppSettings) {
        this[Keys.theme] = s.theme.name
        this[Keys.showClock] = s.showClock
        this[Keys.language] = s.language
        this[Keys.chatVisible] = s.chatVisible
        this[Keys.chatLayout] = s.chatLayout.name
        this[Keys.chatPlacement] = s.chatPlacement.encode()
        this[Keys.chatTextSize] = s.chatTextSize
        this[Keys.emoteSize] = s.emoteSize
        this[Keys.chatTimestamps] = s.chatTimestamps
        this[Keys.deletedStyle] = s.deletedStyle.name
        this[Keys.showNotices] = s.showNotices
        this[Keys.chatHistory] = s.chatHistory
        this[Keys.hideBots] = s.hideBots
        this[Keys.hideCommands] = s.hideCommands
        this[Keys.blockedWords] = s.blockedWords
        this[Keys.blockedUsers] = s.blockedUsers
        this[Keys.quality] = s.quality
        this[Keys.autoRefresh] = s.autoRefresh
        this[Keys.refreshMode] = s.refreshMode.name
        this[Keys.refreshAttempts] = s.refreshAttempts
        this[Keys.refreshInterval] = s.refreshIntervalSeconds
        this[Keys.keepScreenOn] = s.keepScreenOn
        this[Keys.gestures] = s.gestures
        this[Keys.followRaids] = s.followRaids
        this[Keys.pipOnHome] = s.pipOnHome
        this[Keys.livePreviews] = s.livePreviews
        this[Keys.zoomVideo] = s.zoomVideo
        this[Keys.otherChannels] = s.otherChannels.name
        this[Keys.matchFrameRate] = s.matchFrameRate
        this[Keys.goLive] = s.goLiveNotifications
        this[Keys.homeChannel] = s.homeChannel
        this[Keys.autoUpdate] = s.autoUpdateCheck
        this[Keys.adMode] = s.adMode.name
        this[Keys.adProxies] = s.adProxies
        this[Keys.adHttpHost] = s.adHttpHost
        this[Keys.adHttpPort] = s.adHttpPort
        this[Keys.adHttpUser] = s.adHttpUser
        this[Keys.adHttpPassword] = s.adHttpPassword
        this[Keys.adHttpToken] = s.adHttpToken
        this[Keys.adHttpMultivariant] = s.adHttpMultivariant
        this[Keys.adHttpMedia] = s.adHttpMedia
        this[Keys.adSwap] = s.adSwap
        this[Keys.tokenPlayerType] = s.tokenPlayerType
    }

    private inline fun <reified E : Enum<E>> enumOf(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val MIN_RESUME_MS = 15_000L
        const val FINISHED_MARGIN_MS = 30_000L
    }
}
