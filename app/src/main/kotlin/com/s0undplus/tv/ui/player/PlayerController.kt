package com.s0undplus.tv.ui.player

import android.os.SystemClock
import com.s0undplus.tv.AppContainer
import com.s0undplus.tv.data.AdMode
import com.s0undplus.tv.data.AppSettings
import com.s0undplus.tv.ui.components.describe
import com.s0undplus.tv.ui.nav.PlayTarget
import com.s0undplus.twitch.Stream
import com.s0undplus.twitch.eventsub.RaidEvent
import com.s0undplus.twitch.playback.ChannelOfflineException
import com.s0undplus.twitch.playback.PlaybackOptions
import com.s0undplus.twitch.playback.PlaybackStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Resolves a [PlayTarget] into HLS playlists and owns everything that lives exactly as long as the player screen:
 * live chat or VOD chat replay, the raid watcher, and the "other live channels" list used by peek view and
 * channel up/down.
 */
class PlayerController(
    private val c: AppContainer,
    val target: PlayTarget,
    private val scope: CoroutineScope,
) {
    sealed interface Phase {
        data object Loading : Phase
        /** [via] is the proxy this stream came from in [AdMode.PROXY]; null for a normal direct stream. */
        data class Ready(val stream: PlaybackStream, val via: AdSource? = null) : Phase
        data class Failed(val message: String, val offline: Boolean) : Phase
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Loading)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    val settings: StateFlow<AppSettings> =
        c.settings.settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    /** Live chat for live streams; replayed chat for VODs (started by the screen once it can report the position). */
    val chat: ChatController = when (val t = target) {
        is PlayTarget.Live -> ChatController(c, t.login, t.userId, settings, scope).also(ChatController::start)
        is PlayTarget.Vod -> ChatController(c, t.channelLogin, t.channelUserId, settings, scope)
    }

    val isLive: Boolean get() = target is PlayTarget.Live

    private val _raid = MutableStateFlow<RaidEvent?>(null)

    /** Set when the watched channel starts raiding another; the screen offers (and counts down) a redirect. */
    val raid: StateFlow<RaidEvent?> = _raid.asStateFlow()

    private val _others = MutableStateFlow<List<Stream>>(emptyList())

    /** Live followed channels (falling back to top streams) for peek view and channel up/down. */
    val others: StateFlow<List<Stream>> = _others.asStateFlow()

    /** Index of the next proxy to try in [AdMode.PROXY]; moves on whenever one fails, never back. */
    private var proxyCursor = 0

    /** Same, for the sources that supply an ad-free copy during a break. */
    private var altCursor = 0
    private var altExhaustedAtMs = 0L

    init {
        load()
        (target as? PlayTarget.Live)?.let { watchRaids(it) }
    }

    fun startReplay(position: () -> Long) {
        val vod = target as? PlayTarget.Vod ?: return
        chat.startReplay(vod.id, position)
    }

    fun dismissRaid() {
        _raid.value = null
    }

    /** (Re)requests a playback token and playlist. Called again on "Try again" because tokens are short-lived. */
    fun load() {
        _phase.value = Phase.Loading
        scope.launch {
            try {
                _phase.value = when (val t = target) {
                    is PlayTarget.Live -> loadLive(t.login)
                    is PlayTarget.Vod -> Phase.Ready(c.playback.vod(t.id))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChannelOfflineException) {
                _phase.value = Phase.Failed("${e.login} is offline right now.", offline = true)
            } catch (e: Exception) {
                _phase.value = Phase.Failed(describe(e), offline = false)
            }
        }
    }

    /**
     * Live streams normally come straight from Twitch. In [AdMode.PROXY] the whole stream is requested from the
     * viewer's proxy list instead, trying a few entries per load and moving past any that fail (the same failover Xtra
     * has); once the list is used up, or if it is empty, playback falls back to Twitch directly.
     */
    private suspend fun loadLive(login: String): Phase.Ready {
        val s = c.settings.settings.first() // read storage: [settings] still holds its defaults until DataStore emits
        if (s.adMode == AdMode.PROXY) {
            val sources = s.adSources()
            var attempts = 0
            while (attempts < MAX_PROXY_ATTEMPTS && proxyCursor < sources.size) {
                val source = sources[proxyCursor]
                try {
                    return Phase.Ready(c.playback.live(login, source.options), via = source)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    proxyCursor++
                    attempts++
                }
            }
        }
        return Phase.Ready(c.playback.live(login, PlaybackOptions(playerType = s.effectivePlayerType)))
    }

    /** A proxied stream died mid-playback: don't pick the same proxy again for the reconnect. */
    fun noteStreamFailure() {
        if ((_phase.value as? Phase.Ready)?.via != null) proxyCursor++
    }

    /**
     * The ad-free copy to show while an ad plays ([AdMode.HTTP_PROXY], [AdMode.VIDEO_SWAP]): the first source that
     * still works. Null when every source has failed, in which case the ad is just hidden.
     */
    suspend fun resolveAlt(): AltStream? {
        val live = target as? PlayTarget.Live ?: return null
        val sources = c.settings.settings.first().adSources()
        // One transient failure shouldn't switch swapping off for the whole watch session: look again after a while.
        if (altCursor >= sources.size && SystemClock.elapsedRealtime() - altExhaustedAtMs > ALT_RETRY_AFTER_MS) altCursor = 0
        while (altCursor < sources.size) {
            val source = sources[altCursor]
            try {
                return AltStream(source, c.playback.live(live.login, source.options))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                altCursor++
            }
        }
        altExhaustedAtMs = SystemClock.elapsedRealtime()
        return null
    }

    fun noteAltFailure() {
        altCursor++
    }

    /** Whether the live channel is still broadcasting per Helix; null if the check itself failed. */
    suspend fun isStillLive(): Boolean? {
        val live = target as? PlayTarget.Live ?: return null
        return try {
            c.helix.streamByLogin(live.login) != null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Loads [others] once (cheap if already loaded). */
    fun loadOthers() {
        if (_others.value.isNotEmpty()) return
        scope.launch {
            try {
                val followed = c.helix.followedStreams(first = 40).items
                val list = followed.ifEmpty { c.helix.streams(first = 20).items }
                _others.value = list
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // peek view just stays empty
            }
        }
    }

    private fun watchRaids(live: PlayTarget.Live) {
        scope.launch {
            // Read storage directly: `settings` is a StateFlow that still holds its defaults until DataStore emits.
            if (!c.settings.settings.first().followRaids) return@launch
            val userId = live.userId ?: try {
                c.helix.userByLogin(live.login)?.id
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return@launch
            try {
                c.raids.raids(userId).collect { _raid.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // raid redirect is a nicety; never disturb playback
            }
        }
    }

    private companion object {
        /** Each failed proxy costs up to its connect timeout, so a single load only works through a few of them. */
        const val MAX_PROXY_ATTEMPTS = 3

        /** After every ad-free source has failed, wait this long before trying the list again. */
        const val ALT_RETRY_AFTER_MS = 10 * 60_000L
    }
}
