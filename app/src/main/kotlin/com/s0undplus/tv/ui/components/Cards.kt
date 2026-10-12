package com.s0undplus.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.s0undplus.tv.ui.theme.AppType
import com.s0undplus.tv.ui.theme.Layout
import com.s0undplus.tv.ui.theme.Palette
import com.s0undplus.tv.ui.theme.Radius
import com.s0undplus.tv.ui.util.Format
import com.s0undplus.twitch.Category
import com.s0undplus.twitch.Stream
import com.s0undplus.twitch.Video
import com.s0undplus.twitch.library.HistoryEntry

/** A small label on a picture: LIVE, viewer counts, durations. */
@Composable
fun ImageBadge(text: String, modifier: Modifier = Modifier, background: Color = Palette.ImageBadge, dot: Color? = null) {
    Row(
        modifier
            .background(background, RoundedCornerShape(Radius.badge))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (dot != null) Box(Modifier.size(6.dp).background(dot, CircleShape))
        Text(text, style = AppType.Badge, color = Color.White, maxLines = 1)
    }
}

@Composable
fun LiveBadge(modifier: Modifier = Modifier) = ImageBadge("LIVE", modifier, background = Palette.Live)

/**
 * A picture whose memory-cache key is its URL, so a larger version of it (the hero's backdrop) can show this one
 * instantly while it loads. Thumbnails never crossfade: on a TV box that is just extra drawing while scrolling.
 */
@Composable
fun CardImage(url: String, modifier: Modifier = Modifier.fillMaxSize(), contentScale: ContentScale = ContentScale.Crop) {
    if (url.isBlank()) {
        // No picture yet (a broadcast still being recorded has none): a quiet placeholder instead of an empty box.
        Box(modifier.background(Brush.verticalGradient(listOf(Palette.SurfaceFocused, Palette.Surface))), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Videocam, contentDescription = null, tint = Palette.TextTertiary, modifier = Modifier.size(28.dp))
        }
        return
    }
    val context = LocalPlatformContext.current
    val request = remember(url) { ImageRequest.Builder(context).data(url).memoryCacheKey(url).build() }
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) {
        CardImage("", modifier, contentScale)
        return
    }
    AsyncImage(model = request, contentDescription = null, contentScale = contentScale, modifier = modifier, onError = { failed = true })
}

@Composable
private fun CardName(text: String, focused: Boolean, center: Boolean = false) {
    Text(
        text,
        style = AppType.CardTitle,
        color = if (focused) Palette.OnBackground else Palette.Muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (center) TextAlign.Center else null,
        modifier = if (center) Modifier.fillMaxWidth() else Modifier,
    )
}

@Composable
private fun CardMeta(text: String, center: Boolean = false, color: Color = Palette.TextTertiary) {
    if (text.isBlank()) return
    Text(
        text,
        style = AppType.CardMeta,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (center) TextAlign.Center else null,
        modifier = if (center) Modifier.fillMaxWidth() else Modifier,
    )
}

/** A thin progress bar along the bottom of a picture (how much of a video was watched). */
@Composable
private fun BoxScope.ProgressBar(fraction: Float) {
    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Palette.Track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(Palette.Brand))
    }
}

/** Darkens the bottom of a picture so text can sit on it. */
private val bottomShade = Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color(0x66000000), 1f to Color(0xE6000000))

/**
 * A live stream. On the picture: LIVE, the viewer count and a star for favourites. Underneath: the channel and its
 * category. When [showTitleOnFocus], focusing it also lays the stream title over the picture; pages with a hero
 * (which already shows the title) turn that off.
 */
@Composable
fun StreamCard(
    stream: Stream,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    preview: PreviewController? = null,
    isFavorite: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    showTitleOnFocus: Boolean = true,
) {
    MediaCard(
        onClick = onClick,
        imageModifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        modifier = modifier,
        onFocused = onFocused,
        onLongClick = onLongClick,
        image = { focused ->
            CardImage(stream.thumbnail())
            if (focused && preview != null) PreviewLayer(preview, stream.userLogin)
            val overlay = focused && showTitleOnFocus && stream.title.isNotBlank()
            if (overlay) {
                Box(Modifier.fillMaxSize().background(bottomShade))
                Column(Modifier.align(Alignment.BottomStart).padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stream.title, style = AppType.CardMeta, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val uptime = Format.uptime(stream.startedAt)
                    Text(
                        "${Format.count(stream.viewerCount)} viewers" + (uptime?.let { " · $it" }.orEmpty()),
                        style = AppType.Badge,
                        color = Color(0xFFD0D0D8),
                        maxLines = 1,
                    )
                }
            } else {
                ImageBadge(Format.count(stream.viewerCount), Modifier.align(Alignment.BottomStart).padding(6.dp), dot = Palette.Live)
            }
            LiveBadge(Modifier.align(Alignment.TopStart).padding(6.dp))
            if (isFavorite) FavoriteMark(Modifier.align(Alignment.TopEnd).padding(6.dp))
        },
        text = { focused ->
            CardName(stream.userName, focused)
            CardMeta(stream.gameName)
        },
    )
}

