package com.hrvrm.app.hrv

import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class BaselineStatus { BUILDING, READY }

/**
 * A 0-100 "how does today compare with your own recent normal" score plus the baseline
 * stats it was derived from (surfaced for transparency, e.g. in a details view).
 */
data class HrvScoreResult(
    val score: Int?,
    val status: BaselineStatus,
    val lnRmssdToday: Double,
    val baselineMeanLnRmssd: Double?,
    val baselineSdLnRmssd: Double?,
)

/**
 * Maps today's RMSSD against the athlete's own rolling baseline into a readiness-style
 * score. RMSSD is approximately log-normally distributed, so the comparison is done in
 * ln-space (standard practice in HRV sports-science literature, e.g. Plews/Buchheit),
 * then expressed as a z-score re-centered on a 0-100 scale: baseline average -> 50,
 * +-2.5 SD -> 100/0.
 *
 * This is a from-scratch, published-methodology score — not a reproduction of any
 * specific vendor's proprietary "Recovery Score" algorithm.
 */
object HrvScoreCalculator {

    const val MIN_BASELINE_SAMPLES = 7
    const val BASELINE_WINDOW_SIZE = 60

    fun compute(todayRmssdMs: Double, priorRmssdMs: List<Double>): HrvScoreResult {
        val lnToday = ln(todayRmssdMs.coerceAtLeast(1.0))
        val baselineLn = priorRmssdMs.takeLast(BASELINE_WINDOW_SIZE).map { ln(it.coerceAtLeast(1.0)) }

        if (baselineLn.size < MIN_BASELINE_SAMPLES) {
            return HrvScoreResult(
                score = null,
                status = BaselineStatus.BUILDING,
                lnRmssdToday = lnToday,
                baselineMeanLnRmssd = null,
                baselineSdLnRmssd = null,
            )
        }

        val mean = baselineLn.average()
        val variance = baselineLn.sumOf { (it - mean) * (it - mean) } / (baselineLn.size - 1)
        val sd = sqrt(variance).coerceAtLeast(1e-6)

        val z = (lnToday - mean) / sd
        val score = (50 + z * 20).roundToInt().coerceIn(0, 100)

        return HrvScoreResult(
            score = score,
            status = BaselineStatus.READY,
            lnRmssdToday = lnToday,
            baselineMeanLnRmssd = mean,
            baselineSdLnRmssd = sd,
        )
    }
}
