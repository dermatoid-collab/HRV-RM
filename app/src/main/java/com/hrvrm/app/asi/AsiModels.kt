package com.hrvrm.app.asi

/**
 * Output shapes for the ASI (Autonomic Status Index) module -- spec sections 6, 7, 9, 10, 12, 14.
 * Pure data: no calculation lives here (see [AsiEngine]) and no per-parameter classification logic
 * lives here either (see [AsiInterpretation]). Trend is deliberately absent throughout -- removed
 * per the user's explicit instruction, not part of the ported spec's final scope for this app.
 */

/** Semantic color role shared by every status/icon mapping below -- never a raw hex; the eventual
 * UI layer (Phase 2, not yet built) maps each to a theme token, per spec section 6's "Palette semantica". */
enum class AsiColor { SUCCESS, INFO, WARNING, DANGER, MUTED, UNUSUAL }

/**
 * Main ASI status bands -- spec section 6. [CALIBRATING], [INSUFFICIENT_DATA] and [STALE_WINDOW]
 * always carry a null score (checked in that precedence order over [CAUTION]/[CALIBRATING]-type
 * confusion -- see spec 4's "Precedenza degli stati senza punteggio" and 10.3).
 */
enum class AsiStatus(val icon: String, val color: AsiColor, val label: String, val baseComment: String) {
    FAVORABLE("circle-check", AsiColor.SUCCESS, "Regolare", "Profilo autonomico complessivamente regolare."),
    MILD_DEVIATION("info", AsiColor.INFO, "Lievi scostamenti", "Lievi scostamenti dal profilo abituale."),
    CAUTION("triangle-alert", AsiColor.WARNING, "Attenzione", "Scostamenti autonomici che richiedono attenzione."),
    MARKED_DEVIATION("octagon-alert", AsiColor.DANGER, "Scostamenti marcati", "Scostamenti autonomici marcati."),
    CALIBRATING("hourglass", AsiColor.MUTED, "Calibrazione", "Calibrazione in corso: storico insufficiente."),
    INSUFFICIENT_DATA("circle-help", AsiColor.MUTED, "Dati insufficienti", "Dati insufficienti per calcolare ASI."),
    STALE_WINDOW("calendar-clock", AsiColor.MUTED, "Dati non recenti", "Ultime sette letture non sufficientemente recenti."),
}

/** Spec section 7.1. */
enum class HrvDailyStatus(val icon: String, val color: AsiColor) {
    LOW("triangle-alert", AsiColor.WARNING),
    NORMAL("circle-check", AsiColor.SUCCESS),
    HIGH("circle-arrow-up", AsiColor.INFO),
    UNUSUAL_HIGH("activity", AsiColor.UNUSUAL),
}

/** Spec section 7.2. */
enum class Hrv7Status(val icon: String, val color: AsiColor) {
    LOW("triangle-alert", AsiColor.WARNING),
    NORMAL("circle-check", AsiColor.SUCCESS),
    HIGH("circle-arrow-up", AsiColor.INFO),
    UNUSUAL_HIGH("activity", AsiColor.UNUSUAL),
}

/** Spec section 7.3. Classified against this app's own existing ln(RMSSD)-based CV7 field (user-confirmed
 * substitution for the spec's literal raw-RMSSD CV7) and its historical percentiles. Low CV7 is explicitly
 * not automatic favorable recovery, and high CV7 is explicitly not automatic stress (spec's own caveat). */
enum class Cv7Status(val icon: String, val color: AsiColor) {
    LOW("circle-minus", AsiColor.MUTED),
    NORMAL("circle-check", AsiColor.SUCCESS),
    HIGH("info", AsiColor.INFO),
    VERY_HIGH("triangle-alert", AsiColor.WARNING),
}

/** Spec section 7.4. */
enum class RhrDailyStatus(val icon: String, val color: AsiColor) {
    UNUSUAL_LOW("activity", AsiColor.UNUSUAL),
    LOW("circle-arrow-down", AsiColor.INFO),
    NORMAL("circle-check", AsiColor.SUCCESS),
    HIGH("triangle-alert", AsiColor.WARNING),
    VERY_HIGH("octagon-alert", AsiColor.DANGER),
}

/** Spec section 7.5. */
enum class Rhr7Status(val icon: String, val color: AsiColor) {
    UNUSUAL_LOW("activity", AsiColor.UNUSUAL),
    LOW("circle-arrow-down", AsiColor.INFO),
    NORMAL("circle-check", AsiColor.SUCCESS),
    HIGH("triangle-alert", AsiColor.WARNING),
    VERY_HIGH("octagon-alert", AsiColor.DANGER),
}

/**
 * Spec section 10.2 -- shared "why isn't this parameter classified" reasons, generic across all five
 * parameters. None of these ever produce a penalty, never trigger a partial ASI, and are never filled
 * in by imputing a value.
 */
enum class AsiMissingReason(val icon: String, val color: AsiColor) {
    /** Required datum itself is absent (e.g. today's own CV7 field is null). */
    MISSING("circle-help", AsiColor.MUTED),
    /** Historical reference too short to classify against (below this parameter's own minimum). */
    INSUFFICIENT_HISTORY("hourglass", AsiColor.MUTED),
    /** The recent 7-reading window this parameter depends on spans too many calendar days. */
    STALE("calendar-clock", AsiColor.MUTED),
    /** Classification was mathematically impossible (non-finite input). */
    UNAVAILABLE("circle-slash", AsiColor.MUTED),
}

