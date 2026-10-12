package com.s0undplus.tv.ui.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SpeakerNotesOff
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size as CoilSize
import coil3.toBitmap
import com.s0undplus.tv.ui.components.ClockText
import com.s0undplus.tv.ui.nav.PlayTarget
import com.s0undplus.tv.ui.theme.AppType
import com.s0undplus.tv.ui.theme.Palette
import com.s0undplus.tv.ui.util.Format
import com.s0undplus.twitch.Stream
import com.s0undplus.twitch.playback.SeekPreviewFrame
import com.s0undplus.twitch.playback.SeekPreviews
import kotlin.math.roundToInt

/** Behind live by more than this (twice the normal target), the controls offer a way back to live. */
private const val JUMP_TO_LIVE_AFTER_MS = 12_000L

/** A focused control: white with a dark symbol, the way YouTube on TV marks it. */
private val FocusedFill = Color.White
private val FocusedSymbol = Color(0xFF111114)
private val IdleFill = Color(0x24FFFFFF)

/**
 * The player's controls, laid out like YouTube on TV: the title at the top; at the bottom a full-width seek bar and,
 * under it, one row of round buttons (playback on the left, the channel and Options on the right). Focus starts on
 * Play/Pause. From the buttons, Down closes the controls and Up goes to the seek bar (videos) or closes them (live);
 * from the seek bar, Up closes them and Down comes back to the buttons.
 */
@Composable
internal fun ControlsOverlay(
    target: PlayTarget,
    title: String,
    isLive: Boolean,
    playing: Boolean,
    chatOn: Boolean,
    isFavorite: Boolean,
    showClock: Boolean,
    info: Stream?,
    latencyMs: Long?,
    positionMs: Long,
    durationMs: Long,
    seekPreviews: SeekPreviews?,
    firstControl: FocusRequester,
    seekBar: FocusRequester,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onChat: () -> Unit,
    onPeek: () -> Unit,
    onFavorite: () -> Unit,
    onChannel: () -> Unit,
    onOptions: () -> Unit,
    onJumpToLive: () -> Unit,
    onClose: () -> Unit,
    /** Room to leave on the right for chat drawn over the video, so the title doesn't run underneath it. */
    headerEndInset: Dp = 0.dp,
    headerStartInset: Dp = 0.dp,
) {
    // One backdrop over the whole screen, chat included: dark at the top and bottom where the text and buttons are, clear
    // in the middle. On a busy stream the buttons were unreadable over the picture and the chat.
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color(0xC0000000),
                    0.28f to Color(0x40000000),
                    0.5f to Color(0x30000000),
                    0.62f to Color(0x80000000),
                    1f to Color(0xF2000000),
                ),
            ),
    ) {
        Header(target, title, isLive, info, latencyMs, showClock, headerEndInset, headerStartInset)

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 48.dp, end = 48.dp, bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            if (isLive) {
                LiveBar()
            } else {
                SeekBar(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    previews = seekPreviews,
                    focus = seekBar,
                    onSeekTo = onSeekTo,
                    onPlayPause = onPlayPause,
                    onUp = onClose,
                    onDown = { focusQuietly(firstControl) },
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    // Up and Down leave the row: Down closes the controls; Up goes to the seek bar, or closes them on
                    // live streams, which have no seek bar to go to.
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.nativeKeyEvent.keyCode) {
                            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                onClose()
                                true
                            }
                            AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                                if (isLive) onClose() else focusQuietly(seekBar)
                                true
                            }
                            else -> false
                        }
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!isLive) RoundButton(Icons.Rounded.Replay10, "Back 10 s", { onSeek(-10_000) })
                    RoundButton(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (playing) "Pause" else "Play",
                        onPlayPause,
                        Modifier.focusRequester(firstControl),
                        large = true,
                    )
                    if (!isLive) RoundButton(Icons.Rounded.Forward10, "Forward 10 s", { onSeek(10_000) })
                    if (isLive) {
                        // Only worth offering when the viewer has fallen noticeably behind (after a pause, say).
                        val behind = latencyMs?.takeIf { it > JUMP_TO_LIVE_AFTER_MS }
                        if (behind != null) RoundButton(Icons.Rounded.LiveTv, "Go live · ${behind / 1000} s behind", onJumpToLive, active = true)
                    }
                    Divider()
                    RoundButton(
                        if (chatOn) Icons.AutoMirrored.Rounded.Chat else Icons.Rounded.SpeakerNotesOff,
                        if (chatOn) "Hide chat" else "Show chat",
                        onChat,
                        active = chatOn,
                    )
                    if (isLive) RoundButton(Icons.Rounded.ViewCarousel, "Other channels", onPeek)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(
                        if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        if (isFavorite) "Remove from favorites" else "Add to favorites",
                        onFavorite,
                        active = isFavorite,
                    )
                    RoundButton(Icons.Rounded.AccountCircle, "Channel page", onChannel)
                    RoundButton(Icons.Rounded.Settings, "Options", onOptions)
                }
            }
        }
    }
}

