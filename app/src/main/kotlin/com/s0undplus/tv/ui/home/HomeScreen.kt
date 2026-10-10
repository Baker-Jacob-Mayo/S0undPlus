package com.s0undplus.tv.ui.home

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
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
import com.s0undplus.tv.ui.components.CategoryCard
import com.s0undplus.tv.ui.components.ChannelCard
import com.s0undplus.tv.ui.components.LoadingState
import com.s0undplus.tv.ui.components.MessageState
import com.s0undplus.tv.ui.components.PagedGrid
import com.s0undplus.tv.ui.components.PagedLoader
import com.s0undplus.tv.ui.components.PillButton
import com.s0undplus.tv.ui.components.StreamCard
import com.s0undplus.tv.ui.components.TvTextField
import com.s0undplus.tv.ui.components.describe
import com.s0undplus.tv.ui.settings.SettingsContent
import com.s0undplus.tv.ui.theme.Palette
import com.s0undplus.twitch.Category
import com.s0undplus.twitch.ChannelResult
import com.s0undplus.twitch.Stream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class HomeTab(val label: String) {
    Following("Following"),
    Live("Live"),
    Categories("Categories"),
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
    val live = PagedLoader<Stream>(viewModelScope, { it.userId }) { c.helix.streams(after = it) }
    val categories = PagedLoader<Category>(viewModelScope, { it.id }) { c.helix.topCategories(after = it) }

    val gridStates: Map<HomeTab, LazyGridState> = HomeTab.entries.associateWith { LazyGridState() }
    val focusedKeys = mutableMapOf<HomeTab, Any>()

    var query by mutableStateOf("")
        private set
    private val _search = MutableStateFlow<SearchState>(SearchState.Idle)
    val search: StateFlow<SearchState> = _search.asStateFlow()
    private var searchJob: Job? = null

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
    }
}

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    userLogin: String,
    onOpenStream: (Stream) -> Unit,
    onOpenCategory: (Category) -> Unit,
    onOpenChannel: (String) -> Unit,
) {
    val tabRequesters = remember { HomeTab.entries.associateWith { FocusRequester() } }
    LaunchedEffect(Unit) {
        // Fresh start: park focus on the active tab. Returning from a stream: PagedGrid restores the card instead.
        if (vm.focusedKeys[vm.tab] == null) {
            try {
                tabRequesters.getValue(vm.tab).requestFocus()
            } catch (_: IllegalStateException) {
                // not attached yet; the first D-pad press will focus something sensible
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp, vertical = 20.dp)
                // Tabs select on focus, so pressing Up from a card must land on the *active* tab. Without this,
                // focus goes to whichever pill is geometrically above that card and silently swaps the content.
                .focusProperties { enter = { tabRequesters.getValue(vm.tab) } }
                .focusGroup(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("S0und+", style = MaterialTheme.typography.headlineSmall, color = Palette.Brand, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(24.dp))
            for (tab in HomeTab.entries) {
                PillButton(
                    label = tab.label,
                    onClick = { vm.tab = tab },
                    selected = tab == vm.tab,
                    onFocused = { vm.tab = tab },
                    modifier = Modifier.focusRequester(tabRequesters.getValue(tab)),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(userLogin, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
        }

        when (vm.tab) {
            HomeTab.Following -> StreamGrid(
                vm, HomeTab.Following, vm.following, "None of the channels you follow are live right now.", onOpenStream,
            )
            HomeTab.Live -> StreamGrid(vm, HomeTab.Live, vm.live, "No live streams found.", onOpenStream)
            HomeTab.Categories -> PagedGrid(
                loader = vm.categories,
                gridState = vm.gridStates.getValue(HomeTab.Categories),
                emptyText = "No categories found.",
                itemKey = { it.id },
                minCellWidth = 180.dp,
                restoreFocusKey = vm.focusedKeys[HomeTab.Categories],
                onItemFocused = { vm.focusedKeys[HomeTab.Categories] = it },
            ) { category, modifier -> CategoryCard(category, { onOpenCategory(category) }, modifier) }
            HomeTab.Search -> SearchContent(vm, onOpenCategory, onOpenChannel)
            HomeTab.Settings -> SettingsContent()
        }
    }
}

@Composable
private fun StreamGrid(
    vm: HomeViewModel,
    tab: HomeTab,
    loader: PagedLoader<Stream>,
    emptyText: String,
    onOpenStream: (Stream) -> Unit,
) {
    PagedGrid(
        loader = loader,
        gridState = vm.gridStates.getValue(tab),
        emptyText = emptyText,
        itemKey = { it.userId },
        restoreFocusKey = vm.focusedKeys[tab],
        onItemFocused = { vm.focusedKeys[tab] = it },
    ) { stream, modifier -> StreamCard(stream, { onOpenStream(stream) }, modifier) }
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
