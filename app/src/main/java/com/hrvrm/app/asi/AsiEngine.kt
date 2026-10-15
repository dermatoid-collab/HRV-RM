package com.hrvrm.app.asi

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One day's inputs to the ASI engine, already derived from this app's own measurement history --
 * spec section 16.A's "Data preparation" responsibility, as far as the ASI module is concerned
 * (reading the already-stored [com.hrvrm.app.data.MeasurementEntity] history is the caller's job).
 */
data class AsiReading(
    val timestampEpochMs: Long,
    /** HRV_Score per spec 2.1 -- same value and formula as [com.hrvrm.app.hrv.HrvScoreResult.altiniScaleValue]
     * (2 x ln(RMSSD)). ASI recomputes its own baseline from a list of these rather than borrowing
     * [com.hrvrm.app.hrv.HrvScoreCalculator]'s own result object, per spec 0.5 ("modulo indipendente"). */
    val hrvScore: Double,
    /** RHR Daily per spec 2.6 -- this app's resting-HR proxy, [com.hrvrm.app.data.MeasurementEntity.meanHrBpm]. */
    val rhr: Double,
    /**
     * CV7, taken directly from this app's own existing field ([com.hrvrm.app.data.MeasurementEntity.cv7]) --
     * ln(RMSSD)-based, not the spec's literal raw-RMSSD CV7 (spec 2.5). User-confirmed substitution:
     * this app already ships and documents an ln(RMSSD)-based CV7 everywhere else (charts, history,
     * Glossary), so ASI reuses that field as-is instead of computing a second, parallel raw-RMSSD CV7.
     * Null until the app's own 7-reading window has at least
     * [com.hrvrm.app.hrv.HrvScoreCalculator.MIN_BASELINE_SAMPLES] samples -- a real, possible "missing"
     * state (see [AsiMissingReason.MISSING]), not a bug.
     */
    val cv7: Double?,
)

data class AsiInput(
    val today: AsiReading,
    /** Prior readings, most-recent-first, already capped by the caller -- same convention as
     * MeasurementRepository's own `dao.getPriorMeasurements(timestamp, BASELINE_WINDOW_SIZE)` call. */
    val precedingNewestFirst: List<AsiReading>,
)

private data class Baseline(val mean: Double, val sd: Double)

/**
 * Everything [AsiInterpretation] needs, once the main score/status and every z-score/penalty this
 * engine can compute are known. Every field the spec treats as "may be unavailable" is nullable here
 * too -- an unavailable field is never defaulted or imputed (spec 0.8, 10.2 rule 10), it is carried
 * through as null so the interpretation layer can classify it as MISSING/INSUFFICIENT_HISTORY/etc.
 */
internal data class AsiContext(
    val version: String,
    val today: AsiReading,
    val status: AsiStatus,
    val score: Int?,
    val zHrv: Double?,
    val zHrv7: Double?,
    val zRhr: Double?,
    val zRhr7: Double?,
    val hrv7: Double?,
    val rhr7: Double?,
    val hrvBaselineMean: Double?,
    val hrvBaselineSd: Double?,
    val cv7P25: Double?,
    val cv7P75: Double?,
    val cv7P95: Double?,
    val precedingHrvCount: Int,
    val precedingRhrCount: Int,
    val spanDays: Int?,
    val penalties: AsiPenalties,
    val unusualResponse: Boolean,
)

/**
 * The ASI calculation engine -- spec section 16.B: z-scores, penalties, the 0-100 score, main status
 * classification and the single always-on "unusual response" check (spec 10.1). Parameter-level
 * classification, the ten interpretive combinations, confidence and the synthetic comment are
 * [AsiInterpretation]'s job (spec 16.C) -- this object only produces the numbers that layer classifies.
 *
 * Deliberate simplification (user-confirmed): every z-score below -- daily and 7-reading-smoothed
 * alike -- is computed against the SAME up-to-[AsiConfig.BASELINE_WINDOW_SIZE]-reading baseline
 * mean/SD per metric (HRV, RHR), rather than the spec's separate non-overlapping reference-window
 * statistics for the smoothed variant (spec 3.2/3.4). Fewer moving parts, one baseline per metric.
 */
object AsiEngine {

    fun compute(input: AsiInput, version: String = AsiConfig.VERSION): AsiResult {
        val today = input.today
        val preceding = input.precedingNewestFirst

        // Precedence 1 (spec 4): INSUFFICIENT_DATA -- today's own required inputs missing or non-finite.
        if (!today.hrvScore.isFinite() || !today.rhr.isFinite()) {
            val ctx = AsiContext(
                version = version,
                today = today,
                status = AsiStatus.INSUFFICIENT_DATA,
                score = null,
                zHrv = null, zHrv7 = null, zRhr = null, zRhr7 = null,
                hrv7 = null, rhr7 = null,
                hrvBaselineMean = null, hrvBaselineSd = null,
                cv7P25 = null, cv7P75 = null, cv7P95 = null,
                precedingHrvCount = 0, precedingRhrCount = 0,
                spanDays = null,
                penalties = AsiPenalties(null, null, null, null, null),
                unusualResponse = false,
            )
            return AsiInterpretation.build(ctx)
        }

        val precedingHrv = preceding.map { it.hrvScore }.filter { it.isFinite() }
        val precedingRhr = preceding.map { it.rhr }.filter { it.isFinite() }
        val historicalCv7 = preceding.mapNotNull { it.cv7 }.filter { it.isFinite() }

        val hrvBaseline = baselineOf(precedingHrv, AsiConfig.HRV_SD_FLOOR)
        val rhrBaseline = baselineOf(precedingRhr, AsiConfig.RHR_SD_FLOOR)

        val recentWindow = listOf(today) + preceding.take(AsiConfig.SMOOTHING_WINDOW_SIZE - 1)
        val windowComplete = recentWindow.size >= AsiConfig.SMOOTHING_WINDOW_SIZE
        val hrv7 = if (windowComplete) recentWindow.map { it.hrvScore }.average() else null
        val rhr7 = if (windowComplete) recentWindow.map { it.rhr }.average() else null
        val spanDays = if (windowComplete) windowSpanDays(recentWindow.map { it.timestampEpochMs }) else null

        val cv7Sorted = historicalCv7.sorted()
        val cv7P25 = if (cv7Sorted.size >= AsiConfig.MIN_BASELINE_SAMPLES) percentile(cv7Sorted, 0.25) else null
        val cv7P75 = if (cv7Sorted.size >= AsiConfig.MIN_BASELINE_SAMPLES) percentile(cv7Sorted, 0.75) else null
        val cv7P95 = if (cv7Sorted.size >= AsiConfig.MIN_BASELINE_SAMPLES) percentile(cv7Sorted, 0.95) else null

        val zHrv = hrvBaseline?.let { (today.hrvScore - it.mean) / it.sd }
        val zHrv7 = if (hrvBaseline != null && hrv7 != null) (hrv7 - hrvBaseline.mean) / hrvBaseline.sd else null
        val zRhr = rhrBaseline?.let { (today.rhr - it.mean) / it.sd }
        val zRhr7 = if (rhrBaseline != null && rhr7 != null) (rhr7 - rhrBaseline.mean) / rhrBaseline.sd else null

        // -- Penalties (spec 5) -- computed whenever their underlying z-score is available, independent
        // of whether the overall score ends up null, so parameter-level classification can still use them.
        val pHrv = zHrv?.let { penalty(-it, AsiConfig.HRV_DAILY_PENALTY_START, AsiConfig.HRV_DAILY_PENALTY_FULL, AsiConfig.HRV_DAILY_PENALTY_MAX) }
        val pHrv7 = zHrv7?.let { penalty(-it, AsiConfig.HRV7_PENALTY_START, AsiConfig.HRV7_PENALTY_FULL, AsiConfig.HRV7_PENALTY_MAX) }
        val pRhr = zRhr?.let { penalty(it, AsiConfig.RHR_DAILY_PENALTY_START, AsiConfig.RHR_DAILY_PENALTY_FULL, AsiConfig.RHR_DAILY_PENALTY_MAX) }
        val pRhr7 = zRhr7?.let { penalty(it, AsiConfig.RHR7_PENALTY_START, AsiConfig.RHR7_PENALTY_FULL, AsiConfig.RHR7_PENALTY_MAX) }
        val cv7Gated = (zHrv != null && zHrv < AsiConfig.CV7_GATE_HRV_DAILY_Z) || (zHrv7 != null && zHrv7 < AsiConfig.CV7_GATE_HRV7_Z)
        val pCv7 = if (cv7Gated && today.cv7 != null && cv7P75 != null && cv7P95 != null) {
            penalty(today.cv7, cv7P75, cv7P95, AsiConfig.CV7_PENALTY_MAX)
        } else if (cv7Gated) {
            null // gate says a penalty should apply but the data to compute it isn't available -- never imputed.
        } else {
            0.0
        }
        val penalties = AsiPenalties(hrvDaily = pHrv, hrv7 = pHrv7, rhrDaily = pRhr, rhr7 = pRhr7, cv7 = pCv7)

        val unusualResponse = (zHrv != null && zHrv > AsiConfig.HRV_DAILY_UNUSUAL_HIGH_Z) ||
            (zHrv7 != null && zHrv7 > AsiConfig.HRV7_UNUSUAL_HIGH_Z) ||
            (zRhr != null && zRhr < AsiConfig.RHR_DAILY_UNUSUAL_LOW_Z) ||
            (zRhr7 != null && zRhr7 < AsiConfig.RHR7_UNUSUAL_LOW_Z)

        // Precedence 2 (spec 4): CALIBRATING -- history below the spec's minimum, or a degenerate CV7
        // historical distribution (P95 <= P75, spec 5's explicit edge case).
        val fullHistoryReady = precedingHrv.size >= AsiConfig.MIN_PRECEDING_READINGS &&
            precedingRhr.size >= AsiConfig.MIN_PRECEDING_READINGS &&
            historicalCv7.size >= AsiConfig.MIN_CV7_HISTORICAL_VALUES
        val cv7DistributionDegenerate = cv7P75 != null && cv7P95 != null && cv7P95 <= cv7P75

        val status: AsiStatus
        val score: Int?
        when {
            !fullHistoryReady || cv7DistributionDegenerate -> {
                status = AsiStatus.CALIBRATING
                score = null
            }
            // Precedence 3 (spec 4): STALE_WINDOW -- the recent 7-reading window spans too many days.
            spanDays == null || spanDays > AsiConfig.MAX_RECENT_WINDOW_SPAN_DAYS -> {
                status = AsiStatus.STALE_WINDOW
                score = null
            }
            // Precedence 4 (spec 4): compute ASI.
            else -> {
                val raw = 100.0 - (pHrv ?: 0.0) - (pHrv7 ?: 0.0) - (pRhr ?: 0.0) - (pRhr7 ?: 0.0) - (pCv7 ?: 0.0)
                val s = clamp(raw, 0.0, 100.0).roundToInt()
                score = s
                status = mainStatusFor(s)
            }
        }

        val ctx = AsiContext(
            version = version,
            today = today,
            status = status,
            score = score,
            zHrv = zHrv, zHrv7 = zHrv7, zRhr = zRhr, zRhr7 = zRhr7,
            hrv7 = hrv7, rhr7 = rhr7,
            hrvBaselineMean = hrvBaseline?.mean, hrvBaselineSd = hrvBaseline?.sd,
            cv7P25 = cv7P25, cv7P75 = cv7P75, cv7P95 = cv7P95,
            precedingHrvCount = precedingHrv.size, precedingRhrCount = precedingRhr.size,
            spanDays = spanDays,
            penalties = penalties,
            unusualResponse = unusualResponse,
        )
        return AsiInterpretation.build(ctx)
    }

    private fun mainStatusFor(score: Int): AsiStatus = when {
        score >= AsiConfig.FAVORABLE_MIN -> AsiStatus.FAVORABLE
        score >= AsiConfig.MILD_DEVIATION_MIN -> AsiStatus.MILD_DEVIATION
        score >= AsiConfig.CAUTION_MIN -> AsiStatus.CAUTION
        else -> AsiStatus.MARKED_DEVIATION
    }

    /** spec 5: penalty(x,start,full,maxPoints) = maxPoints x clamp((x-start)/(full-start), 0, 1). */
    internal fun penalty(x: Double, start: Double, full: Double, maxPoints: Double): Double =
        maxPoints * clamp((x - start) / (full - start), 0.0, 1.0)

    internal fun clamp(x: Double, a: Double, b: Double): Double = min(max(x, a), b)

    /** Sample SD (n-1 denominator), same convention as [com.hrvrm.app.hrv.HrvScoreCalculator]. Requires
     * at least [AsiConfig.MIN_BASELINE_SAMPLES] values -- fewer than that returns null (not computed). */
    private fun baselineOf(values: List<Double>, sdFloor: Double): Baseline? {
        if (values.size < AsiConfig.MIN_BASELINE_SAMPLES) return null
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
        val sd = sqrt(variance).coerceAtLeast(sdFloor)
        return Baseline(mean, sd)
    }

    /** Linear interpolation between ordered observations (spec 3.5), equivalent to the common
     * "linear"/PERCENTILE.INC method. [sorted] must already be ascending. */
    internal fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.size == 1) return sorted[0]
        val rank = p * (sorted.size - 1)
        val lowerIndex = floor(rank).toInt()
        val upperIndex = ceil(rank).toInt()
        if (lowerIndex == upperIndex) return sorted[lowerIndex]
        val fraction = rank - lowerIndex
        return sorted[lowerIndex] + fraction * (sorted[upperIndex] - sorted[lowerIndex])
    }

    /** Calendar-day span (not elapsed-time span) between the oldest and newest reading in the recent
     * window, in the device's own zone -- same zone convention [com.hrvrm.app.ui.history.buildDailyRhrPoints]
     * already uses for "one point per calendar day". */
    private fun windowSpanDays(timestampsEpochMs: List<Long>): Int? {
        if (timestampsEpochMs.isEmpty()) return null
        val zone = ZoneId.systemDefault()
        val dates = timestampsEpochMs.map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        return ChronoUnit.DAYS.between(dates.min(), dates.max()).toInt()
    }
}
