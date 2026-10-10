package com.hrvrm.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * Which colour palette the app renders in. [dark] says whether a palette is a dark or a light
 * one (null = follows the phone). [HRV_RM_LEGACY] is this app's own original hand-tuned dark
 * palette (see [DarkColors]) and is the default, so an existing install's look doesn't change
 * until the user picks something else in Settings.
 */
enum class ThemeMode(val label: String, val dark: Boolean? = null) {
    SYSTEM("System default"),
    LIGHT("Light", false),
    DARK("Dark", true),
    HRV_RM_LEGACY("HRV-RM Legacy", true),

    // Dark palettes
    TOKYO_NIGHT("Tokyo Night", true),
    NORD("Nord", true),
    DRACULA("Dracula", true),
    GRUVBOX_DARK("Gruvbox Dark", true),
    CATPPUCCIN_MOCHA("Catppuccin Mocha", true),
    ONE_DARK("One Dark", true),
    SOLARIZED_DARK("Solarized Dark", true),
    AMOLED("Black (OLED)", true),

    // Light palettes
    SOLARIZED_LIGHT("Solarized Light", false),
    CATPPUCCIN_LATTE("Catppuccin Latte", false),
    GRUVBOX_LIGHT("Gruvbox Light", false),
}

// HRV-RM Legacy palette: this app's original always-dark look (no light variant), kept exactly
// as-is as one selectable ThemeMode rather than folded into the generic Palette machinery below
// -- it predates that machinery and has its own role mapping (e.g. error mirrors primary) that
// the generic one doesn't reproduce.
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
private val LegacyBackground = Color(0xFF120E10)
private val LegacySurface = Color(0xFF211B1E)
private val LegacySurfaceVariant = Color(0xFF241D21)
private val LegacyOnSurface = Color(0xFFF5F2F4)
private val LegacyOnSurfaceVariant = Color(0xFFB8B0B6)
private val LegacyOutline = Color(0xFF383137)
private val LegacyOutlineVariant = Color(0xFF40383E)

// Not a Material3 ColorScheme role (no "third" text tier exists there) -- the spec's dimmest
// text tier, for the least important labels. Reference directly, not through MaterialTheme.
val TertiaryText = Color(0xFF817981)

// Chart-specific tokens with no ColorScheme slot of their own; alpha is applied at the call
// site per the spec's per-use ranges (e.g. grid lines vs. a normal-range band fill differ).
val ChartGridLine = Color(0xFF50474E)

// The normal-range gauge's track is its own slightly-lighter-than-outlineVariant grey in the
// spec, not reused from anywhere else.
val NormalRangeTrack = Color(0xFF4A444A)

// Measuring screen: the progress ring's track is near-black, darker than any surface token,
// and the Cancel button's border is its own mid-grey -- neither reused elsewhere.
val MeasuringRingTrack = Color(0xFF292329)
val CancelButtonBorder = Color(0xFF5B535A)

// HRV/RHR trend-chart status-dot colours (HrvTrendChart, RhrTrendChart): deliberately NOT part
// of any ColorScheme / palette -- a reading's in-range/above/below status is a clinical signal,
// not decoration, so it must read the same regardless of which colour palette is active.
val RangeWithin = Color(0xFF4CAF50)
val RangeAbove = Color(0xFFFFA726)
val RangeBelow = Color(0xFFE53935)

// Card radius is a "Design System Comune" rule shared by all 3 screens, so it lives on the
// theme (applies to every Card/OutlinedCard's default shape) rather than being repeated as a
// per-call override. Shared by every palette, not just the legacy one.
private val AppShapes = Shapes(medium = RoundedCornerShape(16.dp))

// The spec reuses the same pink for "primary action" and "HRV alert / outside range" --
// unlike a palette with a separate (brighter) error color, there's deliberately no distinct
// red/error hue here. error/onError just mirror primary/onPrimary so the two can never drift
// apart by accident.
private val DarkColors = darkColorScheme(
    primary = Pink,
    onPrimary = OnPink,
    secondary = Teal,
    onSecondary = OnTeal,
    tertiary = Blue,
    onTertiary = OnBlue,
    background = LegacyBackground,
    onBackground = LegacyOnSurface,
    surface = LegacySurface,
    onSurface = LegacyOnSurface,
    surfaceVariant = LegacySurfaceVariant,
    onSurfaceVariant = LegacyOnSurfaceVariant,
    outline = LegacyOutline,
    outlineVariant = LegacyOutlineVariant,
    error = Pink,
    onError = OnPink,
)

