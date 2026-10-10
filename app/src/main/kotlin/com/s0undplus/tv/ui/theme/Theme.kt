package com.s0undplus.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object Palette {
    val Brand = Color(0xFF9147FF)
    val BrandSoft = Color(0xFFB98CFF)
    val Background = Color(0xFF0E0E10)
    val Surface = Color(0xFF18181B)
    val SurfaceFocused = Color(0xFF2A2A31)
    val OnBackground = Color(0xFFEFEFF1)
    val Muted = Color(0xFFADADB8)
    val Live = Color(0xFFEB0400)
    val Scrim = Color(0xCC0E0E10)
}

@Composable
fun S0undPlusTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Brand,
            onPrimary = Color.White,
            background = Palette.Background,
            onBackground = Palette.OnBackground,
            surface = Palette.Surface,
            onSurface = Palette.OnBackground,
            surfaceVariant = Palette.SurfaceFocused,
            onSurfaceVariant = Palette.Muted,
        ),
        content = content,
    )
}
