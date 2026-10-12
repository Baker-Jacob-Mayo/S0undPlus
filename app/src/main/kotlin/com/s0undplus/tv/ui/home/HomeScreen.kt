package com.s0undplus.tv.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.s0undplus.tv.AppContainer
import com.s0undplus.tv.LocalAppContainer
import com.s0undplus.tv.data.AppSettings
import com.s0undplus.tv.ui.components.AvatarTile
import com.s0undplus.tv.ui.components.CategoryPoster
import com.s0undplus.tv.ui.components.CategoryTile
import com.s0undplus.tv.ui.components.CategoryTypeBar
import com.s0undplus.tv.ui.components.ClockText
import com.s0undplus.tv.ui.components.HeroBackdrop
import com.s0undplus.tv.ui.components.HeroInfo
import com.s0undplus.tv.ui.components.HeroModel
import com.s0undplus.tv.ui.components.HeroState
import com.s0undplus.tv.ui.components.HistoryCard
import com.s0undplus.tv.ui.components.KeylineScrolling
import com.s0undplus.tv.ui.components.MediaRow
import com.s0undplus.tv.ui.components.MessageState
import com.s0undplus.tv.ui.components.OfflineChannelCard
import com.s0undplus.tv.ui.components.PagedGrid
import com.s0undplus.tv.ui.components.PagedLoader
import com.s0undplus.tv.ui.components.PillButton
import com.s0undplus.tv.ui.components.PreviewController
import com.s0undplus.tv.ui.components.RefreshButton
import com.s0undplus.tv.ui.components.RowTitle
import com.s0undplus.tv.ui.components.SkeletonBlock
import com.s0undplus.tv.ui.components.Spinner
import com.s0undplus.tv.ui.components.StreamCard
import com.s0undplus.tv.ui.components.StreamFilterBar
import com.s0undplus.tv.ui.components.StreamFilterSheets
import com.s0undplus.tv.ui.components.TvTextField
import com.s0undplus.tv.ui.components.describe
import com.s0undplus.tv.ui.components.filterSummary
import com.s0undplus.tv.ui.components.focusNotifier
import com.s0undplus.tv.ui.components.rememberFilterBarState
import com.s0undplus.tv.ui.components.tagSuggestions
import com.s0undplus.tv.ui.nav.PlayTarget
import com.s0undplus.tv.ui.settings.SettingsContent
import com.s0undplus.tv.ui.theme.AppType
import com.s0undplus.tv.ui.theme.Layout
import com.s0undplus.tv.ui.theme.Palette
import com.s0undplus.tv.ui.update.UpdateBanner
import com.s0undplus.tv.ui.update.UpdateUi
import com.s0undplus.tv.ui.update.UpdateViewModel
import com.s0undplus.tv.ui.util.rememberScreenScope
import com.s0undplus.twitch.Category
import com.s0undplus.twitch.ChannelResult
import com.s0undplus.twitch.FollowedChannel
import com.s0undplus.twitch.Stream
import com.s0undplus.twitch.api.CategoryTag
import com.s0undplus.twitch.api.StreamFilter
import com.s0undplus.twitch.library.AppFollow
import com.s0undplus.twitch.library.FavoriteChannel
import com.s0undplus.twitch.library.FollowedCategory
import com.s0undplus.twitch.library.HistoryEntry
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Results(val channels: List<ChannelResult>, val categories: List<Category>) : SearchState
    data class Failed(val message: String) : SearchState
}

/** What the hero needs about a channel beyond the follow list: its picture and its description. */
data class Profile(val avatarUrl: String, val description: String)

/**
 * Home lives in an Activity-scoped ViewModel so everything the viewer did here — the open page, loaded lists, every
 * scroll position and the card they last focused — is still there after watching a stream and pressing Back.
 */
class HomeViewModel(private val c: AppContainer) : ViewModel() {
    var tab by mutableStateOf(HomeTab.Following)

    val following = PagedLoader<Stream>(viewModelScope, { it.userId }) { c.helix.followedStreams(after = it) }
    val offline = PagedLoader<FollowedChannel>(viewModelScope, { it.login }) { c.helix.followedChannels(after = it) }

    /** Live's sort and filters, kept between launches (null until read from storage). */
    val liveFilter: StateFlow<StreamFilter?> = c.settings.liveFilter.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val live = PagedLoader<Stream>(viewModelScope, { it.userId }) { cursor ->
        c.browse.streams(liveFilter.filterNotNull().first(), cursor = cursor)
    }

    /** The game types ticked on Categories, kept between launches (null until read from storage). */
    val categoryTypes: StateFlow<Set<String>?> = c.settings.categoryTypes.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val categories = PagedLoader<Category>(viewModelScope, { it.id }) { cursor ->
        c.browse.categories(categoryTypes.filterNotNull().first(), cursor)
    }
    private val _categoryTypeList = MutableStateFlow<List<CategoryTag>>(emptyList())

    /** Search's "Popular categories": every category, whatever the Categories page is narrowed to. */
    val popular = PagedLoader<Category>(viewModelScope, { it.id }) { c.helix.topCategories(after = it) }

    /** Every game type, the ones most browsed first. Empty until asked for (or if Twitch won't say). */
    val categoryTypeList: StateFlow<List<CategoryTag>> = _categoryTypeList.asStateFlow()

    /** Scroll positions: grids by page, vertical lists by page, and each horizontal row by its own key. */
    val gridStates: Map<HomeTab, LazyGridState> = HomeTab.entries.associateWith { LazyGridState() }
    val listStates: Map<HomeTab, LazyListState> = HomeTab.entries.associateWith { LazyListState() }
    private val rowStates = HashMap<String, LazyListState>()
    fun rowState(key: String): LazyListState = rowStates.getOrPut(key) { LazyListState() }