// Generic LIGHT/DARK fallbacks (ThemeMode.LIGHT/DARK/SYSTEM) -- plain Material3 tonal palettes
// built from this app's own Pink/Teal/Blue brand accents, distinct from the hand-tuned
// HRV_RM_LEGACY look above (different background/surface tones).
private val GenericLightColors = lightColorScheme(
    primary = Pink, onPrimary = Color.White,
    secondary = Teal, onSecondary = OnTeal,
    tertiary = Blue, onTertiary = OnBlue,
    error = Color(0xFFBA1A1A), onError = Color.White,
)
private val GenericDarkColors = darkColorScheme(
    primary = Pink, onPrimary = OnPink,
    secondary = Teal, onSecondary = OnTeal,
    tertiary = Blue, onTertiary = OnBlue,
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
)

/**
 * One named colour palette, as 0xRRGGBB values. The bars and buttons carry white (or
 * near-black, for a light palette) text, so [primary] should be the deeper/more saturated
 * shade of the palette's own accent so that fixed-contrast text stays readable on it.
 */
private class Palette(
    val dark: Boolean,
    val background: Long,
    val surface: Long,
    val surfaceHigh: Long,
    val surfaceHighest: Long,
    val onBackground: Long,
    val onVariant: Long,
    val primary: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,
    val secondary: Long,
    val tertiary: Long,
    val outline: Long,
    val outlineVariant: Long,
    val error: Long,
    val errorContainer: Long,
) {
    private fun c(rgb: Long) = Color(0xFF000000 or rgb)

    fun scheme(): ColorScheme {
        val onAccent = if (dark) c(background) else Color.White
        return if (dark) {
            darkColorScheme(
                primary = c(primary), onPrimary = Color.White,
                primaryContainer = c(primaryContainer), onPrimaryContainer = c(onPrimaryContainer),
                secondary = c(secondary), onSecondary = onAccent,
                secondaryContainer = c(surfaceHigh), onSecondaryContainer = c(onBackground),
                tertiary = c(tertiary), onTertiary = onAccent,
                background = c(background), onBackground = c(onBackground),
                surface = c(surface), onSurface = c(onBackground),
                surfaceVariant = c(surfaceHigh), onSurfaceVariant = c(onVariant),
                surfaceContainerLowest = c(background), surfaceContainerLow = c(surface), surfaceContainer = c(surface),
                surfaceContainerHigh = c(surfaceHigh), surfaceContainerHighest = c(surfaceHighest),
                outline = c(outline), outlineVariant = c(outlineVariant),
                error = c(error), onError = c(background),
                errorContainer = c(errorContainer), onErrorContainer = c(onBackground),
            )
        } else {
            lightColorScheme(
                primary = c(primary), onPrimary = Color.White,
                primaryContainer = c(primaryContainer), onPrimaryContainer = c(onPrimaryContainer),
                secondary = c(secondary), onSecondary = onAccent,
                secondaryContainer = c(surfaceHigh), onSecondaryContainer = c(onBackground),
                tertiary = c(tertiary), onTertiary = onAccent,
                background = c(background), onBackground = c(onBackground),
                surface = c(surface), onSurface = c(onBackground),
                surfaceVariant = c(surfaceHigh), onSurfaceVariant = c(onVariant),
                surfaceContainerLowest = c(background), surfaceContainerLow = c(surface), surfaceContainer = c(surface),
                surfaceContainerHigh = c(surfaceHigh), surfaceContainerHighest = c(surfaceHighest),
                outline = c(outline), outlineVariant = c(outlineVariant),
                error = c(error), onError = Color.White,
                errorContainer = c(errorContainer), onErrorContainer = c(onBackground),
            )
        }
    }

    /** Four colours that say what the palette looks like: page, bar, and the two accents. */
    fun swatches(): List<Color> = listOf(c(background), c(primary), c(secondary), c(tertiary))
}

