package com.s0undplus.tv.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.withFrameNanos
import com.s0undplus.tv.ui.theme.Layout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.s0undplus.twitch.Page
import com.s0undplus.twitch.TwitchApiException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PagedState<T>(
    val items: List<T> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val nextCursor: String? = null,
    val loadedAtMillis: Long = 0,
)

/** Turns a cursor-paginated fetch into observable state with refresh, load-more and de-duplication. */
class PagedLoader<T>(
    private val scope: CoroutineScope,
    private val key: (T) -> Any,
    private val fetch: suspend (cursor: String?) -> Page<T>,
) {
    private val _state = MutableStateFlow(PagedState<T>())
    val state: StateFlow<PagedState<T>> = _state.asStateFlow()
    private var job: Job? = null

    /** Reloads from the first page, keeping the old items on screen until the new ones arrive. */
    fun refresh() {
        job?.cancel()
        _state.value = _state.value.copy(loading = true, error = null)
        job = scope.launch {
            try {
                val page = fetch(null)
                _state.value = PagedState(
                    items = page.items.distinctBy(key),
                    nextCursor = page.nextCursor,
                    loadedAtMillis = System.currentTimeMillis(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading the first page failed", e)
                _state.value = _state.value.copy(loading = false, error = describe(e))
            }
        }
    }

    fun loadMore() {
        val current = _state.value
        val cursor = current.nextCursor
        if (current.loading || cursor == null) return
        _state.value = current.copy(loading = true, error = null)
        job = scope.launch {
            try {
                val page = fetch(cursor)
                val latest = _state.value
                _state.value = latest.copy(
                    items = (latest.items + page.items).distinctBy(key),
                    nextCursor = page.nextCursor,
                    loading = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading more failed", e)
                _state.value = _state.value.copy(loading = false, error = describe(e))
            }
        }
    }

    /** Refreshes unless fresh data was loaded within [maxAgeMillis] or a load is already running. */
    fun ensureFresh(maxAgeMillis: Long) {
        val s = _state.value
        if (s.loading) return
        if (s.loadedAtMillis == 0L || System.currentTimeMillis() - s.loadedAtMillis > maxAgeMillis) refresh()
    }
}

fun describe(e: Throwable): String = when {
    e is TwitchApiException && e.status == 401 -> "Your Twitch session expired. Please sign in again."
    e is TwitchApiException -> "Twitch returned an error (${e.status})."
    e is IOException -> "Couldn't reach Twitch. Check your connection."
    else -> e.message ?: "Something went wrong (${e.javaClass.simpleName})."
}

private const val TAG = "PagedLoader"

/**
 * A grid of [columns] fed by a [PagedLoader], starting at the page keyline. [header] is the first full-width item and
 * scrolls away with the cards. Handles first-load skeletons, empty and error states, and loading more near the end.
 *
 * Focus: [entryRequester] sits on the card focus should go to when the page is entered: the [restoreFocusKey] card if
 * it is still in the list, otherwise the first card. When the first cards arrive and [focusOnReady] says so, that card
 * takes focus (a page that was just opened, or coming back from a stream). The grid never takes focus on its own
 * otherwise, so browsing the navigation past it leaves focus where the viewer put it.
 */
@Composable
fun <T : Any> PagedGrid(
    loader: PagedLoader<T>,
    gridState: LazyGridState,
    emptyText: String,
    itemKey: (T) -> Any,
    modifier: Modifier = Modifier,
    columns: Int = 4,
    restoreFocusKey: Any? = null,
    entryRequester: FocusRequester? = null,
    focusOnReady: () -> Boolean = { false },
    onItemFocused: (Any) -> Unit = {},
    header: (@Composable () -> Unit)? = null,
    contentType: Any = "card",
    keyline: Dp = GRID_KEYLINE,
    itemContent: @Composable (item: T, modifier: Modifier) -> Unit,
) {
    val state by loader.state.collectAsStateWithLifecycle()
    LaunchedEffect(loader) {
        // A refresh replaces the list with its first page. Coming back to a card further down than that, keep the list
        // as it is, or the card (and the viewer's place) would vanish.
        val restoreIndex = restoreFocusKey?.let { key -> loader.state.value.items.indexOfFirst { itemKey(it) == key } } ?: -1
        if (restoreIndex < SAFE_RESTORE_INDEX) loader.ensureFresh(REFRESH_AGE_MS)
    }

    if (state.items.isEmpty()) {
        Column(modifier.fillMaxSize()) {
            if (header != null) Box(Modifier.padding(start = Layout.keyline, end = Layout.endMargin, top = 16.dp)) { header() }
            when {
                state.loading || (state.loadedAtMillis == 0L && state.error == null) -> GridSkeleton(columns)
                state.error != null -> MessageState(state.error ?: "", Modifier.fillMaxSize(), "Try again", loader::refresh)
                else -> MessageState(emptyText, Modifier.fillMaxSize())
            }
        }
        return
    }

    val nearEnd by remember(gridState) {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - LOAD_MORE_THRESHOLD
        }
    }
    LaunchedEffect(nearEnd, state.items.size) { if (nearEnd) loader.loadMore() }

    val entryKey = restoreFocusKey?.takeIf { key -> state.items.any { itemKey(it) == key } } ?: itemKey(state.items.first())
    LaunchedEffect(Unit) {
        if (entryRequester != null && focusOnReady()) {
            withFrameNanos { }
            try {
                entryRequester.requestFocus()
            } catch (_: IllegalStateException) {
                // the card isn't attached yet; the viewer can simply navigate
            }
        }
    }

    KeylineScrolling(keyline) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Layout.keyline, end = Layout.endMargin, top = 16.dp, bottom = 64.dp),
            horizontalArrangement = Arrangement.spacedBy(Layout.cardGap),
            verticalArrangement = Arrangement.spacedBy(Layout.gridLineGap),
        ) {
            if (header != null) item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") { header() }
            items(state.items, key = { itemKey(it) }, contentType = { contentType }) { item ->
                val key = itemKey(item)
                val cardModifier = Modifier
                    .fillMaxWidth()
                    .let { if (entryRequester != null && key == entryKey) it.focusRequester(entryRequester) else it }
                    .focusNotifier { onItemFocused(key) }
                itemContent(item, cardModifier)
            }
            if (state.loading) {
                item(span = { GridItemSpan(maxLineSpan) }, contentType = "loading") {
                    LoadingState(Modifier.fillMaxWidth().padding(16.dp))
                }
            }
        }
    }
}

