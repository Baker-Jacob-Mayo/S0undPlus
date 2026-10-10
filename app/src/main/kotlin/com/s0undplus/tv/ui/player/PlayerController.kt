package com.s0undplus.tv.ui.player

import com.s0undplus.tv.AppContainer
import com.s0undplus.tv.data.AppSettings
import com.s0undplus.tv.ui.components.describe
import com.s0undplus.tv.ui.nav.PlayTarget
import com.s0undplus.twitch.Stream
import com.s0undplus.twitch.eventsub.RaidEvent
import com.s0undplus.twitch.playback.ChannelOfflineException
import com.s0undplus.twitch.playback.PlaybackStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        data class Ready(val stream: PlaybackStream) : Phase
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
                val stream = when (val t = target) {
                    is PlayTarget.Live -> c.playback.live(t.login)
                    is PlayTarget.Vod -> c.playback.vod(t.id)
                }
                _phase.value = Phase.Ready(stream)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChannelOfflineException) {
                _phase.value = Phase.Failed("${e.login} is offline right now.", offline = true)
            } catch (e: Exception) {
                _phase.value = Phase.Failed(describe(e), offline = false)
            }
        }
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
            if (!settings.value.followRaids) return@launch
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
}
