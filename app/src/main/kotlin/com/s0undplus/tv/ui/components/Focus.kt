package com.s0undplus.tv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.s0undplus.tv.ui.theme.Motion
import com.s0undplus.tv.ui.theme.Palette
import com.s0undplus.tv.ui.theme.Radius

/**
 * The player's clickable building block: focusable with the remote, scales up and gets a brand-colored ring when
 * focused, and activates on the center/enter button (or [onLongClick] on a long press). [content] receives the
 * current focus state. The browse screens use [MediaCard], [ControlSurface] and [ListRowSurface] instead.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    container: Color = Palette.Surface,
    focusedContainer: Color = Palette.SurfaceFocused,
    focusedScale: Float = 1.05f,
    onFocused: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, label = "focusScale")
    Box(
        modifier
            .onFocusChanged { state ->
                focused = state.isFocused
                if (state.isFocused) onFocused?.invoke()
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(if (focused) focusedContainer else container)
            .border(3.dp, if (focused) Palette.Brand else Color.Transparent, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        content(focused)
    }
}

/** Focus state, OK and long-press for every browse control: one place, so they all behave the same on the remote. */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.tvClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onFocused: (() -> Unit)?,
    onFocusState: (Boolean) -> Unit,
): Modifier = this
    .onFocusChanged { state ->
        onFocusState(state.isFocused)
        if (state.isFocused) onFocused?.invoke()
    }
    .combinedClickable(onClick = onClick, onLongClick = onLongClick)

/** The focus scale, animated in and out at different speeds; read it only inside `graphicsLayer {}`. */
@Composable
private fun focusScale(focused: Boolean, target: Float): () -> Float {
    val scale by animateFloatAsState(
        targetValue = if (focused) target else 1f,
        animationSpec = if (focused) {
            tween(Motion.FOCUS_IN_MS, easing = Motion.EmphasizedDecelerate)
        } else {
            tween(Motion.FOCUS_OUT_MS, easing = Motion.StandardAccelerate)
        },
        label = "focusScale",
    )
    return { scale }
}

/**
 * A white ring drawn [gap] outside the element, so a dark band separates it from the artwork and it stays visible on
 * white or brand-coloured thumbnails. [cornerRadius] is the element's own; the ring's follows it outwards.
 */
fun Modifier.focusRing(
    visible: Boolean,
    cornerRadius: Dp,
    width: Dp = 3.dp,
    gap: Dp = 2.dp,
    circle: Boolean = false,
    scale: () -> Float = { 1f },
): Modifier =
    if (!visible) this else drawWithContent {
        drawContent()
        val s = scale()
        val stroke = width.toPx()
        val inset = gap.toPx() + stroke / 2
        val w = size.width * s
        val h = size.height * s
        val radius = if (circle) minOf(w, h) / 2 + inset else cornerRadius.toPx() * s + inset
        drawRoundRect(
            color = Palette.FocusRing,
            topLeft = Offset((size.width - w) / 2 - inset, (size.height - h) / 2 - inset),
            size = Size(w + inset * 2, h + inset * 2),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(stroke),
        )
    }

/**
 * Artwork you watch (stream, video, history, category and channel tiles): only the picture grows on focus and gets
 * the white ring; the text under it stays put and just brightens. [image] is drawn clipped to [shape] on a
 * placeholder fill; [text] goes underneath.
 */
@Composable
fun MediaCard(
    onClick: () -> Unit,
    imageModifier: Modifier,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radius.card),
    ringRadius: Dp = Radius.card,
    circle: Boolean = false,
    focusedScale: Float = 1.08f,
    onFocused: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    image: @Composable BoxScope.(focused: Boolean) -> Unit,
    text: @Composable ColumnScope.(focused: Boolean) -> Unit = {},
    /** Drawn over the picture but outside its clip, and grown with it (a badge sitting on an avatar's edge). */
    overlay: (@Composable BoxScope.() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val scale = focusScale(focused, focusedScale)
    Column(
        modifier.tvClickable(onClick, onLongClick, onFocused) { focused = it },
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (overlay == null) {
            Box(
                imageModifier
                    // The ring is drawn outside the picture's layer (so it isn't clipped), grown by the same scale.
                    .focusRing(focused, ringRadius, circle = circle, scale = scale)
                    // One layer does both the focus scale and the rounded clip.
                    .graphicsLayer {
                        scaleX = scale()
                        scaleY = scale()
                        this.shape = shape
                        clip = true
                    }
                    .background(Palette.Surface),
            ) { image(focused) }
        } else {
            // The scale on an outer layer, the clip on the picture alone, so the overlay can reach past its edge.
            Box(
                imageModifier
                    .focusRing(focused, ringRadius, circle = circle, scale = scale)
                    .graphicsLayer {
                        scaleX = scale()
                        scaleY = scale()
                    },
            ) {
                Box(Modifier.matchParentSize().clip(shape).background(Palette.Surface)) { image(focused) }
                overlay()
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { text(focused) }
    }
}

/**
 * Things you press (buttons, chips, navigation items): they fill with the brand colour when focused, the same
 * language as the player's round buttons. [content] gets the focus state to pick [Palette.OnBrand] for its text.
 */
@Composable
fun ControlSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(50),
    container: Color = Palette.SurfaceFocused,
    focusedScale: Float = 1.05f,
    onFocused: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale = focusScale(focused, focusedScale)
    Box(
        modifier
            .tvClickable(onClick, onLongClick, onFocused) { focused = it }
            .graphicsLayer {
                scaleX = scale()
                scaleY = scale()
            }
            .background(if (focused) Palette.Brand else container, shape),
        contentAlignment = Alignment.Center,
    ) { content(focused) }
}

/**
 * Rows in lists (Settings, dialogs): transparent until focused, then white with dark text, which reads at a glance
 * from across the room and never wobbles the way a scaled full-width row does.
 */
@Composable
fun ListRowSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = Color.Transparent,
    onFocused: (() -> Unit)? = null,
    content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .tvClickable(onClick, null, onFocused) { focused = it }
            .background(if (focused) Palette.FocusRing else container, RoundedCornerShape(Radius.row)),
        verticalAlignment = Alignment.CenterVertically,
    ) { content(focused) }
}
