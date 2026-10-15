package com.hrvrm.app.asi

import kotlin.math.abs

/**
 * The ASI interpretation engine -- spec section 16.C: classifying the five parameters, evaluating
 * the ten interpretive combinations (spec 9), confidence (spec 12), and assembling the final
 * [AsiResult]. Trend is deliberately absent -- removed per the user's explicit instruction.
 */
internal object AsiInterpretation {

    fun build(ctx: AsiContext): AsiResult {
        val parameters = listOf(
            classifyHrvDaily(ctx.today.hrvScore, ctx.zHrv),
            classifyHrv7(ctx.hrv7, ctx.zHrv7, ctx.spanDays),
            classifyCv7(ctx.today.cv7, ctx.cv7P25, ctx.cv7P75, ctx.cv7P95, ctx.spanDays),
            classifyRhrDaily(ctx.today.rhr, ctx.zRhr),
            classifyRhr7(ctx.rhr7, ctx.zRhr7, ctx.spanDays),
        )

        val detected = evaluateCombinations(
            z = ctx.zHrv, z7 = ctx.zHrv7, zr = ctx.zRhr, zr7 = ctx.zRhr7,
            cv7 = ctx.today.cv7, p25 = ctx.cv7P25, p75 = ctx.cv7P75, p95 = ctx.cv7P95,
        )
        val primary = primaryCombination(detected, ctx.status)
        val confidence = computeConfidence(ctx.status, ctx.precedingHrvCount, ctx.precedingRhrCount)
        val exceptions = if (ctx.unusualResponse) listOf(AsiException.UNUSUAL_RESPONSE) else emptyList()

        return AsiResult(
            version = ctx.version,
            score = ctx.score,
            status = ctx.status,
            title = ctx.status.label,
            icon = ctx.status.icon,
            color = ctx.status.color,
            confidence = confidence,
            unusualResponse = ctx.unusualResponse,
            parameters = parameters,
            primaryCombination = primary,
            detectedCombinations = detected,
            interpretation = primary?.comment,
            exceptions = exceptions,
            penalties = ctx.penalties,
            metrics = AsiMetrics(
                hrvScore = ctx.today.hrvScore,
                hrvBaseline7 = ctx.hrv7,
                hrvNormalLow = normalBound(ctx.hrvBaselineMean, ctx.hrvBaselineSd, -1.0),
                hrvNormalHigh = normalBound(ctx.hrvBaselineMean, ctx.hrvBaselineSd, 1.0),
                cv7 = ctx.today.cv7,
                rhrDaily = ctx.today.rhr,
                rhr7 = ctx.rhr7,
            ),
        )
    }

    private fun normalBound(mean: Double?, sd: Double?, sign: Double): Double? =
        if (mean != null && sd != null) mean + sign * sd else null

    // -- Parameter classification (spec 7) --------------------------------------------------------

    private fun classified(key: AsiParameterKey, value: Double, statusName: String, icon: String, color: AsiColor) =
        AsiParameterResult(key, value, statusName, icon, color, available = true)

    private fun unavailable(key: AsiParameterKey, reason: AsiMissingReason) =
        AsiParameterResult(key, null, reason.name, reason.icon, reason.color, available = false)

    /** Spec 7.1. Today's own HRV value is always present by the time this runs (the engine already
     * returns INSUFFICIENT_DATA earlier when it isn't) -- the only failure mode reachable here is a
     * baseline too short to z-score against. */
    private fun classifyHrvDaily(value: Double, z: Double?): AsiParameterResult {
        if (z == null) return unavailable(AsiParameterKey.HRV_DAILY, AsiMissingReason.INSUFFICIENT_HISTORY)
        val s = when {
            z < AsiConfig.HRV_DAILY_LOW_Z -> HrvDailyStatus.LOW
            z <= AsiConfig.HRV_DAILY_HIGH_Z -> HrvDailyStatus.NORMAL
            z <= AsiConfig.HRV_DAILY_UNUSUAL_HIGH_Z -> HrvDailyStatus.HIGH
            else -> HrvDailyStatus.UNUSUAL_HIGH
        }
        return classified(AsiParameterKey.HRV_DAILY, value, s.name, s.icon, s.color)
    }

    /** Spec 7.2. Needs the 7-reading smoothed value and a baseline to compare it to; both come from
     * the same preceding-readings list with the window's own threshold (6) stricter than the
     * baseline's (3), so whenever [value]/[z] exist together they're both available or both aren't. */
    private fun classifyHrv7(value: Double?, z: Double?, spanDays: Int?): AsiParameterResult {
        if (value == null || z == null) return unavailable(AsiParameterKey.HRV7, AsiMissingReason.INSUFFICIENT_HISTORY)
        if (spanDays != null && spanDays > AsiConfig.MAX_RECENT_WINDOW_SPAN_DAYS) {
            return unavailable(AsiParameterKey.HRV7, AsiMissingReason.STALE)
        }
        val s = when {
            z < AsiConfig.HRV7_LOW_Z -> Hrv7Status.LOW
            z <= AsiConfig.HRV7_HIGH_Z -> Hrv7Status.NORMAL
            z <= AsiConfig.HRV7_UNUSUAL_HIGH_Z -> Hrv7Status.HIGH
            else -> Hrv7Status.UNUSUAL_HIGH
        }
        return classified(AsiParameterKey.HRV7, value, s.name, s.icon, s.color)
    }

