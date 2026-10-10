package com.s0undplus.tv.ui.player

import com.s0undplus.tv.AppContainer
import com.s0undplus.tv.data.AppSettings
import com.s0undplus.tv.ui.components.describe
import com.s0undplus.tv.ui.nav.PlayTarget
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

/** Resolves a [PlayTarget] into HLS playlists and (for live) owns the chat for as long as the player screen lives. */
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

    /** Null for VODs: replaying historical chat in sync isn't supported yet. */
    val chat: ChatController? = (target as? PlayTarget.Live)?.let {
        ChatController(c, it.login, it.userId, settings, scope).also(ChatController::start)
    }

    val isLive: Boolean get() = target is PlayTarget.Live

    init {
        load()
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
}