    /** The card each page last had focused, by that card's key. */
    val focusedKeys = mutableMapOf<HomeTab, Any>()

    /** Set while another screen covers Home, so that coming back puts focus back on the card that was opened. */
    var returning = false

    /** At launch, focus goes to the first card once it arrives, unless the viewer has pressed a key by then. */
    var launchFocusPending = true

    /** A page opened from the navigation before its cards arrived: they take focus when they come. */
    var pendingEnter by mutableStateOf<HomeTab?>(null)

    val hero = HeroState()

    val favorites: StateFlow<List<FavoriteChannel>> =
        c.settings.favorites.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val history: StateFlow<List<HistoryEntry>> =
        c.settings.history.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Channels and categories followed in this app (the account's own follows come from Twitch). */
    val appFollows: StateFlow<List<AppFollow>> = c.follows.channels
    val followedCategories: StateFlow<List<FollowedCategory>> = c.follows.categories

    private val _extraLive = MutableStateFlow<Map<String, Stream>>(emptyMap())

    /**
     * Favorites and channels followed in this app that are live right now, by lowercase login. The account's own
     * follows that are live come with [following].
     */
    val extraLive: StateFlow<Map<String, Stream>> = _extraLive.asStateFlow()

    private val _extraLiveKnown = MutableStateFlow(false)
    private var extraJob: Job? = null

    /** The favorites' and app follows' logins, once both have been read from storage (null until then). */
    private var extraLogins: List<String>? = null

    /** Whether [extraLive] has been asked for at least once: until then a channel followed in this app may be live. */
    val extraLiveKnown: StateFlow<Boolean> = _extraLiveKnown.asStateFlow()

    private val _profiles = MutableStateFlow<Map<String, Profile>>(emptyMap())
    private val profilesRequested = HashSet<String>()

    /** Profile pictures and descriptions by user id (the follow and stream lists carry neither). */
    val profiles: StateFlow<Map<String, Profile>> = _profiles.asStateFlow()

    /** Looks up the profiles of [ids] not asked for yet, 100 per request, once each. */
    fun loadProfiles(ids: List<String>) {
        val missing = ids.filter { it.isNotEmpty() && profilesRequested.add(it) }
        if (missing.isEmpty()) return
        viewModelScope.launch {
            try {
                val found = missing.chunked(100).flatMap { c.helix.usersById(it) }
                    .associate { it.id to Profile(it.profileImageUrl, it.description) }
                _profiles.value = _profiles.value + found
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                profilesRequested.removeAll(missing.toSet()) // try again next time the list shows
            }
        }
    }

    var query by mutableStateOf("")
        private set
    private val _search = MutableStateFlow<SearchState>(SearchState.Idle)
    val search: StateFlow<SearchState> = _search.asStateFlow()
    private var searchJob: Job? = null

    init {
        // From storage itself, not the StateFlows above: those start out empty, and an empty first list would ask
        // about nobody and call the answer known.
        viewModelScope.launch {
            combine(c.settings.favorites, c.settings.appFollows) { f, a -> f.map { it.login } + a.map { it.login } }
                .distinctUntilChanged()
                .collect {
                    extraLogins = it
                    refreshExtraLive(it)
                }
        }
        // A changed filter starts its list again from the top.
        viewModelScope.launch { liveFilter.filterNotNull().drop(1).collect { restart(HomeTab.Live, live) } }
        viewModelScope.launch { categoryTypes.filterNotNull().drop(1).collect { restart(HomeTab.Categories, categories) } }
    }

    private fun restart(tab: HomeTab, loader: PagedLoader<*>) {
        focusedKeys.remove(tab)
        gridStates.getValue(tab).takeIf { it.layoutInfo.totalItemsCount > 0 }?.let { viewModelScope.launch { it.scrollToItem(0) } }
        loader.refresh()
    }

    fun setLiveFilter(filter: StreamFilter) {
        viewModelScope.launch { c.settings.setLiveFilter(filter) }
    }

    fun toggleCategoryType(id: String) {
        val now = categoryTypes.value ?: return
        viewModelScope.launch { c.settings.setCategoryTypes(if (id in now) now - id else now + id) }
    }

    fun clearCategoryTypes() {
        viewModelScope.launch { c.settings.setCategoryTypes(emptySet()) }
    }

    fun loadCategoryTypes() {
        if (_categoryTypeList.value.isNotEmpty()) return
        viewModelScope.launch {
            try {
                _categoryTypeList.value = commonTypesFirst(c.directory.categoryTags())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // no game types to offer; the page still lists every category
            }
        }
    }

    /** The Refresh button on Following: everything again, now. */
    fun refreshFollowingNow() {
        following.refresh()
        offline.refresh()
        extraLogins?.let(::refreshExtraLive)
        c.follows.refreshTwitch(force = true)
    }

    fun refreshFollowing() {
        following.ensureFresh(FRESH_MS)
        offline.ensureFresh(OFFLINE_FRESH_MS)
        extraLogins?.let(::refreshExtraLive)
        c.follows.refreshTwitch()
    }

    private fun refreshExtraLive(logins: List<String>) {
        val distinct = logins.map { it.lowercase() }.distinct()
        // The newest question wins: an older answer arriving later would undo it.
        extraJob?.cancel()
        extraJob = viewModelScope.launch {
            if (distinct.isEmpty()) {
                _extraLive.value = emptyMap()
                _extraLiveKnown.value = true
                return@launch
            }
            try {
                _extraLive.value = distinct.chunked(100).flatMap { c.helix.streamsByLogins(it) }.associateBy { it.userLogin.lowercase() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // keep the previous status; these channels still show, just possibly as offline
            }
            _extraLiveKnown.value = true
        }
    }

    fun removeHistory(videoId: String) {
        viewModelScope.launch { c.settings.removeHistory(videoId) }
    }

    /** Back from a page's content: the next visit starts at its top, on its first card. */
    fun resetPlace(tab: HomeTab) {
        focusedKeys.remove(tab)
        if (tab == HomeTab.Following) hero.reset()
        // Only lists that are on screen: scrolling one that has never been laid out waits for a layout that may never come.
        gridStates.getValue(tab).takeIf { it.layoutInfo.totalItemsCount > 0 }?.let { viewModelScope.launch { it.scrollToItem(0) } }
        val lists = listOf(listStates.getValue(tab)) + rowStates.filterKeys { it.startsWith("${tab.name}/") }.values
        lists.filter { it.layoutInfo.totalItemsCount > 0 }.forEach { viewModelScope.launch { it.scrollToItem(0) } }
    }

    /** The Refresh button on Search: the same search again, or the popular categories when there isn't one. */
    fun searchAgain() {
        if (query.trim().length >= 2) onQueryChange(query) else popular.refresh()
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

/**
 * How a page hands focus to its content: [entry] sits on the card that should be focused when the page is entered,
 * and [shouldFocusNow] says whether that should happen as soon as the cards arrive (coming back from a stream, the
 * first launch, or a page opened from the navigation before it had loaded). [done] is called once it has.
 */
class PageFocus(val tab: HomeTab, val entry: FocusRequester, private val vm: HomeViewModel) {
    fun shouldFocusNow(): Boolean =
        vm.returning || vm.pendingEnter == tab || (vm.launchFocusPending && tab == HomeTab.Following)

    fun done() {
        vm.returning = false
        vm.launchFocusPending = false
        if (vm.pendingEnter == tab) vm.pendingEnter = null
    }

    val restoreKey: Any? get() = vm.focusedKeys[tab]

    fun remember(key: Any) {
        vm.focusedKeys[tab] = key
    }
}

/** Requests focus on [requester] after the next frame (so a card that was just composed is attached); false if it can't. */
private suspend fun focusSoon(requester: FocusRequester): Boolean {
    withFrameNanos { }
    return try {
        requester.requestFocus()
        true
    } catch (_: IllegalStateException) {
        false
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    updates: UpdateViewModel,
    account: String,
    onOpenStream: (Stream) -> Unit,
    onOpenCategory: (Category) -> Unit,
    onOpenChannel: (String) -> Unit,
    onPlay: (PlayTarget) -> Unit,
) {
    val container = LocalAppContainer.current
    val settings by container.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val preview = if (settings.livePreviews) rememberPreviewController() else null
    val railRequesters = remember { HomeTab.entries.associateWith { FocusRequester() } }
    val entryRequesters = remember { HomeTab.entries.associateWith { FocusRequester() } }
    var railFocused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Home leaves composition only when another screen (player, channel, category) covers it.
    DisposableEffect(Unit) { onDispose { vm.returning = !railFocused } }

    fun focusRail() {
        try {
            railRequesters.getValue(vm.tab).requestFocus()
        } catch (_: IllegalStateException) {
            // not attached yet
        }
    }

    /** Opens [tab] and moves into it: onto its remembered card, or its first one (as soon as it has one). */
    fun enter(tab: HomeTab) {
        vm.tab = tab
        scope.launch {
            if (!focusSoon(entryRequesters.getValue(tab))) vm.pendingEnter = tab
        }
    }

    // Back from anywhere in a page goes to the navigation first (and the page starts from its top next time); only
    // Back on the navigation leaves the app, so a stray press while browsing a long list doesn't close it.
    BackHandler(enabled = !railFocused) {
        vm.resetPlace(vm.tab)
        vm.pendingEnter = null
        focusRail()
    }

    val live by vm.following.state.collectAsStateWithLifecycle()
    val extraLive by vm.extraLive.collectAsStateWithLifecycle()
    val liveCount = remember(live.items, extraLive) { (live.items.map { it.userLogin.lowercase() } + extraLive.keys).toSet().size }
    val updateState by updates.state.collectAsStateWithLifecycle()
    val updateDot = updateState is UpdateUi.Available || updateState is UpdateUi.NeedsPermission ||
        updateState is UpdateUi.Downloading || updateState is UpdateUi.SignatureMismatch

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown) vm.launchFocusPending = false
                false
            },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                // Up and Down at a page's edges stay put (the navigation is only reached with Left). Only those two are
                // vetoed: anything else, such as Back handing focus to the navigation, must be let through.
                .focusProperties {
                    exit = { direction ->
                        if (direction == FocusDirection.Up || direction == FocusDirection.Down) FocusRequester.Cancel else FocusRequester.Default
                    }
                }
                .focusGroup(),
        ) {
            UpdateBanner(updates, Modifier.padding(start = Layout.keyline, end = Layout.endMargin, top = 16.dp))
            Box(Modifier.weight(1f)) {
                val focus = PageFocus(vm.tab, entryRequesters.getValue(vm.tab), vm)
                when (vm.tab) {
                    HomeTab.Following -> FollowingContent(
                        vm, focus, preview, onOpenStream, onOpenChannel, onOpenCategory, onPlay,
                        onEmpty = ::focusRail,
                        clockShown = settings.showClock,
                    )
                    HomeTab.Live -> LivePage(vm, focus, preview, onOpenStream, onOpenChannel)
                    HomeTab.Categories -> CategoriesPage(vm, focus, onOpenCategory)
                    HomeTab.History -> HistoryContent(vm, focus, onPlay)
                    HomeTab.Search -> SearchContent(vm, focus, onOpenCategory, onOpenChannel)
                    HomeTab.Settings -> SettingsContent(updates, focus.entry, focusNow = { focus.shouldFocusNow().also { if (it) focus.done() } })
                }
            }
        }

        NavRail(
            selected = vm.tab,
            requesters = railRequesters,
            onSettle = { vm.tab = it },
            onEnter = ::enter,
            onFocusChanged = { focused -> railFocused = focused },
            onItemFocused = {
                // Moving in the navigation means the viewer is choosing: nothing should pull focus into a page now.
                vm.pendingEnter = null
                vm.launchFocusPending = false
            },
            account = account,
            liveCount = liveCount,
            updateDot = updateDot,
            modifier = Modifier.testTag("navRail"),
        )
        if (settings.showClock) {
            ClockText(Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = Layout.endMargin))
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

/** A page's title and a quieter line under it; the first item of a grid, so it scrolls away with the cards. */
@Composable
fun PageHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = AppType.Headline, color = Palette.OnBackground)
            if (subtitle != null) Text(subtitle, style = AppType.BodyM, color = Palette.TextTertiary)
        }
        if (action != null) action()
    }
}

