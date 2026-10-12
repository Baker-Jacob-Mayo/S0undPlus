package com.s0undplus.tv.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.s0undplus.tv.ui.theme.AppType
import com.s0undplus.tv.ui.theme.Palette
import com.s0undplus.tv.ui.theme.Radius

@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "angle",
    )
    Canvas(modifier.size(size).graphicsLayer { rotationZ = angle }) {
        val stroke = this.size.minDimension / 8f
        drawArc(
            color = Palette.Brand,
            startAngle = 0f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier.fillMaxSize()) {
    Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Spinner()
    }
}

/** A centred message (empty list, error), optionally with an icon above it and a button below it. */
@Composable
fun MessageState(
    message: String,
    modifier: Modifier = Modifier.fillMaxSize(),
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    icon: ImageVector? = null,
) {
    Column(
        modifier.padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = Palette.TextTertiary, modifier = Modifier.size(44.dp))
        Text(
            message,
            style = AppType.BodyL,
            color = Palette.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 560.dp),
        )
        if (actionLabel != null && onAction != null) PillButton(actionLabel, onAction)
    }
}

/**
 * A button: a rounded pill that fills with the brand colour when focused, like every other control. [selected]
 * marks a toggle that is on (a favourite, the chosen tab).
 */
@Composable
fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onFocused: (() -> Unit)? = null,
    icon: ImageVector? = null,
) {
    ControlSurface(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        container = if (selected) Palette.Brand.copy(alpha = 0.3f) else Palette.SurfaceFocused,
        onFocused = onFocused,
    ) { focused ->
        val color = when {
            focused -> Palette.OnBrand
            selected -> Palette.BrandSoft
            else -> Palette.OnBackground
        }
        Row(
            Modifier.padding(start = if (icon != null) 16.dp else 20.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Text(label, style = AppType.Button, color = color, maxLines = 1)
        }
    }
}

/** A grey placeholder block in the shape of the content that is still loading. Static: no shimmer, no pulse. */
@Composable
fun SkeletonBlock(modifier: Modifier, radius: Dp = Radius.card) {
    Box(modifier.background(Palette.Surface, RoundedCornerShape(radius)))
}

/** A round button showing only [icon]; its [label] appears beside it while it has focus. */
@Composable
fun RoundIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Box(contentAlignment = Alignment.CenterStart) {
        ControlSurface(
            onClick = onClick,
            modifier = modifier.size(40.dp).onFocusChanged { focused = it.isFocused },
            shape = CircleShape,
        ) { f ->
            Icon(icon, contentDescription = label, tint = if (f) Palette.OnBrand else Palette.OnBackground, modifier = Modifier.size(20.dp))
        }
        if (focused) {
            Text(
                label,
                style = AppType.Button,
                color = Palette.OnBackground,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.offset(x = 52.dp).wrapContentWidth(unbounded = true),
            )
        }
    }
}
