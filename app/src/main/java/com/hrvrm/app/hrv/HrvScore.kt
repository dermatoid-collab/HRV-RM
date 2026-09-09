package com.hrvrm.app.hrv

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class BaselineStatus { BUILDING, READY }

/**
 * A 0-100 "how does today compare with your own recent normal" score, an HRV4Training-style
 * ln-scale reading, the qualitative "normal range" signal that actually drives guidance, and
 * the baseline stats behind all three (surfaced for transparency, e.g. in a details view).
 */
data class HrvScoreResult(
    val score: Int?,
    val status: BaselineStatus,
    /**
     * True once READY: the smoothed value falls within the narrow "normal range" band
     * (+-0.5 SD, the sports-science "smallest worthwhile change"). This is the signal
     * that should drive any train/rest guidance — a single day outside it is noise,
     * several days in a row outside it means something real changed.
     */
    val withinNormalRange: Boolean?,
    /** 7-reading rolling average of ln(RMSSD), including today — the value actually compared to baseline. */
    val smoothedLnRmssd: Double,
    /**
     * [smoothedLnRmssd] on an HRV4Training-like display scale (roughly 6-10 for typical
     * adults, instead of ~3-5 for plain ln(RMSSD)). HRV4Training displays ln(RMSSD^2), i.e.
     * 2x ln(RMSSD) — a z-score is unaffected by this constant factor, it only changes how
     * the number reads on screen.
     */
    val altiniScaleValue: Double,
    val baselineMeanLnRmssd: Double?,
    val baselineSdLnRmssd: Double?,
    /** Normal-range band bounds on the same display scale as [altiniScaleValue], for a details view. */
    val normalRangeLowAltiniScale: Double?,
    val normalRangeHighAltiniScale: Double?,
)

/**
 * Mirrors HRV4Training's published methodology (Altini, "Daily score, baseline and normal
 * range: an overview") rather than a naive single-day z-score:
 *  - the value compared to baseline is a 7-reading rolling average of ln(RMSSD), not a
 *    single day's raw reading — one noisy morning shouldn't swing the result;
 *  - "normal range" is a narrow band, +-0.5x the day-to-day SD of the last up to 60
 *    readings (the "smallest worthwhile change"), not a wide +-2.5 SD;
 *  - the 0-100 [HrvScoreResult.score] is kept only as a continuous number for a trend
 *    line, using the same z-score scaling as before but now driven by the smoothed value;
 *    [HrvScoreResult.withinNormalRange] carries the actual qualitative signal.
 *
 * Still a from-scratch, published-methodology score, not a reproduction of any vendor's
 * proprietary algorithm.
 */
object HrvScoreCalculator {

    const val MIN_BASELINE_SAMPLES = 7
    const val BASELINE_WINDOW_SIZE = 60
    const val SMOOTHING_WINDOW_SIZE = 7
    const val NORMAL_RANGE_SD_MULTIPLIER = 0.5

    /** Display-scale factor: HRV4Training shows ln(RMSSD^2) = this many x ln(RMSSD). */
    private const val ALTINI_SCALE_FACTOR = 2.0

    /**
     * @param todayRmssdMs RMSSD (ms) from today's measurement.
     * @param priorRmssdMs RMSSD (ms) of previous measurements, most recent first.
     */
    fun compute(todayRmssdMs: Double, priorRmssdMs: List<Double>): HrvScoreResult {
        val lnToday = ln(todayRmssdMs.coerceAtLeast(1.0))
        val priorLn = priorRmssdMs.map { ln(it.coerceAtLeast(1.0)) }

        val smoothedToday = (listOf(lnToday) + priorLn.take(SMOOTHING_WINDOW_SIZE - 1)).average()
        val altiniScaleValue = smoothedToday * ALTINI_SCALE_FACTOR

        val baselineLn = priorLn.take(BASELINE_WINDOW_SIZE)
        if (baselineLn.size < MIN_BASELINE_SAMPLES) {
            return HrvScoreResult(
                score = null,
                status = BaselineStatus.BUILDING,
                withinNormalRange = null,
                smoothedLnRmssd = smoothedToday,
                altiniScaleValue = altiniScaleValue,
                baselineMeanLnRmssd = null,
                baselineSdLnRmssd = null,
                normalRangeLowAltiniScale = null,
                normalRangeHighAltiniScale = null,
            )
        }

        val mean = baselineLn.average()
        val variance = baselineLn.sumOf { (it - mean) * (it - mean) } / (baselineLn.size - 1)
        val sd = sqrt(variance).coerceAtLeast(1e-6)

        val z = (smoothedToday - mean) / sd
        val score = (50 + z * 20).roundToInt().coerceIn(0, 100)
        val halfBand = NORMAL_RANGE_SD_MULTIPLIER * sd

        return HrvScoreResult(
            score = score,
            status = BaselineStatus.READY,
            withinNormalRange = abs(z) <= NORMAL_RANGE_SD_MULTIPLIER,
            smoothedLnRmssd = smoothedToday,
            altiniScaleValue = altiniScaleValue,
            baselineMeanLnRmssd = mean,
            baselineSdLnRmssd = sd,
            normalRangeLowAltiniScale = (mean - halfBand) * ALTINI_SCALE_FACTOR,
            normalRangeHighAltiniScale = (mean + halfBand) * ALTINI_SCALE_FACTOR,
        )
    }
}
