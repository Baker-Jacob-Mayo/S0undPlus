package com.s0undplus.tv.ui.player

import android.os.SystemClock
import com.s0undplus.tv.AppContainer
import com.s0undplus.tv.data.AdMode
import com.s0undplus.tv.data.OtherChannels
import com.s0undplus.tv.data.AppSettings
import com.s0undplus.tv.ui.components.describe
import com.s0undplus.tv.ui.nav.PlayTarget
import com.s0undplus.twitch.Stream
import com.s0undplus.twitch.TwitchApiException
import com.s0undplus.twitch.User
import com.s0undplus.twitch.eventsub.RaidEvent
import com.s0undplus.twitch.playback.ChannelOfflineException
import com.s0undplus.twitch.playback.PlaybackOptions
import com.s0undplus.twitch.playback.PlaybackStream
import com.s0undplus.twitch.playback.SourceCooldown
import com.s0undplus.twitch.playback.VodRestrictedException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
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
        /**
         * [via] is the proxy this stream came from in [AdMode.PROXY]; null for a normal direct stream.
         * [fellBackFromProxies] is true when proxy mode is on but none of the proxies could be used, so the viewer is
         * watching the direct stream (with its ads hidden instead of removed).
         */
        data class Ready(
            val stream: PlaybackStream,
            val via: AdSource? = null,
            val fellBackFromProxies: Boolean = false,
        ) : Phase
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

    /**
     * Live channels for peek view and Channel +/-: the viewer's follows (the one on screen included, so the order is
     * stable), plus popular streams when the setting asks for them and few followed channels are live.
     */
    val others: StateFlow<List<Stream>> = _others.asStateFlow()

    private val _othersStatus = MutableStateFlow(OthersStatus.Idle)

    /** Whether [others] is loading, loaded or failed, so the peek view can say which instead of loading for ever. */
    val othersStatus: StateFlow<OthersStatus> = _othersStatus.asStateFlow()

    /** The name to show for the channel: the target's own, upgraded to Twitch's capitalised one for deep links. */
    private val _displayName = MutableStateFlow(
        when (target) {
            is PlayTarget.Live -> target.displayName
            is PlayTarget.Vod -> target.channelName
        },
    )
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    private val _streamInfo = MutableStateFlow<Stream?>(null)

    /** Viewer count, uptime, title and category of the live stream, refreshed on demand (see [refreshStreamInfo]). */
    val streamInfo: StateFlow<Stream?> = _streamInfo.asStateFlow()
    private var streamInfoAtMs = 0L

    /** The channel's profile, looked up once and only when the target did not already carry its user id. */
    private val userLookup: Deferred<User?> by lazy {
        scope.async {
            try {
                c.helix.userByLogin((target as PlayTarget.Live).login)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Proxies that failed recently are skipped for a while and then tried again; none is written off for good. */
    private val proxyCooldown = SourceCooldown<String>(PROXY_COOLDOWN_MS, SystemClock::elapsedRealtime)

    /** Same, for the sources that supply an ad-free copy during a break. */
    private val altCooldown = SourceCooldown<String>(ALT_COOLDOWN_MS, SystemClock::elapsedRealtime)

    /** The ad-free source currently being resolved, so a lookup that is abandoned part-way can still blame it. */
    private var tryingAlt: AdSource? = null

    init {
        load()
        (target as? PlayTarget.Live)?.let { live ->
            if (live.userId == null) {
                scope.launch { userLookup.await()?.displayName?.takeIf { it.isNotBlank() }?.let { _displayName.value = it } }
            }
            watchRaids(live)
        }
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
            } catch (_: VodRestrictedException) {
                _phase.value = Phase.Failed("This video is restricted — it may be for subscribers only.", offline = true)
            } catch (e: TwitchApiException) {
                // Playback is anonymous, so this is not about the viewer's session: don't tell them to sign in again.
                _phase.value = Phase.Failed("Twitch refused the playback request (HTTP ${e.status}).", offline = false)
            } catch (e: Exception) {
                _phase.value = Phase.Failed(describe(e), offline = false)
            }
        }
    }

    /**
     * Live streams normally come straight from Twitch. In [AdMode.PROXY] the whole stream is requested from the
     * viewer's proxy list instead, working down the list within a time budget and moving past any that fail (the same
     * failover Xtra has); if none can be used, playback falls back to Twitch directly.
     */
    private suspend fun loadLive(login: String): Phase.Ready {
        val s = c.settings.settings.first() // read storage: [settings] still holds its defaults until DataStore emits
        val proxies = if (s.adMode == AdMode.PROXY) s.adSources() else emptyList()
        var triedAProxy = false
        val deadline = SystemClock.elapsedRealtime() + PROXY_BUDGET_MS
        for (source in proxyCooldown.available(proxies) { it.key }) {
            if (SystemClock.elapsedRealtime() >= deadline) break
            triedAProxy = true
            try {
                return Phase.Ready(c.playback.live(login, source.options), via = source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChannelOfflineException) {
                // The proxy says the channel isn't live. It may be right, or it may be broken (some answer 404 to
                // everything), so let Twitch decide before blaming it or giving up on the channel.
                if (isStillLive() == false) throw e
                proxyCooldown.failed(source.key)
            } catch (_: Exception) {
                proxyCooldown.failed(source.key)
            }
        }
        try {
            val direct = c.playback.live(login, PlaybackOptions(playerType = s.effectivePlayerType))
            return Phase.Ready(direct, fellBackFromProxies = proxies.isNotEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not even Twitch answered: the network is down, which says nothing against the proxies.
            if (triedAProxy) proxyCooldown.clear()
            throw e
        }
    }

    /** A proxied stream died mid-playback: pick a different proxy for the reconnect. */
    fun noteStreamFailure() {
        (_phase.value as? Phase.Ready)?.via?.let { proxyCooldown.failed(it.key) }
    }

    /**
     * The ad-free copy to show while an ad plays ([AdMode.HTTP_PROXY], [AdMode.VIDEO_SWAP]): the first source that
     * is not cooling down after a failure and still works. Null when none is left, in which case the ad is just hidden.
     */
    suspend fun resolveAlt(): AltStream? {
        val live = target as? PlayTarget.Live ?: return null
        for (source in altCooldown.available(c.settings.settings.first().adSources()) { it.key }) {
            tryingAlt = source
            try {
                return AltStream(source, c.playback.live(live.login, source.options))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                altCooldown.failed(source.key)
            } finally {
                tryingAlt = null
            }
        }
        return null
    }

    /** An ad-free source failed ([source]), or a lookup was given up on before it said which (null). */
    fun noteAltFailure(source: AdSource?) {
        (source ?: tryingAlt)?.let { altCooldown.failed(it.key) }
    }

    /** Fetches fresh stream details unless they are less than a minute old. Called while the controls are on screen. */
    fun refreshStreamInfo() {
        val live = target as? PlayTarget.Live ?: return
        val now = SystemClock.elapsedRealtime()
        if (_streamInfo.value != null && now - streamInfoAtMs < STREAM_INFO_MIN_AGE_MS) return
        streamInfoAtMs = now
        scope.launch {
            try {
                c.helix.streamByLogin(live.login)?.let { _streamInfo.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // the overlay just keeps what it had
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
        if (_others.value.isNotEmpty() || _othersStatus.value == OthersStatus.Loading) return
        _othersStatus.value = OthersStatus.Loading
        val current = when (target) {
            is PlayTarget.Live -> target.login
            is PlayTarget.Vod -> target.channelLogin
        }
        scope.launch {
            try {
                // In the followed list's own order, the channel on screen included: Channel +/- steps from its place.
                val followed = c.helix.followedStreams(first = 40).items
                val otherFollowed = followed.count { !it.userLogin.equals(current, ignoreCase = true) }
                val mode = c.settings.settings.first().otherChannels
                _others.value = if (mode == OtherChannels.FOLLOWED || otherFollowed >= MIN_OTHERS) {
                    followed
                } else {
                    (followed + c.helix.streams(first = 20).items).distinctBy { it.userId }
                }
                _othersStatus.value = OthersStatus.Loaded
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _othersStatus.value = OthersStatus.Failed
            }
        }
    }

    private fun watchRaids(live: PlayTarget.Live) {
        scope.launch {
            // Read storage directly: `settings` is a StateFlow that still holds its defaults until DataStore emits.
            if (!c.settings.settings.first().followRaids) return@launch
            val userId = live.userId ?: userLookup.await()?.id ?: return@launch
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
        /** One load works down the proxy list for at most this long before falling back to Twitch directly. */
        const val PROXY_BUDGET_MS = 20_000L

        /** With "followed, then popular": fewer other followed channels live than this, and popular ones are added. */
        const val MIN_OTHERS = 4

        const val STREAM_INFO_MIN_AGE_MS = 55_000L

        /** A proxy that failed is left alone for this long, then tried again. */
        const val PROXY_COOLDOWN_MS = 5 * 60_000L

        /** Same for the sources that supply an ad-free copy during a break. */
        const val ALT_COOLDOWN_MS = 10 * 60_000L
    }
}

enum class OthersStatus { Idle, Loading, Loaded, Failed }
