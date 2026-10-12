package com.s0undplus.tv.ui.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.s0undplus.tv.ui.theme.AppType
import com.s0undplus.tv.ui.theme.Layout
import com.s0undplus.tv.ui.theme.Motion
import com.s0undplus.tv.ui.theme.Palette
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Home's destinations, in the order the rail lists them (Settings sits apart at the bottom). */
enum class HomeTab(val label: String, val icon: ImageVector) {
    Search("Search", Icons.Rounded.Search),
    Following("Following", Icons.Rounded.FavoriteBorder),
    Live("Live", Icons.Rounded.LiveTv),
    Categories("Categories", Icons.Rounded.GridView),
    History("History", Icons.Rounded.History),
    Settings("Settings", Icons.Rounded.Settings),
}

private const val ITEM_HEIGHT = 44
private const val ITEM_PITCH = 48
private const val FIRST_ITEM_Y = 120

/**
 * The navigation: a column of icons down the left edge that opens out, with labels, over the content while it has
 * focus. Resting on an item for a moment opens that page behind it ([onSettle]); OK or Right opens it at once and
 * moves into it ([onEnter]). Focus arriving from the content always lands on the [selected] item, never on whichever
 * icon happens to be level with the card it left.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NavRail(
    selected: HomeTab,
    requesters: Map<HomeTab, FocusRequester>,
    onSettle: (HomeTab) -> Unit,
    onEnter: (HomeTab) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onItemFocused: () -> Unit,
    account: String,
    liveCount: Int,
    updateDot: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var focusedTab by remember { mutableStateOf<HomeTab?>(null) }
    val open by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(if (expanded) 200 else 160, easing = if (expanded) Motion.EmphasizedDecelerate else Motion.StandardAccelerate),
        label = "rail",
    )
    val scope = rememberCoroutineScope()
    var settleJob by remember { mutableStateOf<Job?>(null) }

    Box(
        modifier
            .fillMaxHeight()
            // Laid out at its closed width; open, it draws past that edge. Focus search measures the rail by this box:
            // as wide as the open rail, it would reach past narrow cards in the first column (category posters), and
            // Left from those would find nothing to its left.
            .width(Layout.railCollapsed)
            // The content to the right dims while the rail is open; when closed, a narrow fade keeps the icons
            // readable over pictures sliding underneath.
            .drawBehind {
                val bg = Palette.Background
                val closedWidth = 120.dp.toPx()
                val closed = Brush.horizontalGradient(0f to bg.copy(alpha = 0.92f), 0.45f to bg.copy(alpha = 0.75f), 1f to Color.Transparent, endX = closedWidth)
                drawRect(closed, size = size.copy(width = closedWidth))
                if (open > 0f) {
                    val opened = Brush.horizontalGradient(
                        0f to bg.copy(alpha = 0.97f * open),
                        0.48f to bg.copy(alpha = 0.97f * open),
                        1f to Color.Transparent,
                        endX = 520.dp.toPx(),
                    )
                    drawRect(opened, size = size.copy(width = 520.dp.toPx()))
                }
            }
            .onFocusChanged {
                expanded = it.hasFocus
                onFocusChanged(it.hasFocus)
                if (!it.hasFocus) {
                    settleJob?.cancel()
                    focusedTab = null
                }
            }
            .focusProperties { enter = { requesters.getValue(selected) } }
            .focusGroup(),
    ) {
        Brand(open, account)
        val main = HomeTab.entries.filter { it != HomeTab.Settings }
        for ((i, tab) in HomeTab.entries.withIndex()) {
            val y = if (tab == HomeTab.Settings) 540 - 28 - ITEM_HEIGHT else FIRST_ITEM_Y + main.indexOf(tab) * ITEM_PITCH
            RailItem(
                tab = tab,
                selected = tab == selected,
                focused = focusedTab == tab,
                open = open,
                badge = if (tab == HomeTab.Following && liveCount > 0) (if (liveCount > 9) "9+" else "$liveCount") else null,
                dot = tab == HomeTab.Settings && updateDot,
                modifier = Modifier
                    .offset(x = 16.dp, y = y.dp)
                    .focusRequester(requesters.getValue(tab))
                    .focusProperties {
                        // Up and Down stay in the rail (the open rail overlaps the cards, which would otherwise win).
                        up = HomeTab.entries.getOrNull(i - 1)?.let { requesters.getValue(it) } ?: FocusRequester.Cancel
                        down = HomeTab.entries.getOrNull(i + 1)?.let { requesters.getValue(it) } ?: FocusRequester.Cancel
                        left = FocusRequester.Cancel
                    }
                    .onFocusChanged { state ->
                        if (state.isFocused) {
                            focusedTab = tab
                            onItemFocused()
                            settleJob?.cancel()
                            if (tab != selected) {
                                settleJob = scope.launch {
                                    delay(Motion.NAV_SETTLE_MS)
                                    onSettle(tab)
                                }
                            }
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT) {
                            settleJob?.cancel()
                            onEnter(tab)
                            true
                        } else {
                            false
                        }
                    }
                    // The item draws its own focused pill; the default overlay would only cover its closed width.
                    .clickable(interactionSource = null, indication = null) {
                        settleJob?.cancel()
                        onEnter(tab)
                    },
            )
        }
    }
}

/** "S+" while closed, the full wordmark and the signed-in account while open. Both stay composed (only faded). */
@Composable
private fun BoxScope.Brand(open: Float, account: String) {
    Text(
        "S+",
        style = AppType.Wordmark,
        color = Palette.Brand,
        modifier = Modifier.offset(x = 26.dp, y = 30.dp).graphicsLayer { alpha = 1f - open },
    )
    Column(Modifier.offset(x = 28.dp, y = 30.dp).wrapContentWidth(Alignment.Start, unbounded = true).graphicsLayer { alpha = open }) {
        Text("S0und+", style = AppType.Wordmark, color = Palette.Brand)
        Text(account, style = AppType.CardMeta, color = Palette.TextTertiary, maxLines = 1)
    }
}

@Composable
private fun RailItem(
    tab: HomeTab,
    selected: Boolean,
    focused: Boolean,
    open: Float,
    badge: String?,
    dot: Boolean,
    modifier: Modifier,
) {
    val content = when {
        focused -> Palette.OnBrand
        selected -> Palette.OnBackground
        else -> Palette.Muted
    }
    Box(
        modifier
            .semantics { this.selected = selected }
            .height(ITEM_HEIGHT.dp)
            .wrapContentWidth(Alignment.Start, unbounded = true)
            .width((48 + (216 - 48) * open).dp)
            .background(if (focused) Palette.Brand else Color.Transparent, RoundedCornerShape(22.dp)),
    ) {
        if (selected && !focused) {
            Box(Modifier.align(Alignment.CenterStart).width(3.dp).height(20.dp).background(Palette.Brand, RoundedCornerShape(2.dp)))
        }
        Box(Modifier.align(Alignment.CenterStart).padding(start = 12.dp)) {
            Icon(tab.icon, contentDescription = null, tint = content, modifier = Modifier.size(24.dp))
            if (badge != null) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-6).dp).height(16.dp).background(Palette.Live, CircleShape).padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(badge, style = AppType.Badge, color = Color.White) }
            }
            if (dot) Box(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-2).dp).size(8.dp).background(Palette.Brand, CircleShape))
        }
        Text(
            tab.label,
            style = AppType.Button,
            color = content,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 56.dp)
                .graphicsLayer { alpha = ((open - 0.4f) / 0.6f).coerceIn(0f, 1f) },
        )
    }
}