@Composable
private fun StreamGridPage(
    vm: HomeViewModel,
    focus: PageFocus,
    loader: PagedLoader<Stream>,
    preview: PreviewController?,
    onOpenStream: (Stream) -> Unit,
    onOpenChannel: (String) -> Unit,
    title: String,
    subtitle: String,
    emptyText: String,
    controls: (@Composable () -> Unit)? = null,
) {
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val favoriteLogins = remember(favorites) { favorites.map { it.login.lowercase() }.toSet() }
    PagedGrid(
        loader = loader,
        gridState = vm.gridStates.getValue(focus.tab),
        emptyText = emptyText,
        itemKey = { it.userId },
        restoreFocusKey = focus.restoreKey,
        entryRequester = focus.entry,
        focusOnReady = { focus.shouldFocusNow().also { if (it) focus.done() } },
        onItemFocused = focus::remember,
        // Below the header and its filters, so a focused first line never scrolls them away.
        keyline = 196.dp,
        header = {
            Column {
                PageHeader(title, "$subtitle  ·  Hold OK on a stream for its channel")
                if (controls != null) controls()
            }
        },
    ) { stream, modifier ->
        StreamCard(
            stream = stream,
            onClick = { onOpenStream(stream) },
            modifier = modifier,
            preview = preview,
            isFavorite = stream.userLogin.lowercase() in favoriteLogins,
            onLongClick = { onOpenChannel(stream.userLogin) },
        )
    }
}

