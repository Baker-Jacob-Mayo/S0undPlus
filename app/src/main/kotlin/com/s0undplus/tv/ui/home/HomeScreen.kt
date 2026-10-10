package com.s0undplus.tv.ui.home

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.s0undplus.tv.AppContainer
import com.s0undplus.tv.LocalAppContainer
import com.s0undplus.tv.data.AppSettings
import com.s0undplus.tv.ui.components.CategoryCard
import com.s0undplus.tv.ui.components.ChannelCard
import com.s0undplus.tv.ui.components.ClockText
import com.s0undplus.tv.ui.components.FocusRestorer
import com.s0undplus.tv.ui.components.HistoryCard
import com.s0undplus.tv.ui.components.LoadMoreEffect
import com.s0undplus.tv.ui.components.LoadingState
import com.s0undplus.tv.ui.components.MessageState
import com.s0undplus.tv.ui.components.PagedGrid
import com.s0undplus.tv.ui.components.PagedLoader
import com.s0undplus.tv.ui.components.PillButton
import com.s0undplus.tv.ui.components.PreviewController
import com.s0undplus.tv.ui.components.StreamCard
import com.s0undplus.tv.ui.components.TvTextField
import com.s0undplus.tv.ui.components.describe
import com.s0undplus.tv.ui.components.focusNotifier
import com.s0undplus.tv.ui.nav.PlayTarget
import com.s0undplus.tv.ui.settings.SettingsContent
import com.s0undplus.tv.ui.theme.Palette
import com.s0undplus.tv.ui.update.UpdateBanner
import com.s0undplus.tv.ui.update.UpdateViewModel
import com.s0undplus.tv.ui.util.rememberScreenScope
import com.s0undplus.twitch.Category
import com.s0undplus.twitch.ChannelResult
import com.s0undplus.twitch.FollowedChannel
import com.s0undplus.twitch.Stream
import com.s0undplus.twitch.TwitchLanguages
import com.s0undplus.twitch.library.FavoriteChannel
import com.s0undplus.twitch.library.HistoryEntry
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class HomeTab(val label: String) {
    Following("Following"),
    Live("Live"),
    Language("Language"),
    Categories("Categories"),
    History("History"),
    Search("Search"),
    Settings("Settings"),
}

sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Results(val channels: List<ChannelResult>, val categories: List<Category>) : SearchState
    data class Failed(val message: String) : SearchState
}

/**
 * Home lives in an Activity-scoped ViewModel so everything the viewer did here — the selected tab, loaded pages,
 * scroll positions and the card they last focused — is still there after watching a stream and pressing Back.
 */
class HomeViewModel(private val c: AppContainer) : ViewModel() {
    var tab by mutableStateOf(HomeTab.Following)

    val following = PagedLoader<Stream>(viewModelScope, { it.userId }) { c.helix.followedStreams(after = it) }
    val offline = PagedLoader<FollowedChannel>(viewModelScope, { it.login }) { c.helix.followedChannels(after = it) }
    val live = PagedLoader<Stream>(viewModelScope, { it.userId }) { c.helix.streams(after = it) }
    val language = PagedLoader<Stream>(viewModelScope, { it.userId }) { cursor ->
        c.helix.streams(language = c.settings.settings.map { it.language }.first(), after = cursor)
    }
    val categories = PagedLoader<Category>(viewModelScope, { it.id }) { c.helix.topCategories(after = it) }

    val gridStates: Map<HomeTab, LazyGridState> = HomeTab.entries.associateWith { LazyGridState() }
    val focusedKeys = mutableMapOf<HomeTab, Any>()

    val favorites: StateFlow<List<FavoriteChannel>> =
        c.settings.favorites.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val history: StateFlow<List<HistoryEntry>> =
        c.settings.history.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _favoriteStreams = MutableStateFlow<Map<String, Stream>>(emptyMap())

    /** Favorites that are live right now, by lowercase login. */
    val favoriteStreams: StateFlow<Map<String, Stream>> = _favoriteStreams.asStateFlow()

    var query by mutableStateOf("")
        private set
    private val _search = MutableStateFlow<SearchState>(SearchState.Idle)
    val search: StateFlow<SearchState> = _search.asStateFlow()
    private var searchJob: Job? = null