private val Palettes: Map<ThemeMode, Palette> = mapOf(
    // folke/tokyonight.nvim "night"
    ThemeMode.TOKYO_NIGHT to Palette(
        dark = true, background = 0x1A1B26, surface = 0x1F2335, surfaceHigh = 0x292E42, surfaceHighest = 0x2F3549,
        onBackground = 0xC0CAF5, onVariant = 0xA9B1D6, primary = 0x5A7BD0, primaryContainer = 0x3D59A1, onPrimaryContainer = 0xC0CAF5,
        secondary = 0xBB9AF7, tertiary = 0x7DCFFF, outline = 0x565F89, outlineVariant = 0x3B4261, error = 0xF7768E, errorContainer = 0x51283A,
    ),
    // Arctic Ice Studio's Nord
    ThemeMode.NORD to Palette(
        dark = true, background = 0x2E3440, surface = 0x3B4252, surfaceHigh = 0x434C5E, surfaceHighest = 0x4C566A,
        onBackground = 0xECEFF4, onVariant = 0xD8DEE9, primary = 0x5E81AC, primaryContainer = 0x4C6A8F, onPrimaryContainer = 0xECEFF4,
        secondary = 0x88C0D0, tertiary = 0xA3BE8C, outline = 0x616E88, outlineVariant = 0x4C566A, error = 0xBF616A, errorContainer = 0x5B3438,
    ),
    ThemeMode.DRACULA to Palette(
        dark = true, background = 0x282A36, surface = 0x2F3142, surfaceHigh = 0x44475A, surfaceHighest = 0x51556B,
        onBackground = 0xF8F8F2, onVariant = 0xC9CBE0, primary = 0x7E5FC9, primaryContainer = 0x5B4A99, onPrimaryContainer = 0xF1E9FF,
        secondary = 0xFF79C6, tertiary = 0x8BE9FD, outline = 0x6272A4, outlineVariant = 0x44475A, error = 0xFF5555, errorContainer = 0x5A2A2F,
    ),
    ThemeMode.GRUVBOX_DARK to Palette(
        dark = true, background = 0x282828, surface = 0x32302F, surfaceHigh = 0x3C3836, surfaceHighest = 0x504945,
        onBackground = 0xEBDBB2, onVariant = 0xD5C4A1, primary = 0xC4560A, primaryContainer = 0x8F3F08, onPrimaryContainer = 0xFBE9CF,
        secondary = 0xB8BB26, tertiary = 0x83A598, outline = 0x928374, outlineVariant = 0x504945, error = 0xFB4934, errorContainer = 0x5C2420,
    ),
    ThemeMode.CATPPUCCIN_MOCHA to Palette(
        dark = true, background = 0x1E1E2E, surface = 0x262637, surfaceHigh = 0x313244, surfaceHighest = 0x45475A,
        onBackground = 0xCDD6F4, onVariant = 0xBAC2DE, primary = 0x7B68D9, primaryContainer = 0x5B4DA8, onPrimaryContainer = 0xE0D8FF,
        secondary = 0xF5C2E7, tertiary = 0x89DCEB, outline = 0x6C7086, outlineVariant = 0x45475A, error = 0xF38BA8, errorContainer = 0x5A2D3A,
    ),
    ThemeMode.ONE_DARK to Palette(
        dark = true, background = 0x282C34, surface = 0x2C313A, surfaceHigh = 0x353B45, surfaceHighest = 0x3E4451,
        onBackground = 0xABB2BF, onVariant = 0x9DA5B4, primary = 0x4D78CC, primaryContainer = 0x3A5A9B, onPrimaryContainer = 0xDCE8FF,
        secondary = 0xC678DD, tertiary = 0x56B6C2, outline = 0x5C6370, outlineVariant = 0x3E4451, error = 0xE06C75, errorContainer = 0x58323A,
    ),
    ThemeMode.SOLARIZED_DARK to Palette(
        dark = true, background = 0x002B36, surface = 0x073642, surfaceHigh = 0x0E4350, surfaceHighest = 0x15505E,
        onBackground = 0x93A1A1, onVariant = 0x839496, primary = 0x1F7BBF, primaryContainer = 0x14557F, onPrimaryContainer = 0xD6ECFA,
        secondary = 0x2AA198, tertiary = 0xB58900, outline = 0x586E75, outlineVariant = 0x0E4350, error = 0xDC322F, errorContainer = 0x4A1F1E,
    ),
    // True black for OLED screens, with this app's own pink
    ThemeMode.AMOLED to Palette(
        dark = true, background = 0x000000, surface = 0x0A0A0A, surfaceHigh = 0x161616, surfaceHighest = 0x222222,
        onBackground = 0xE6E1E5, onVariant = 0xCAC4D0, primary = 0xD9426F, primaryContainer = 0x8A1E3A, onPrimaryContainer = 0xFFD9E2,
        secondary = 0x42D9CC, tertiary = 0x64B5E8, outline = 0x6A6A6A, outlineVariant = 0x2E2E2E, error = 0xFF6B6B, errorContainer = 0x5A1E1E,
    ),
    ThemeMode.SOLARIZED_LIGHT to Palette(
        dark = false, background = 0xFDF6E3, surface = 0xF5EEDA, surfaceHigh = 0xEEE8D5, surfaceHighest = 0xE6DFC8,
        onBackground = 0x073642, onVariant = 0x586E75, primary = 0x1F7BBF, primaryContainer = 0xBBDDF3, onPrimaryContainer = 0x073642,
        secondary = 0x2AA198, tertiary = 0xB58900, outline = 0x93A1A1, outlineVariant = 0xD9D2BA, error = 0xDC322F, errorContainer = 0xF8D3CF,
    ),
    ThemeMode.CATPPUCCIN_LATTE to Palette(
        dark = false, background = 0xEFF1F5, surface = 0xE6E9EF, surfaceHigh = 0xDCE0E8, surfaceHighest = 0xCCD0DA,
        onBackground = 0x4C4F69, onVariant = 0x6C6F85, primary = 0x8839EF, primaryContainer = 0xE2CFFB, onPrimaryContainer = 0x2E1A52,
        secondary = 0x1E66F5, tertiary = 0x179299, outline = 0x9CA0B0, outlineVariant = 0xBCC0CC, error = 0xD20F39, errorContainer = 0xF8CCD4,
    ),
    ThemeMode.GRUVBOX_LIGHT to Palette(
        dark = false, background = 0xFBF1C7, surface = 0xF2E5BC, surfaceHigh = 0xEBDBB2, surfaceHighest = 0xD5C4A1,
        onBackground = 0x3C3836, onVariant = 0x504945, primary = 0xAF3A03, primaryContainer = 0xFAD7B5, onPrimaryContainer = 0x5A1F00,
        secondary = 0x79740E, tertiary = 0x427B58, outline = 0x928374, outlineVariant = 0xD5C4A1, error = 0x9D0006, errorContainer = 0xF4C7C3,
    ),
)

