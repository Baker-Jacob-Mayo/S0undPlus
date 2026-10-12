package com.s0undplus.tv.ui.channel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.s0undplus.tv.AppContainer
import com.s0undplus.tv.LocalAppContainer
import com.s0undplus.tv.ui.components.Avatar
import com.s0undplus.tv.ui.components.CardImage
import com.s0undplus.tv.ui.components.KeylineScrolling
import com.s0undplus.tv.ui.components.LiveBadge
import com.s0undplus.tv.ui.components.LoadingState
import com.s0undplus.tv.ui.components.MediaRow
import com.s0undplus.tv.ui.components.MessageState
import com.s0undplus.tv.ui.components.PagedGrid
import com.s0undplus.tv.ui.components.PagedLoader
import com.s0undplus.tv.ui.components.PillButton
import com.s0undplus.tv.ui.components.QrDialog
import com.s0undplus.tv.ui.components.SkeletonBlock
import com.s0undplus.tv.ui.components.StreamCard
import com.s0undplus.tv.ui.components.VideoCard
import com.s0undplus.tv.ui.components.describe
import com.s0undplus.tv.ui.components.focusNotifier
import com.s0undplus.tv.ui.home.PageHeader
import com.s0undplus.tv.ui.nav.PlayTarget
import com.s0undplus.tv.ui.nav.toPlayTarget
import com.s0undplus.tv.ui.theme.AppType
import com.s0undplus.tv.ui.theme.Layout
import com.s0undplus.tv.ui.theme.Palette
import com.s0undplus.tv.ui.theme.Radius
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
 * A category's list, scroll position and focused card, scoped to its place on the back stack, so coming back from a
 * stream finds the page as it was, focus on the card that was opened, instead of reloading it.
 */
class CategoryViewModel(c: AppContainer, id: String) : ViewModel() {
    val loader = PagedLoader<Stream>(viewModelScope, { it.userId }) { c.helix.streams(gameId = id, after = it) }
    val gridState = LazyGridState()
    var focusedKey: Any? = null
}

/** Top streams in one category: its box art and name, then a grid of who is streaming it. */
@Composable
fun CategoryScreen(id: String, name: String, boxArtUrl: String, onOpenStream: (Stream) -> Unit, onOpenChannel: (String) -> Unit) {
    val container = LocalAppContainer.current
    val vm = viewModel(key = "category-$id") { CategoryViewModel(container, id) }
    val entry = remember { FocusRequester() }
    PagedGrid(
        loader = vm.loader,
        gridState = vm.gridState,
        emptyText = "Nobody is streaming $name right now.",
        itemKey = { it.userId },
        restoreFocusKey = vm.focusedKey,
        entryRequester = entry,
        // This page has no navigation to sit on: a card always has focus, the remembered one when coming back.
        focusOnReady = { true },
        onItemFocused = { vm.focusedKey = it },
        keyline = 190.dp,
        header = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                if (boxArtUrl.isNotBlank()) {
                    CardImage(
                        boxArtUrl,
                        Modifier.width(72.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(Radius.card)),
                    )
                }
                PageHeader(name, "Live channels, most viewers first  ·  Hold OK on a stream for its channel", Modifier.weight(1f))
            }
        },
    ) { stream, modifier ->
        StreamCard(stream, { onOpenStream(stream) }, modifier, onLongClick = { onOpenChannel(stream.userLogin) })
    }
}

sealed interface ChannelState {
    data object Loading : ChannelState
    data class Loaded(val user: User, val stream: Stream?) : ChannelState
    data class Failed(val message: String) : ChannelState
}