/** Spec section 13/14 -- identifies which of the five parameters a result entry describes, and its
 * compact-view label ("HRV oggi", "HRV7", "CV7", "RHR oggi", "RHR7" per spec 13's format line). */
enum class AsiParameterKey(val label: String) {
    HRV_DAILY("HRV oggi"),
    HRV7("HRV7"),
    CV7("CV7"),
    RHR_DAILY("RHR oggi"),
    RHR7("RHR7"),
}

/**
 * One parameter entry in [AsiResult.parameters] -- spec section 14's `parameters[]`. [statusName] is
 * the enum constant name of whichever per-parameter status enum applied (e.g. "LOW" from [HrvDailyStatus])
 * when [available], or of [AsiMissingReason] when not -- kept as a plain string here so this one shape
 * covers all five parameters without a sealed-class/generics detour.
 */
data class AsiParameterResult(
    val key: AsiParameterKey,
    val value: Double?,
    val statusName: String,
    val icon: String,
    val color: AsiColor,
    val available: Boolean,
) {
    val label: String get() = key.label
}

/** Spec section 9 -- the ten interpretive combinations, each independently evaluated. [comment] is the
 * fixed Italian text from the spec; never programmatically generated or substituted. */
enum class AsiCombination(val icon: String, val comment: String) {
    COMBO_01("trending-down", "HRV7 ridotta e RHR7 elevata: segnali concordanti."),
    COMBO_02("activity", "Riduzione isolata dell'HRV odierna."),
    COMBO_03("circle-minus", "HRV7 ridotta e CV7 basso: possibile soppressione stabile."),
    COMBO_04("heart-pulse", "RHR odierna elevata senza corrispondente riduzione HRV."),
    COMBO_05("trending-up", "HRV odierna nella fascia abituale, ma HRV7 ancora ridotta."),
    COMBO_06("check-check", "Indicatori complessivamente coerenti con lo storico."),
    COMBO_07("activity", "Risposta autonomica insolita: non equivale automaticamente a recupero superiore."),
    COMBO_08("waves", "CV7 molto elevato senza riduzione consolidata di HRV7."),
    COMBO_09("heart-pulse", "RHR7 persistentemente elevata con HRV7 non ridotta."),
    COMBO_10("triangle-alert", "HRV odierna ridotta e RHR odierna elevata."),
}

/** Spec section 10.1 -- informational only; never changes [AsiResult.score] or [AsiResult.status]. */
enum class AsiException(val icon: String, val color: AsiColor) {
    UNUSUAL_RESPONSE("activity", AsiColor.UNUSUAL),
}

/** Spec section 12 -- statistical representativeness of the historical data only. Explicitly not
 * measurement quality, and (per the user's trend-removal decision) has no relationship to trend either. */
enum class AsiConfidenceStatus(val icon: String, val color: AsiColor) {
    HIGH("shield-check", AsiColor.SUCCESS),
    MODERATE("shield", AsiColor.INFO),
    LOW("shield-alert", AsiColor.WARNING),
    UNAVAILABLE("circle-help", AsiColor.MUTED),
}

data class AsiConfidenceResult(
    val status: AsiConfidenceStatus,
    val icon: String,
    val color: AsiColor,
)

/** Spec section 5/14 -- each penalty is null, not zero, when its underlying z-score couldn't be
 * computed at all (as opposed to computing to a genuine zero contribution). */
data class AsiPenalties(
    val hrvDaily: Double?,
    val hrv7: Double?,
    val rhrDaily: Double?,
    val rhr7: Double?,
    val cv7: Double?,
)

/** Spec section 14's `metrics` object -- the raw values behind the scoring, for a details view. */
data class AsiMetrics(
    val hrvScore: Double?,
    val hrvBaseline7: Double?,
    val hrvNormalLow: Double?,
    val hrvNormalHigh: Double?,
    val cv7: Double?,
    val rhrDaily: Double?,
    val rhr7: Double?,
)

/**
 * The full ASI result -- spec section 14's JSON structure, minus the `trend` field (removed per the
 * user's explicit instruction). [score] and [status] are null-score together only for [AsiStatus.CALIBRATING],
 * [AsiStatus.INSUFFICIENT_DATA] and [AsiStatus.STALE_WINDOW] (spec 10.3's precedence order).
 */
data class AsiResult(
    val version: String,
    val score: Int?,
    val status: AsiStatus,
    val title: String,
    val icon: String,
    val color: AsiColor,
    val confidence: AsiConfidenceResult,
    val unusualResponse: Boolean,
    val parameters: List<AsiParameterResult>,
    val primaryCombination: AsiCombination?,
    val detectedCombinations: List<AsiCombination>,
    val interpretation: String?,
    val exceptions: List<AsiException>,
    val penalties: AsiPenalties,
    val metrics: AsiMetrics,
)

/** Spec section 13's compact synthetic view, built as plain text/icon-name strings -- no Compose
 * dependency, so Phase 1 can test it without a UI layer. Icon names are resolved to an actual
 * glyph/mapping only in Phase 2. */
data class AsiSummaryText(
    val headline: String,
    val parametersLine: String,
    val interpretation: String?,
)
