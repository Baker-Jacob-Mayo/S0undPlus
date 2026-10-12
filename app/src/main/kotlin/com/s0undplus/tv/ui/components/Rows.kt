package com.s0undplus.tv.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.s0undplus.tv.ui.theme.AppType
import com.s0undplus.tv.ui.theme.Layout
import com.s0undplus.tv.ui.theme.Palette

/**
 * Scrolls a list so that the focused item sits at a fixed distance ([keylinePx]) from the list's start, the way
 * streaming apps hold the focused card still and slide the content under it. At the ends of a list the scroll is
 * clamped as usual.
 */
@OptIn(ExperimentalFoundationApi::class)
private class KeylineSpec(private val keylinePx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - keylinePx
}

/** Holds the focused item of every scrollable list inside [content] at [keyline] from that list's start. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeylineScrolling(keyline: Dp, content: @Composable () -> Unit) {
    val px = with(LocalDensity.current) { keyline.toPx() }
    val spec = remember(px) { KeylineSpec(px) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** A row heading: the title, then a quieter count (kept as a separate text so the title reads exactly). */
@Composable
fun RowTitle(title: String, count: Int? = null, modifier: Modifier = Modifier) {
    Row(modifier.padding(start = Layout.keyline), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = AppType.RowTitle, color = Palette.OnBackground)
        if (count != null && count > 0) Text("$count", style = AppType.BodyM, color = Palette.TextTertiary)
    }
}

/**
 * A titled horizontal row of cards. The focused card is held at the page keyline and the row slides under it; the
 * next card peeks in at the right edge. Coming back to the row with Up or Down lands on the card last focused in it.
 * [onNearEnd] is called when the viewer gets within a few cards of the end (to load more).
 */
@Composable
fun MediaRow(
    title: String,
    state: LazyListState,
    modifier: Modifier = Modifier,
    count: Int? = null,
    itemSpacing: Dp = Layout.cardGap,
    onNearEnd: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RowTitle(title, count)
        KeylineScrolling(Layout.keyline) {
            LazyRow(
                state = state,
                modifier = Modifier.fillMaxWidth().focusRestorer(),
                contentPadding = PaddingValues(start = Layout.keyline, end = Layout.endMargin, top = 14.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                content = content,
            )
        }
    }
    if (onNearEnd != null) {
        val nearEnd by remember(state) {
            derivedStateOf {
                val info = state.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                info.totalItemsCount > 0 && last >= info.totalItemsCount - ROW_LOAD_MORE_THRESHOLD
            }
        }
        LaunchedEffect(nearEnd) { if (nearEnd) onNearEnd() }
    }
}

private const val ROW_LOAD_MORE_THRESHOLD = 6
