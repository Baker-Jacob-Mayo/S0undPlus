package com.s0undplus.tv.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.s0undplus.twitch.User
import com.s0undplus.twitch.api.HelixApi
import com.s0undplus.twitch.library.AppFollow
import com.s0undplus.twitch.library.FollowedCategory
import com.s0undplus.twitch.library.LibraryOps
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where a channel is followed: on the viewer's Twitch account, in this app, or both. */
enum class FollowSource { TWITCH, APP, BOTH }

/**
 * Who the viewer follows, for the badges on channel pictures throughout the app: the Twitch account's follows (every
 * page, loaded once per sign-in and refreshed now and then) and the channels and categories followed in this app.
 * The login sets are snapshot state, so a card that calls [sourceOf] recomposes when they change without subscribing
 * to anything itself.
 */
class Follows(private val helix: HelixApi, private val settings: SettingsRepository, private val scope: CoroutineScope) {
    /** Lowercase logins the Twitch account follows. */
    var twitchLogins by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Lowercase logins followed in this app. */
    var appLogins by mutableStateOf<Set<String>>(emptySet())
        private set

    val channels: StateFlow<List<AppFollow>> = settings.appFollows.stateIn(scope, SharingStarted.Eagerly, emptyList())
    val categories: StateFlow<List<FollowedCategory>> =
        settings.followedCategories.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private var loadJob: Job? = null
    private var loadedAtMs = 0L

    init {
        scope.launch { channels.collect { list -> appLogins = list.mapTo(HashSet()) { it.login.lowercase() } } }
    }

    fun sourceOf(login: String): FollowSource? {
        val key = login.lowercase()
        val twitch = key in twitchLogins
        val app = key in appLogins
        return when {
            twitch && app -> FollowSource.BOTH
            twitch -> FollowSource.TWITCH
            app -> FollowSource.APP
            else -> null
        }
    }

    fun toggle(channel: AppFollow) {
        scope.launch { settings.toggleAppFollow(channel) }
    }

    fun toggle(category: FollowedCategory) {
        scope.launch { settings.toggleCategoryFollow(category) }
    }

    /** Loads every channel the Twitch account follows, unless that was done recently. A failure keeps what was known. */
    fun refreshTwitch(force: Boolean = false) {
        if (loadJob?.isActive == true) return
        if (!force && loadedAtMs != 0L && System.currentTimeMillis() - loadedAtMs < STALE_MS) return
        loadJob = scope.launch {
            try {
                val logins = HashSet<String>()
                var cursor: String? = null
                var pages = 0
                do {
                    val page = helix.followedChannels(after = cursor)
                    page.items.mapTo(logins) { it.login.lowercase() }
                    cursor = page.nextCursor
                } while (cursor != null && ++pages < MAX_PAGES)
                twitchLogins = logins
                loadedAtMs = System.currentTimeMillis()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // badges are a nicety: keep what was known and try again next time
            }
        }
    }

    /**
     * Channels can change their name (and someone else can then take the old one). Follows are kept by name too, so
     * once per sign-in the stored names are brought up to date from the channels' ids.
     */
    fun refreshNames() {
        scope.launch {
            try {
                val ids = settings.appFollows.first().mapNotNull { it.userId }
                if (ids.isEmpty()) return@launch
                val users = helix.usersById(ids)
                settings.updateAppFollows { LibraryOps.renamed(it, users) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // try again at the next sign-in
            }
        }
    }

    /** A channel page has just loaded [user]: if it's followed under an older name, store the new one. */
    fun noteUser(user: User) {
        if (channels.value.none { it.userId == user.id && (it.login != user.login || it.displayName != user.displayName) }) return
        scope.launch { settings.updateAppFollows { LibraryOps.renamed(it, listOf(user)) } }
    }

    /** Signing out or switching account: forget the previous account's follows. */
    fun clearTwitch() {
        loadJob?.cancel()
        twitchLogins = emptySet()
        loadedAtMs = 0L
    }

    private companion object {
        const val STALE_MS = 10 * 60_000L

        /** 100 follows a page: enough for anyone, and a bound if Twitch ever kept handing out cursors. */
        const val MAX_PAGES = 30
    }
}
