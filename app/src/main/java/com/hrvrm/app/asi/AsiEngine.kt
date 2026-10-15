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
 * Deliberate simplification (user-confirmed): both the daily and the 7-reading-smoothed z-scores
 * for a metric are drawn from the SAME up-to-[AsiConfig.BASELINE_WINDOW_SIZE] pooled window of
 * preceding readings, rather than the spec's two entirely separate reference windows (spec
 * 3.2/3.4) -- one window per metric instead of two. Within that shared window, the smoothed
 * variant still gets its own mean/SD (see [smoothedSeries]), not the single-reading daily
 * baseline's -- a 7-reading average has much lower variance than a single reading, so reusing the
 * daily SD as zHRV7/zRHR7's denominator silently understated both almost always, starving the
 * HRV7/RHR7 penalties and the CV7 gate (which depends on zHRV7). [smoothedSeries] builds that SD
 * from genuinely non-overlapping 7-reading blocks, per the spec's own "le finestre non devono
 * sovrapporsi" (3.2) -- an earlier version used a sliding/overlapping window instead, which is
 * autocorrelated (adjacent windows share 6 of 7 readings) and not a statistically sound variance
 * estimate, independent of which direction that bias happened to push any one example.
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
        val hrv7Baseline = baselineOf(smoothedSeries(preceding) { it.hrvScore }, AsiConfig.HRV_SD_FLOOR, AsiConfig.MIN_SMOOTHED_BASELINE_BLOCKS)
        val rhr7Baseline = baselineOf(smoothedSeries(preceding) { it.rhr }, AsiConfig.RHR_SD_FLOOR, AsiConfig.MIN_SMOOTHED_BASELINE_BLOCKS)

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
        val zHrv7 = if (hrv7Baseline != null && hrv7 != null) (hrv7 - hrv7Baseline.mean) / hrv7Baseline.sd else null
        val zRhr = rhrBaseline?.let { (today.rhr - it.mean) / it.sd }
        val zRhr7 = if (rhr7Baseline != null && rhr7 != null) (rhr7 - rhr7Baseline.mean) / rhr7Baseline.sd else null

        // -- Penalties (spec 5) -- computed whenever their underlying z-score is available, independent
        // of whether the overall score ends up null, so parameter-level classification can still use them.
        // The four main penalties use penaltySensitive (user-approved change, 2026-10-15) -- CV7 stays
        // on the original step function below, deliberately, to isolate the change's effect.
        val pHrv = zHrv?.let { penaltySensitive(-it, AsiConfig.HRV_DAILY_PENALTY_START, AsiConfig.HRV_DAILY_PENALTY_FULL, AsiConfig.HRV_DAILY_PENALTY_MAX) }
        val pHrv7 = zHrv7?.let { penaltySensitive(-it, AsiConfig.HRV7_PENALTY_START, AsiConfig.HRV7_PENALTY_FULL, AsiConfig.HRV7_PENALTY_MAX) }
        val pRhr = zRhr?.let { penaltySensitive(it, AsiConfig.RHR_DAILY_PENALTY_START, AsiConfig.RHR_DAILY_PENALTY_FULL, AsiConfig.RHR_DAILY_PENALTY_MAX) }
        val pRhr7 = zRhr7?.let { penaltySensitive(it, AsiConfig.RHR7_PENALTY_START, AsiConfig.RHR7_PENALTY_FULL, AsiConfig.RHR7_PENALTY_MAX) }
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

    /** spec 5: penalty(x,start,full,maxPoints) = maxPoints x clamp((x-start)/(full-start), 0, 1).
     * Still used for CV7 (deliberately left unchanged, see [compute]'s pCv7). */
    internal fun penalty(x: Double, start: Double, full: Double, maxPoints: Double): Double =
        maxPoints * clamp((x - start) / (full - start), 0.0, 1.0)

    /**
     * User-approved two-segment penalty (2026-10-15, [AsiConfig.PENALTY_SENSITIVITY_ALPHA]) used for
     * the four main parameters (HRV daily/7, RHR daily/7) in place of [penalty]'s hard dead zone:
     * - x <= 0 (favorable direction, or exactly on baseline): 0, same as before.
     * - 0 < x <= start: ramps from 0 to maxPoints*alpha -- a small but non-zero penalty for any
     *   unfavorable deviation, however small, instead of [penalty]'s flat zero in this whole range.
     * - start < x < full: continues from maxPoints*alpha up to maxPoints -- continuous with the
     *   segment above at x=start (both give maxPoints*alpha there), just a steeper slope.
     * - x >= full: maxPoints, same ceiling as [penalty].
     * Continuous everywhere (verified at x=start and x=full in [AsiEngineTest]); only the slope
     * changes at x=start, which is fine for a penalty curve -- no continuity in the derivative is
     * required here, same as it wasn't for the old hard dead zone.
     */
    internal fun penaltySensitive(
        x: Double,
        start: Double,
        full: Double,
        maxPoints: Double,
        alpha: Double = AsiConfig.PENALTY_SENSITIVITY_ALPHA,
    ): Double = when {
        x <= 0.0 -> 0.0
        x <= start -> maxPoints * alpha * (x / start)
        x < full -> maxPoints * (alpha + (1 - alpha) * (x - start) / (full - start))
        else -> maxPoints
    }

    internal fun clamp(x: Double, a: Double, b: Double): Double = min(max(x, a), b)

    /**
     * Historical reference series for the smoothed (HRV7/RHR7) z-scores: [precedingNewestFirst]
     * split into consecutive, genuinely NON-overlapping 7-reading blocks -- block 0 is the 7 most
     * recent preceding readings, block 1 the next 7 older ones, and so on -- each averaged with
     * [valueOf]. Non-overlapping per the spec's own "le finestre non devono sovrapporsi" (3.2):
     * sliding the window by 1 reading at a time instead would make adjacent points share 6 of
     * their 7 readings, an autocorrelated series that isn't a statistically sound basis for a
     * variance estimate. A partial trailing block (fewer than 7 readings left) is dropped rather
     * than averaged short, same as [AsiConfig.BASELINE_WINDOW_SIZE] capping the daily baseline.
     */
    private fun smoothedSeries(precedingNewestFirst: List<AsiReading>, valueOf: (AsiReading) -> Double): List<Double> {
        val blockCount = precedingNewestFirst.size / AsiConfig.SMOOTHING_WINDOW_SIZE
        return (0 until blockCount).map { block ->
            val start = block * AsiConfig.SMOOTHING_WINDOW_SIZE
            precedingNewestFirst.subList(start, start + AsiConfig.SMOOTHING_WINDOW_SIZE).map(valueOf).average()
        }
    }

    /** Sample SD (n-1 denominator), same convention as [com.hrvrm.app.hrv.HrvScoreCalculator]. Requires
     * at least [minSamples] values -- fewer than that returns null (not computed). */
    private fun baselineOf(values: List<Double>, sdFloor: Double, minSamples: Int = AsiConfig.MIN_BASELINE_SAMPLES): Baseline? {
        if (values.size < minSamples) return null
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
