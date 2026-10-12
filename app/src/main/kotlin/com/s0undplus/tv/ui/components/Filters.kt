package com.s0undplus.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.s0undplus.tv.ui.theme.AppType
import com.s0undplus.tv.ui.theme.Palette
import com.s0undplus.tv.ui.theme.Radius
import com.s0undplus.twitch.Stream
import com.s0undplus.twitch.TwitchLanguages
import com.s0undplus.twitch.api.CategoryTag
import com.s0undplus.twitch.api.StreamFilter
import com.s0undplus.twitch.api.StreamSort

/** A round button that reloads the page it sits on. The arrow says what it does; no label needed. */
@Composable
fun RefreshButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    ControlSurface(
        onClick = onClick,
        modifier = modifier.size(40.dp).semantics { contentDescription = "Refresh" },
        shape = CircleShape,
    ) { focused ->
        Icon(Icons.Rounded.Refresh, contentDescription = null, tint = if (focused) Palette.OnBrand else Palette.OnBackground, modifier = Modifier.size(22.dp))
    }
}

/**
 * A filter's pill: what it is ([label]), what it's set to ([value]), and a dot when that isn't the default, so a page
 * says at a glance how its list is narrowed. OK opens the filter's list.
 */
@Composable
fun FilterPill(label: String, value: String, set: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ControlSurface(onClick = onClick, modifier = modifier.height(40.dp)) { focused ->
        val main = if (focused) Palette.OnBrand else Palette.OnBackground
        val quiet = if (focused) Palette.OnBrand.copy(alpha = 0.7f) else Palette.TextTertiary
        Row(
            Modifier.padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (set) Box(Modifier.size(7.dp).background(if (focused) Palette.OnBrand else Palette.BrandSoft, CircleShape))
            Text(label, style = AppType.BodyM, color = quiet, maxLines = 1)
            Text(value, style = AppType.Button, color = main, maxLines = 1)
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, tint = quiet, modifier = Modifier.size(20.dp))
        }
    }
}

/** Which of a stream list's filters has its list open. */
enum class FilterSheet { SORT, LANGUAGE, TAGS }

/** The open filter list, and the pills that open them (focus goes back to its pill when a list closes). */
class FilterBarState {
    var open by mutableStateOf<FilterSheet?>(null)
    val pills: Map<FilterSheet, FocusRequester> = FilterSheet.entries.associateWith { FocusRequester() }
}

@Composable
fun rememberFilterBarState(): FilterBarState = remember { FilterBarState() }

/**
 * Refresh, then Sort, Language and Tags for a list of live streams. [entry], when given, goes on the refresh button:
 * the place a page with nothing listed is entered.
 */
@Composable
fun StreamFilterBar(
    state: FilterBarState,
    filter: StreamFilter,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    entry: FocusRequester? = null,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        RefreshButton(onRefresh, if (entry != null) Modifier.focusRequester(entry) else Modifier)
        FilterPill(
            "Sort", filter.sort.label, set = filter.sort != StreamSort.VIEWERS,
            onClick = { state.open = FilterSheet.SORT },
            modifier = Modifier.focusRequester(state.pills.getValue(FilterSheet.SORT)),
        )
        FilterPill(
            "Language", languagesLabel(filter.languages), set = filter.languages.isNotEmpty(),
            onClick = { state.open = FilterSheet.LANGUAGE },
            modifier = Modifier.focusRequester(state.pills.getValue(FilterSheet.LANGUAGE)),
        )
        FilterPill(
            "Tags", listLabel(filter.tags.sorted(), "Any"), set = filter.tags.isNotEmpty(),
            onClick = { state.open = FilterSheet.TAGS },
            modifier = Modifier.focusRequester(state.pills.getValue(FilterSheet.TAGS)),
        )
    }
}

/** The tags on [streams], most used first (spelled as most of them spell it), as choices for the Tags filter. */
fun tagSuggestions(streams: List<Stream>, max: Int = 40): List<String> =
    streams.flatMap { it.tags }
        .filter { it.isNotBlank() }
        .groupBy { it.lowercase() }
        .values
        .sortedByDescending { it.size }
        .take(max)
        .map { spellings -> spellings.groupingBy { it }.eachCount().maxBy { it.value }.key }

/** "All", one language's own name, two of them, or the first and how many more. */
fun languagesLabel(codes: Set<String>): String =
    listLabel(TwitchLanguages.ALL.filter { it.first in codes }.map { it.second } + codes.filter { c -> TwitchLanguages.ALL.none { it.first == c } }, "All")

private fun listLabel(names: List<String>, none: String): String = when (names.size) {
    0 -> none
    1, 2 -> names.joinToString(", ")
    else -> "${names.first()} +${names.size - 1}"
}

/** One line of a filter's subtitle: "Most viewers first · English, Dansk · Chill". */
fun filterSummary(filter: StreamFilter): String = listOfNotNull(
    when (filter.sort) {
        StreamSort.VIEWERS -> "Most viewers first"
        StreamSort.RECENT -> "Most recently started first"
        StreamSort.FEWEST_VIEWERS -> "Fewest viewers first"
    },
    filter.languages.takeIf { it.isNotEmpty() }?.let(::languagesLabel),
    filter.tags.takeIf { it.isNotEmpty() }?.sorted()?.let { listLabel(it, "") },
).joinToString("  ·  ")

/**
 * The list a [StreamFilterBar] pill opened, as a panel down the right of the screen. Draw it outside the page's grid
 * (the grid would clip it). Sort applies as soon as one is picked; languages and tags are ticked one by one and apply
 * when the panel closes. [tagSuggestions] are the tags to offer, most used first.
 */
