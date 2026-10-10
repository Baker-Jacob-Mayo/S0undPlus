package com.s0undplus.tv.ui.components

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
    else -> e.message ?: "Something went wrong."
}

/**
 * A responsive grid fed by a [PagedLoader]. Handles first-load spinner, empty and error states, infinite scroll and
 * restoring D-pad focus to [restoreFocusKey] when coming back from another screen.
 */
@Composable
fun <T : Any> PagedGrid(
    loader: PagedLoader<T>,
    gridState: LazyGridState,
    emptyText: String,
    itemKey: (T) -> Any,
    modifier: Modifier = Modifier,
    minCellWidth: Dp = 260.dp,
    restoreFocusKey: Any? = null,
    onItemFocused: (Any) -> Unit = {},
    itemContent: @Composable (item: T, modifier: Modifier) -> Unit,
) {
    val state by loader.state.collectAsStateWithLifecycle()
    LaunchedEffect(loader) { loader.ensureFresh(REFRESH_AGE_MS) }

    if (state.items.isEmpty()) {
        when {
            state.loading || (state.loadedAtMillis == 0L && state.error == null) -> LoadingState(modifier)
            state.error != null -> MessageState(state.error ?: "", modifier, "Try again", loader::refresh)
            else -> MessageState(emptyText, modifier)
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

    var restored by remember(loader) { mutableStateOf(restoreFocusKey == null) }
    val restoreRequester = remember { FocusRequester() }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minCellWidth),
        state = gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(state.items, key = { itemKey(it) }) { item ->
            val key = itemKey(item)
            val isRestoreTarget = !restored && key == restoreFocusKey
            val cardModifier = Modifier.fillMaxWidth().let { if (isRestoreTarget) it.focusRequester(restoreRequester) else it }
            if (isRestoreTarget) {
                LaunchedEffect(Unit) {
                    try {
                        restoreRequester.requestFocus()
                    } catch (_: IllegalStateException) {
                        // item not attached yet; the user can simply navigate
                    }
                    restored = true
                }
            }
            itemContent(item, cardModifier.focusNotifier { onItemFocused(key) })
        }
        if (state.loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LoadingState(Modifier.fillMaxWidth().padding(16.dp))
            }
        }
    }
}

private const val REFRESH_AGE_MS = 60_000L
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
