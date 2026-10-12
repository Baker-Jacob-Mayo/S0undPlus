package com.s0undplus.tv.ui.player

import android.util.Log
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.s0undplus.tv.LocalActivityBridge
import com.s0undplus.tv.LocalAppContainer
import com.s0undplus.tv.data.AdMode
import com.s0undplus.tv.data.ChatLayout
import com.s0undplus.tv.data.OtherChannels
import com.s0undplus.tv.ui.components.MessageState
import com.s0undplus.tv.ui.components.QrDialog
import com.s0undplus.tv.ui.components.Spinner
import com.s0undplus.tv.ui.nav.PlayTarget
import com.s0undplus.tv.ui.nav.toPlayTarget
import com.s0undplus.tv.ui.theme.Palette
import com.s0undplus.tv.ui.util.Format
import com.s0undplus.tv.ui.util.rememberScreenScope
import com.s0undplus.twitch.eventsub.RaidEvent
import com.s0undplus.twitch.library.FavoriteChannel
import com.s0undplus.twitch.library.HistoryEntry
import com.s0undplus.twitch.playback.AutoRefreshPolicy
import com.s0undplus.twitch.playback.PlaybackStream
import com.s0undplus.twitch.playback.Variant
import com.s0undplus.twitch.playback.QualityPreference
import com.s0undplus.twitch.playback.RefreshMode
import com.s0undplus.twitch.playback.RefreshStep
import com.s0undplus.twitch.playback.SeekPreviews
import com.s0undplus.twitch.playback.StallDetector
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import android.view.KeyEvent as AndroidKeyEvent

private enum class Menu { None, Options, Quality, Peek }

private const val SEEK_SMALL_MS = 10_000L
private const val SEEK_LARGE_MS = 30_000L
private const val CONTROLS_TIMEOUT_MS = 8_000L
private const val RAID_COUNTDOWN_SECONDS = 10

/** How long a [Banner] note such as "No other followed channels are live" stays up. */
private const val FLASH_MS = 3_000L

private const val RESUME_WAIT_MS = 12_000L

