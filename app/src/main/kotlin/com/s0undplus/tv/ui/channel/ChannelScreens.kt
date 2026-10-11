package com.s0undplus.tv.ui.channel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModel
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.grid.LazyGridState
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

/**
 * A category's list, scroll position and focused card. Kept in an Activity-scoped ViewModel (like Home's) so that
 * coming back from a stream finds the page as it was, focus on the card that was opened, instead of reloading it.
 */
class CategoryViewModel(c: AppContainer, id: String) : ViewModel() {
    val loader = PagedLoader<Stream>(viewModelScope, { it.userId }) { c.helix.streams(gameId = id, after = it) }
    val gridState = LazyGridState()
    var focusedKey: Any? = null
}

/** Top streams in one category. */
@Composable
fun CategoryScreen(id: String, name: String, onOpenStream: (Stream) -> Unit) {
    val container = LocalAppContainer.current
    val vm = viewModel(key = "category-$id") { CategoryViewModel(container, id) }

    Column(Modifier.fillMaxSize()) {
        Text(
            name,
            style = MaterialTheme.typography.headlineMedium,
            color = Palette.OnBackground,
            modifier = Modifier.padding(horizontal = 48.dp, vertical = 24.dp),
        )
        PagedGrid(
            loader = vm.loader,
            gridState = vm.gridState,
            emptyText = "Nobody is streaming $name right now.",
            itemKey = { it.userId },
            modifier = Modifier.weight(1f),
            restoreFocusKey = vm.focusedKey,
            onItemFocused = { vm.focusedKey = it },
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
    private var loadedAtMs = 0L

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
                loadedAtMs = System.currentTimeMillis()
                videos.getValue(VideoType.ARCHIVE).refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ChannelState.Failed(describe(e))
            }
        }
    }

    /** On coming back to the page: try again if it failed, otherwise re-check the live status if it is old. */
    fun retryOrRefresh() {
        if (_state.value is ChannelState.Failed) load() else refreshIfStale()
    }

    /** Re-checks whether the channel is live when the page was loaded a while ago, without blanking the page. */
    private fun refreshIfStale() {
        val loaded = _state.value as? ChannelState.Loaded ?: return
        if (System.currentTimeMillis() - loadedAtMs < STALE_MS) return
        loadedAtMs = System.currentTimeMillis()
        scope.launch {
            try {
                _state.value = loaded.copy(stream = c.helix.streamByLogin(login))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // keep what is shown
            }
        }
    }

    private companion object {
        const val STALE_MS = 60_000L
    }
}

/**
 * One channel's page state: its data, the chosen kind of video, scroll positions and the focused card. Activity-scoped
 * (like Home's), so coming back from a video finds the page as it was, focus on the video that was played.
 */
class ChannelViewModel(c: AppContainer, login: String) : ViewModel() {
    val controller = ChannelController(c, login, viewModelScope).also { it.load() }
    var videoType by mutableStateOf(VideoType.ARCHIVE)
    val gridStates: Map<VideoType, LazyGridState> = VideoType.entries.associateWith { LazyGridState() }
    val focusedKeys = mutableMapOf<VideoType, Any>()
}

@Composable
fun ChannelScreen(login: String, onPlay: (PlayTarget) -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberScreenScope()
    val vm = viewModel(key = "channel-${login.lowercase()}") { ChannelViewModel(container, login) }
    val controller = vm.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val favorites by container.settings.favorites.collectAsStateWithLifecycle(emptyList())
    val videoType = vm.videoType
    var showFollowQr by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { controller.retryOrRefresh() }
    // A fresh visit starts on "Watch live" (or the video tabs when offline); coming back, the grid restores the card.
    val loaded = state is ChannelState.Loaded
    LaunchedEffect(loaded) {
        if (loaded && vm.focusedKeys[vm.videoType] == null) {
            try {
                firstFocus.requestFocus()
            } catch (_: IllegalStateException) {
                // not attached yet; the first D-pad press still lands on the page
            }
        }
    }

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
                    watchFocus = firstFocus,
                )
                Row(
                    Modifier.padding(horizontal = 48.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (type in VideoType.entries) {
                        PillButton(
                            type.label,
                            {
                                // Switching kind stays on the pill: the grid only restores focus when coming back.
                                vm.focusedKeys.remove(type)
                                vm.videoType = type
                            },
                            // Offline channels start here (there is no "Watch live" to start on).
                            modifier = if (s.stream == null && type == videoType) Modifier.focusRequester(firstFocus) else Modifier,
                            selected = videoType == type,
                        )
                    }
                }
                PagedGrid(
                    loader = controller.videos.getValue(videoType),
                    gridState = vm.gridStates.getValue(videoType),
                    emptyText = "${s.user.displayName} has no ${videoType.label.lowercase()}.",
                    itemKey = { it.id },
                    modifier = Modifier.weight(1f),
                    restoreFocusKey = vm.focusedKeys[videoType],
                    onItemFocused = { vm.focusedKeys[videoType] = it },
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
    watchFocus: FocusRequester,
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
                if (stream != null) PillButton("Watch live", onWatch, Modifier.focusRequester(watchFocus))
                PillButton(if (isFavorite) "★ Favorite" else "☆ Favorite", onFavorite, selected = isFavorite)
            }
            PillButton("Follow on Twitch (QR)", onFollowQr)
        }
    }
}