private fun focusQuietly(requester: FocusRequester) {
    try {
        requester.requestFocus()
    } catch (_: IllegalStateException) {
        // not attached this frame
    }
}

@Composable
private fun Header(
    target: PlayTarget,
    title: String,
    isLive: Boolean,
    info: Stream?,
    latencyMs: Long?,
    showClock: Boolean,
    endInset: Dp,
    startInset: Dp,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 48.dp + startInset, end = 48.dp + endInset, top = 28.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = when (target) {
                is PlayTarget.Live -> listOfNotNull(
                    info?.title?.takeIf { it.isNotBlank() } ?: target.title,
                    info?.gameName?.takeIf { it.isNotBlank() } ?: target.game,
                ).joinToString(" · ")
                is PlayTarget.Vod -> target.channelName
            }
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (isLive && info != null) {
                val uptime = Format.uptime(info.startedAt)
                val behind = latencyMs?.let { " · ${(it + 500) / 1000} s behind live" }.orEmpty()
                Text(
                    "${Format.count(info.viewerCount)} viewers" + (uptime?.let { " · live for $it" }.orEmpty()) + behind,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                )
            }
        }
        if (showClock) ClockText()
    }
}

@Composable
private fun Divider() = Box(Modifier.width(1.dp).height(26.dp).background(Color(0x40FFFFFF)))

/** A round symbol button: white when focused, with its name underneath; a toggle that is on keeps a tinted symbol. */
@Composable
private fun RoundButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    active: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val size = if (large) 52.dp else 44.dp
    // Fixed width: the label may be wider than the button and simply overhangs, so the row never shifts as focus moves.
    Box(Modifier.width(size), contentAlignment = Alignment.TopCenter) {
        Box(
            modifier
                .size(size)
                .onFocusChanged { focused = it.isFocused }
                .clickable(onClick = onClick)
                .graphicsLayer {
                    val s = if (focused) 1.08f else 1f
                    scaleX = s
                    scaleY = s
                }
                .background(if (focused) FocusedFill else IdleFill, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = when {
                    focused -> FocusedSymbol
                    active -> Palette.BrandSoft
                    else -> Color.White
                },
                modifier = Modifier.size(if (large) 30.dp else 24.dp),
            )
        }
        Text(
            label,
            style = AppType.CardMeta.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
            color = Color.White,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .offset(y = size + 8.dp)
                .wrapContentWidth(unbounded = true)
                .alpha(if (focused) 1f else 0f),
        )
    }
}

/** On live streams the bar is simply full, with LIVE at its end. */
@Composable
private fun LiveBar() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.weight(1f).height(4.dp).background(Palette.Brand, RoundedCornerShape(2.dp)))
        Text(
            "LIVE",
            style = AppType.Badge,
            color = Color.White,
            modifier = Modifier.background(Palette.Live, RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

/** How far one Left/Right moves the scrub marker: further the longer the key is held. */
private fun scrubStepMs(repeatCount: Int): Long = when {
    repeatCount < 6 -> 10_000L
    repeatCount < 16 -> 30_000L
    repeatCount < 30 -> 60_000L
    else -> 300_000L
}

/**
 * A past broadcast's seek bar. Focused, Left/Right move a marker (in bigger steps the longer the key is held) while
 * the video keeps playing, with a preview picture of that moment above it; OK jumps there, Back puts the marker away.
 * OK with no marker out pauses or plays.
 */
@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    previews: SeekPreviews?,
    focus: FocusRequester,
    onSeekTo: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<Long?>(null) }
    // One Back puts the marker away; the next closes the controls as usual.
    BackHandler(enabled = target != null) { target = null }

    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val targetFraction = target?.let { if (durationMs > 0) (it.toFloat() / durationMs).coerceIn(0f, 1f) else 0f }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(Format.clock(positionMs / 1000), style = AppType.CardMeta, color = Color.White)
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .height(24.dp)
                .focusRequester(focus)
                .onFocusChanged {
                    focused = it.isFocused
                    if (!it.isFocused) target = null
                }
                .onPreviewKeyEvent { event ->
                    val code = event.nativeKeyEvent.keyCode
                    val confirm = code == AndroidKeyEvent.KEYCODE_DPAD_CENTER || code == AndroidKeyEvent.KEYCODE_ENTER ||
                        code == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent confirm // the OK key-up too
                    when {
                        code == AndroidKeyEvent.KEYCODE_DPAD_LEFT || code == AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (durationMs > 0) {
                                val step = scrubStepMs(event.nativeKeyEvent.repeatCount)
                                val from = target ?: positionMs
                                target = (if (code == AndroidKeyEvent.KEYCODE_DPAD_RIGHT) from + step else from - step).coerceIn(0L, durationMs)
                            }
                            true
                        }
                        confirm -> {
                            val t = target
                            if (t != null) {
                                onSeekTo(t)
                                target = null
                            } else {
                                onPlayPause()
                            }
                            true
                        }
                        code == AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                            target = null
                            onUp()
                            true
                        }
                        code == AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                            target = null
                            onDown()
                            true
                        }
                        else -> false
                    }
                }
                .focusable(),
            contentAlignment = Alignment.CenterStart,
        ) {
            val width = maxWidth
            Track(fraction, targetFraction, focused)
            if (targetFraction != null) {
                // Centred over the marker, but never past either end of the bar.
                val x = (width * targetFraction - PREVIEW_WIDTH / 2).coerceIn(0.dp, (width - PREVIEW_WIDTH).coerceAtLeast(0.dp))
                ScrubPreview(
                    atMs = target ?: 0L,
                    frame = previews?.frameAt((target ?: 0L) / 1000.0),
                    // Unbounded: the bar's box is only 24 dp tall, and the preview rises well above it.
                    modifier = Modifier
                        .offset(x = x, y = -(PREVIEW_HEIGHT + 28.dp))
                        .wrapContentSize(Alignment.TopStart, unbounded = true),
                )
                Text(
                    "OK  jump there   ·   Back  cancel",
                    style = AppType.CardMeta,
                    color = Color(0xFFD8D4E2),
                    modifier = Modifier.align(Alignment.TopEnd).offset(y = (-26).dp),
                )
            }
        }
        Text(Format.clock(durationMs / 1000), style = AppType.CardMeta, color = Palette.Muted)
    }
}

