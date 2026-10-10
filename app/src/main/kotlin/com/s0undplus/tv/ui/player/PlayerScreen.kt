package com.s0undplus.tv.ui.player

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.s0undplus.tv.LocalActivityBridge
import com.s0undplus.tv.LocalAppContainer
import com.s0undplus.tv.data.AppSettings
import com.s0undplus.tv.data.ChatLayout
import com.s0undplus.tv.ui.components.ClockText
import com.s0undplus.tv.ui.components.FocusSurface
import com.s0undplus.tv.ui.components.MessageState
import com.s0undplus.tv.ui.components.PillButton
import com.s0undplus.tv.ui.components.QrDialog
import com.s0undplus.tv.ui.components.Spinner
import com.s0undplus.tv.ui.nav.PlayTarget
import com.s0undplus.tv.ui.nav.toPlayTarget
import com.s0undplus.tv.ui.theme.Palette
import com.s0undplus.tv.ui.util.Format
import com.s0undplus.tv.ui.util.rememberScreenScope
import com.s0undplus.twitch.Stream
import com.s0undplus.twitch.eventsub.RaidEvent
import com.s0undplus.twitch.library.FavoriteChannel
import com.s0undplus.twitch.library.HistoryEntry
import com.s0undplus.twitch.playback.AutoRefreshPolicy
import com.s0undplus.twitch.playback.PlaybackStream
import com.s0undplus.twitch.playback.QualityPreference
import com.s0undplus.twitch.playback.RefreshMode
import com.s0undplus.twitch.playback.RefreshStep
import com.s0undplus.twitch.playback.StallDetector
import com.s0undplus.twitch.playback.Variant
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import android.view.KeyEvent as AndroidKeyEvent

private enum class Menu { None, Quality, Peek }

