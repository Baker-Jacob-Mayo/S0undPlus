package com.s0undplus.tv.ui.player

import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.s0undplus.tv.ui.components.FocusSurface
import com.s0undplus.tv.ui.theme.Palette
import com.s0undplus.tv.ui.util.Format
import com.s0undplus.twitch.Stream
import com.s0undplus.twitch.playback.Variant
import kotlinx.coroutines.flow.first

/**
 * Peek view: browse other live channels without leaving the stream. Uses thumbnails only — a second live video
 * decoder next to the playing stream is more than many Google TV chips can do.
 */
@Composable
internal fun PeekPanel(
    streams: List<Stream>,
    firstItem: FocusRequester,
    onPick: (Stream) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The panel can open before the list has loaded; take focus as soon as there is a first card to take it.
    LaunchedEffect(streams.isNotEmpty()) {
        if (streams.isNotEmpty()) {
            try {
                firstItem.requestFocus()
            } catch (_: IllegalStateException) {
                // not attached this frame
            }
        }
    }
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
internal fun QualityMenu(
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
        // Focus goes to the ticked row, or to Auto when nothing matches (audio-only, or a rung that is no longer offered).
        val matched = variants.any { it.name == current }
        QualityRow("Auto", current == "Auto", { onPick(null) }, if (!matched) Modifier.focusRequester(selectedItem) else Modifier)
        for (v in variants) {
            val selected = current == v.name
            QualityRow(v.name, selected, { onPick(v) }, if (selected) Modifier.focusRequester(selectedItem) else Modifier)
        }
    }
}

@Composable
internal fun QualityRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    FocusSurface(onClick, modifier.fillMaxWidth(), focusedScale = 1.03f) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = Palette.OnBackground)
            if (selected) Text("✓", style = MaterialTheme.typography.titleSmall, color = Palette.BrandSoft)
        }
    }
}