/** The bar itself: what was played in the brand colour; while scrubbing, a dashed run to the white target marker. */
@Composable
private fun Track(fraction: Float, targetFraction: Float?, focused: Boolean) {
    val thickness = if (focused) 6.dp else 4.dp
    Canvas(Modifier.fillMaxWidth().height(24.dp)) {
        val y = size.height / 2
        val t = thickness.toPx()
        val radius = CornerRadius(t / 2, t / 2)
        drawRoundRect(Color(0x47FFFFFF), Offset(0f, y - t / 2), Size(size.width, t), radius)
        drawRoundRect(Palette.Brand, Offset(0f, y - t / 2), Size(size.width * fraction, t), radius)
        if (targetFraction != null) {
            val from = size.width * fraction
            val to = size.width * targetFraction
            drawLine(
                Color(0xD9FFFFFF),
                Offset(minOf(from, to), y),
                Offset(maxOf(from, to), y),
                strokeWidth = t,
                cap = StrokeCap.Butt,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
            )
            drawCircle(Palette.Brand, radius = 12.dp.toPx(), center = Offset(to, y))
            drawCircle(Color.White, radius = 9.dp.toPx(), center = Offset(to, y))
        } else if (focused) {
            drawCircle(Color.White, radius = 8.dp.toPx(), center = Offset(size.width * fraction, y))
        }
    }
}

private val PREVIEW_WIDTH = 192.dp
private val PREVIEW_HEIGHT = 108.dp

/** The picture of the moment the marker is on (when Twitch has previews for this video), and its time. */
@Composable
private fun ScrubPreview(atMs: Long, frame: SeekPreviewFrame?, modifier: Modifier) {
    Column(modifier.width(PREVIEW_WIDTH), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (frame != null) {
            SpriteTile(
                frame,
                Modifier
                    .size(PREVIEW_WIDTH, PREVIEW_HEIGHT)
                    .border(2.dp, Color.White, RoundedCornerShape(6.dp))
                    .padding(2.dp),
            )
        } else {
            Spacer(Modifier.height(PREVIEW_HEIGHT))
        }
        Text(
            Format.clock(atMs / 1000),
            style = AppType.Button,
            color = FocusedSymbol,
            modifier = Modifier.background(Color.White, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * One frame cut out of a preview sprite sheet. Sheets are loaded at full size (the frame's position is in the sheet's
 * own pixels) and kept by Coil's memory cache, so scrubbing within a sheet costs nothing more.
 */
@Composable
private fun SpriteTile(frame: SeekPreviewFrame, modifier: Modifier) {
    val context = LocalPlatformContext.current
    var sheet by remember(frame.imageUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(frame.imageUrl) {
        val request = ImageRequest.Builder(context).data(frame.imageUrl).size(CoilSize.ORIGINAL).build()
        val result = SingletonImageLoader.get(context).execute(request)
        sheet = (result as? SuccessResult)?.image?.toBitmap()?.asImageBitmap()
    }
    Canvas(modifier.background(Color.Black)) {
        val image = sheet ?: return@Canvas
        drawImage(
            image,
            srcOffset = IntOffset(frame.x, frame.y),
            srcSize = IntSize(frame.width.coerceAtMost(image.width - frame.x), frame.height.coerceAtMost(image.height - frame.y)),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        )
    }
}
