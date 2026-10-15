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
    // Ported from the sibling SUNW-RM app's own palette set.
    ROSE_PINE("Rosé Pine", true),
    EVERFOREST("Everforest", true),
    NIGHT_OWL("Night Owl", true),
    PALENIGHT("Palenight", true),
    AYU_MIRAGE("Ayu Mirage", true),
    VINO("Vino", true),
    METEOBLUE("MeteoBlue", true),
    TURCHESE("Turchese", true),
    VIOLA("Viola", true),
    CORALLO("Corallo", true),
    LIME("Lime", true),

    // Light palettes
    SOLARIZED_LIGHT("Solarized Light", false),
    CATPPUCCIN_LATTE("Catppuccin Latte", false),
    GRUVBOX_LIGHT("Gruvbox Light", false),
    // Ported from the sibling SUNW-RM app's own palette set.
    ARDESIA("Ardesia", false),
    SALVIA("Salvia", false),
    DUNA("Duna", false),
    ILMETEO("ilMeteo", false),
    AZZURRO("Azzurro", false),
    GIALLO("Giallo", false),
}

/** Whether [ThemeMode] is picked by hand (see Settings' palette list) or rotates on its own. */
enum class AutoThemeMode(val label: String) {
    OFF("Off"),
    DAILY("Every day"),
    PER_MEASUREMENT("After each measurement"),
}

/**
 * Named looks eligible for [AutoThemeMode]'s random rotation -- excludes SYSTEM/LIGHT/DARK,
 * which are generic fallbacks that follow the phone rather than a distinct look of their own.
 */
val AutoThemePalettes: List<ThemeMode> = ThemeMode.entries.filterNot {
    it == ThemeMode.SYSTEM || it == ThemeMode.LIGHT || it == ThemeMode.DARK
}

