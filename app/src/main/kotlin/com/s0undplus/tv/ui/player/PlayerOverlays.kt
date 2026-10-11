package com.s0undplus.tv.ui.player

import androidx.compose.foundation.background
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
import com.s0undplus.tv.ui.components.PillButton
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
                    0.68f to Color(0x80000000),
                    1f to Color(0xF0000000),
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
                    Text(
                        "${Format.count(info.viewerCount)} viewers" + (uptime?.let { " · live for $it" }.orEmpty()),
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
                .padding(horizontal = 48.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!isLive) SeekBar(positionMs, durationMs)
            // What you reach for while watching, on the row the remote lands on.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!isLive) PillButton("−30s", { onSeek(-30_000) }, Modifier.focusRequester(firstControl))
                PillButton(
                    if (playing) "Pause" else "Play",
                    onPlayPause,
                    if (isLive) Modifier.focusRequester(firstControl) else Modifier,
                )
                if (!isLive) PillButton("+30s", { onSeek(30_000) })
                if (isLive) {
                    // Only worth offering when the viewer has fallen noticeably behind (after a pause, say).
                    val behind = latencyMs?.takeIf { it > JUMP_TO_LIVE_AFTER_MS }
                    if (behind != null) PillButton("Go live (${behind / 1000} s behind)", onJumpToLive)
                }
                PillButton("Quality: $qualityLabel", onQuality)
                PillButton(if (chatOn) "Chat: On" else "Chat: Off", onChat)
                if (isLive) PillButton("Other channels", onPeek)
            }
            // Everything else, one row down.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton(if (isFavorite) "★ Favorite" else "☆ Favorite", onFavorite, selected = isFavorite)
                PillButton("Channel page", onChannel)
                PillButton(if (audioOnly) "Audio only: On" else "Audio only", onAudioOnly, selected = audioOnly)
                PillButton(if (zoom) "Video: fill" else "Video: fit", onZoom)
                if (pipSupported) PillButton("Picture-in-picture", onPip)
                PillButton("Chat on phone", onChatOnPhone)
            }
        }
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