/** The longest the picture stays covered while an ad-free copy loads, even if it never draws a frame. */
private const val ALT_COVER_MAX_MS = 6_000L
private const val GESTURE_THRESHOLD_PX = 140f
private val CHAT_WIDTH = 380.dp

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    target: PlayTarget,
    onExit: () -> Unit,
    onOpenChannel: (String) -> Unit,
    onSwitch: (PlayTarget) -> Unit,
    /** Shared across player screens (see AppRoot), so switching streams doesn't reset the TV's refresh rate in between. */
    frameRate: FrameRateSwitcher? = null,
) {
    val container = LocalAppContainer.current
    val bridge = LocalActivityBridge.current
    val context = LocalContext.current
    val screenScope = rememberScreenScope()
    val uiScope = rememberCoroutineScope()

    val controller = remember(target) { PlayerController(container, target, screenScope) }
    val settings by controller.settings.collectAsStateWithLifecycle()
    val phase by controller.phase.collectAsStateWithLifecycle()
    val others by controller.others.collectAsStateWithLifecycle()
    val othersStatus by controller.othersStatus.collectAsStateWithLifecycle()
    val raid by controller.raid.collectAsStateWithLifecycle()
    val favorites by container.settings.favorites.collectAsStateWithLifecycle(emptyList())
    val isLive = controller.isLive
    val channelName by controller.displayName.collectAsStateWithLifecycle()
    val streamInfo by controller.streamInfo.collectAsStateWithLifecycle()
    val channelLogin = when (target) {
        is PlayTarget.Live -> target.login
        is PlayTarget.Vod -> target.channelLogin
    }
    val isFavorite = favorites.any { it.login.equals(channelLogin, ignoreCase = true) }
    val headerTitle = if (isLive) channelName else titleOf(target)

    val exo = remember {
        ExoPlayer.Builder(context)
            // The app's one OkHttp client (shared pool and timeouts) instead of Media3's separate default stack.
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(container.http)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    val adBlocker = remember(controller) {
        AdBlocker(
            http = container.http,
            liveOffsetMs = { exo.currentLiveOffset.takeIf { it != C.TIME_UNSET } },
            resolveAlt = controller::resolveAlt,
            onAltFailed = controller::noteAltFailure,
        )
    }
    val adState = adBlocker.state
    val alt = adBlocker.alt
    // The ad-free copy is only uncovered (and unmuted) once it has drawn a frame, so the frozen last frame of the ad
    // never shows while it loads.
    var altShowing by remember { mutableStateOf(false) }
    val covered = adState == AdState.Hiding || (adState == AdState.Swapped && !altShowing)

    var controlsVisible by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(Menu.None) }
    var qualityChoice by remember { mutableStateOf<String?>(null) }
    var audioOnly by remember { mutableStateOf(false) }
    var qualityLabel by remember { mutableStateOf("Auto") }
    var buffering by remember { mutableStateOf(true) }
    var latencyMs by remember { mutableStateOf<Long?>(null) }
    var playing by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var resumeAfterStop by remember { mutableStateOf(false) }
    var recoveryMessage by remember { mutableStateOf<String?>(null) }
    /** A short note shown for a few seconds, e.g. when Channel +/- has nowhere to go. */
    var flashMessage by remember { mutableStateOf<String?>(null) }
    var endedNote by remember { mutableStateOf<String?>(null) }
    var showChatQr by remember { mutableStateOf(false) }

    // Twitch's preview pictures along a past broadcast, for scrubbing; without them the seek bar just shows the time.
    val seekPreviews by produceState<SeekPreviews?>(null, target) {
        val vod = target as? PlayTarget.Vod ?: return@produceState
        value = try {
            container.seekPreviews.load(vod.id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    var raidSeconds by remember { mutableIntStateOf(RAID_COUNTDOWN_SECONDS) }

    val recoveryJob = remember { arrayOfNulls<Job>(1) }
    LaunchedEffect(flashMessage) {
        if (flashMessage != null) {
            delay(FLASH_MS)
            flashMessage = null
        }
    }
    val stall = remember { StallDetector() }


    val rootFocus = remember { FocusRequester() }
    val firstControl = remember { FocusRequester() }
    val seekBar = remember { FocusRequester() }
    val firstOptionItem = remember { FocusRequester() }
    val selectedQualityItem = remember { FocusRequester() }
    val firstPeekItem = remember { FocusRequester() }

    val ready = (phase as? PlayerController.Phase.Ready)?.stream
    val readyVia = (phase as? PlayerController.Phase.Ready)?.via

    // The TV's refresh rate follows the stream's frame rate; AppRoot puts it back to normal on leaving playback.
    // The rate of the quality that will actually play: the one picked, or for Auto the source (what adaptive playback
    // settles on with a good connection). Renditions can differ: a 1080p50 source often comes with a 720p60 copy.
    val playedUrl = ready?.let { resolve(it, qualityChoice, audioOnly, settings.quality).second }
    val streamFps = ready?.let { stream ->
        val video = stream.variants.filterNot { it.isAudioOnly }
        (video.firstOrNull { it.url == playedUrl } ?: video.maxByOrNull { it.bandwidth })?.frameRate?.toFloat()
    } ?: 0f
    LaunchedEffect(frameRate, playedUrl, streamFps, settings.matchFrameRate, audioOnly) {
        val switcher = frameRate ?: return@LaunchedEffect
        when {
            !settings.matchFrameRate || audioOnly -> switcher.reset()
            // Loading or reconnecting: keep whatever mode is in force until the new stream says otherwise.
            playedUrl == null -> Unit
            else -> switcher.apply(playedUrl, streamFps)
        }
    }
    // A stream that already came from the proxy list is expected to be ad-free, so only a direct stream is watched.
    val watchAds = isLive && settings.adMode != AdMode.OFF && !(settings.adMode == AdMode.PROXY && readyVia != null)
    val swapAds = settings.adMode == AdMode.HTTP_PROXY || settings.adMode == AdMode.VIDEO_SWAP
    val chatShown = settings.chatVisible && !bridge.inPip

    fun saveProgress() {
        val vod = target as? PlayTarget.Vod ?: return
        val position = exo.currentPosition
        val duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        container.appScope.launch {
            container.settings.saveVodPosition(vod.id, position, duration)
            if (position >= 15_000) {
                container.settings.recordHistory(
                    HistoryEntry(
                        videoId = vod.id,
                        title = vod.title,
                        channelName = vod.channelName,
                        channelLogin = vod.channelLogin,
                        durationSeconds = vod.durationSeconds,
                        thumbnailUrl = vod.thumbnailUrl,
                        positionMs = position,
                        watchedAtMillis = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    fun seekBy(deltaMs: Long) {
        val limit = if (durationMs > 0) durationMs else Long.MAX_VALUE
        exo.seekTo((exo.currentPosition + deltaMs).coerceIn(0L, limit))
    }

    fun togglePlayback() {
        if (exo.isPlaying) exo.pause() else exo.play()
    }

    fun toggleChat() {
        uiScope.launch { container.settings.update { it.copy(chatVisible = !it.chatVisible) } }
    }

    fun openPeek() {
        controller.loadOthers()
        menu = Menu.Peek
    }

    /** Switches to the next/previous live channel in the viewer's followed list (the remote's Channel +/- keys). */
    fun surf(delta: Int) {
        uiScope.launch {
            controller.loadOthers()
            // Wait for the load to end either way, so "nothing else is live" is told apart from "still loading".
            val status = withTimeoutOrNull(8_000) {
                controller.othersStatus.first { it == OthersStatus.Loaded || it == OthersStatus.Failed }
            }
            val list = controller.others.value
            val candidates = list.filterNot { it.userLogin.equals(channelLogin, ignoreCase = true) }
            if (candidates.isEmpty()) {
                flashMessage = when {
                    status != OthersStatus.Loaded -> "Couldn't load channels to switch to"
                    settings.otherChannels == OtherChannels.FOLLOWED -> "No other followed channels are live"
                    else -> "No other channels to switch to"
                }
                return@launch
            }
            val currentIndex = list.indexOfFirst { it.userLogin.equals(channelLogin, ignoreCase = true) }
            val next = if (currentIndex < 0) candidates.first() else list[Math.floorMod(currentIndex + delta, list.size)]
            if (!next.userLogin.equals(channelLogin, ignoreCase = true)) onSwitch(next.toPlayTarget())
        }
    }

    fun goToRaid(r: RaidEvent) {
        controller.dismissRaid()
        onSwitch(PlayTarget.Live(r.toLogin, r.toDisplayName, r.toUserId))
    }

    /**
     * Recovers a live stream that stopped or stalled: re-request a *fresh* playback token and playlist (tokens
     * expire, so merely calling prepare() again would just fail), at most N times, checking first that the streamer
     * hasn't actually gone offline.
     */
    fun startRecovery() {
        if (!isLive || !settings.autoRefresh) return
        if (recoveryJob[0]?.isActive == true) return
        recoveryJob[0] = uiScope.launch {
            val policy = AutoRefreshPolicy(settings.refreshMode, settings.refreshAttempts, settings.refreshIntervalSeconds * 1_000L)
            var attempt = 0
            try {
                while (true) {
                    when (val step = policy.next(attempt, controller.isStillLive())) {
                        RefreshStep.Offline -> {
                            endedNote = "The stream has ended."
                            return@launch
                        }
                        RefreshStep.GaveUp -> {
                            playerError = "STALLED"
                            return@launch
                        }
                        is RefreshStep.Retry -> {
                            recoveryMessage = "Reconnecting… (${attempt + 1}/${policy.maxAttempts})"
                            delay(step.delayMs)
                            controller.noteStreamFailure()
                            controller.load()
                            val after = controller.phase.first { it !is PlayerController.Phase.Loading }
                            if (after is PlayerController.Phase.Ready) {
                                val resumed = withTimeoutOrNull(RESUME_WAIT_MS) { snapshotFlow { playing }.first { it } }
                                if (resumed != null) return@launch
                            } else if (after is PlayerController.Phase.Failed && after.offline && policy.mode == RefreshMode.NORMAL) {
                                endedNote = "The stream has ended."
                                return@launch
                            }
                            attempt++
                        }
                    }
                }
            } finally {
                recoveryMessage = null
            }
        }
    }

    /** A live stream stopped working: if that is the ad-free copy, give it up; otherwise reconnect as usual. */
    fun streamProblem() {
        if (adBlocker.state == AdState.Swapped) adBlocker.altFailed() else startRecovery()
    }

    fun adjustVolume(up: Boolean) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI,
        )
    }

    // --- Player wiring -------------------------------------------------------------------------------------------

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                when (playbackState) {
                    Player.STATE_BUFFERING -> stall.onBuffering(SystemClock.elapsedRealtime())
                    Player.STATE_READY -> {
                        if (audioOnly) altShowing = true // audio-only draws no frame to wait for
                        stall.onProgress()
                        playerError = null
                        endedNote = null
                    }
                    Player.STATE_ENDED -> if (isLive) streamProblem()
                }
            }

            override fun onRenderedFirstFrame() {
                altShowing = true
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (isPlaying) stall.onProgress()
            }

            override fun onPlayerError(error: PlaybackException) {
                when {
                    error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                        // We fell too far behind the live edge (e.g. a long stall): jump back to live and carry on.
                        exo.seekToDefaultPosition()
                        exo.prepare()
                    }
                    isLive && adBlocker.state == AdState.Swapped -> adBlocker.altFailed()
                    isLive && settings.autoRefresh -> startRecovery()
                    else -> playerError = error.errorCodeName
                }
            }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            recoveryJob[0]?.cancel()
            saveProgress()
            exo.release()
        }
    }

    DisposableEffect(ready != null, settings.pipOnHome) {
        bridge.pipEligible = ready != null && settings.pipOnHome
        onDispose { bridge.pipEligible = false }
    }

    // Ad handling: watch the original stream's playlist for ad breaks, and mute/black out while one plays.
    LaunchedEffect(watchAds, swapAds) {
        Log.d("PlayerScreen", "ad handling: mode=${settings.adMode} watch=$watchAds swap=$swapAds")
    }
    val watchUrl = rememberUpdatedState(ready?.let { mediaPlaylistUrl(it, qualityChoice, audioOnly, settings.quality) })
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(watchAds, swapAds, ready != null, lifecycleOwner) {
        // Only while the screen is on: no polling, token requests or proxy traffic while the app is in the background.
        if (watchAds) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                adBlocker.run({ watchUrl.value }, swapAds)
            }
        }
    }
    LaunchedEffect(alt) {
        altShowing = false
        if (alt != null) {
            delay(ALT_COVER_MAX_MS) // never keep the cover up longer than this if no frame ever arrives
            altShowing = true
        }
    }
    LaunchedEffect(covered) {
        exo.volume = if (covered) 0f else 1f
    }

    // What the previous run of the effect below played, so that an ad swap can tell itself from a quality change.
    val previousAlt = remember { arrayOfNulls<AltStream>(1) }
    LaunchedEffect(ready, qualityChoice, audioOnly, alt) {
        val original = ready ?: return@LaunchedEffect
        val swapOnly = previousAlt[0] != alt
        previousAlt[0] = alt
        val stream = alt?.stream ?: original // during an ad break the ad-free copy stands in for the original
        val preferredQuality = container.settings.settings.first().quality
        // ExoPlayer may only be used on the main thread, and waiting for storage can resume this effect elsewhere.
        withContext(Dispatchers.Main.immediate) {
            val (label, url) = resolve(stream, qualityChoice, audioOnly, preferredQuality)
            qualityLabel = label
            val start = when {
                isLive -> C.TIME_UNSET
                exo.mediaItemCount > 0 -> exo.currentPosition // changing quality mid-VOD keeps your place
                else -> (target as? PlayTarget.Vod)?.let { container.settings.vodPosition(it.id) } ?: C.TIME_UNSET
            }
            val item = MediaItem.Builder()
                .setUri(url)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .apply { if (isLive) setLiveConfiguration(liveConfiguration()) }
                .build()
            // The HTTP proxy is meant to serve this stream's media playlists, so the player must fetch them through it.
            val client = alt?.source?.mediaClient(container.http) ?: container.http
            val factory = HlsMediaSource.Factory(OkHttpDataSource.Factory(client))
                .setPlaylistParserFactory(LiveEdgePlaylistParserFactory())
                // A stream that came from a proxy fails over quickly: one retry, not Media3's default three.
                .apply { if (readyVia != null && alt == null) setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(1)) }
            exo.setMediaSource(factory.createMediaSource(item), start)
            exo.prepare()
            // Switching to or from the ad-free copy keeps whatever the viewer had chosen (a pause stays a pause).
            if (!swapOnly) exo.play()
            playerError = null
        }
    }

    // Viewer count, uptime and title are fetched only while the controls are on screen, and at most once a minute.
    LaunchedEffect(controlsVisible, isLive) {
        if (controlsVisible && isLive) {
            while (true) {
                controller.refreshStreamInfo()
                delay(60_000)
            }
        }
    }

    LaunchedEffect(exo) {
        withContext(Dispatchers.Main.immediate) {
            while (true) {
                positionMs = exo.currentPosition
                durationMs = exo.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                latencyMs = if (isLive) exo.currentLiveOffset.takeIf { it != C.TIME_UNSET } else null
                delay(if (controlsVisible) 500 else 5_000)
            }
        }
    }

    if (isLive) {
        // A stream that sits in "buffering" without erroring is the commonest way Twitch streams die: catch it.
        LaunchedEffect(exo) {
            withContext(Dispatchers.Main.immediate) {
                while (true) {
                    delay(2_000)
                    if (stall.isStalled(SystemClock.elapsedRealtime())) {
                        stall.onProgress()
                        streamProblem()
                    }
                }
            }
        }
    } else {
        LaunchedEffect(exo) {
            withContext(Dispatchers.Main.immediate) {
                controller.startReplay { exo.currentPosition }
                while (true) {
                    delay(20_000)
                    saveProgress()
                }
            }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        // Fires when the app is backgrounded *or* the PiP window is dismissed: never keep playing invisibly.
        resumeAfterStop = exo.playWhenReady
        exo.pause()
        saveProgress()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (resumeAfterStop) {
            if (isLive) exo.seekToDefaultPosition()
            exo.play()
            resumeAfterStop = false
        }
    }

    // Raid redirect: count down, then follow the raid unless the viewer cancels with Back.
    LaunchedEffect(raid) {
        val r = raid ?: return@LaunchedEffect
        raidSeconds = RAID_COUNTDOWN_SECONDS
        while (raidSeconds > 0) {
            delay(1_000)
            raidSeconds--
        }
        goToRaid(r)
    }

    // --- Focus + controls visibility -----------------------------------------------------------------------------

    LaunchedEffect(controlsVisible, menu) {
        try {
            when {
                menu == Menu.Options -> firstOptionItem.requestFocus()
                menu == Menu.Quality -> selectedQualityItem.requestFocus()
                menu == Menu.Peek -> firstPeekItem.requestFocus()
                controlsVisible -> firstControl.requestFocus()
                else -> rootFocus.requestFocus()
            }
        } catch (_: IllegalStateException) {
            // target not attached yet this frame
        }
    }

    LaunchedEffect(controlsVisible, interaction, menu) {
        if (controlsVisible && menu == Menu.None) {
            delay(CONTROLS_TIMEOUT_MS)
            controlsVisible = false
        }
    }

    BackHandler {
        when {
            raid != null -> controller.dismissRaid()
            menu != Menu.None -> menu = Menu.None
            controlsVisible -> controlsVisible = false
            else -> onExit()
        }
    }

    // --- UI ------------------------------------------------------------------------------------------------------

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .pointerInput(settings.gestures) {
                if (!settings.gestures) return@pointerInput
                detectTapGestures(onTap = { controlsVisible = !controlsVisible })
            }
            .pointerInput(settings.gestures, isLive) {
                if (!settings.gestures) return@pointerInput
                var dx = 0f
                var dy = 0f
                detectDragGestures(
                    onDragStart = {
                        dx = 0f
                        dy = 0f
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dx += amount.x
                        dy += amount.y
                    },
                    onDragEnd = {
                        when {
                            abs(dy) >= abs(dx) && abs(dy) > GESTURE_THRESHOLD_PX -> adjustVolume(up = dy < 0)
                            abs(dx) > GESTURE_THRESHOLD_PX && isLive -> {
                                // Swipe left to reveal chat, right to tuck it away.
                                uiScope.launch { container.settings.update { it.copy(chatVisible = dx < 0) } }
                            }
                            abs(dx) > GESTURE_THRESHOLD_PX -> seekBy(if (dx > 0) SEEK_LARGE_MS else -SEEK_LARGE_MS)
                        }
                    },
                )
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                interaction++
                val confirm = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                raid?.let { r ->
                    if (confirm) {
                        goToRaid(r)
                        return@onPreviewKeyEvent true
                    }
                }
                // Remote shortcuts (colour buttons, channel +/-, info/menu) work everywhere.
                when (event.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_PROG_RED -> { toggleChat(); return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_PROG_GREEN -> { menu = Menu.Quality; return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_PROG_YELLOW -> { audioOnly = !audioOnly; return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_PROG_BLUE -> { if (isLive) openPeek(); return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_CHANNEL_UP -> { if (isLive) surf(+1); return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> { if (isLive) surf(-1); return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_INFO, AndroidKeyEvent.KEYCODE_MENU -> {
                        controlsVisible = true
                        return@onPreviewKeyEvent true
                    }
                }
                when (event.key) {
                    Key.MediaPlayPause -> { togglePlayback(); return@onPreviewKeyEvent true }
                    Key.MediaPlay -> { exo.play(); return@onPreviewKeyEvent true }
                    Key.MediaPause -> { exo.pause(); return@onPreviewKeyEvent true }
                    Key.MediaRewind -> if (!isLive) { seekBy(-SEEK_LARGE_MS); return@onPreviewKeyEvent true }
                    Key.MediaFastForward -> if (!isLive) { seekBy(SEEK_LARGE_MS); return@onPreviewKeyEvent true }
                }
                // With the overlay hidden, the D-pad drives playback instead of moving focus.
                if (controlsVisible || menu != Menu.None) return@onPreviewKeyEvent false
                when {
                    // Other channels are reached from the controls or the blue button, never by a stray Down press.
                    confirm || event.key == Key.DirectionUp || event.key == Key.DirectionDown -> {
                        controlsVisible = true
                        true
                    }
                    event.key == Key.DirectionLeft -> {
                        if (isLive) {
                            if (settings.chatVisible) toggleChat()
                        } else {
                            seekBy(-SEEK_SMALL_MS)
                        }
                        true
                    }
                    event.key == Key.DirectionRight -> {
                        if (isLive) {
                            if (!settings.chatVisible) toggleChat()
                        } else {
                            seekBy(SEEK_SMALL_MS)
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        val side = chatShown && settings.chatLayout == ChatLayout.SIDE
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setKeepContentOnPlayerReset(true)
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                            isFocusable = false
                            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                            player = exo
                        }
                    },
                    update = {
                        it.keepScreenOn = settings.keepScreenOn
                        it.resizeMode = if (settings.zoomVideo) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                if (covered) {
                    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                        Text(
                            if (adState == AdState.Hiding) {
                                "Ad break — hidden" + (adBlocker.remainingSeconds?.let { " · ${Format.clock(it)} left" }.orEmpty())
                            } else {
                                "Ad break — switching to an ad-free copy"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = Palette.Muted,
                        )
                    }
                }

                if (audioOnly && playerError == null) {
                    Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                        Text("Audio only", style = MaterialTheme.typography.headlineMedium, color = Palette.BrandSoft)
                        Text(headerTitle, style = MaterialTheme.typography.titleMedium, color = Palette.Muted)
                    }
                }

                when (val p = phase) {
                    PlayerController.Phase.Loading -> CenteredSpinner()
                    is PlayerController.Phase.Failed -> if (recoveryMessage == null) {
                        MessageState(
                            p.message,
                            Modifier.fillMaxSize().background(Color.Black),
                            actionLabel = if (p.offline) "Back" else "Try again",
                            onAction = if (p.offline) onExit else controller::load,
                        )
                    }
                    is PlayerController.Phase.Ready -> {
                        if (endedNote != null) {
                            MessageState(
                                endedNote.orEmpty(),
                                Modifier.fillMaxSize().background(Color(0xCC000000)),
                                actionLabel = "Back",
                                onAction = onExit,
                            )
                        } else if (playerError != null && recoveryMessage == null) {
                            MessageState(
                                "Playback problem ($playerError).",
                                Modifier.fillMaxSize().background(Color(0xCC000000)),
                                actionLabel = "Try again",
                                onAction = {
                                    playerError = null
                                    controller.load()
                                },
                            )
                        } else if (buffering && !bridge.inPip && !covered) {
                            CenteredSpinner()
                        }
                    }
                }

                if (chatShown && !side) {
                    ChatPanel(
                        controller.chat,
                        settings,
                        Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(CHAT_WIDTH).background(Palette.Scrim),
                    )
                }

                if (!bridge.inPip) {
                    recoveryMessage?.let { Banner(it, Modifier.align(Alignment.TopCenter)) }
                    if (recoveryMessage == null) flashMessage?.let { Banner(it, Modifier.align(Alignment.TopCenter)) }
                    if (adState == AdState.Swapped && !covered) {
                        Banner("Ad break — playing an ad-free copy", Modifier.align(Alignment.TopCenter))
                    } else if (recoveryMessage == null && adState == AdState.Idle &&
                        (phase as? PlayerController.Phase.Ready)?.fellBackFromProxies == true
                    ) {
                        Banner("No ad-free proxy is available right now — ads will be hidden instead", Modifier.align(Alignment.TopCenter))
                    }
                    raid?.let { r ->
                        Banner(
                            "${r.fromLogin.ifEmpty { "This channel" }} is raiding ${r.toDisplayName} with ${Format.count(r.viewers)} viewers — " +
                                "joining in ${raidSeconds}s · OK to go now · Back to stay",
                            Modifier.align(Alignment.TopCenter),
                        )
                    }
                }

            }

            if (side) {
                ChatPanel(controller.chat, settings, Modifier.fillMaxHeight().width(CHAT_WIDTH).background(Palette.Background))
            }
        }

        // Over the whole screen, whichever chat layout: beside-video chat leaves too little width for the button row.
        if (controlsVisible && !bridge.inPip) {
            ControlsOverlay(
                target = target,
                title = headerTitle,
                isLive = isLive,
                playing = playing,
                chatOn = settings.chatVisible,
                isFavorite = isFavorite,
                showClock = settings.showClock,
                info = streamInfo,
                latencyMs = latencyMs,
                positionMs = positionMs,
                durationMs = durationMs,
                seekPreviews = seekPreviews,
                firstControl = firstControl,
                seekBar = seekBar,
                onPlayPause = ::togglePlayback,
                onSeek = ::seekBy,
                onSeekTo = { ms ->
                    exo.seekTo(ms)
                    positionMs = ms
                },
                onChat = ::toggleChat,
                onPeek = ::openPeek,
                onFavorite = {
                    val display = when (target) {
                        is PlayTarget.Live -> target.displayName
                        is PlayTarget.Vod -> target.channelName
                    }
                    val id = (target as? PlayTarget.Live)?.userId ?: (target as? PlayTarget.Vod)?.channelUserId
                    uiScope.launch { container.settings.toggleFavorite(FavoriteChannel(channelLogin, display, id)) }
                },
                onChannel = { onOpenChannel(channelLogin) },
                onOptions = { menu = Menu.Options },
                onJumpToLive = {
                    exo.seekToDefaultPosition()
                    exo.play()
                    controlsVisible = false
                },
                onClose = { controlsVisible = false },
                headerEndInset = if (chatShown) CHAT_WIDTH else 0.dp,
            )
        }
        if (menu == Menu.Options && !bridge.inPip) {
            OptionsSheet(
                qualityLabel = qualityLabel,
                audioOnly = audioOnly,
                zoom = settings.zoomVideo,
                pipSupported = bridge.pipSupported,
                firstItem = firstOptionItem,
                modifier = Modifier.align(Alignment.CenterEnd),
                onQuality = { menu = Menu.Quality },
                onAudioOnly = { audioOnly = !audioOnly },
                onZoom = { uiScope.launch { container.settings.update { it.copy(zoomVideo = !it.zoomVideo) } } },
                onPip = {
                    menu = Menu.None
                    controlsVisible = false
                    bridge.enterPip()
                },
                onChatOnPhone = {
                    menu = Menu.None
                    showChatQr = true
                },
            )
        }
        if (menu == Menu.Quality && ready != null) {
            QualityMenu(
                // Best first: playlists list their rungs in no particular order.
                variants = ready.variants.filter { !it.isAudioOnly }.sortedWith(compareByDescending<Variant> { it.height }.thenByDescending { it.frameRate }),
                current = qualityLabel,
                selectedItem = selectedQualityItem,
                modifier = Modifier.align(Alignment.CenterEnd),
                onPick = { variant ->
                    qualityChoice = variant?.name ?: QualityPreference.AUTO
                    audioOnly = false
                    val preference = when {
                        variant == null -> QualityPreference.AUTO
                        variant.isSource -> QualityPreference.SOURCE
                        else -> variant.height.toString()
                    }
                    uiScope.launch { container.settings.update { it.copy(quality = preference) } }
                    menu = Menu.None
                },
            )
        }

        if (menu == Menu.Peek && !bridge.inPip) {
            PeekPanel(
                streams = others.filterNot { it.userLogin.equals(channelLogin, ignoreCase = true) },
                firstItem = firstPeekItem,
                modifier = Modifier.align(Alignment.BottomCenter),
                status = othersStatus,
                followedOnly = settings.otherChannels == OtherChannels.FOLLOWED,
                onPick = { stream ->
                    menu = Menu.None
                    onSwitch(stream.toPlayTarget())
                },
            )
        }

        if (showChatQr) {
            QrDialog(
                title = "Chat from your phone",
                subtitle = "S0undPlus chat is read-only. Scan to open this channel's chat on your phone and join in.",
                url = "https://www.twitch.tv/popout/$channelLogin/chat?popout=",
                onDismiss = { showChatQr = false },
            )
        }
    }
}

private fun titleOf(target: PlayTarget): String = when (target) {
    is PlayTarget.Live -> target.displayName
    is PlayTarget.Vod -> target.title
}

/** The media playlist (never the master) of the quality being watched, which is where ad breaks show up. */
private fun mediaPlaylistUrl(stream: PlaybackStream, choice: String?, audioOnly: Boolean, preferred: String): String? {
    val picked = resolve(stream, choice, audioOnly, preferred).second
    if (picked != stream.masterUrl) return picked
    return (stream.variants.firstOrNull { !it.isAudioOnly } ?: stream.variants.firstOrNull())?.url
}

/** Picks the playlist to hand to ExoPlayer and the label to show for it. */
private fun resolve(stream: PlaybackStream, choice: String?, audioOnly: Boolean, preferred: String): Pair<String, String> {
    if (audioOnly) stream.variants.firstOrNull { it.isAudioOnly }?.let { return it.name to it.url }
    val pinned = when (choice) {
        null -> QualityPreference.pick(stream.variants, preferred)
        QualityPreference.AUTO -> null
        else -> stream.variants.firstOrNull { it.name == choice }
    }
    return if (pinned != null) pinned.name to pinned.url else "Auto" to stream.masterUrl
}

@Composable
private fun CenteredSpinner() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
}

@Composable
private fun Banner(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = Color.White,
        modifier = modifier
            .padding(top = 24.dp, start = 24.dp, end = 24.dp)
            .clip(RoundedCornerShape(50))
            .background(Palette.Brand.copy(alpha = 0.92f))
            .padding(horizontal = 24.dp, vertical = 10.dp),
    )
}
