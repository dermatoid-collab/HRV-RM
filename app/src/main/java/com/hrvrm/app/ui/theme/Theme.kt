package com.hrvrm.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Always-dark palette (no light variant): matches a measurement app that's mostly used
// in a dim room first thing in the morning.
private val Coral = Color(0xFFFF7A90)
private val OnCoral = Color(0xFF3A0A16)
private val Teal = Color(0xFF45E0CB)
private val OnTeal = Color(0xFF0A2320)
private val Background = Color(0xFF171112)
private val Surface = Color(0xFF241B1D)
private val SurfaceVariant = Color(0xFF2C2224)
private val OnSurface = Color(0xFFF3ECEA)
private val OnSurfaceVariant = Color(0xFFB8A9AC)
private val Outline = Color(0xFF382E31)
private val Error = Color(0xFFFF8A9A)
private val OnError = Color(0xFF3A0A12)

private val DarkColors = darkColorScheme(
    primary = Coral,
    onPrimary = OnCoral,
    secondary = Teal,
    onSecondary = OnTeal,
    background = Background,
    onBackground = OnSurface,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    outline = Outline,
    error = Error,
    onError = OnError,
)

@Composable
fun HrvRmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