private const val SEEK_SMALL_MS = 10_000L
private const val SEEK_LARGE_MS = 30_000L
private const val CONTROLS_TIMEOUT_MS = 6_000L
private const val RAID_COUNTDOWN_SECONDS = 10
private const val RESUME_WAIT_MS = 12_000L
private const val GESTURE_THRESHOLD_PX = 140f
private val CHAT_WIDTH = 380.dp

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    target: PlayTarget,
    onExit: () -> Unit,
    onOpenChannel: (String) -> Unit,
    onSwitch: (PlayTarget) -> Unit,
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
    val raid by controller.raid.collectAsStateWithLifecycle()
    val favorites by container.settings.favorites.collectAsStateWithLifecycle(emptyList())
    val isLive = controller.isLive
    val channelLogin = when (target) {
        is PlayTarget.Live -> target.login
        is PlayTarget.Vod -> target.channelLogin
    }
    val isFavorite = favorites.any { it.login.equals(channelLogin, ignoreCase = true) }

    val exo = remember {
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    var controlsVisible by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(Menu.None) }
    var qualityChoice by remember { mutableStateOf<String?>(null) }
    var audioOnly by remember { mutableStateOf(false) }
    var qualityLabel by remember { mutableStateOf("Auto") }
    var buffering by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var resumeAfterStop by remember { mutableStateOf(false) }
    var recoveryMessage by remember { mutableStateOf<String?>(null) }
    var endedNote by remember { mutableStateOf<String?>(null) }
    var showChatQr by remember { mutableStateOf(false) }
    var raidSeconds by remember { mutableIntStateOf(RAID_COUNTDOWN_SECONDS) }

    val recoveryJob = remember { arrayOfNulls<Job>(1) }
    val stall = remember { StallDetector() }

    val rootFocus = remember { FocusRequester() }
    val firstControl = remember { FocusRequester() }
    val selectedQualityItem = remember { FocusRequester() }
    val firstPeekItem = remember { FocusRequester() }

    val ready = (phase as? PlayerController.Phase.Ready)?.stream
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
            val list = withTimeoutOrNull(4_000) { controller.others.first { it.isNotEmpty() } } ?: return@launch
            val candidates = list.filterNot { it.userLogin.equals(channelLogin, ignoreCase = true) }
            if (candidates.isEmpty()) return@launch
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
                        stall.onProgress()
                        playerError = null
                        endedNote = null
                    }
                    Player.STATE_ENDED -> if (isLive) startRecovery()
                }
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

    LaunchedEffect(ready, qualityChoice, audioOnly) {
        val stream = ready ?: return@LaunchedEffect
        val (label, url) = resolve(stream, qualityChoice, audioOnly, controller.settings.value.quality)
        qualityLabel = label
        val start = when {
            isLive -> C.TIME_UNSET
            exo.mediaItemCount > 0 -> exo.currentPosition // changing quality mid-VOD keeps your place
            else -> (target as? PlayTarget.Vod)?.let { container.settings.vodPosition(it.id) } ?: C.TIME_UNSET
        }
        exo.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build(), start)
        exo.prepare()
        exo.play()
        playerError = null
    }

    LaunchedEffect(exo) {
        while (true) {
            positionMs = exo.currentPosition
            durationMs = exo.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            delay(if (controlsVisible) 500 else 5_000)
        }
    }

    if (isLive) {
        // A stream that sits in "buffering" without erroring is the commonest way Twitch streams die: catch it.
        LaunchedEffect(exo) {
            while (true) {
                delay(2_000)
                if (stall.isStalled(SystemClock.elapsedRealtime())) {
                    stall.onProgress()
                    startRecovery()
                }
            }
        }
    } else {
        LaunchedEffect(exo) {
            controller.startReplay { exo.currentPosition }
            while (true) {
                delay(20_000)
                saveProgress()
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
                    confirm || event.key == Key.DirectionUp -> {
                        controlsVisible = true
                        true
                    }
                    event.key == Key.DirectionDown -> {
                        if (isLive) openPeek() else controlsVisible = true
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
                    update = { it.keepScreenOn = settings.keepScreenOn },
                    modifier = Modifier.fillMaxSize(),
                )

                if (audioOnly && playerError == null) {
                    Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                        Text("Audio only", style = MaterialTheme.typography.headlineMedium, color = Palette.BrandSoft)
                        Text(titleOf(target), style = MaterialTheme.typography.titleMedium, color = Palette.Muted)
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
                        } else if (buffering && !bridge.inPip) {
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
                    raid?.let { r ->
                        Banner(
                            "${r.fromLogin.ifEmpty { "This channel" }} is raiding ${r.toDisplayName} with ${Format.count(r.viewers)} viewers — " +
                                "joining in ${raidSeconds}s · OK to go now · Back to stay",
                            Modifier.align(Alignment.TopCenter),
                        )
                    }
                }

                if (controlsVisible && !bridge.inPip) {
                    ControlsOverlay(
                        target = target,
                        isLive = isLive,
                        playing = playing,
                        qualityLabel = qualityLabel,
                        chatOn = settings.chatVisible,
                        audioOnly = audioOnly,
                        pipSupported = bridge.pipSupported,
                        isFavorite = isFavorite,
                        showClock = settings.showClock,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        firstControl = firstControl,
                        onPlayPause = ::togglePlayback,
                        onSeek = ::seekBy,
                        onQuality = { menu = Menu.Quality },
                        onChat = ::toggleChat,
                        onAudioOnly = { audioOnly = !audioOnly },
                        onPip = {
                            controlsVisible = false
                            bridge.enterPip()
                        },
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
                        onChatOnPhone = { showChatQr = true },
                    )
                }
            }

            if (side) {
                ChatPanel(controller.chat, settings, Modifier.fillMaxHeight().width(CHAT_WIDTH).background(Palette.Background))
            }
        }

        if (menu == Menu.Quality && ready != null) {
            QualityMenu(
                variants = ready.variants.filter { !it.isAudioOnly },
                current = if (qualityChoice == null) qualityLabel else qualityChoice,
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

@Composable
private fun ControlsOverlay(
    target: PlayTarget,
    isLive: Boolean,
    playing: Boolean,
    qualityLabel: String,
    chatOn: Boolean,
    audioOnly: Boolean,
    pipSupported: Boolean,
    isFavorite: Boolean,
    showClock: Boolean,
    positionMs: Long,
    durationMs: Long,
    firstControl: FocusRequester,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onQuality: () -> Unit,
    onChat: () -> Unit,
    onAudioOnly: () -> Unit,
    onPip: () -> Unit,
    onPeek: () -> Unit,
    onFavorite: () -> Unit,
    onChannel: () -> Unit,
    onChatOnPhone: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                .padding(horizontal = 48.dp, vertical = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(titleOf(target), style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = when (target) {
                    is PlayTarget.Live -> listOfNotNull(target.title, target.game).joinToString(" · ")
                    is PlayTarget.Vod -> target.channelName
                }
                if (subtitle.isNotBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (showClock) ClockText()
        }

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                .padding(horizontal = 48.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!isLive) SeekBar(positionMs, durationMs)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!isLive) PillButton("−30s", { onSeek(-30_000) }, Modifier.focusRequester(firstControl))
                PillButton(
                    if (playing) "Pause" else "Play",
                    onPlayPause,
                    if (isLive) Modifier.focusRequester(firstControl) else Modifier,
                )
                if (!isLive) PillButton("+30s", { onSeek(30_000) })
                PillButton("Quality: $qualityLabel", onQuality)
                PillButton(if (chatOn) "Chat: On" else "Chat: Off", onChat)
                PillButton(if (audioOnly) "Audio only: On" else "Audio only", onAudioOnly, selected = audioOnly)
                if (pipSupported) PillButton("Picture-in-picture", onPip)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isLive) PillButton("Peek other streams", onPeek)
                PillButton(if (isFavorite) "★ Favorite" else "☆ Favorite", onFavorite, selected = isFavorite)
                PillButton("Channel page", onChannel)
                PillButton("Chat on phone", onChatOnPhone)
            }
        }
    }
}

