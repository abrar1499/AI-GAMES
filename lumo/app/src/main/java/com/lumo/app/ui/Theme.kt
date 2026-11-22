package com.lumo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One set of neutral colors per system theme; the accent colors are the same in both. */
data class Palette(
    val bg: Color, val surface: Color, val surface2: Color, val stroke: Color, val text: Color, val sub: Color,
)

private val DarkPalette = Palette(
    bg = Color(0xFF07070A), surface = Color(0xFF111117), surface2 = Color(0xFF1A1A22),
    stroke = Color(0xFF24242F), text = Color(0xFFF5F5FA), sub = Color(0xFF8C8C9C),
)
private val LightPalette = Palette(
    bg = Color(0xFFF7F7FA), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFECECF2),
    stroke = Color(0xFFE1E1E9), text = Color(0xFF111118), sub = Color(0xFF6E6E80),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

object C {
    val Bg: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.bg
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface
    val Surface2: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface2
    val Stroke: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.stroke
    val Text: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.text
    val Sub: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.sub
    val Accent = Color(0xFF7C6CFF)
    val Accent2 = Color(0xFF3FA9F5)
    val Red = Color(0xFFFF4D5E)
    val Green = Color(0xFF2BD17E)
    val AccentBrush = Brush.linearGradient(listOf(Accent, Accent2))
}

private val avatarPalettes = listOf(
    listOf(Color(0xFF7C6CFF), Color(0xFF3FA9F5)),
    listOf(Color(0xFFFF7A8A), Color(0xFFFFB86B)),
    listOf(Color(0xFF2BD17E), Color(0xFF1FA2C9)),
    listOf(Color(0xFFB36BFF), Color(0xFFFF6BC1)),
    listOf(Color(0xFFFFB86B), Color(0xFFFF5E62)),
    listOf(Color(0xFF4FACFE), Color(0xFF00F2FE)),
)

fun initials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(1).uppercase()
        else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
    }
}

@Composable
fun Avatar(name: String, key: String, size: Dp = 48.dp) {
    val colors = avatarPalettes[(key.hashCode() and 0x7fffffff) % avatarPalettes.size]
    Box(
        Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials(name), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.38f).sp)
    }
}

/** Follows the phone: light when the system is light, dark when it is dark. Pass dark = true to force dark. */
@Composable
fun LumoTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (dark) DarkPalette else LightPalette
    val base = Typography()
    val type = Typography(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    )
    val scheme = if (dark) darkColorScheme(
        primary = C.Accent, onPrimary = Color.White, secondary = C.Accent2,
        background = palette.bg, onBackground = palette.text, surface = palette.surface, onSurface = palette.text,
        surfaceVariant = palette.surface2, onSurfaceVariant = palette.sub, outline = palette.stroke, error = C.Red,
    ) else lightColorScheme(
        primary = C.Accent, onPrimary = Color.White, secondary = C.Accent2,
        background = palette.bg, onBackground = palette.text, surface = palette.surface, onSurface = palette.text,
        surfaceVariant = palette.surface2, onSurfaceVariant = palette.sub, outline = palette.stroke, error = C.Red,
    )
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
