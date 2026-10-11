package com.s0undplus.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.SpeakerNotesOff
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.tv.material3.Icon
import com.s0undplus.tv.ui.components.FocusSurface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.s0undplus.tv.ui.components.ClockText
import com.s0undplus.tv.ui.nav.PlayTarget
import com.s0undplus.tv.ui.theme.Palette
import com.s0undplus.tv.ui.util.Format
import com.s0undplus.twitch.Stream

/** Behind live by more than this (twice the normal target), the controls offer a way back to live. */
private const val JUMP_TO_LIVE_AFTER_MS = 12_000L

@Composable
internal fun ControlsOverlay(
    target: PlayTarget,
    title: String,
    isLive: Boolean,
    playing: Boolean,
    qualityLabel: String,
    chatOn: Boolean,
    audioOnly: Boolean,
    pipSupported: Boolean,
    isFavorite: Boolean,
    showClock: Boolean,
    info: Stream?,
    latencyMs: Long?,
    zoom: Boolean,
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
    onZoom: () -> Unit,
    onJumpToLive: () -> Unit,
    /** Room to leave on the right for chat drawn over the video, so the title doesn't run underneath it. */
    headerEndInset: Dp = 0.dp,
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
        Row(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(start = 48.dp, end = 48.dp + headerEndInset, top = 28.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
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

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (!isLive) SeekBar(positionMs, durationMs)
            // One row of symbols in three groups (playback, viewing, channel). The focused one shows its name below it,
            // and toggles that are on stay tinted, so the state is readable at a glance.
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!isLive) ControlButton(Icons.Rounded.Replay30, "Back 30 s", { onSeek(-30_000) }, Modifier.focusRequester(firstControl))
                ControlButton(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (playing) "Pause" else "Play",
                    onPlayPause,
                    if (isLive) Modifier.focusRequester(firstControl) else Modifier,
                    large = true,
                )
                if (!isLive) ControlButton(Icons.Rounded.Forward30, "Forward 30 s", { onSeek(30_000) })
                if (isLive) {
                    // Only worth offering when the viewer has fallen noticeably behind (after a pause, say).
                    val behind = latencyMs?.takeIf { it > JUMP_TO_LIVE_AFTER_MS }
                    if (behind != null) ControlButton(Icons.Rounded.LiveTv, "Go live · ${behind / 1000} s behind", onJumpToLive, active = true)
                }
                GroupGap()
                ControlButton(Icons.Rounded.HighQuality, "Quality · $qualityLabel", onQuality)
                ControlButton(
                    if (chatOn) Icons.AutoMirrored.Rounded.Chat else Icons.Rounded.SpeakerNotesOff,
                    if (chatOn) "Chat · on" else "Chat · off",
                    onChat,
                    active = chatOn,
                )
                if (isLive) ControlButton(Icons.Rounded.ViewCarousel, "Other channels", onPeek)
                ControlButton(Icons.Rounded.Headphones, if (audioOnly) "Audio only · on" else "Audio only · off", onAudioOnly, active = audioOnly)
                ControlButton(Icons.Rounded.AspectRatio, if (zoom) "Picture · fill screen" else "Picture · fit", onZoom, active = zoom)
                if (pipSupported) ControlButton(Icons.Rounded.PictureInPictureAlt, "Picture-in-picture", onPip)
                GroupGap()
                ControlButton(
                    if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    if (isFavorite) "Favorite · on" else "Add to favorites",
                    onFavorite,
                    active = isFavorite,
                )
                ControlButton(Icons.Rounded.AccountCircle, "Channel page", onChannel)
                ControlButton(Icons.Rounded.QrCode2, "Chat on your phone", onChatOnPhone)
            }
        }
    }
}

@Composable
private fun GroupGap() = Spacer(Modifier.width(14.dp))

/** A round symbol button whose name appears underneath while it has focus. */
@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    active: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val size = if (large) 72.dp else 56.dp
    // Fixed width: the label may be wider than the button and simply overhangs, so the row never shifts as focus moves.
    Column(Modifier.width(size), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FocusSurface(
            onClick = onClick,
            modifier = modifier.size(size).onFocusChanged { focused = it.isFocused },
            shape = CircleShape,
            container = if (active) Palette.Brand.copy(alpha = 0.55f) else Color(0xCC1C1C22), // dark glass: readable over any picture
            focusedContainer = Palette.Brand,
            focusedScale = 1.12f,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(if (large) 40.dp else 28.dp),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.wrapContentWidth(unbounded = true).alpha(if (focused) 1f else 0f),
        )
    }
}

@Composable
internal fun SeekBar(positionMs: Long, durationMs: Long) {
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
