package com.bizzle.cuberemote.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.bizzle.cube.core.ThemeSpec

val NeonGreen = Color(0xFF39FF14)

data class BizzlePalette(val accent: Color, val bg: Color, val surface: Color, val text: Color, val name: String)

val LocalPalette = staticCompositionLocalOf { BizzlePalette(NeonGreen, Color.Black, Color(0xFF0F140F), Color(0xFFB8FFA8), "Neon v4.20") }

fun ThemeSpec.palette() = BizzlePalette(Color(primary), Color(background), Color(surface), Color(onSurface), name)

@Composable
fun BizzleTheme(spec: ThemeSpec, content: @Composable () -> Unit) {
    val p = spec.palette()
    val onAccent = if (ThemeSpec.contrast(spec.primary, 0xFF000000) > ThemeSpec.contrast(spec.primary, 0xFFFFFFFF)) Color.Black else Color.White
    val scheme = darkColorScheme(
        primary = p.accent, onPrimary = onAccent, secondary = p.accent, onSecondary = onAccent,
        background = p.bg, onBackground = p.text, surface = p.surface, onSurface = p.text,
        surfaceVariant = p.surface, onSurfaceVariant = p.text, outline = p.accent.copy(alpha = 0.6f),
        primaryContainer = p.surface, onPrimaryContainer = p.accent, error = Color(0xFFFF5370), onError = Color.Black,
        surfaceContainer = p.surface, surfaceContainerHigh = p.surface, surfaceContainerHighest = p.surface, surfaceContainerLow = p.bg
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
