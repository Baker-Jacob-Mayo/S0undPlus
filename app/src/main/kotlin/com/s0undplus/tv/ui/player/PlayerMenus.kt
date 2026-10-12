package com.s0undplus.tv.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.tv.material3.Icon
import com.s0undplus.tv.ui.theme.AppType
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
    status: OthersStatus = OthersStatus.Loading,
    followedOnly: Boolean = true,
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
            Text(
                when {
                    status == OthersStatus.Failed -> "Couldn't load channels. Close this and try again."
                    status != OthersStatus.Loaded -> "Loading channels…"
                    followedOnly -> "None of the other channels you follow are live right now."
                    else -> "No other channels are live right now."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Muted,
            )
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

/** The panel the player's menus open in: down the right edge, opaque (it opens over chat), the D-pad kept inside. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SidePanel(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxHeight()
            .width(340.dp)
            .background(Color(0xF7141319))
            // Arrow keys stay in the panel; Back closes it.
            .focusProperties {
                exit = { direction ->
                    if (direction == FocusDirection.Left || direction == FocusDirection.Right ||
                        direction == FocusDirection.Up || direction == FocusDirection.Down
                    ) {
                        FocusRequester.Cancel
                    } else {
                        FocusRequester.Default
                    }
                }
            }
            .focusGroup()
            .padding(horizontal = 20.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = AppType.TitleL, color = Color.White, modifier = Modifier.padding(start = 14.dp, bottom = 12.dp))
        content()
    }
}

/** A row in a player panel: white with dark text when focused, the way the controls mark focus. */
@Composable
private fun PanelRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    value: String? = null,
    checked: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .background(if (focused) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val ink = if (focused) Color(0xFF111114) else Color.White
        if (icon != null) Icon(icon, contentDescription = null, tint = if (focused) Color(0xFF4A4655) else Color(0xFFCFCBD8), modifier = Modifier.size(22.dp))
        Text(label, style = AppType.BodyL, color = ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (value != null) Text(value, style = AppType.BodyM, color = if (focused) Color(0xFF4A4655) else Palette.BrandSoft, maxLines = 1)
        if (checked) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = if (focused) Color(0xFF111114) else Palette.BrandSoft, modifier = Modifier.size(20.dp))
    }
}

/** What the Options button opens: everything that isn't on the controls' main row. */
@Composable
internal fun OptionsSheet(
    qualityLabel: String,
    audioOnly: Boolean,
    zoom: Boolean,
    pipSupported: Boolean,
    firstItem: FocusRequester,
    onQuality: () -> Unit,
    onAudioOnly: () -> Unit,
    onZoom: () -> Unit,
    onPip: () -> Unit,
    onChatOnPhone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SidePanel("Options", modifier) {
        PanelRow("Quality", onQuality, Modifier.focusRequester(firstItem), Icons.Rounded.HighQuality, value = qualityLabel)
        PanelRow("Audio only", onAudioOnly, icon = Icons.Rounded.Headphones, value = if (audioOnly) "On" else "Off")
        PanelRow("Picture", onZoom, icon = Icons.Rounded.AspectRatio, value = if (zoom) "Fill" else "Fit")
        if (pipSupported) PanelRow("Picture-in-picture", onPip, icon = Icons.Rounded.PictureInPictureAlt)
        PanelRow("Chat on your phone", onChatOnPhone, icon = Icons.Rounded.QrCode2)
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
    SidePanel("Quality", modifier) {
        // Focus goes to the ticked row, or to Auto when nothing matches (audio-only, or a rung that is no longer offered).
        val matched = variants.any { it.name == current }
        PanelRow("Auto", { onPick(null) }, if (!matched) Modifier.focusRequester(selectedItem) else Modifier, checked = current == "Auto")
        for (v in variants) {
            val selected = current == v.name
            PanelRow(v.name, { onPick(v) }, if (selected) Modifier.focusRequester(selectedItem) else Modifier, checked = selected)
        }
    }
}