    /** Spec 7.3. [value] (today's own CV7) can be genuinely absent independent of history length --
     * see [AsiReading.cv7]'s doc comment -- so MISSING and INSUFFICIENT_HISTORY are distinct failure
     * modes here, unlike the other four parameters. */
    private fun classifyCv7(value: Double?, p25: Double?, p75: Double?, p95: Double?, spanDays: Int?): AsiParameterResult {
        if (value == null) return unavailable(AsiParameterKey.CV7, AsiMissingReason.MISSING)
        if (p25 == null || p75 == null || p95 == null) return unavailable(AsiParameterKey.CV7, AsiMissingReason.INSUFFICIENT_HISTORY)
        if (spanDays != null && spanDays > AsiConfig.MAX_RECENT_WINDOW_SPAN_DAYS) {
            return unavailable(AsiParameterKey.CV7, AsiMissingReason.STALE)
        }
        val s = when {
            value < p25 -> Cv7Status.LOW
            value <= p75 -> Cv7Status.NORMAL
            value <= p95 -> Cv7Status.HIGH
            else -> Cv7Status.VERY_HIGH
        }
        return classified(AsiParameterKey.CV7, value, s.name, s.icon, s.color)
    }

    /** Spec 7.4. */
    private fun classifyRhrDaily(value: Double, z: Double?): AsiParameterResult {
        if (z == null) return unavailable(AsiParameterKey.RHR_DAILY, AsiMissingReason.INSUFFICIENT_HISTORY)
        val s = when {
            z < AsiConfig.RHR_DAILY_UNUSUAL_LOW_Z -> RhrDailyStatus.UNUSUAL_LOW
            z < AsiConfig.RHR_DAILY_LOW_Z -> RhrDailyStatus.LOW
            z <= AsiConfig.RHR_DAILY_HIGH_Z -> RhrDailyStatus.NORMAL
            z <= AsiConfig.RHR_DAILY_VERY_HIGH_Z -> RhrDailyStatus.HIGH
            else -> RhrDailyStatus.VERY_HIGH
        }
        return classified(AsiParameterKey.RHR_DAILY, value, s.name, s.icon, s.color)
    }

    /** Spec 7.5. */
    private fun classifyRhr7(value: Double?, z: Double?, spanDays: Int?): AsiParameterResult {
        if (value == null || z == null) return unavailable(AsiParameterKey.RHR7, AsiMissingReason.INSUFFICIENT_HISTORY)
        if (spanDays != null && spanDays > AsiConfig.MAX_RECENT_WINDOW_SPAN_DAYS) {
            return unavailable(AsiParameterKey.RHR7, AsiMissingReason.STALE)
        }
        val s = when {
            z < AsiConfig.RHR7_UNUSUAL_LOW_Z -> Rhr7Status.UNUSUAL_LOW
            z < AsiConfig.RHR7_LOW_Z -> Rhr7Status.LOW
            z <= AsiConfig.RHR7_HIGH_Z -> Rhr7Status.NORMAL
            z <= AsiConfig.RHR7_VERY_HIGH_Z -> Rhr7Status.HIGH
            else -> Rhr7Status.VERY_HIGH
        }
        return classified(AsiParameterKey.RHR7, value, s.name, s.icon, s.color)
    }

    // -- Interpretive combinations (spec 9) --------------------------------------------------------

    /** Spec's priority order when several combinations are detected simultaneously -- only this one
     * is shown as [AsiResult.primaryCombination]/[AsiResult.interpretation]; every detected code is
     * still retained in [AsiResult.detectedCombinations]. */
    private val PRIORITY = listOf(
        AsiCombination.COMBO_01, AsiCombination.COMBO_10, AsiCombination.COMBO_03, AsiCombination.COMBO_09,
        AsiCombination.COMBO_05, AsiCombination.COMBO_04, AsiCombination.COMBO_08, AsiCombination.COMBO_02,
        AsiCombination.COMBO_07, AsiCombination.COMBO_06,
    )