/** Live: every live stream on Twitch, sorted and filtered the viewer's way (kept between launches). */
@Composable
private fun LivePage(
    vm: HomeViewModel,
    focus: PageFocus,
    preview: PreviewController?,
    onOpenStream: (Stream) -> Unit,
    onOpenChannel: (String) -> Unit,
) {
    val filter = vm.liveFilter.collectAsStateWithLifecycle().value ?: StreamFilter()
    val state by vm.live.state.collectAsStateWithLifecycle()
    val bar = rememberFilterBarState()
    val suggestions = remember(state.items) { tagSuggestions(state.items) }
    val nothing = state.items.isEmpty() && (state.loadedAtMillis != 0L || state.error != null)
    EnterControlsWhenEmpty(focus, nothing)
    Box(Modifier.fillMaxSize()) {
        StreamGridPage(
            vm, focus, vm.live, preview, onOpenStream, onOpenChannel,
            title = "Live",
            subtitle = filterSummary(filter),
            emptyText = "No live streams match these filters.",
            controls = {
                // With nothing listed, the controls are where the page is entered (to change what didn't match).
                StreamFilterBar(bar, filter, vm.live::refresh, Modifier.padding(top = 8.dp, bottom = 4.dp), entry = focus.entry.takeIf { nothing })
            },
        )
        StreamFilterSheets(bar, filter, suggestions, vm::setLiveFilter)
    }
}

/** Categories by viewers, narrowed to the game types ticked along the top. */
@Composable
private fun CategoriesPage(vm: HomeViewModel, focus: PageFocus, onOpenCategory: (Category) -> Unit) {
    val selected = vm.categoryTypes.collectAsStateWithLifecycle().value.orEmpty()
    val types by vm.categoryTypeList.collectAsStateWithLifecycle()
    val state by vm.categories.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadCategoryTypes() }
    val nothing = state.items.isEmpty() && (state.loadedAtMillis != 0L || state.error != null)
    EnterControlsWhenEmpty(focus, nothing)
    val names = types.filter { it.id in selected }.map { it.name }
    PagedGrid(
        loader = vm.categories,
        gridState = vm.gridStates.getValue(HomeTab.Categories),
        emptyText = if (selected.isEmpty()) "No categories found." else "No category is filed under all of these types.",
        itemKey = { it.id },
        columns = 6,
        restoreFocusKey = focus.restoreKey,
        entryRequester = focus.entry,
        focusOnReady = { focus.shouldFocusNow().also { if (it) focus.done() } },
        onItemFocused = focus::remember,
        keyline = 196.dp,
        header = {
            Column {
                PageHeader(
                    "Categories",
                    if (names.isEmpty()) "What people are watching, most viewers first" else "${names.joinToString(" + ")}  ·  most viewers first",
                )
                CategoryTypeBar(
                    types, selected, vm::toggleCategoryType, vm::clearCategoryTypes, vm.categories::refresh,
                    Modifier.padding(top = 8.dp, bottom = 4.dp),
                    entry = focus.entry.takeIf { nothing },
                )
            }
        },
        contentType = "poster",
    ) { category, modifier -> CategoryPoster(category, { onOpenCategory(category) }, modifier) }
}