    init {
        viewModelScope.launch { favorites.collect { refreshFavoriteStatus(it) } }
        // Changing the language in Settings reloads the Language row.
        viewModelScope.launch {
            c.settings.settings.map { it.language }.distinctUntilChanged().drop(1).collect { language.refresh() }
        }
    }

    fun refreshFollowing() {
        following.ensureFresh(FRESH_MS)
        offline.ensureFresh(OFFLINE_FRESH_MS)
        refreshFavoriteStatus(favorites.value)
    }

    private fun refreshFavoriteStatus(list: List<FavoriteChannel>) {
        viewModelScope.launch {
            if (list.isEmpty()) {
                _favoriteStreams.value = emptyMap()
                return@launch
            }
            try {
                _favoriteStreams.value = c.helix.streamsByLogins(list.map { it.login }).associateBy { it.userLogin.lowercase() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // keep the previous status; favorites still show, just possibly as offline
            }
        }
    }

    fun removeHistory(videoId: String) {
        viewModelScope.launch { c.settings.removeHistory(videoId) }
    }

    fun onQueryChange(value: String) {
        query = value
        searchJob?.cancel()
        val q = value.trim()
        if (q.length < 2) {
            _search.value = SearchState.Idle
            return
        }
        _search.value = SearchState.Loading
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            try {
                val channels = c.helix.searchChannels(q).items
                val categories = c.helix.searchCategories(q).items
                _search.value = SearchState.Results(channels, categories)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _search.value = SearchState.Failed(describe(e))
            }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 400L
        const val FRESH_MS = 60_000L
        const val OFFLINE_FRESH_MS = 5 * 60_000L
    }
}

@OptIn(ExperimentalComposeUiApi::class) // FocusProperties.enter: redirects focus entering the tab row to the active tab
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    updates: UpdateViewModel,
    userLogin: String,
    onOpenStream: (Stream) -> Unit,
    onOpenCategory: (Category) -> Unit,
    onOpenChannel: (String) -> Unit,
    onPlay: (PlayTarget) -> Unit,
) {
    val container = LocalAppContainer.current
    val settings by container.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val preview = if (settings.livePreviews) rememberPreviewController() else null
    val tabRequesters = remember { HomeTab.entries.associateWith { FocusRequester() } }
    LaunchedEffect(Unit) {
        // Fresh start: park focus on the active tab. Returning from a stream: the grid restores the card instead.
        if (vm.focusedKeys[vm.tab] == null) {
            try {
                tabRequesters.getValue(vm.tab).requestFocus()
            } catch (_: IllegalStateException) {
                // not attached yet; the first D-pad press will focus something sensible
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        UpdateBanner(updates)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("S0und+", style = MaterialTheme.typography.headlineSmall, color = Palette.Brand, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Row(
                Modifier
                    .weight(1f)
                    // Tabs select on focus, so pressing Up from a card must land on the *active* tab. Without this,
                    // focus goes to whichever pill is geometrically above that card and silently swaps the content.
                    .focusProperties { enter = { tabRequesters.getValue(vm.tab) } }
                    .focusGroup()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (tab in HomeTab.entries) {
                    PillButton(
                        label = tab.label,
                        onClick = { vm.tab = tab },
                        selected = tab == vm.tab,
                        onFocused = { vm.tab = tab },
                        modifier = Modifier.focusRequester(tabRequesters.getValue(tab)),
                    )
                }
            }
            if (settings.showClock) ClockText()
            Text(userLogin, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
        }

        when (vm.tab) {
            HomeTab.Following -> FollowingContent(vm, preview, onOpenStream, onOpenChannel)
            HomeTab.Live -> StreamGrid(vm, HomeTab.Live, vm.live, "No live streams found.", preview, onOpenStream, onOpenChannel)
            HomeTab.Language -> Column(Modifier.fillMaxSize()) {
                Text(
                    "Streams in ${TwitchLanguages.name(settings.language)}  ·  change the language in Settings",
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.Muted,
                    modifier = Modifier.padding(horizontal = 48.dp),
                )
                Box(Modifier.weight(1f)) {
                    StreamGrid(vm, HomeTab.Language, vm.language, "Nobody is streaming in this language right now.", preview, onOpenStream, onOpenChannel)
                }
            }
            HomeTab.Categories -> PagedGrid(
                loader = vm.categories,
                gridState = vm.gridStates.getValue(HomeTab.Categories),
                emptyText = "No categories found.",
                itemKey = { it.id },
                minCellWidth = 180.dp,
                restoreFocusKey = vm.focusedKeys[HomeTab.Categories],
                onItemFocused = { vm.focusedKeys[HomeTab.Categories] = it },
            ) { category, modifier -> CategoryCard(category, { onOpenCategory(category) }, modifier) }
            HomeTab.History -> HistoryContent(vm, onPlay)
            HomeTab.Search -> SearchContent(vm, onOpenCategory, onOpenChannel)
            HomeTab.Settings -> SettingsContent(updates)
        }
    }
}

@Composable
private fun rememberPreviewController(): PreviewController {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberScreenScope()
    val controller = remember { PreviewController(container, context, scope) }
    DisposableEffect(controller) { onDispose { controller.release() } }
    return controller
}

@Composable
private fun StreamGrid(
    vm: HomeViewModel,
    tab: HomeTab,
    loader: PagedLoader<Stream>,
    emptyText: String,
    preview: PreviewController?,
    onOpenStream: (Stream) -> Unit,
    onOpenChannel: (String) -> Unit,
) {
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    PagedGrid(
        loader = loader,
        gridState = vm.gridStates.getValue(tab),
        emptyText = emptyText,
        itemKey = { it.userId },
        restoreFocusKey = vm.focusedKeys[tab],
        onItemFocused = { vm.focusedKeys[tab] = it },
    ) { stream, modifier ->
        StreamCard(
            stream = stream,
            onClick = { onOpenStream(stream) },
            modifier = modifier,
            preview = preview,
            isFavorite = favorites.any { it.login.equals(stream.userLogin, ignoreCase = true) },
            onLongClick = { onOpenChannel(stream.userLogin) },
        )
    }
}

/** Followed channels in three sections: your favorites first, then who is live, then who is offline. */
@Composable
private fun FollowingContent(
    vm: HomeViewModel,
    preview: PreviewController?,
    onOpenStream: (Stream) -> Unit,
    onOpenChannel: (String) -> Unit,
) {
    val live by vm.following.state.collectAsStateWithLifecycle()
    val offline by vm.offline.state.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val favoriteLive by vm.favoriteStreams.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshFollowing() }

    val gridState = vm.gridStates.getValue(HomeTab.Following)
    val restorer = remember { FocusRestorer(vm.focusedKeys[HomeTab.Following]) }
    LoadMoreEffect(gridState, enabled = live.nextCursor != null || offline.nextCursor != null) {
        vm.following.loadMore()
        vm.offline.loadMore()
    }

    val liveLogins = live.items.map { it.userLogin.lowercase() }.toSet() + favoriteLive.keys
    val offlineItems = offline.items.filter { it.login.lowercase() !in liveLogins }

    if (live.items.isEmpty() && favorites.isEmpty() && offlineItems.isEmpty()) {
        when {
            live.loading || offline.loading || (live.loadedAtMillis == 0L && live.error == null) -> LoadingState()
            live.error != null -> MessageState(live.error.orEmpty(), actionLabel = "Try again", onAction = vm::refreshFollowing)
            else -> MessageState("You aren't following anyone yet. Follow channels on twitch.tv and they'll appear here.")
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(260.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (favorites.isNotEmpty()) {
            item(key = "h-fav", span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Favorites") }
            items(favorites, key = { "fav:${it.login}" }) { fav ->
                val itemKey = "fav:${fav.login}"
                val stream = favoriteLive[fav.login.lowercase()]
                val itemModifier = Modifier
                    .fillMaxWidth()
                    .then(restorer.forItem(itemKey))
                    .focusNotifier { vm.focusedKeys[HomeTab.Following] = itemKey }
                if (stream != null) {
                    StreamCard(stream, { onOpenStream(stream) }, itemModifier, preview, isFavorite = true, onLongClick = { onOpenChannel(fav.login) })
                } else {
                    ChannelCard(
                        ChannelResult(id = fav.userId.orEmpty(), login = fav.login, displayName = fav.displayName),
                        onClick = { onOpenChannel(fav.login) },
                        modifier = itemModifier,
                    )
                }
            }
        }
        if (live.error != null && live.items.isEmpty()) {
            // Favorites are local, so they show even when Twitch could not be asked who is live: say so, don't hide it.
            item(key = "live-error", span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Couldn't load who is live: ${live.error.orEmpty()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.Muted,
                    )
                    PillButton("Try again", vm::refreshFollowing)
                }
            }
        }
        if (live.items.isNotEmpty()) {
            item(key = "h-live", span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Live now") }
            items(live.items, key = { "live:${it.userId}" }) { stream ->
                val itemKey = "live:${stream.userId}"
                StreamCard(
                    stream = stream,
                    onClick = { onOpenStream(stream) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(restorer.forItem(itemKey))
                        .focusNotifier { vm.focusedKeys[HomeTab.Following] = itemKey },
                    preview = preview,
                    isFavorite = favorites.any { it.login.equals(stream.userLogin, ignoreCase = true) },
                    onLongClick = { onOpenChannel(stream.userLogin) },
                )
            }
        }
        if (offlineItems.isNotEmpty()) {
            item(key = "h-off", span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Offline") }
            items(offlineItems, key = { "off:${it.login}" }) { channel ->
                val itemKey = "off:${channel.login}"
                ChannelCard(
                    ChannelResult(id = channel.id, login = channel.login, displayName = channel.displayName),
                    onClick = { onOpenChannel(channel.login) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(restorer.forItem(itemKey))
                        .focusNotifier { vm.focusedKeys[HomeTab.Following] = itemKey },
                )
            }
        }
    }
}

@Composable
private fun HistoryContent(vm: HomeViewModel, onPlay: (PlayTarget) -> Unit) {
    val history by vm.history.collectAsStateWithLifecycle()
    if (history.isEmpty()) {
        MessageState("Past broadcasts you watch show up here so you can pick up where you left off.")
        return
    }
    val gridState = vm.gridStates.getValue(HomeTab.History)
    val restorer = remember { FocusRestorer(vm.focusedKeys[HomeTab.History]) }
    Column(Modifier.fillMaxSize()) {
        Text(
            "Long-press a video to remove it from history",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.Muted,
            modifier = Modifier.padding(horizontal = 48.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(260.dp),
            state = gridState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(history, key = { it.videoId }) { entry ->
                HistoryCard(
                    entry = entry,
                    onClick = {
                        onPlay(
                            PlayTarget.Vod(
                                id = entry.videoId,
                                title = entry.title,
                                channelName = entry.channelName,
                                channelLogin = entry.channelLogin,
                                channelUserId = null,
                                durationSeconds = entry.durationSeconds,
                                thumbnailUrl = entry.thumbnailUrl,
                            ),
                        )
                    },
                    onLongClick = { vm.removeHistory(entry.videoId) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(restorer.forItem(entry.videoId))
                        .focusNotifier { vm.focusedKeys[HomeTab.History] = entry.videoId },
                )
            }
        }
    }
}

@Composable
private fun SearchContent(vm: HomeViewModel, onOpenCategory: (Category) -> Unit, onOpenChannel: (String) -> Unit) {
    val state by vm.search.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TvTextField(
            value = vm.query,
            onValueChange = vm::onQueryChange,
            hint = "Search channels and categories",
            imeAction = ImeAction.Search,
        )
        when (val s = state) {
            SearchState.Idle -> MessageState("Type at least two letters to search.")
            SearchState.Loading -> LoadingState()
            is SearchState.Failed -> MessageState(s.message, actionLabel = "Try again", onAction = { vm.onQueryChange(vm.query) })
            is SearchState.Results -> {
                if (s.channels.isEmpty() && s.categories.isEmpty()) {
                    MessageState("Nothing found for “${vm.query.trim()}”.")
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(260.dp),
                        state = gridState,
                        contentPadding = PaddingValues(vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (s.channels.isNotEmpty()) {
                            item(key = "h-channels", span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Channels") }
                            items(s.channels, key = { "ch:${it.id}" }) { channel ->
                                ChannelCard(channel, { onOpenChannel(channel.login) }, Modifier.fillMaxWidth())
                            }
                        }
                        if (s.categories.isNotEmpty()) {
                            item(key = "h-categories", span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Categories") }
                            items(s.categories, key = { "cat:${it.id}" }) { category ->
                                CategoryCard(category, { onOpenCategory(category) }, Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = Palette.OnBackground, modifier = Modifier.padding(top = 8.dp))
}