/** The colours that sum up [mode], for the little preview swatch next to its name in Settings. */
fun themeSwatches(mode: ThemeMode): List<Color> = when (mode) {
    ThemeMode.HRV_RM_LEGACY -> listOf(LegacyBackground, Pink, Teal, Blue)
    ThemeMode.SYSTEM -> listOf(LegacyBackground, Color(0xFFFAFAFA), Pink, Teal)
    ThemeMode.LIGHT -> listOf(Color(0xFFFFFBFE), Pink, Teal, Blue)
    ThemeMode.DARK -> listOf(Color(0xFF1C1B1F), Pink, Teal, Blue)
    else -> Palettes[mode]?.swatches() ?: listOf(LegacyBackground, Pink, Teal, Blue)
}

@Composable
fun HrvRmTheme(mode: ThemeMode = ThemeMode.HRV_RM_LEGACY, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val isDark = mode.dark ?: systemDark
    val colors = when (mode) {
        ThemeMode.HRV_RM_LEGACY -> DarkColors
        ThemeMode.SYSTEM -> if (systemDark) GenericDarkColors else GenericLightColors
        ThemeMode.LIGHT -> GenericLightColors
        ThemeMode.DARK -> GenericDarkColors
        else -> Palettes[mode]?.scheme() ?: DarkColors
    }

    // Status/nav bar icon colour follows the CHOSEN palette's light/dark-ness, not the
    // phone's own setting -- e.g. picking Catppuccin Latte (light) while the phone itself is
    // in dark mode still needs dark icons, or they'd be invisible on that palette's light bars.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !isDark
            controller.isAppearanceLightNavigationBars = !isDark
        }
    }

    MaterialTheme(colorScheme = colors, shapes = AppShapes, content = content)
}