/**
 * A page whose list came back empty (or failed) has no card to enter on: its controls take [PageFocus.entry]
 * instead, and focus goes there when it should have gone to a card.
 */
@Composable
private fun EnterControlsWhenEmpty(focus: PageFocus, nothing: Boolean) {
    LaunchedEffect(nothing) {
        if (nothing && focus.shouldFocusNow() && focusSoon(focus.entry)) focus.done()
    }
}

/** Game types people browse most, in that order, then the rest alphabetically. */
private fun commonTypesFirst(tags: List<CategoryTag>): List<CategoryTag> {
    val rank = COMMON_TYPES.withIndex().associate { (i, name) -> name.lowercase() to i }
    return tags.sortedWith(compareBy({ rank[it.name.lowercase()] ?: Int.MAX_VALUE }, { it.name.lowercase() }))
}

private val COMMON_TYPES = listOf(
    "IRL", "Shooter", "FPS", "RPG", "MOBA", "Strategy", "Survival", "Horror", "Action", "Adventure Game", "MMO",
    "Open World", "Simulation", "Sports Game", "Driving/Racing Game", "Fighting", "Party", "Creative", "Card & Board Game",
    "Puzzle", "Platformer", "Roguelike", "Indie Game",
)

/** Following: a hero describing the focused card, over rows of favourites, who is live, what to resume, and the rest. */
@Composable
private fun FollowingContent(
    vm: HomeViewModel,
    focus: PageFocus,
    preview: PreviewController?,
    onOpenStream: (Stream) -> Unit,
    onOpenChannel: (String) -> Unit,
    onOpenCategory: (Category) -> Unit,
    onPlay: (PlayTarget) -> Unit,
    onEmpty: () -> Unit,
    clockShown: Boolean,
) {
    val live by vm.following.state.collectAsStateWithLifecycle()
    val offline by vm.offline.state.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val extraLive by vm.extraLive.collectAsStateWithLifecycle()
    val appFollows by vm.appFollows.collectAsStateWithLifecycle()
    val extraKnown by vm.extraLiveKnown.collectAsStateWithLifecycle()
    val categories by vm.followedCategories.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshFollowing() }

    val favoriteLogins = remember(favorites) { favorites.map { it.login.lowercase() }.toSet() }
    // Live now: channels followed on Twitch or in the app, together, most viewers first; followed categories after them.
    val liveChannels = remember(live.items, appFollows, extraLive) {
        (live.items + appFollows.mapNotNull { extraLive[it.login.lowercase()] })
            .distinctBy { it.userLogin.lowercase() }
            .sortedByDescending { it.viewerCount }
    }
    val offlineItems = remember(offline.items, liveChannels, extraLive, extraKnown, appFollows) {
        val liveLogins = liveChannels.map { it.userLogin.lowercase() }.toSet() + extraLive.keys
        // Not before their status is known: one that turns out to be live would jump from here to Live now. They go
        // first, and Twitch's list (which arrives a page at a time) after them, so later pages never move anything.
        val appOffline = if (!extraKnown) emptyList() else appFollows.filter { it.login.lowercase() !in liveLogins }
        val appLogins = appOffline.mapTo(HashSet()) { it.login.lowercase() }
        appOffline.map { FollowedChannel(id = it.userId.orEmpty(), login = it.login, displayName = it.displayName) } +
            offline.items.filter { it.login.lowercase() !in liveLogins && it.login.lowercase() !in appLogins }
    }
    val continueWatching = remember(history) { history.take(CONTINUE_WATCHING_COUNT) }
    LaunchedEffect(offlineItems.size, favorites, liveChannels.size) {
        vm.loadProfiles(liveChannels.map { it.userId } + favorites.mapNotNull { it.userId } + offlineItems.map { it.id })
    }

    val hasAnything = liveChannels.isNotEmpty() || favorites.isNotEmpty() || offlineItems.isNotEmpty() || categories.isNotEmpty()
    if (!hasAnything) {
        when {
            live.loading || offline.loading || (live.loadedAtMillis == 0L && live.error == null) || !extraKnown -> FollowingSkeleton()
            live.error != null -> MessageState(live.error.orEmpty(), actionLabel = "Try again", onAction = vm::refreshFollowing)
            else -> {
                MessageState("You aren't following anyone yet. Follow channels on twitch.tv, or on a channel page here, and they'll appear here.")
                LaunchedEffect(Unit) {
                    if (focus.shouldFocusNow()) {
                        focus.done()
                        onEmpty()
                    }
                }
            }
        }
        return
    }

    // Each card's key and its hero description, in screen order; the first is where focus goes by default.
    fun liveModel(stream: Stream, section: String) =
        HeroModel.Live(stream, profiles[stream.userId]?.avatarUrl.orEmpty(), stream.userLogin.lowercase() in favoriteLogins, section)

    fun channelModel(id: String?, name: String, favorite: Boolean, section: String): HeroModel.Channel {
        val p = id?.let { profiles[it] }
        return HeroModel.Channel(name, p?.avatarUrl.orEmpty(), p?.description.orEmpty(), favorite, section)
    }

    val firstKey: String? = favorites.firstOrNull()?.let { "fav:${it.login}" }
        ?: liveChannels.firstOrNull()?.let { "live:${it.userId}" }
        ?: categories.firstOrNull()?.let { "cat:${it.id}" }
        ?: continueWatching.firstOrNull()?.let { "vod:${it.videoId}" }
        ?: offlineItems.firstOrNull()?.let { "off:${it.login}" }
    val keys = remember(favorites, liveChannels, categories, continueWatching, offlineItems) {
        buildSet {
            favorites.forEach { add("fav:${it.login}") }
            liveChannels.forEach { add("live:${it.userId}") }
            categories.forEach { add("cat:${it.id}") }
            continueWatching.forEach { add("vod:${it.videoId}") }
            offlineItems.forEach { add("off:${it.login}") }
        }
    }
    val focusedKey = (focus.restoreKey as? String)?.takeIf { it in keys }
    val entryKey = focusedKey ?: firstKey
    val hero = vm.hero
    // Who is live is known: rows no longer move about, so focus can go to the first card without it moving away.
    val settled = (live.loadedAtMillis != 0L || live.error != null) && extraKnown

    // The hero describes the first card until something is focused.
    val firstModel: HeroModel? = favorites.firstOrNull()?.let { fav ->
        extraLive[fav.login.lowercase()]?.let { liveModel(it, "Favorites") } ?: channelModel(fav.userId, fav.displayName, true, "Favorites")
    } ?: liveChannels.firstOrNull()?.let { liveModel(it, "Live now") }
        ?: categories.firstOrNull()?.let { HeroModel.CategoryItem(it.toCategory(), "Live now") }
        ?: continueWatching.firstOrNull()?.let { HeroModel.Vod(it, "Continue watching") }
        ?: offlineItems.firstOrNull()?.let { channelModel(it.id, it.displayName, false, "Offline") }
    LaunchedEffect(firstModel, hero.shown == null) {
        if (firstModel != null && (hero.shown == null || focusedKey == null)) hero.showNow(firstModel)
    }
    LaunchedEffect(entryKey != null && settled) {
        if (entryKey != null && settled && focus.shouldFocusNow()) {
            focusSoon(focus.entry)
            focus.done()
        }
    }

    @Composable
    fun cardModifier(key: String, model: () -> HeroModel): Modifier = Modifier
        .let { if (key == entryKey) it.focusRequester(focus.entry) else it }
        .focusNotifier {
            focus.remember(key)
            hero.target = model()
        }

    val listState = vm.listStates.getValue(HomeTab.Following)
    // A row that appears above the top one (someone you follow goes live) would open above the screen, because the
    // list keeps its first row where it is. Unless the viewer is on a card, stay at the top.
    val topRow = when {
        favorites.isNotEmpty() -> "row-fav"
        live.error != null && live.items.isEmpty() -> "live-error"
        liveChannels.isNotEmpty() || categories.isNotEmpty() -> "row-live"
        continueWatching.isNotEmpty() -> "row-vod"
        else -> "off-title"
    }
    var shownTopRow by remember { mutableStateOf(topRow) }
    LaunchedEffect(topRow) {
        if (topRow == shownTopRow) return@LaunchedEffect
        shownTopRow = topRow
        if (focusedKey == null && listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
    }
    val nearEnd by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, offline.items.size) {
        if (nearEnd) {
            vm.offline.loadMore()
            vm.following.loadMore()
        }
    }

    Box(Modifier.fillMaxSize()) {
        HeroBackdrop(hero, Modifier.align(Alignment.TopEnd))
        // Following has no title row for it: refresh sits at the top right, beside the clock. Up from the top row.
        RefreshButton(
            vm::refreshFollowingNow,
            Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = Layout.endMargin + if (clockShown) 96.dp else 0.dp),
        )
        Column(Modifier.fillMaxSize()) {
            HeroInfo(hero)
            KeylineScrolling(FOLLOWING_ROW_KEYLINE) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(Layout.rowGap),
                ) {
                    if (favorites.isNotEmpty()) {
                        item(key = "row-fav", contentType = "cardRow") {
                            MediaRow("Favorites", vm.rowState("Following/fav")) {
                                items(favorites, key = { it.login }, contentType = { "card" }) { fav ->
                                    val key = "fav:${fav.login}"
                                    val stream = extraLive[fav.login.lowercase()]
                                    if (stream != null) {
                                        StreamCard(
                                            stream, { onOpenStream(stream) },
                                            cardModifier(key) { liveModel(stream, "Favorites") }.width(Layout.cardWidth),
                                            preview, isFavorite = true,
                                            onLongClick = { onOpenChannel(fav.login) },
                                            showTitleOnFocus = false,
                                        )
                                    } else {
                                        OfflineChannelCard(
                                            fav.displayName, fav.userId?.let { profiles[it]?.avatarUrl }.orEmpty(),
                                            onClick = { onOpenChannel(fav.login) },
                                            modifier = cardModifier(key) { channelModel(fav.userId, fav.displayName, true, "Favorites") }.width(Layout.cardWidth),
                                            isFavorite = true,
                                            login = fav.login,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (live.error != null && live.items.isEmpty()) {
                        // Favorites are local, so they show even when Twitch could not be asked who is live: say so.
                        item(key = "live-error", contentType = "notice") {
                            Row(
                                Modifier.padding(start = Layout.keyline, end = Layout.endMargin),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Couldn't load who is live: ${live.error.orEmpty()}", style = AppType.BodyM, color = Palette.Muted, modifier = Modifier.weight(1f, fill = false))
                                PillButton("Try again", vm::refreshFollowing)
                            }
                        }
                    }
                    if (liveChannels.isNotEmpty() || categories.isNotEmpty()) {
                        item(key = "row-live", contentType = "cardRow") {
                            MediaRow("Live now", vm.rowState("Following/live"), count = liveChannels.size + categories.size, onNearEnd = vm.following::loadMore) {
                                items(liveChannels, key = { "s:${it.userId}" }, contentType = { "card" }) { stream ->
                                    StreamCard(
                                        stream, { onOpenStream(stream) },
                                        cardModifier("live:${stream.userId}") { liveModel(stream, "Live now") }.width(Layout.cardWidth),
                                        preview, isFavorite = stream.userLogin.lowercase() in favoriteLogins,
                                        onLongClick = { onOpenChannel(stream.userLogin) },
                                        showTitleOnFocus = false,
                                    )
                                }
                                items(categories, key = { "c:${it.id}" }, contentType = { "category" }) { followed ->
                                    val category = followed.toCategory()
                                    CategoryTile(
                                        category,
                                        onClick = { onOpenCategory(category) },
                                        modifier = cardModifier("cat:${followed.id}") { HeroModel.CategoryItem(category, "Live now") }.width(Layout.cardWidth),
                                    )
                                }
                            }
                        }
                    }
                    if (continueWatching.isNotEmpty()) {
                        item(key = "row-vod", contentType = "cardRow") {
                            MediaRow("Continue watching", vm.rowState("Following/vod")) {
                                items(continueWatching, key = { it.videoId }, contentType = { "card" }) { entry ->
                                    HistoryCard(
                                        entry,
                                        onClick = { onPlay(entry.toPlayTarget()) },
                                        modifier = cardModifier("vod:${entry.videoId}") { HeroModel.Vod(entry, "Continue watching") }.width(Layout.cardWidth),
                                    )
                                }
                            }
                        }
                    }
                    if (offlineItems.isNotEmpty()) {
                        item(key = "off-title", contentType = "title") { RowTitle("Offline", offlineItems.size) }
                        // A wall of avatars, a line at a time: hundreds of follows stay a few Down presses apart.
                        itemsIndexed(offlineItems.chunked(OFFLINE_PER_LINE), key = { i, _ -> "off-line:$i" }, contentType = { _, _ -> "avatarLine" }) { _, line ->
                            Row(Modifier.padding(start = Layout.keyline, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                for (channel in line) {
                                    // Keyed by channel, so a tile that moves within its line keeps its focus.
                                    key(channel.login) {
                                        val favorite = channel.login.lowercase() in favoriteLogins
                                        AvatarTile(
                                            channel.displayName, profiles[channel.id]?.avatarUrl.orEmpty(),
                                            onClick = { onOpenChannel(channel.login) },
                                            modifier = cardModifier("off:${channel.login}") { channelModel(channel.id, channel.displayName, favorite, "Offline") },
                                            login = channel.login,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (live.loading || offline.loading) {
                        item(key = "loading", contentType = "loading") {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Spinner(size = 28.dp) }
                        }
                    }
                }
            }
        }
    }
}

private fun FollowedCategory.toCategory() = Category(id = id, name = name, boxArtUrl = boxArtUrl)

private fun HistoryEntry.toPlayTarget() = PlayTarget.Vod(
    id = videoId,
    title = title,
    channelName = channelName,
    channelLogin = channelLogin,
    channelUserId = null,
    durationSeconds = durationSeconds,
    thumbnailUrl = thumbnailUrl,
)

/** The shape of Following while it loads: hero lines, then a row of cards. */
@Composable
private fun FollowingSkeleton() {
    Column(Modifier.fillMaxSize().padding(start = Layout.keyline, top = 56.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SkeletonBlock(Modifier.width(120.dp).height(12.dp), radius = 4.dp)
        SkeletonBlock(Modifier.width(280.dp).height(32.dp))
        SkeletonBlock(Modifier.width(220.dp).height(16.dp), radius = 4.dp)
        SkeletonBlock(Modifier.width(420.dp).height(14.dp), radius = 4.dp)
        Box(Modifier.height(40.dp))
        SkeletonBlock(Modifier.width(120.dp).height(16.dp), radius = 4.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(Layout.cardGap)) {
            repeat(5) { SkeletonBlock(Modifier.width(Layout.cardWidth).aspectRatio(16f / 9f)) }
        }
    }
}

@Composable
private fun HistoryContent(vm: HomeViewModel, focus: PageFocus, onPlay: (PlayTarget) -> Unit) {
    val history by vm.history.collectAsStateWithLifecycle()
    if (history.isEmpty()) {
        MessageState("Past broadcasts you watch show up here so you can pick up where you left off.", icon = Icons.Rounded.History)
        return
    }
    val entryKey = (focus.restoreKey as? String)?.takeIf { key -> history.any { it.videoId == key } } ?: history.first().videoId
    LaunchedEffect(Unit) {
        if (focus.shouldFocusNow()) {
            focusSoon(focus.entry)
            focus.done()
        }
    }
    // After a removal, focus moves to the card that took its place (or the one before, if it was the last).
    var removedIndex by remember { mutableStateOf(-1) }
    val refocus = remember { FocusRequester() }
    val refocusIndex = if (removedIndex >= 0) removedIndex.coerceAtMost(history.lastIndex) else -1
    LaunchedEffect(history.size) {
        if (removedIndex >= 0) {
            focusSoon(refocus)
            removedIndex = -1
        }
    }
    KeylineScrolling(140.dp) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            state = vm.gridStates.getValue(HomeTab.History),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Layout.keyline, end = Layout.endMargin, top = 16.dp, bottom = 64.dp),
            horizontalArrangement = Arrangement.spacedBy(Layout.cardGap),
            verticalArrangement = Arrangement.spacedBy(Layout.gridLineGap),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                PageHeader("History", "Continue where you left off  ·  Hold OK on a video to remove it")
            }
            itemsIndexed(history, key = { _, it -> it.videoId }, contentType = { _, _ -> "card" }) { index, entry ->
                HistoryCard(
                    entry = entry,
                    onClick = { onPlay(entry.toPlayTarget()) },
                    onLongClick = {
                        removedIndex = index
                        vm.removeHistory(entry.videoId)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .let { if (entry.videoId == entryKey) it.focusRequester(focus.entry) else it }
                        .let { if (index == refocusIndex) it.focusRequester(refocus) else it }
                        .focusNotifier { focus.remember(entry.videoId) },
                )
            }
        }
    }
}

@Composable
private fun SearchContent(vm: HomeViewModel, focus: PageFocus, onOpenCategory: (Category) -> Unit, onOpenChannel: (String) -> Unit) {
    val state by vm.search.collectAsStateWithLifecycle()
    val popular by vm.popular.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        vm.popular.ensureFresh(10 * 60_000L)
        if (focus.shouldFocusNow()) {
            focusSoon(focus.entry)
            focus.done()
        }
    }
    // Coming back from a channel or category opened here, focus returns to that result; otherwise to the field.
    val restore = focus.restoreKey as? String
    val shownKeys = remember(state, popular.items) {
        when (val s = state) {
            is SearchState.Results -> s.channels.map { "ch:${it.id}" } + s.categories.map { "cat:${it.id}" }
            SearchState.Idle -> popular.items.take(20).map { "pop:${it.id}" }
            else -> emptyList()
        }.toSet()
    }
    val entryKey = restore?.takeIf { it in shownKeys }
    fun result(key: String): Modifier = Modifier
        .let { if (key == entryKey) it.focusRequester(focus.entry) else it }
        .focusNotifier { focus.remember(key) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(start = Layout.keyline, end = 160.dp, top = 32.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TvTextField(
                value = vm.query,
                onValueChange = vm::onQueryChange,
                hint = "Search channels and categories",
                imeAction = ImeAction.Search,
                leadingIcon = Icons.Rounded.Search,
                modifier = Modifier
                    .weight(1f)
                    .let { if (entryKey == null) it.focusRequester(focus.entry) else it },
            )
            RefreshButton(vm::searchAgain)
        }
        KeylineScrolling(FOLLOWING_ROW_KEYLINE) {
            LazyColumn(
                state = vm.listStates.getValue(HomeTab.Search),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 200.dp),
                verticalArrangement = Arrangement.spacedBy(Layout.rowGap),
            ) {
                when (val s = state) {
                    SearchState.Idle -> {
                        item(key = "hint", contentType = "hint") {
                            Text("Type at least two letters to search.", style = AppType.BodyM, color = Palette.TextTertiary, modifier = Modifier.padding(start = Layout.keyline))
                        }
                        if (popular.items.isNotEmpty()) {
                            item(key = "popular", contentType = "posterRow") {
                                MediaRow("Popular categories", vm.rowState("Search/popular")) {
                                    items(popular.items.take(20), key = { it.id }, contentType = { "poster" }) { category ->
                                        CategoryPoster(category, { onOpenCategory(category) }, result("pop:${category.id}").width(120.dp))
                                    }
                                }
                            }
                        }
                    }
                    SearchState.Loading -> item(key = "loading", contentType = "loading") {
                        Row(Modifier.padding(start = Layout.keyline, top = 32.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            repeat(7) { SkeletonBlock(Modifier.width(Layout.avatar).height(Layout.avatar), radius = 40.dp) }
                        }
                    }
                    is SearchState.Failed -> item(key = "failed", contentType = "notice") {
                        MessageState(s.message, Modifier.fillMaxWidth(), actionLabel = "Try again", onAction = { vm.onQueryChange(vm.query) })
                    }
                    is SearchState.Results -> {
                        if (s.channels.isEmpty() && s.categories.isEmpty()) {
                            item(key = "none", contentType = "notice") {
                                MessageState("Nothing found for “${vm.query.trim()}”.", Modifier.fillMaxWidth())
                            }
                        }
                        if (s.channels.isNotEmpty()) {
                            item(key = "channels", contentType = "avatarRow") {
                                MediaRow("Channels", vm.rowState("Search/channels"), count = s.channels.size, itemSpacing = 12.dp) {
                                    items(s.channels, key = { it.id }, contentType = { "avatar" }) { channel ->
                                        AvatarTile(
                                            channel.displayName, channel.thumbnailUrl,
                                            onClick = { onOpenChannel(channel.login) },
                                            modifier = result("ch:${channel.id}"),
                                            live = channel.isLive,
                                            login = channel.login,
                                            subtitle = if (channel.isLive) channel.gameName.ifBlank { "Live" } else "Offline",
                                        )
                                    }
                                }
                            }
                        }
                        if (s.categories.isNotEmpty()) {
                            item(key = "categories", contentType = "posterRow") {
                                MediaRow("Categories", vm.rowState("Search/categories"), count = s.categories.size) {
                                    items(s.categories, key = { it.id }, contentType = { "poster" }) { category ->
                                        CategoryPoster(category, { onOpenCategory(category) }, result("cat:${category.id}").width(120.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Where the focused row's cards sit, measured from the top of the list (just under the row's title). */
private val FOLLOWING_ROW_KEYLINE = 46.dp
private const val OFFLINE_PER_LINE = 7
private const val CONTINUE_WATCHING_COUNT = 12