@Composable
private fun FavoriteMark(modifier: Modifier = Modifier) {
    Box(modifier.size(22.dp).background(Palette.ImageBadge, CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Star, contentDescription = "Favorite", tint = Palette.BrandSoft, modifier = Modifier.size(14.dp))
    }
}

/**
 * A channel's round picture, or its initial on a plain circle when there is none (yet). [clipped] is off where the
 * caller already clips it to a circle (a [MediaCard]), so the picture isn't clipped twice.
 */
@Composable
fun Avatar(url: String, name: String, modifier: Modifier = Modifier, clipped: Boolean = true) {
    Box(
        (if (clipped) modifier.clip(CircleShape) else modifier).background(Palette.SurfaceFocused, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isBlank()) {
            Text(name.take(1).uppercase(), style = AppType.TitleL, color = Palette.Muted)
        } else {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** An offline favourite in a row of stream cards: same size as its neighbours, with the channel's picture. */
@Composable
fun OfflineChannelCard(
    name: String,
    avatarUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: (() -> Unit)? = null,
    isFavorite: Boolean = false,
) {
    MediaCard(
        onClick = onClick,
        imageModifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        modifier = modifier,
        onFocused = onFocused,
        image = {
            Box(
                Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Palette.SurfaceFocused, Palette.Surface))),
                contentAlignment = Alignment.Center,
            ) { Avatar(avatarUrl, name, Modifier.size(56.dp)) }
            if (isFavorite) FavoriteMark(Modifier.align(Alignment.TopEnd).padding(6.dp))
        },
        text = { focused ->
            CardName(name, focused)
            CardMeta("Offline")
        },
    )
}

/**
 * A channel as a round picture with its name underneath (offline channels, search results). [live] adds a red ring
 * and a LIVE label; [subtitle] is a second line (the category, or "Offline").
 */
@Composable
fun AvatarTile(
    name: String,
    avatarUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    live: Boolean = false,
    subtitle: String? = null,
    onFocused: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    MediaCard(
        onClick = onClick,
        imageModifier = Modifier.size(Layout.avatar),
        modifier = modifier.width(Layout.avatarTile),
        shape = CircleShape,
        circle = true,
        focusedScale = 1.1f,
        onFocused = onFocused,
        onLongClick = onLongClick,
        horizontalAlignment = Alignment.CenterHorizontally,
        image = {
            Avatar(avatarUrl, name, Modifier.fillMaxSize(), clipped = false)
            if (live) {
                Box(Modifier.fillMaxSize().border(3.dp, Palette.Live, CircleShape))
                LiveBadge(Modifier.align(Alignment.BottomCenter).offset(y = (-2).dp))
            }
        },
        text = { focused ->
            CardName(name, focused, center = true)
            if (subtitle != null) CardMeta(subtitle, center = true, color = if (live) Palette.LiveText else Palette.TextTertiary)
        },
    )
}

/** A category's box art with its name underneath. */
@Composable
fun CategoryPoster(category: Category, onClick: () -> Unit, modifier: Modifier = Modifier, onFocused: (() -> Unit)? = null) {
    MediaCard(
        onClick = onClick,
        imageModifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
        modifier = modifier,
        onFocused = onFocused,
        image = { CardImage(category.boxArt(270, 360)) },
        text = { focused -> CardName(category.name, focused) },
    )
}

/** A past broadcast, highlight or upload. A bar along the bottom shows how much of it was already watched. */
@Composable
fun VideoCard(
    video: Video,
    resumeSeconds: Long?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: (() -> Unit)? = null,
) {
    MediaCard(
        onClick = onClick,
        imageModifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        modifier = modifier,
        onFocused = onFocused,
        image = {
            CardImage(video.thumbnail())
            ImageBadge(Format.clock(video.durationSeconds), Modifier.align(Alignment.BottomEnd).padding(6.dp))
            val duration = video.durationSeconds
            if (resumeSeconds != null && resumeSeconds > 0 && duration > 0) ProgressBar(resumeSeconds.toFloat() / duration)
        },
        text = { focused ->
            CardName(video.title, focused)
            val meta = "${Format.count(video.viewCount)} views · ${Format.ago(video.createdAt)}"
            if (focused && resumeSeconds != null && resumeSeconds > 0) {
                CardMeta("Resume from ${Format.clock(resumeSeconds)}", color = Palette.BrandSoft)
            } else {
                CardMeta(meta)
            }
        },
    )
}

/** A past broadcast the viewer was watching, with how far they got. */
@Composable
fun HistoryCard(
    entry: HistoryEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
) {
    MediaCard(
        onClick = onClick,
        imageModifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        modifier = modifier,
        onFocused = onFocused,
        onLongClick = onLongClick,
        image = {
            CardImage(entry.thumbnailUrl)
            val left = (entry.durationSeconds - entry.positionMs / 1000).coerceAtLeast(0)
            if (entry.durationSeconds > 0) {
                ImageBadge("${Format.clock(left)} left", Modifier.align(Alignment.BottomEnd).padding(start = 6.dp, end = 6.dp, bottom = 10.dp))
                ProgressBar(entry.positionMs / 1000f / entry.durationSeconds)
            }
        },
        text = { focused ->
            CardName(entry.title, focused)
            CardMeta("${entry.channelName} · ${Format.agoMillis(entry.watchedAtMillis)}")
        },
    )
}
