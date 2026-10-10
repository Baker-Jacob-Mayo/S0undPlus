package com.s0undplus.tv.ui.player

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import com.s0undplus.tv.LocalActivityBridge
import com.s0undplus.tv.LocalAppContainer
import com.s0undplus.tv.data.ChatLayout
import com.s0undplus.tv.ui.components.FocusSurface
import com.s0undplus.tv.ui.components.MessageState
import com.s0undplus.tv.ui.components.PillButton
import com.s0undplus.tv.ui.components.Spinner
import com.s0undplus.tv.ui.nav.PlayTarget
import com.s0undplus.tv.ui.theme.Palette
import com.s0undplus.tv.ui.util.Format
import com.s0undplus.tv.ui.util.rememberScreenScope
import com.s0undplus.twitch.playback.PlaybackStream
import com.s0undplus.twitch.playback.QualityPreference
import com.s0undplus.twitch.playback.Variant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Menu { None, Quality }

private const val SEEK_SMALL_MS = 10_000L
private const val SEEK_LARGE_MS = 30_000L
private const val CONTROLS_TIMEOUT_MS = 6_000L
private val CHAT_WIDTH = 380.dp

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(target: PlayTarget, onExit: () -> Unit) {
    val container = LocalAppContainer.current
    val bridge = LocalActivityBridge.current
    val context = LocalContext.current
    val screenScope = rememberScreenScope()
    val uiScope = rememberCoroutineScope()

    val controller = remember(target) { PlayerController(container, target, screenScope) }
    val settings by controller.settings.collectAsStateWithLifecycle()
    val phase by controller.phase.collectAsStateWithLifecycle()
    val isLive = controller.isLive

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

    val rootFocus = remember { FocusRequester() }
    val firstControl = remember { FocusRequester() }
    val selectedQualityItem = remember { FocusRequester() }

    val ready = (phase as? PlayerController.Phase.Ready)?.stream
    val chatShown = settings.chatVisible && controller.chat != null && !bridge.inPip

    fun saveVodPosition() {
        val vod = target as? PlayTarget.Vod ?: return
        val position = exo.currentPosition
        val duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        container.appScope.launch { container.settings.saveVodPosition(vod.id, position, duration) }
    }

    fun seekBy(deltaMs: Long) {
        val limit = if (durationMs > 0) durationMs else Long.MAX_VALUE
        exo.seekTo((exo.currentPosition + deltaMs).coerceIn(0L, limit))
    }

    fun togglePlayback() {
        if (exo.isPlaying) exo.pause() else exo.play()
    }

    // --- Player wiring -------------------------------------------------------------------------------------------

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) playerError = null
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    // We fell too far behind the live edge (e.g. a long stall): jump back to live and carry on.
                    exo.seekToDefaultPosition()
                    exo.prepare()
                } else {
                    playerError = error.errorCodeName
                }
            }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            saveVodPosition()
            exo.release()
        }
    }

    DisposableEffect(ready != null) {
        bridge.pipEligible = ready != null
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

    if (!isLive) {
        LaunchedEffect(exo) {
            while (true) {
                delay(20_000)
                saveVodPosition()
            }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        // Fires when the app is backgrounded *or* the PiP window is dismissed: never keep playing invisibly.
        resumeAfterStop = exo.playWhenReady
        exo.pause()
        saveVodPosition()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (resumeAfterStop) {
            if (isLive) exo.seekToDefaultPosition()
            exo.play()
            resumeAfterStop = false
        }
    }

    // --- Focus + controls visibility -----------------------------------------------------------------------------

    LaunchedEffect(controlsVisible, menu) {
        try {
            when {
                menu == Menu.Quality -> selectedQualityItem.requestFocus()
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
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                interaction++
                // Media keys work everywhere.
                when (event.key) {
                    Key.MediaPlayPause -> { togglePlayback(); return@onPreviewKeyEvent true }
                    Key.MediaPlay -> { exo.play(); return@onPreviewKeyEvent true }
                    Key.MediaPause -> { exo.pause(); return@onPreviewKeyEvent true }
                    Key.MediaRewind -> if (!isLive) { seekBy(-SEEK_LARGE_MS); return@onPreviewKeyEvent true }
                    Key.MediaFastForward -> if (!isLive) { seekBy(SEEK_LARGE_MS); return@onPreviewKeyEvent true }
                }
                // With the overlay hidden, the D-pad drives playback instead of moving focus.
                if (controlsVisible || menu != Menu.None) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.DirectionUp, Key.DirectionDown -> {
                        controlsVisible = true
                        true
                    }
                    Key.DirectionLeft -> {
                        if (isLive) {
                            if (settings.chatVisible) uiScope.launch { container.settings.update { it.copy(chatVisible = false) } }
                        } else {
                            seekBy(-SEEK_SMALL_MS)
                        }
                        true
                    }
                    Key.DirectionRight -> {
                        if (isLive) {
                            if (!settings.chatVisible) uiScope.launch { container.settings.update { it.copy(chatVisible = true) } }
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
                            keepScreenOn = true
                            isFocusable = false
                            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                            player = exo
                        }
                    },
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
                    is PlayerController.Phase.Failed -> MessageState(
                        p.message,
                        Modifier.fillMaxSize().background(Color.Black),
                        actionLabel = if (p.offline) "Back" else "Try again",
                        onAction = if (p.offline) onExit else controller::load,
                    )
                    is PlayerController.Phase.Ready -> {
                        if (playerError != null) {
                            MessageState(
                                "Playback problem (${playerError}).",
                                Modifier.fillMaxSize().background(Color(0xCC000000)),
                                actionLabel = "Try again",
                                onAction = { playerError = null; controller.load() },
                            )
                        } else if (buffering && !bridge.inPip) {
                            CenteredSpinner()
                        }
                    }
                }

                if (chatShown && !side) {
                    controller.chat?.let { chat ->
                        ChatPanel(
                            chat,
                            settings.chatTextSize,
                            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(CHAT_WIDTH).background(Palette.Scrim),
                        )
                    }
                }

                if (controlsVisible && !bridge.inPip) {
                    ControlsOverlay(
                        target = target,
                        isLive = isLive,
                        playing = playing,
                        qualityLabel = qualityLabel,
                        chatAvailable = controller.chat != null,
                        chatOn = settings.chatVisible,
                        audioOnly = audioOnly,
                        pipSupported = bridge.pipSupported,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        firstControl = firstControl,
                        onPlayPause = ::togglePlayback,
                        onSeek = ::seekBy,
                        onQuality = { menu = Menu.Quality },
                        onChat = { uiScope.launch { container.settings.update { it.copy(chatVisible = !it.chatVisible) } } },
                        onAudioOnly = { audioOnly = !audioOnly },
                        onPip = {
                            controlsVisible = false
                            bridge.enterPip()
                        },
                    )
                }
            }

            if (side) {
                controller.chat?.let { chat ->
                    ChatPanel(chat, settings.chatTextSize, Modifier.fillMaxHeight().width(CHAT_WIDTH).background(Palette.Background))
                }
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
private fun ControlsOverlay(
    target: PlayTarget,
    isLive: Boolean,
    playing: Boolean,
    qualityLabel: String,
    chatAvailable: Boolean,
    chatOn: Boolean,
    audioOnly: Boolean,
    pipSupported: Boolean,
    positionMs: Long,
    durationMs: Long,
    firstControl: FocusRequester,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onQuality: () -> Unit,
    onChat: () -> Unit,
    onAudioOnly: () -> Unit,
    onPip: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                .padding(horizontal = 48.dp, vertical = 28.dp),
        ) {
            Text(titleOf(target), style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = when (target) {
                is PlayTarget.Live -> listOfNotNull(target.title, target.game).joinToString(" · ")
                is PlayTarget.Vod -> target.channelName
            }
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
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
                if (chatAvailable) PillButton(if (chatOn) "Chat: On" else "Chat: Off", onChat)
                PillButton(if (audioOnly) "Audio only: On" else "Audio only", onAudioOnly, selected = audioOnly)
                if (pipSupported) PillButton("Picture-in-picture", onPip)
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