    /** Every combo requires all the z-scores/CV7 values it reads to be non-null -- spec 10.2 rule 9:
     * "non generare combinazioni che dipendono da dati assenti." */
    private fun evaluateCombinations(
        z: Double?, z7: Double?, zr: Double?, zr7: Double?,
        cv7: Double?, p25: Double?, p75: Double?, p95: Double?,
    ): List<AsiCombination> {
        val detected = mutableListOf<AsiCombination>()

        if (z7 != null && zr7 != null && z7 < -0.5 && zr7 > 0.5) {
            detected += AsiCombination.COMBO_01
        }
        if (z != null && z7 != null && zr != null && z < -1 && z7 >= -0.3 && zr <= 0.5) {
            detected += AsiCombination.COMBO_02
        }
        if (z7 != null && cv7 != null && p25 != null && z7 < -0.5 && cv7 < p25) {
            detected += AsiCombination.COMBO_03
        }
        if (zr != null && z != null && z7 != null && zr > 1 && z >= -1 && z7 >= -0.5) {
            detected += AsiCombination.COMBO_04
        }
        if (z7 != null && z != null && zr != null && z7 < -0.5 && abs(z) <= 1 && zr <= 0.5) {
            detected += AsiCombination.COMBO_05
        }
        if (z != null && z7 != null && zr != null && zr7 != null && cv7 != null && p75 != null &&
            abs(z) <= 1 && abs(z7) <= 0.5 && abs(zr) <= 1 && abs(zr7) <= 0.5 && cv7 <= p75
        ) {
            detected += AsiCombination.COMBO_06
        }
        if (z != null && zr != null && z7 != null && zr7 != null &&
            (z > 2 || zr < -2 || z7 > 2 || zr7 < -2)
        ) {
            detected += AsiCombination.COMBO_07
        }
        if (cv7 != null && p95 != null && z7 != null && cv7 > p95 && z7 >= -0.5) {
            detected += AsiCombination.COMBO_08
        }
        if (zr7 != null && z7 != null && zr7 > 1 && z7 >= -0.5) {
            detected += AsiCombination.COMBO_09
        }
        if (z != null && zr != null && z < -1 && zr > 1) {
            detected += AsiCombination.COMBO_10
        }

        return detected
    }

    /** COMBO_06's comment is explicitly suppressed when the main status is FAVORABLE (spec 9's own
     * note under COMBO_06) -- it still stays in [AsiResult.detectedCombinations], just never becomes
     * the shown interpretation in that case. */
    private fun primaryCombination(detected: List<AsiCombination>, status: AsiStatus): AsiCombination? {
        val eligible = if (status == AsiStatus.FAVORABLE) {
            detected.filterNot { it == AsiCombination.COMBO_06 }
        } else {
            detected
        }
        return PRIORITY.firstOrNull { it in eligible }
    }

    // -- Confidence (spec 12) -----------------------------------------------------------------------

    /**
     * Confidence describes only the historical data's statistical representativeness -- never
     * measurement quality, and (per the user's trend-removal decision) never trend either.
     *
     * LOW's "storico utilizzabile per analisi descrittive, ma non per ASI definitivo" is interpreted
     * as: the main status is CALIBRATING (so no definitive ASI), but at least a minimal baseline
     * ([AsiConfig.MIN_BASELINE_SAMPLES] readings) exists for descriptive use; otherwise UNAVAILABLE.
     */
    private fun computeConfidence(status: AsiStatus, precedingHrvCount: Int, precedingRhrCount: Int): AsiConfidenceResult {
        val s = when {
            status == AsiStatus.INSUFFICIENT_DATA -> AsiConfidenceStatus.UNAVAILABLE
            status == AsiStatus.CALIBRATING -> {
                if (precedingHrvCount >= AsiConfig.MIN_BASELINE_SAMPLES && precedingRhrCount >= AsiConfig.MIN_BASELINE_SAMPLES) {
                    AsiConfidenceStatus.LOW
                } else {
                    AsiConfidenceStatus.UNAVAILABLE
                }
            }
            precedingHrvCount >= AsiConfig.CONFIDENCE_HIGH_MIN_READINGS && precedingRhrCount >= AsiConfig.CONFIDENCE_HIGH_MIN_READINGS ->
                AsiConfidenceStatus.HIGH
            else -> AsiConfidenceStatus.MODERATE
        }
        return AsiConfidenceResult(s, s.icon, s.color)
    }

    // -- Synthetic comment (spec 13) -----------------------------------------------------------------

    /** Spec 13's compact format: "ASI {score} — {title}", then the five parameter icons, then the
     * single prioritized interpretation sentence if one was detected. Plain text/icon-name strings --
     * no Compose dependency, so this is testable without a UI layer; the eventual Phase 2 UI maps
     * each icon name to a real glyph. */
    fun buildSummaryText(result: AsiResult): AsiSummaryText {
        val scoreText = result.score?.toString() ?: "—"
        return AsiSummaryText(
            headline = "ASI $scoreText — ${result.title}",
            parametersLine = result.parameters.joinToString(" · ") { "${it.label} [${it.icon}]" },
            interpretation = result.interpretation,
        )
    }
}