/** A random pick from [AutoThemePalettes], never [current] again when more than one choice exists. */
fun randomThemeMode(current: ThemeMode): ThemeMode {
    val choices = AutoThemePalettes.filter { it != current }.ifEmpty { AutoThemePalettes }
    return choices.random()
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

// RhrTrendChart's own normal-range band colour -- fixed, unlike HrvTrendChart's (which stays
// MaterialTheme.colorScheme.secondary, following the active palette) specifically so the two
// trend charts read as visually distinct at a glance regardless of which palette is active,
// rather than only coincidentally differing (secondary vs tertiary) depending on the palette's
// own color choices. A warm rose -- distinct from the Range* trio and every palette's own
// secondary/tertiary hues.
val RhrBandOverlay = Color(0xFFF06292)

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
    // Below: ported from SUNW-RM's own theme set (same background/text/accent sources),
    // with the extra Material3 roles HRV-RM needs (surfaceHigh/Highest, primaryContainer,
    // outlineVariant, errorContainer) derived consistently rather than hand-picked per theme.
    // Rosé Pine (rosepinetheme.com) official stops.
    ThemeMode.ROSE_PINE to Palette(
        dark = true, background = 0x191724, surface = 0x1F1D2E, surfaceHigh = 0x2E2C3E, surfaceHighest = 0x424052,
        onBackground = 0xE0DEF4, onVariant = 0x908CAA, primary = 0xEB6F92, primaryContainer = 0xA2506C, onPrimaryContainer = 0xE0DEF4,
        secondary = 0x9CCFD8, tertiary = 0xF6C177, outline = 0x6E6A86, outlineVariant = 0x4E4B63, error = 0xEB6F92, errorContainer = 0x43293A,
    ),
    ThemeMode.EVERFOREST to Palette(
        dark = true, background = 0x2D353B, surface = 0x343F44, surfaceHigh = 0x414A4C, surfaceHighest = 0x515756,
        onBackground = 0xD3C6AA, onVariant = 0xA7A08B, primary = 0x7FBBB3, primaryContainer = 0x628C89, onPrimaryContainer = 0xD3C6AA,
        secondary = 0xE69875, tertiary = 0xDBBC7F, outline = 0x859289, outlineVariant = 0x65716D, error = 0xE67E80, errorContainer = 0x524449,
    ),
    ThemeMode.NIGHT_OWL to Palette(
        dark = true, background = 0x011627, surface = 0x0B2942, surfaceHigh = 0x1B3750, surfaceHighest = 0x304A60,
        onBackground = 0xD6DEEB, onVariant = 0x8AA3BF, primary = 0x82AAFF, primaryContainer = 0x5576B3, onPrimaryContainer = 0xD6DEEB,
        secondary = 0xF78C6C, tertiary = 0xECC48D, outline = 0x5F7E97, outlineVariant = 0x3D5C75, error = 0xFF5874, errorContainer = 0x342336,
    ),
    ThemeMode.PALENIGHT to Palette(
        dark = true, background = 0x292D3E, surface = 0x32364A, surfaceHigh = 0x3D4257, surfaceHighest = 0x4B5066,
        onBackground = 0xBFC7E8, onVariant = 0x9AA3CC, primary = 0x82AAFF, primaryContainer = 0x637EBB, onPrimaryContainer = 0xBFC7E8,
        secondary = 0xF78C6C, tertiary = 0xFFCB6B, outline = 0x676E95, outlineVariant = 0x525877, error = 0xF07178, errorContainer = 0x513B4A,
    ),
    ThemeMode.AYU_MIRAGE to Palette(
        dark = true, background = 0x1F2430, surface = 0x2A3142, surfaceHigh = 0x373D4C, surfaceHighest = 0x474D59,
        onBackground = 0xCCCAC2, onVariant = 0x9C9A93, primary = 0x73D0FF, primaryContainer = 0x5694B7, onPrimaryContainer = 0xCCCAC2,
        secondary = 0xFFAD66, tertiary = 0xFFD173, outline = 0x707A8C, outlineVariant = 0x545D6E, error = 0xF28779, errorContainer = 0x49383F,
    ),
    ThemeMode.VINO to Palette(
        dark = true, background = 0x2A151B, surface = 0x3A1D25, surfaceHigh = 0x492C33, surfaceHighest = 0x5B3F46,
        onBackground = 0xF1DADA, onVariant = 0xC9A5A8, primary = 0x7A9CE0, primaryContainer = 0x5E6D9B, onPrimaryContainer = 0xF1DADA,
        secondary = 0xF29A5E, tertiary = 0xF6CB7A, outline = 0x8A6469, outlineVariant = 0x6A484E, error = 0xE8665F, errorContainer = 0x502529,
    ),
    ThemeMode.METEOBLUE to Palette(
        dark = true, background = 0x163466, surface = 0x1B3F78, surfaceHigh = 0x2D4E83, surfaceHighest = 0x446290,
        onBackground = 0xFFFFFF, onVariant = 0xBCD0F0, primary = 0x4DA3FF, primaryContainer = 0x3A7CC9, onPrimaryContainer = 0xFFFFFF,
        secondary = 0xFFA94D, tertiary = 0xFFE066, outline = 0x7F9BCB, outlineVariant = 0x5776AA, error = 0xFF6B8A, errorContainer = 0x453F6D,
    ),
    ThemeMode.TURCHESE to Palette(
        dark = true, background = 0x00636C, surface = 0x006F79, surfaceHigh = 0x147B84, surfaceHighest = 0x2E8991,
        onBackground = 0xFFFFFF, onVariant = 0xCFF3F6, primary = 0x38C6D9, primaryContainer = 0x24A3B3, onPrimaryContainer = 0xFFFFFF,
        secondary = 0xFFB74D, tertiary = 0xFFE57F, outline = 0x6FB5BC, outlineVariant = 0x4399A1, error = 0xFF6F91, errorContainer = 0x336573,
    ),
    ThemeMode.VIOLA to Palette(
        dark = true, background = 0x4A1F9F, surface = 0x5527B8, surfaceHigh = 0x6338BE, surfaceHighest = 0x744EC5,
        onBackground = 0xFFFFFF, onVariant = 0xDCCCFF, primary = 0x5CC8FF, primaryContainer = 0x568DDD, onPrimaryContainer = 0xFFFFFF,
        secondary = 0xFF9F5A, tertiary = 0xFFE066, outline = 0x9C86E0, outlineVariant = 0x8060D0, error = 0xFF5FA2, errorContainer = 0x6E2CA0,
    ),
    ThemeMode.CORALLO to Palette(
        dark = true, background = 0x932420, surface = 0xA82A24, surfaceHigh = 0xAF3A34, surfaceHighest = 0xB84E49,
        onBackground = 0xFFF1EF, onVariant = 0xFFCFC9, primary = 0x7FB6FF, primaryContainer = 0x8683B1, onPrimaryContainer = 0xFFF1EF,
        secondary = 0xFFC857, tertiary = 0xFFE066, outline = 0xE08E86, outlineVariant = 0xCA665F, error = 0xFFB3A7, errorContainer = 0xA9413B,
    ),
    ThemeMode.LIME to Palette(
        dark = true, background = 0x0F4F20, surface = 0x14602A, surfaceHigh = 0x266D39, surfaceHighest = 0x3C7D4C,
        onBackground = 0xF0FFE8, onVariant = 0xBDEBB0, primary = 0x4DD0E1, primaryContainer = 0x37A39D, onPrimaryContainer = 0xF0FFE8,
        secondary = 0xFFB74D, tertiary = 0xFFEE58, outline = 0x6DB760, outlineVariant = 0x49944A, error = 0xFF8A80, errorContainer = 0x3F5B33,
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
    // Below: ported from SUNW-RM's own light theme set -- see the dark-set comment above for
    // how the extra Material3 roles are derived.
    ThemeMode.ARDESIA to Palette(
        dark = false, background = 0xE6EBF1, surface = 0xCBD3DD, surfaceHigh = 0xBDC5D0, surfaceHighest = 0xACB4BF,
        onBackground = 0x1F2937, onVariant = 0x475569, primary = 0x2563EB, primaryContainer = 0x6993ED, onPrimaryContainer = 0x1F2937,
        secondary = 0xEA580C, tertiary = 0xCA8A04, outline = 0x8793A3, outlineVariant = 0xA2ADBA, error = 0xDC2626, errorContainer = 0xE4C4C8,
    ),
    ThemeMode.SALVIA to Palette(
        dark = false, background = 0xE3EADD, surface = 0xC9D6C1, surfaceHigh = 0xBCC9B4, surfaceHighest = 0xABB8A4,
        onBackground = 0x22301F, onVariant = 0x425A3C, primary = 0x2F6F8F, primaryContainer = 0x6E9AAA, onPrimaryContainer = 0x22301F,
        secondary = 0xC2571A, tertiary = 0xA8740A, outline = 0x8A9A84, outlineVariant = 0xA3B29C, error = 0xB23A48, errorContainer = 0xD9C7BF,
    ),
    ThemeMode.DUNA to Palette(
        dark = false, background = 0xEFE6D3, surface = 0xD9CBB0, surfaceHigh = 0xCCBFA4, surfaceHighest = 0xBCAF96,
        onBackground = 0x3A2F1E, onVariant = 0x5E4F37, primary = 0x2F6B8A, primaryContainer = 0x7296A4, onPrimaryContainer = 0x3A2F1E,
        secondary = 0xC0561A, tertiary = 0xA97612, outline = 0x9A8B70, outlineVariant = 0xB3A58A, error = 0xB03A2E, errorContainer = 0xE2C4B2,
    ),
    ThemeMode.ILMETEO to Palette(
        dark = false, background = 0xFFE2B8, surface = 0xFFC066, surfaceHigh = 0xEEB25E, surfaceHighest = 0xD9A154,
        onBackground = 0x2B1500, onVariant = 0x5C3A10, primary = 0x1E6FB8, primaryContainer = 0x6D97B8, onPrimaryContainer = 0x2B1500,
        secondary = 0xE65100, tertiary = 0xF57C00, outline = 0xB8864A, outlineVariant = 0xD49D55, error = 0xD32F2F, errorContainer = 0xF6BE9D,
    ),
    ThemeMode.AZZURRO to Palette(
        dark = false, background = 0xDDF1FF, surface = 0x9ED4FF, surfaceHigh = 0x92C6EF, surfaceHighest = 0x83B4DC,
        onBackground = 0x06203D, onVariant = 0x1F4A75, primary = 0x0A6CE0, primaryContainer = 0x549BEB, onPrimaryContainer = 0x06203D,
        secondary = 0xE85D04, tertiary = 0xE09F00, outline = 0x6C9BC8, outlineVariant = 0x80B2DE, error = 0xD6293E, errorContainer = 0xDCC9D8,
    ),
    ThemeMode.GIALLO to Palette(
        dark = false, background = 0xFFF5C2, surface = 0xFFE066, surfaceHigh = 0xEED15E, surfaceHighest = 0xD9BD54,
        onBackground = 0x2A2000, onVariant = 0x5C4A00, primary = 0x1E70C8, primaryContainer = 0x6D9FC6, onPrimaryContainer = 0x2A2000,
        secondary = 0xE65100, tertiary = 0xC77700, outline = 0xB39B2E, outlineVariant = 0xD1B744, error = 0xD32F2F, errorContainer = 0xF6CDA5,
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
