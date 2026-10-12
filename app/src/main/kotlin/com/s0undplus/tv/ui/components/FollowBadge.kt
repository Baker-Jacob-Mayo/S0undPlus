package com.s0undplus.tv.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.s0undplus.tv.LocalAppContainer
import com.s0undplus.tv.data.FollowSource
import com.s0undplus.tv.ui.theme.Palette

private val TwitchPurple = Color(0xFF9146FF)

/** Twitch's mark, simplified (a speech bubble with two bars), in a 24-unit square. */
private fun glitchOuter() = Path().apply {
    moveTo(6f, 3f); lineTo(4f, 7f); lineTo(4f, 19f); lineTo(8.5f, 19f); lineTo(8.5f, 22f); lineTo(11f, 22f)
    lineTo(14f, 19f); lineTo(17.5f, 19f); lineTo(21f, 15.5f); lineTo(21f, 3f); close()
}

private fun glitchInner() = Path().apply {
    moveTo(7f, 5f); lineTo(19f, 5f); lineTo(19f, 14.7f); lineTo(16.7f, 17f); lineTo(13.2f, 17f); lineTo(11f, 19.2f)
    lineTo(11f, 17f); lineTo(7f, 17f); close()
}

/**
 * Where a channel is followed, as a small circle: Twitch's mark (followed on the Twitch account), a plus in the theme
 * colour (followed in this app), or the two split across the corner (both). A dark [ring] keeps it readable on any
 * picture. Drawn once per size and theme, never per frame.
 */
@Composable
fun FollowBadge(source: FollowSource, modifier: Modifier = Modifier, ring: Color = Color(0x99000000)) {
    val app = Palette.Brand
    val ink = Palette.OnBrand
    val description = when (source) {
        FollowSource.TWITCH -> "Followed on Twitch"
        FollowSource.APP -> "Followed in S0undPlus"
        FollowSource.BOTH -> "Followed on Twitch and in S0undPlus"
    }
    Box(
        modifier
            .semantics { contentDescription = description }
            .drawWithCache {
                val s = size.minDimension / 24f
                val outer = glitchOuter()
                val inner = glitchInner()
                val disc = Path().apply { addOval(Rect(Offset(12f * s, 12f * s), 10.3f * s)) }
                val corner = Path().apply { moveTo(0f, 0f); lineTo(24f * s, 0f); lineTo(0f, 24f * s); close() }

                fun DrawScope.glitch(tx: Float, ty: Float, k: Float) {
                    withTransform({
                        translate(tx * s, ty * s)
                        scale(k * s, k * s, pivot = Offset.Zero)
                    }) {
                        drawPath(outer, Color.White)
                        drawPath(inner, TwitchPurple)
                        drawRect(Color.White, Offset(11f, 7.5f), Size(1.8f, 5f))
                        drawRect(Color.White, Offset(15.3f, 7.5f), Size(1.8f, 5f))
                    }
                }

                fun DrawScope.plus(cx: Float, cy: Float, arm: Float, width: Float) {
                    drawLine(ink, Offset((cx - arm) * s, cy * s), Offset((cx + arm) * s, cy * s), width * s, StrokeCap.Round)
                    drawLine(ink, Offset(cx * s, (cy - arm) * s), Offset(cx * s, (cy + arm) * s), width * s, StrokeCap.Round)
                }

                onDrawBehind {
                    drawCircle(ring, radius = 12f * s, center = Offset(12f * s, 12f * s))
                    clipPath(disc) {
                        when (source) {
                            FollowSource.TWITCH -> {
                                drawRect(TwitchPurple)
                                glitch(4.1f, 3.6f, 0.66f)
                            }
                            FollowSource.APP -> {
                                drawRect(app)
                                plus(12f, 12f, 4.6f, 2.6f)
                            }
                            FollowSource.BOTH -> {
                                drawRect(app)
                                drawPath(corner, TwitchPurple)
                                glitch(2.2f, 2.4f, 0.4f)
                                plus(16.2f, 16.2f, 2.7f, 2.1f)
                            }
                        }
                    }
                }
            },
    )
}

/** The badge for [login] if the viewer follows that channel anywhere; nothing otherwise. */
@Composable
fun FollowSourceBadge(login: String, modifier: Modifier = Modifier, ring: Color = Color(0x99000000)) {
    val source = LocalAppContainer.current.follows.sourceOf(login) ?: return
    FollowBadge(source, modifier, ring)
}