/** Loads the profile, live status and VOD lists of one channel. */
class ChannelController(private val c: AppContainer, private val login: String, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<ChannelState>(ChannelState.Loading)
    val state: StateFlow<ChannelState> = _state.asStateFlow()

    private var userId: String? = null
    private var loadedAtMs = 0L

    /** One list per kind of saved video, each loaded the first time its shelf is shown. */
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
 * One channel's page state: its data, the page and shelf scroll positions and the focused video, scoped to its place
 * on the back stack, so coming back from a video finds the page as it was, focus on the video that was played.
 */
class ChannelViewModel(c: AppContainer, login: String) : ViewModel() {
    val controller = ChannelController(c, login, viewModelScope).also { it.load() }
    val listState = LazyListState()
    val shelfStates: Map<VideoType, LazyListState> = VideoType.entries.associateWith { LazyListState() }
    var focusedKey: String? = null
}

@Composable
fun ChannelScreen(login: String, onPlay: (PlayTarget) -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberScreenScope()
    val vm = viewModel(key = "channel-${login.lowercase()}") { ChannelViewModel(container, login) }
    val controller = vm.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val favorites by container.settings.favorites.collectAsStateWithLifecycle(emptyList())
    var showFollowQr by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    val restoreFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { controller.retryOrRefresh() }
    // A fresh visit starts on "Watch live" (or Favorite when offline); coming back, on the video that was played.
    val loaded = state is ChannelState.Loaded
    LaunchedEffect(loaded) {
        if (!loaded) return@LaunchedEffect
        withFrameNanos { }
        val restored = vm.focusedKey != null && try {
            restoreFocus.requestFocus()
            true
        } catch (_: IllegalStateException) {
            false
        }
        if (!restored) {
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
            val isFavorite = favorites.any { it.login.equals(s.user.login, ignoreCase = true) }
            KeylineScrolling(SHELF_KEYLINE) {
                LazyColumn(
                    state = vm.listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 200.dp),
                    verticalArrangement = Arrangement.spacedBy(Layout.rowGap),
                ) {
                    item(key = "header", contentType = "header") {
                      // The picture sits behind the header only, so it scrolls away with it instead of staying behind the shelves.
                      Box {
                        Box(Modifier.matchParentSize()) {
                            ChannelBackdrop(s.user, s.stream, Modifier.align(Alignment.TopEnd).width(640.dp).fillMaxHeight())
                        }
                        ChannelHeader(
                            user = s.user,
                            stream = s.stream,
                            isFavorite = isFavorite,
                            onWatch = {
                                val target = s.stream?.toPlayTarget() ?: PlayTarget.Live(s.user.login, s.user.displayName, s.user.id)
                                onPlay(target)
                            },
                            onFavorite = {
                                scope.launch { container.settings.toggleFavorite(FavoriteChannel(s.user.login, s.user.displayName, s.user.id)) }
                            },
                            onFollowQr = { showFollowQr = true },
                            firstFocus = firstFocus,
                        )
                      }
                    }
                    for (type in VideoType.entries) {
                        item(key = "shelf-${type.name}", contentType = "shelf") {
                            Shelf(
                                type = type,
                                loader = controller.videos.getValue(type),
                                state = vm.shelfStates.getValue(type),
                                restoreKey = vm.focusedKey,
                                restoreFocus = restoreFocus,
                                onFocused = { vm.focusedKey = it },
                                onPlay = onPlay,
                            )
                        }
                    }
                    item(key = "none", contentType = "notice") {
                        val counts = VideoType.entries.map { controller.videos.getValue(it).state.collectAsStateWithLifecycle().value }
                        if (counts.all { it.loadedAtMillis > 0 && it.items.isEmpty() && it.error == null }) {
                            Text(
                                "${s.user.displayName} has no videos yet.",
                                style = AppType.BodyM,
                                color = Palette.TextTertiary,
                                modifier = Modifier.padding(start = Layout.keyline),
                            )
                        }
                    }
                }
            }
            if (showFollowQr) {
                QrDialog(
                    title = "Follow ${s.user.displayName}",
                    subtitle = "Twitch doesn't let third-party apps follow channels. Scan to open the channel on your phone, " +
                        "then tap Follow — it will show up in your Following page.",
                    url = "https://www.twitch.tv/${s.user.login}",
                    onDismiss = { showFollowQr = false },
                )
            }
        }
    }
}

/**
 * The picture behind the channel's header: the live stream, Twitch's offline picture for the channel, or a soft wash
 * in the theme's colour, faded into the page on the left and at the bottom.
 */
