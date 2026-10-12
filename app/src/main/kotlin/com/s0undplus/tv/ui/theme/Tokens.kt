package com.s0undplus.tv.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Layout constants for the browse screens. The TV renders at 1080p with density 2, so the screen is 960×540 dp.
 * Every page starts its content at [keyline], whether or not the navigation rail is beside it, so the left edge
 * never jumps as the viewer moves between pages.
 */
object Layout {
    val keyline = 96.dp
    val endMargin = 48.dp
    val railCollapsed = 80.dp
    val railExpanded = 248.dp

    /** Landscape cards (streams, videos, history) everywhere: four across the content width. */
    val cardWidth = 192.dp
    val cardHeight = 108.dp
    val cardGap = 16.dp
    val gridLineGap = 24.dp
    val rowGap = 20.dp

    /** Avatar tiles (offline channels, search results). */
    val avatarTile = 104.dp
    val avatar = 80.dp

    /** The fixed hero at the top of Following; the rows scroll underneath it. */
    val heroHeight = 236.dp
}

object Radius {
    val badge = 4.dp
    val card = 8.dp
    val row = 12.dp
    val dialog = 16.dp
}

object Motion {
    /** Material "emphasized decelerate": focus growing. */
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Material "standard accelerate": focus shrinking. */
    val StandardAccelerate = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    const val FOCUS_IN_MS = 150
    const val FOCUS_OUT_MS = 120

    /**
     * How long focus must rest before the hero follows it: longer than a row's scroll, so the hero's work never lands on
     * the frames of a scroll, and long enough that browsing at a normal pace updates it once per pause, not per press.
     */
    const val HERO_SETTLE_MS = 450L

    /** How long focus must rest on a navigation item before its page opens (OK or Right opens it at once). */
    const val NAV_SETTLE_MS = 300L
}

/**
 * The type scale for the browse screens. The system font (Roboto on Google TV) at weights it ships on every Android
 * version the app supports. `MaterialTheme.typography` is left alone so the player looks exactly as before.
 */
object AppType {
    private val trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    private fun style(size: Int, line: Int, weight: FontWeight, tracking: Float = 0f) =
        TextStyle(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = tracking.sp, lineHeightStyle = trim)

    val Display = style(32, 38, FontWeight.Bold, -0.25f)
    val Headline = style(26, 32, FontWeight.Bold)
    val TitleL = style(20, 26, FontWeight.SemiBold)
    val RowTitle = style(18, 24, FontWeight.SemiBold)
    val BodyL = style(16, 23, FontWeight.Normal, 0.1f)
    val BodyM = style(14, 20, FontWeight.Normal, 0.1f)
    val Button = style(15, 20, FontWeight.Medium, 0.1f)
    val CardTitle = style(15, 20, FontWeight.Medium)
    val CardMeta = style(13, 18, FontWeight.Normal)
    val Overline = style(12, 16, FontWeight.Bold, 1.2f)
    val Badge = style(11, 14, FontWeight.Bold, 0.5f)
    val Wordmark = style(22, 26, FontWeight.Black, -0.5f)
}
