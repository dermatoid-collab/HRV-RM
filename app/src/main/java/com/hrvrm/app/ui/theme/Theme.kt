package com.hrvrm.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Always-dark palette (no light variant): matches a measurement app that's mostly used
// in a dim room first thing in the morning.
//
// Tokens follow the "HRV-RM UI Design Specification" doc. Card() in this project's Material3
// version (BOM 2024.06.00, ~1.2) defaults its container to colorScheme.surface -- confirmed by
// the existing cards already rendering in this file's custom Surface color -- so plain cards
// pick up Surface automatically; code that specifically wants the "elevated" card/graph surface
// (e.g. PpgWaveform's background) reaches for surfaceVariant explicitly.
private val Pink = Color(0xFFFF6F91)
private val OnPink = Color(0xFF3A0E1B)
private val Teal = Color(0xFF42D9CC)
private val OnTeal = Color(0xFF0A2320)
// Distinct from Pink (HRV) and Teal (within-range status) — used for resting heart rate,
// a different vital sign, not another view of HRV.
private val Blue = Color(0xFF64B5E8)
private val OnBlue = Color(0xFF07293F)
private val Background = Color(0xFF120E10)
private val Surface = Color(0xFF211B1E)
private val SurfaceVariant = Color(0xFF241D21)
private val OnSurface = Color(0xFFF5F2F4)
private val OnSurfaceVariant = Color(0xFFB8B0B6)
private val Outline = Color(0xFF383137)
private val OutlineVariant = Color(0xFF40383E)

// Not a Material3 ColorScheme role (no "third" text tier exists there) -- the spec's dimmest
// text tier, for the least important labels. Reference directly, not through MaterialTheme.
val TertiaryText = Color(0xFF817981)

// Chart-specific tokens with no ColorScheme slot of their own; alpha is applied at the call
// site per the spec's per-use ranges (e.g. grid lines vs. a normal-range band fill differ).
val ChartGridLine = Color(0xFF50474E)

// The spec reuses the same pink for "primary action" and "HRV alert / outside range" --
// unlike the previous palette's separate (brighter) error color, there's deliberately no
// distinct red/error hue here. error/onError just mirror primary/onPrimary so the two can
// never drift apart by accident.
private val DarkColors = darkColorScheme(
    primary = Pink,
    onPrimary = OnPink,
    secondary = Teal,
    onSecondary = OnTeal,
    tertiary = Blue,
    onTertiary = OnBlue,
    background = Background,
    onBackground = OnSurface,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    outline = Outline,
    outlineVariant = OutlineVariant,
    error = Pink,
    onError = OnPink,
)

@Composable
fun HrvRmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
