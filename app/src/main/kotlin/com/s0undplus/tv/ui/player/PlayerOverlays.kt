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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.s0undplus.tv.ui.components.ClockText
import com.s0undplus.tv.ui.components.PillButton
import com.s0undplus.tv.ui.nav.PlayTarget
import com.s0undplus.tv.ui.theme.Palette
import com.s0undplus.tv.ui.util.Format
import com.s0undplus.twitch.Stream

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
                PillButton(if (zoom) "Video: fill" else "Video: fit", onZoom)
                PillButton(if (chatOn) "Chat: On" else "Chat: Off", onChat)
                PillButton(if (audioOnly) "Audio only: On" else "Audio only", onAudioOnly, selected = audioOnly)
                if (pipSupported) PillButton("Picture-in-picture", onPip)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isLive) {
                    PillButton(
                        latencyMs?.let { "Jump to live (${"%.1f".format(java.util.Locale.US, it / 1000.0)} s behind)" } ?: "Jump to live",
                        onJumpToLive,
                    )
                    PillButton("Peek other streams", onPeek)
                }
                PillButton(if (isFavorite) "★ Favorite" else "☆ Favorite", onFavorite, selected = isFavorite)
                PillButton("Channel page", onChannel)
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
