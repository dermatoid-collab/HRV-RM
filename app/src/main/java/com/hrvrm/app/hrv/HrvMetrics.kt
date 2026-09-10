package com.hrvrm.app.hrv

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** Standard time-domain HRV metrics computed from a clean inter-beat-interval series. */
data class HrvMetrics(
    val meanHrBpm: Double,
    val meanIbiMs: Double,
    val sdnnMs: Double,
    val rmssdMs: Double,
    val pnn50Percent: Double,
    val beatCount: Int,
    /**
     * RMSSD normalized by mean RR (as a %): RMSSD/meanIbiMs x 100. A higher resting HR
     * means shorter RR intervals, which structurally leaves less room for beat-to-beat
     * variation — this correction is what makes RMSSD comparable across measurements
     * (or people) taken at different heart rates.
     */
    val normalizedHrvPercent: Double,
)

object HrvMetricsCalculator {

    /** Minimum number of clean beats needed for the metrics to be statistically meaningful. */
    const val MIN_BEATS = 10

    fun compute(cleanIbiMs: List<Long>): HrvMetrics? {
        if (cleanIbiMs.size < MIN_BEATS) return null

        val mean = cleanIbiMs.average()
        val sdnn = sqrt(cleanIbiMs.sumOf { (it - mean).pow(2) } / (cleanIbiMs.size - 1))

        val diffs = cleanIbiMs.zipWithNext { a, b -> (b - a).toDouble() }
        val rmssd = sqrt(diffs.sumOf { it * it } / diffs.size)
        val nn50 = diffs.count { abs(it) > 50.0 }
        val pnn50 = if (diffs.isNotEmpty()) 100.0 * nn50 / diffs.size else 0.0

        val meanHr = 60_000.0 / mean
        val normalizedHrv = 100.0 * rmssd / mean

        return HrvMetrics(
            meanHrBpm = meanHr,
            meanIbiMs = mean,
            sdnnMs = sdnn,
            rmssdMs = rmssd,
            pnn50Percent = pnn50,
            beatCount = cleanIbiMs.size,
            normalizedHrvPercent = normalizedHrv,
        )
    }
}
