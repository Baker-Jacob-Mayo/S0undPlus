package com.s0undplus.tv.ui.channel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.s0undplus.tv.AppContainer
import com.s0undplus.tv.LocalAppContainer
import com.s0undplus.tv.ui.components.LoadingState
import com.s0undplus.tv.ui.components.MessageState
import com.s0undplus.tv.ui.components.PagedGrid
import com.s0undplus.tv.ui.components.PagedLoader
import com.s0undplus.tv.ui.components.PillButton
import com.s0undplus.tv.ui.components.QrDialog
import com.s0undplus.tv.ui.components.StreamCard
import com.s0undplus.tv.ui.components.VideoCard
import com.s0undplus.tv.ui.components.describe
import com.s0undplus.tv.ui.nav.PlayTarget
import com.s0undplus.tv.ui.nav.toPlayTarget
import com.s0undplus.tv.ui.theme.Palette
import com.s0undplus.tv.ui.util.Format
import com.s0undplus.tv.ui.util.rememberScreenScope
import com.s0undplus.twitch.Stream
import com.s0undplus.twitch.User
import com.s0undplus.twitch.Video
import com.s0undplus.twitch.VideoType
import com.s0undplus.twitch.library.FavoriteChannel
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Top streams in one category. */
@Composable
fun CategoryScreen(id: String, name: String, onOpenStream: (Stream) -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberScreenScope()
    val loader = remember(id) { PagedLoader<Stream>(scope, { it.userId }) { container.helix.streams(gameId = id, after = it) } }
    val gridState = rememberLazyGridState()

    Column(Modifier.fillMaxSize()) {
        Text(
            name,
            style = MaterialTheme.typography.headlineMedium,
            color = Palette.OnBackground,
            modifier = Modifier.padding(horizontal = 48.dp, vertical = 24.dp),
        )
        PagedGrid(
            loader = loader,
            gridState = gridState,
            emptyText = "Nobody is streaming $name right now.",
            itemKey = { it.userId },
            modifier = Modifier.weight(1f),
        ) { stream, modifier -> StreamCard(stream, { onOpenStream(stream) }, modifier) }
    }
}

sealed interface ChannelState {
    data object Loading : ChannelState
    data class Loaded(val user: User, val stream: Stream?) : ChannelState
    data class Failed(val message: String) : ChannelState
}

/** Loads the profile, live status and VOD list of one channel. */
class ChannelController(private val c: AppContainer, private val login: String, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<ChannelState>(ChannelState.Loading)
    val state: StateFlow<ChannelState> = _state.asStateFlow()

    private var userId: String? = null

    /** One list per kind of saved video, each loaded the first time its tab is shown. */
    val videos: Map<VideoType, PagedLoader<Video>> = VideoType.entries.associateWith { type ->
        PagedLoader<Video>(scope, { it.id }) { cursor ->
            c.helix.videos(userId ?: error("channel not loaded"), after = cursor, type = type)
        }
    }

    fun load() {
        _state.value = ChannelState.Loading
        scope.launch {
            try {
                val user = c.helix.userByLogin(login)
                if (user == null) {
                    _state.value = ChannelState.Failed("Channel “$login” doesn't exist.")
                    return@launch
                }
                userId = user.id
                val stream = c.helix.streamByLogin(login)
                _state.value = ChannelState.Loaded(user, stream)
                videos.getValue(VideoType.ARCHIVE).refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ChannelState.Failed(describe(e))
            }
        }
    }
}

@Composable
fun ChannelScreen(login: String, onPlay: (PlayTarget) -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberScreenScope()
    val controller = remember(login) { ChannelController(container, login, scope).also { it.load() } }
    val state by controller.state.collectAsStateWithLifecycle()
    val favorites by container.settings.favorites.collectAsStateWithLifecycle(emptyList())
    val gridStates = VideoType.entries.associateWith { rememberLazyGridState() }
    var videoType by remember { mutableStateOf(VideoType.ARCHIVE) }
    var showFollowQr by remember { mutableStateOf(false) }

    when (val s = state) {
        ChannelState.Loading -> LoadingState()
        is ChannelState.Failed -> MessageState(s.message, actionLabel = "Try again", onAction = controller::load)
        is ChannelState.Loaded -> Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                ChannelHeader(
                    user = s.user,
                    stream = s.stream,
                    isFavorite = favorites.any { it.login.equals(s.user.login, ignoreCase = true) },
                    onWatch = {
                        val target = s.stream?.toPlayTarget() ?: PlayTarget.Live(s.user.login, s.user.displayName, s.user.id)
                        onPlay(target)
                    },
                    onFavorite = {
                        scope.launch { container.settings.toggleFavorite(FavoriteChannel(s.user.login, s.user.displayName, s.user.id)) }
                    },
                    onFollowQr = { showFollowQr = true },
                )
                Row(
                    Modifier.padding(horizontal = 48.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (type in VideoType.entries) {
                        PillButton(type.label, { videoType = type }, selected = videoType == type)
                    }
                }
                PagedGrid(
                    loader = controller.videos.getValue(videoType),
                    gridState = gridStates.getValue(videoType),
                    emptyText = "${s.user.displayName} has no ${videoType.label.lowercase()}.",
                    itemKey = { it.id },
                    modifier = Modifier.weight(1f),
                ) { video, modifier ->
                    val settings = container.settings
                    val resume by produceState(0L, video.id) { value = settings.vodPosition(video.id) / 1000 }
                    VideoCard(
                        video = video,
                        resumeSeconds = resume,
                        onClick = { onPlay(video.toPlayTarget()) },
                        modifier = modifier,
                    )
                }
            }
            if (showFollowQr) {
                QrDialog(
                    title = "Follow ${s.user.displayName}",
                    subtitle = "Twitch doesn't let third-party apps follow channels. Scan to open the channel on your phone, " +
                        "then tap Follow — it will show up in your Following tab.",
                    url = "https://www.twitch.tv/${s.user.login}",
                    onDismiss = { showFollowQr = false },
                )
            }
        }
    }
}

@Composable
private fun ChannelHeader(
    user: User,
    stream: Stream?,
    isFavorite: Boolean,
    onWatch: () -> Unit,
    onFavorite: () -> Unit,
    onFollowQr: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = user.profileImageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(96.dp).clip(CircleShape).background(Palette.SurfaceFocused),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(user.displayName, style = MaterialTheme.typography.headlineMedium, color = Palette.OnBackground)
            Text(
                if (stream != null) {
                    "LIVE · ${stream.gameName} · ${Format.count(stream.viewerCount)} viewers"
                } else {
                    "Offline"
                },
                style = MaterialTheme.typography.titleSmall,
                color = if (stream != null) Palette.Live else Palette.Muted,
            )
            if (user.description.isNotBlank()) {
                Text(
                    user.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (stream != null) PillButton("Watch live", onWatch)
                PillButton(if (isFavorite) "★ Favorite" else "☆ Favorite", onFavorite, selected = isFavorite)
            }
            PillButton("Follow on Twitch (QR)", onFollowQr)
        }
    }
}
