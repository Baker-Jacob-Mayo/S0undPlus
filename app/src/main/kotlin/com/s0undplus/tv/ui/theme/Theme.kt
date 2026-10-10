package com.s0undplus.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.s0undplus.tv.data.AppTheme

class ThemeColors(
    val brand: Color,
    val brandSoft: Color,
    val background: Color,
    val surface: Color,
    val surfaceFocused: Color,
    val onBackground: Color = Color(0xFFEFEFF1),
    val muted: Color = Color(0xFFADADB8),
    val live: Color = Color(0xFFEB0400),
    val scrim: Color,
)

fun AppTheme.colors(): ThemeColors = when (this) {
    AppTheme.PURPLE -> ThemeColors(
        brand = Color(0xFF9147FF), brandSoft = Color(0xFFB98CFF), background = Color(0xFF0E0E10),
        surface = Color(0xFF18181B), surfaceFocused = Color(0xFF2A2A31), scrim = Color(0xCC0E0E10),
    )
    AppTheme.MIDNIGHT -> ThemeColors(
        brand = Color(0xFF4F8CFF), brandSoft = Color(0xFF9CC0FF), background = Color(0xFF0A1020),
        surface = Color(0xFF121A30), surfaceFocused = Color(0xFF1F2B4A), scrim = Color(0xCC0A1020),
    )
    AppTheme.AMOLED -> ThemeColors(
        brand = Color(0xFF9147FF), brandSoft = Color(0xFFB98CFF), background = Color(0xFF000000),
        surface = Color(0xFF0B0B0D), surfaceFocused = Color(0xFF1C1C21), scrim = Color(0xE6000000),
    )
    AppTheme.CRIMSON -> ThemeColors(
        brand = Color(0xFFE5384F), brandSoft = Color(0xFFFF8A9A), background = Color(0xFF140A0C),
        surface = Color(0xFF211215), surfaceFocused = Color(0xFF3A1D23), scrim = Color(0xCC140A0C),
        live = Color(0xFFFF6A00),
    )
    AppTheme.FOREST -> ThemeColors(
        brand = Color(0xFF2EBD6B), brandSoft = Color(0xFF8FE3B2), background = Color(0xFF0A130E),
        surface = Color(0xFF121F17), surfaceFocused = Color(0xFF1E3326), scrim = Color(0xCC0A130E),
    )
}

/**
 * The app's colors. Every property reads Compose snapshot state, so any composable that uses `Palette.X` recomposes
 * automatically when [apply] switches theme — no need to thread colors through every function.
 */
object Palette {
    private var colors by mutableStateOf(AppTheme.PURPLE.colors())

    fun apply(theme: AppTheme) {
        colors = theme.colors()
    }

    val Brand: Color get() = colors.brand
    val BrandSoft: Color get() = colors.brandSoft
    val Background: Color get() = colors.background
    val Surface: Color get() = colors.surface
    val SurfaceFocused: Color get() = colors.surfaceFocused
    val OnBackground: Color get() = colors.onBackground
    val Muted: Color get() = colors.muted
    val Live: Color get() = colors.live
    val Scrim: Color get() = colors.scrim
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
