package com.s0undplus.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.s0undplus.tv.ui.theme.Palette
import com.s0undplus.tv.ui.util.Format
import com.s0undplus.twitch.Category
import com.s0undplus.twitch.ChannelResult
import com.s0undplus.twitch.Stream
import com.s0undplus.twitch.Video

@Composable
private fun Chip(text: String, background: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun CardText(primary: String, secondary: String?, tertiary: String? = null) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            primary,
            style = MaterialTheme.typography.titleSmall,
            color = Palette.OnBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!secondary.isNullOrBlank()) {
            Text(
                secondary,
                style = MaterialTheme.typography.bodySmall,
                color = Palette.OnBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!tertiary.isNullOrBlank()) {
            Text(
                tertiary,
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun StreamCard(stream: Stream, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface(onClick, modifier) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                AsyncImage(
                    model = stream.thumbnail(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Chip("LIVE", Palette.Live, Modifier.align(Alignment.TopStart).padding(8.dp))
                Row(Modifier.align(Alignment.BottomStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("${Format.count(stream.viewerCount)} viewers", Color(0xCC000000))
                    Format.uptime(stream.startedAt)?.let { Chip(it, Color(0xCC000000)) }
                }
            }
            CardText(stream.userName, stream.title, stream.gameName)
        }
    }
}

@Composable
fun CategoryCard(category: Category, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface(onClick, modifier) {
        Column {
            AsyncImage(
                model = category.boxArt(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
            )
            CardText(category.name, null)
        }
    }
}

@Composable
fun ChannelCard(channel: ChannelResult, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface(onClick, modifier) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AsyncImage(
                model = channel.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp).clip(CircleShape).background(Palette.SurfaceFocused),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    channel.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = Palette.OnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (channel.isLive) "LIVE · ${channel.gameName}" else "Offline",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (channel.isLive) Palette.Live else Palette.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun VideoCard(video: Video, resumeSeconds: Long?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface(onClick, modifier) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                AsyncImage(
                    model = video.thumbnail(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Chip(Format.clock(video.durationSeconds), Color(0xCC000000), Modifier.align(Alignment.BottomEnd).padding(8.dp))
                if (resumeSeconds != null && resumeSeconds > 0) {
                    Chip("Resume ${Format.clock(resumeSeconds)}", Palette.Brand, Modifier.align(Alignment.TopStart).padding(8.dp))
                }
            }
            CardText(video.title, "${Format.count(video.viewCount)} views", Format.ago(video.createdAt))
        }
    }
}