@Composable
private fun SeekBar(positionMs: Long, durationMs: Long) {
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0x55FFFFFF))) {
            Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(Palette.Brand))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(Format.clock(positionMs / 1000), style = MaterialTheme.typography.labelMedium, color = Color.White)
            Text(Format.clock(durationMs / 1000), style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
        }
    }
}

/**
 * Peek view: browse other live channels without leaving the stream. Uses thumbnails only — a second live video
 * decoder next to the playing stream is more than many Google TV chips can do.
 */
@Composable
private fun PeekPanel(
    streams: List<Stream>,
    firstItem: FocusRequester,
    onPick: (Stream) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Palette.Scrim, Palette.Scrim)))
            .padding(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Peek — pick a channel to switch (Back to close)", style = MaterialTheme.typography.titleSmall, color = Palette.OnBackground)
        if (streams.isEmpty()) {
            Text("Loading channels…", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(streams, key = { it.userId }) { stream ->
                    val isFirst = stream.userId == streams.first().userId
                    FocusSurface(
                        onClick = { onPick(stream) },
                        modifier = Modifier.width(220.dp).let { if (isFirst) it.focusRequester(firstItem) else it },
                    ) {
                        Column {
                            Box(Modifier.fillMaxWidth().height(124.dp)) {
                                AsyncImage(
                                    model = stream.thumbnail(320, 180),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Column(Modifier.padding(10.dp)) {
                                Text(stream.userName, style = MaterialTheme.typography.titleSmall, color = Palette.OnBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${Format.count(stream.viewerCount)} · ${stream.gameName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Palette.Muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QualityMenu(
    variants: List<Variant>,
    current: String?,
    selectedItem: FocusRequester,
    onPick: (Variant?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxHeight()
            .width(320.dp)
            .background(Palette.Scrim)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        Text("Quality", style = MaterialTheme.typography.titleLarge, color = Palette.OnBackground)
        QualityRow("Auto", current == "Auto", { onPick(null) }, if (current == "Auto" || current == null) Modifier.focusRequester(selectedItem) else Modifier)
        for (v in variants) {
            val selected = current == v.name
            QualityRow(v.name, selected, { onPick(v) }, if (selected) Modifier.focusRequester(selectedItem) else Modifier)
        }
    }
}

@Composable
private fun QualityRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    FocusSurface(onClick, modifier.fillMaxWidth(), focusedScale = 1.03f) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = Palette.OnBackground)
            if (selected) Text("✓", style = MaterialTheme.typography.titleSmall, color = Palette.BrandSoft)
        }
    }
}