@Composable
fun StreamFilterSheets(state: FilterBarState, filter: StreamFilter, tagSuggestions: List<String>, onChange: (StreamFilter) -> Unit) {
    val which = state.open ?: return
    val close = { state.open = null }
    val returnFocus = state.pills.getValue(which)
    when (which) {
        FilterSheet.SORT -> SideSheet("Sort by", onDismiss = close, returnFocus = returnFocus) { first ->
            items(StreamSort.entries, key = { it.name }) { sort ->
                CheckRow(sort.label, sort == filter.sort, Modifier.focusFirst(first, sort == filter.sort)) {
                    close()
                    if (sort != filter.sort) onChange(filter.copy(sort = sort))
                }
            }
        }
        FilterSheet.LANGUAGE -> {
            var picked by remember { mutableStateOf(filter.languages) }
            SideSheet(
                "Languages",
                onDismiss = {
                    close()
                    if (picked != filter.languages) onChange(filter.copy(languages = picked))
                },
                returnFocus = returnFocus,
                tag = "languageSheet",
            ) { first ->
                item(key = "all") { CheckRow("All languages", picked.isEmpty(), Modifier.focusRequester(first)) { picked = emptySet() } }
                items(TwitchLanguages.ALL, key = { it.first }) { (code, name) ->
                    CheckRow(name, code in picked) { picked = if (code in picked) picked - code else picked + code }
                }
            }
        }
        FilterSheet.TAGS -> {
            var picked by remember { mutableStateOf(filter.tags) }
            // What's ticked stays at the top even when nothing live uses it any more.
            val offered = remember(tagSuggestions) { (filter.tags.sorted() + tagSuggestions).distinctBy { it.lowercase() } }
            SideSheet(
                "Tags",
                onDismiss = {
                    close()
                    if (picked != filter.tags) onChange(filter.copy(tags = picked))
                },
                returnFocus = returnFocus,
                subtitle = "Tags streamers set themselves, from what's live now. Streams with every ticked tag are shown.",
            ) { first ->
                item(key = "any") { CheckRow("Any tag", picked.isEmpty(), Modifier.focusRequester(first)) { picked = emptySet() } }
                items(offered, key = { it.lowercase() }) { tag ->
                    CheckRow(tag, tag in picked) { picked = if (tag in picked) picked - tag else picked + tag }
                }
            }
        }
    }
}

private fun Modifier.focusFirst(requester: FocusRequester, isIt: Boolean): Modifier = if (isIt) focusRequester(requester) else this

/** A row with a tick when [checked], white when focused like every list row. */
@Composable
fun CheckRow(label: String, checked: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ListRowSurface(onClick = onClick, modifier = modifier.fillMaxWidth()) { focused ->
        Text(
            label,
            style = AppType.BodyL,
            color = if (focused) Palette.OnFocusFill else Palette.OnBackground,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 12.dp),
        )
        if (checked) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = "Selected",
                tint = if (focused) Palette.OnFocusFill else Palette.BrandSoft,
                modifier = Modifier.padding(end = 16.dp).size(20.dp),
            )
        }
    }
}

/**
 * A panel down the right edge over the dimmed page, holding a list. Like [DialogFrame], the D-pad stays inside it,
 * Back closes it, and focus goes back to [returnFocus] afterwards. [content] gets the requester for the row to focus
 * first, which it must attach to one of its rows.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SideSheet(
    title: String,
    onDismiss: () -> Unit,
    returnFocus: FocusRequester?,
    subtitle: String? = null,
    tag: String? = null,
    content: LazyListScope.(first: FocusRequester) -> Unit,
) {
    val first = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    if (returnFocus != null) {
        DisposableEffect(Unit) {
            onDispose {
                try {
                    returnFocus.requestFocus()
                } catch (_: IllegalStateException) {
                    // the pill scrolled away with the page header; nothing to return to
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        try {
            first.requestFocus()
        } catch (_: IllegalStateException) {
            // not attached yet; the D-pad still reaches the panel
        }
    }
    Box(Modifier.fillMaxSize().background(Palette.Scrim), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .let { if (tag != null) it.testTag(tag) else it }
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
                .fillMaxHeight()
                .width(400.dp)
                .background(Palette.SurfaceRaised, RoundedCornerShape(topStart = Radius.dialog, bottomStart = Radius.dialog))
                // Clear of the clock, which sits over the top right of every page.
                .padding(start = 24.dp, end = 24.dp, top = 76.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = AppType.TitleL, color = Palette.OnBackground)
            if (subtitle != null) Text(subtitle, style = AppType.BodyM, color = Palette.Muted)
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 40.dp),
            ) { content(first) }
        }
    }
}

/**
 * Game types for the Categories page: refresh, "All", then each type. Ticking types shows only the categories filed
 * under all of them; "All" clears them.
 */
@Composable
fun CategoryTypeBar(
    tags: List<CategoryTag>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    entry: FocusRequester? = null,
) {
    // In a fixed order: a type that moved when ticked would take the focus with it.
    LazyRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        // Room for a focused pill to grow without being clipped.
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "refresh") { RefreshButton(onRefresh, if (entry != null) Modifier.focusRequester(entry) else Modifier) }
        item(key = "all") { PillButton("All", onClear, selected = selected.isEmpty()) }
        items(tags, key = { it.id }) { tag ->
            PillButton(tag.name, { onToggle(tag.id) }, selected = tag.id in selected)
        }
    }
}