/** Placeholder cards in the grid's shape while its first page loads. */
@Composable
fun GridSkeleton(columns: Int, lines: Int = 2) {
    Column(
        Modifier.padding(start = Layout.keyline, end = Layout.endMargin, top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(Layout.gridLineGap),
    ) {
        repeat(lines) {
            Row(horizontalArrangement = Arrangement.spacedBy(Layout.cardGap)) {
                repeat(columns) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SkeletonBlock(Modifier.fillMaxWidth().aspectRatio(if (columns > 4) 3f / 4f else 16f / 9f))
                        SkeletonBlock(Modifier.fillMaxWidth(0.6f).height(12.dp), radius = 4.dp)
                    }
                }
            }
        }
    }
}

/**
 * Where a focused grid line sits, measured from the top of the grid: below the page header (so the first line never
 * scrolls it away) and low enough that the line above still peeks in.
 */
private val GRID_KEYLINE = 140.dp

private const val REFRESH_AGE_MS = 60_000L

/** Cards before this index are on the first page of every list here, so a refresh keeps them. */
private const val SAFE_RESTORE_INDEX = 12
private const val LOAD_MORE_THRESHOLD = 8

/** Invokes [onFocused] whenever this item (or a child) gains focus. */
fun Modifier.focusNotifier(onFocused: () -> Unit): Modifier =
    this.onFocusChanged { if (it.hasFocus) onFocused() }

/**
 * Puts D-pad focus back on the card the viewer last had selected when a screen is re-entered (e.g. returning from the
 * player). Create one per screen visit with the remembered key and apply [forItem] to every item.
 */
class FocusRestorer(private val key: Any?) {
    private var done = key == null
    private val requester = FocusRequester()

    @Composable
    fun forItem(itemKey: Any): Modifier {
        val target = !done && itemKey == key
        if (target) {
            LaunchedEffect(Unit) {
                try {
                    requester.requestFocus()
                } catch (_: IllegalStateException) {
                    // item not attached yet; the viewer can simply navigate
                }
                done = true
            }
        }
        return if (target) Modifier.focusRequester(requester) else Modifier
    }
}

/** Scrolls-to-end detection shared by every infinite grid. */
@Composable
fun LoadMoreEffect(gridState: LazyGridState, enabled: Boolean = true, onLoadMore: () -> Unit) {
    val nearEnd by remember(gridState) {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - LOAD_MORE_THRESHOLD
        }
    }
    LaunchedEffect(nearEnd, enabled) { if (nearEnd && enabled) onLoadMore() }
}