@Composable
private fun ChannelBackdrop(user: User, stream: Stream?, modifier: Modifier) {
    // Its own cached layer (see HeroBackdrop): redrawn when the picture changes, not on every frame of scrolling.
    Box(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val image = stream?.thumbnail(1280, 720) ?: user.offlineImageUrl.takeIf { it.isNotBlank() }
        if (image != null) {
            AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Palette.Brand.copy(alpha = 0.3f), Color.Transparent))))
        }
        val bg = Palette.Background
        Box(
            Modifier.fillMaxSize().drawWithCache {
                val left = Brush.horizontalGradient(0f to bg, 0.2f to bg.copy(alpha = 0.85f), 0.5f to bg.copy(alpha = 0.3f), 0.75f to Color.Transparent)
                val bottom = Brush.verticalGradient(0.4f to Color.Transparent, 0.8f to bg.copy(alpha = 0.85f), 1f to bg)
                val dim = bg.copy(alpha = 0.4f)
                onDrawBehind {
                    drawRect(dim)
                    drawRect(left)
                    drawRect(bottom)
                }
            },
        )
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
    firstFocus: FocusRequester,
) {
    Column(
        Modifier.fillMaxWidth().padding(start = Layout.keyline, top = 48.dp, end = Layout.endMargin),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(contentAlignment = Alignment.BottomCenter) {
                Avatar(
                    user.profileImageUrl,
                    user.displayName,
                    Modifier
                        .size(80.dp)
                        .let { if (stream != null) it.border(3.dp, Palette.Live, CircleShape) else it },
                )
                if (stream != null) LiveBadge()
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(user.displayName, style = AppType.Display, color = Palette.OnBackground, maxLines = 1)
                Text(
                    if (stream != null) {
                        listOfNotNull(
                            stream.gameName.ifBlank { null },
                            "${Format.count(stream.viewerCount)} viewers",
                            Format.uptime(stream.startedAt),
                        ).joinToString(" · ")
                    } else {
                        "Offline"
                    },
                    style = AppType.BodyM,
                    color = if (stream != null) Palette.OnBackground else Palette.TextTertiary,
                )
            }
        }
        val text = stream?.title?.takeIf { it.isNotBlank() } ?: user.description
        if (text.isNotBlank()) {
            Text(
                text,
                style = AppType.BodyL,
                color = if (stream != null) Palette.OnBackground else Palette.Muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 520.dp),
            )
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (stream != null) PillButton("Watch live", onWatch, Modifier.focusRequester(firstFocus), icon = Icons.Rounded.PlayArrow)
            PillButton(
                if (isFavorite) "Favorite" else "Add to favorites",
                onFavorite,
                if (stream == null) Modifier.focusRequester(firstFocus) else Modifier,
                selected = isFavorite,
                icon = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            )
            PillButton("Follow on Twitch", onFollowQr, icon = Icons.Rounded.QrCode2)
        }
    }
}

/**
 * One kind of saved video as a row: loads the first time it is shown, loads more near its end, shows placeholders
 * while loading, and disappears when the channel has none of this kind.
 */
@Composable
private fun Shelf(
    type: VideoType,
    loader: PagedLoader<Video>,
    state: LazyListState,
    restoreKey: String?,
    restoreFocus: FocusRequester,
    onFocused: (String) -> Unit,
    onPlay: (PlayTarget) -> Unit,
) {
    val container = LocalAppContainer.current
    val page by loader.state.collectAsStateWithLifecycle()
    LaunchedEffect(loader) { loader.ensureFresh(SHELF_FRESH_MS) }
    when {
        page.items.isNotEmpty() -> MediaRow(type.label, state, onNearEnd = loader::loadMore) {
            items(page.items, key = { it.id }, contentType = { "video" }) { video ->
                val key = "${type.name}:${video.id}"
                val resume by produceState(0L, video.id) { value = container.settings.vodPosition(video.id) / 1000 }
                VideoCard(
                    video = video,
                    resumeSeconds = resume,
                    onClick = { onPlay(video.toPlayTarget()) },
                    modifier = Modifier
                        .width(Layout.cardWidth)
                        .let { if (key == restoreKey) it.focusRequester(restoreFocus) else it }
                        .focusNotifier { onFocused(key) },
                )
            }
        }
        page.loading || page.loadedAtMillis == 0L && page.error == null -> Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(type.label, style = AppType.RowTitle, color = Palette.OnBackground, modifier = Modifier.padding(start = Layout.keyline))
            Row(Modifier.padding(start = Layout.keyline), horizontalArrangement = Arrangement.spacedBy(Layout.cardGap)) {
                repeat(4) { SkeletonBlock(Modifier.width(Layout.cardWidth).aspectRatio(16f / 9f)) }
            }
        }
        page.error != null -> Row(
            Modifier.padding(start = Layout.keyline),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Couldn't load ${type.label.lowercase()}: ${page.error}", style = AppType.BodyM, color = Palette.Muted)
            PillButton("Try again", loader::refresh)
        }
        else -> Unit // none of this kind: no shelf
    }
}

/**
 * Where a focused shelf's videos sit, measured from the top of the page: where the first shelf rests under the header.
 * So the first shelf never scrolls the header away, later shelves take its place, and going back up to the buttons
 * returns the page to its top.
 */
private val SHELF_KEYLINE = 300.dp
private const val SHELF_FRESH_MS = 5 * 60_000L
