package com.hrvrm.app.ui.asi

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.hrvrm.app.asi.AsiColor
import com.hrvrm.app.asi.AsiConfidenceStatus
import com.hrvrm.app.asi.AsiException

/**
 * Maps each spec icon name (originally a Lucide icon, e.g. "circle-check") to its closest
 * standard Material icon -- the product decision made over adding a Lucide-for-Compose library,
 * since material-icons-extended is already a dependency. Every mapping below is a long-standing,
 * universally-bundled Material icon rather than a newer/rarer glyph, to avoid an exact Lucide
 * shape match at the cost of relying on something obscure.
 */
fun asiIconFor(name: String): ImageVector = when (name) {
    "circle-check" -> Icons.Filled.CheckCircle
    "info" -> Icons.Filled.Info
    "triangle-alert" -> Icons.Filled.Warning
    "octagon-alert" -> Icons.Filled.Error
    "hourglass" -> Icons.Filled.HourglassEmpty
    "circle-help" -> Icons.Filled.Help
    "calendar-clock" -> Icons.Filled.Schedule
    "circle-arrow-up" -> Icons.Filled.KeyboardArrowUp
    "circle-arrow-down" -> Icons.Filled.KeyboardArrowDown
    "activity" -> Icons.Filled.PriorityHigh
    "circle-minus" -> Icons.Filled.RemoveCircle
    "trending-down" -> Icons.Filled.TrendingDown
    "trending-up" -> Icons.Filled.TrendingUp
    "check-check" -> Icons.Filled.DoneAll
    "waves" -> Icons.Filled.ShowChart
    "heart-pulse" -> Icons.Filled.Favorite
    "circle-slash" -> Icons.Filled.Block
    "shield-check" -> Icons.Filled.VerifiedUser
    "shield" -> Icons.Filled.Security
    "shield-alert" -> Icons.Filled.ReportProblem
    else -> Icons.Filled.Help
}

// Fixed clinical-signal colors per the ASI spec's own "Palette semantica" (section 6) -- never
// themed, same convention as Theme.kt's RangeWithin/Above/Below and RhrBandOverlay: a status
// needs to read the same regardless of which color palette is active.
private val AsiSuccess = Color(0xFF16865D)
private val AsiInfo = Color(0xFF3978BD)
private val AsiWarning = Color(0xFFC28A20)
private val AsiDanger = Color(0xFFC44848)
private val AsiMuted = Color(0xFF7B8490)
private val AsiUnusual = Color(0xFF8064BD)

fun AsiColor.toComposeColor(): Color = when (this) {
    AsiColor.SUCCESS -> AsiSuccess
    AsiColor.INFO -> AsiInfo
    AsiColor.WARNING -> AsiWarning
    AsiColor.DANGER -> AsiDanger
    AsiColor.MUTED -> AsiMuted
    AsiColor.UNUSUAL -> AsiUnusual
}

/**
 * Human-readable label for a parameter's [com.hrvrm.app.asi.AsiParameterResult.statusName] --
 * shared across all five parameters since the underlying per-parameter enums (HrvDailyStatus,
 * Cv7Status, etc.) and [com.hrvrm.app.asi.AsiMissingReason] reuse several of the same constant
 * names. English, per the user's 2026-10-15 approval (see CLAUDE.md) -- these per-status words
 * aren't literally given by the spec (unlike [com.hrvrm.app.asi.AsiStatus.label]/`baseComment`,
 * [com.hrvrm.app.asi.AsiCombination.comment] and [com.hrvrm.app.asi.AsiParameterKey.label], which
 * are translated from the spec's own fixed Italian text), so this module's own wording was free to
 * switch to match the rest of this app's English copy. Used both as the visible detail-screen text
 * and as each icon's accessibility content description (spec 15.1's "Mantenere etichette
 * accessibili per tutte le icone").
 */
fun asiParameterStatusLabel(statusName: String): String = when (statusName) {
    "LOW" -> "Low"
    "NORMAL" -> "Normal"
    "HIGH" -> "High"
    "VERY_HIGH" -> "Very high"
    "UNUSUAL_HIGH" -> "Unusually high"
    "UNUSUAL_LOW" -> "Unusually low"
    "MISSING" -> "Missing"
    "INSUFFICIENT_HISTORY" -> "Insufficient history"
    "STALE" -> "Not recent"
    "UNAVAILABLE" -> "Not available"
    else -> statusName
}

/** Spec 10.1: informational only, never shown as a substitute for the main status or score. Reuses
 * COMBO_07's own (translated) comment text verbatim -- same underlying idea ("an unusual reading
 * isn't automatically a good one"), already given by the spec in exactly these words. */
fun asiExceptionLabel(exception: AsiException): String = when (exception) {
    AsiException.UNUSUAL_RESPONSE -> "Unusual autonomic response: not automatically superior recovery."
}

/** Spec 12: describes only the historical data's statistical representativeness. */
fun asiConfidenceLabel(status: AsiConfidenceStatus): String = when (status) {
    AsiConfidenceStatus.HIGH -> "High"
    AsiConfidenceStatus.MODERATE -> "Moderate"
    AsiConfidenceStatus.LOW -> "Low"
    AsiConfidenceStatus.UNAVAILABLE -> "Not available"
}
