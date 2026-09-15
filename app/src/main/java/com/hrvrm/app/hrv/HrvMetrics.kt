package com.hrvrm.app.hrv

import kotlin.math.abs
import kotlin.math.max
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
    /** Poincaré plot short-term variability: the spread of RR(n+1)-RR(n), i.e. RMSSD/sqrt(2). */
    val sd1Ms: Double,
    /** Poincaré plot long-term variability, derived from SDNN and SD1: sqrt(2*SDNN^2 - SD1^2). */
    val sd2Ms: Double,
    /**
     * Baevsky's Stress Index: a geriatric/space-medicine measure of autonomic "rigidity"
     * from the RR histogram — see [HrvMetricsCalculator.stressIndex]. Higher means a
     * narrower, more peaked RR distribution (less beat-to-beat variability).
     */
    val stressIndex: Double,
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

        val sd1 = rmssd / sqrt(2.0)
        val sd2 = sqrt(max(0.0, 2.0 * sdnn.pow(2) - sd1.pow(2)))

        return HrvMetrics(
            meanHrBpm = meanHr,
            meanIbiMs = mean,
            sdnnMs = sdnn,
            rmssdMs = rmssd,
            pnn50Percent = pnn50,
            beatCount = cleanIbiMs.size,
            normalizedHrvPercent = normalizedHrv,
            sd1Ms = sd1,
            sd2Ms = sd2,
            stressIndex = stressIndex(cleanIbiMs),
        )
    }

    /**
     * Baevsky's Stress Index: SI = AMo / (2 x Mo x MxDMn), from a histogram of the clean RR
     * series (50ms bins, the bin width from the original method). Mo (mode) is the center
     * of the most populated bin in seconds; AMo (amplitude of mode) is the percentage of
     * beats falling in that bin; MxDMn is the full RR range in seconds. A narrow, peaked
     * distribution (low variability) gives a high SI; a wide, flat one gives a low SI.
     * Standard formula from the cardiovascular/space-medicine literature (Baevsky &
     * Chernikova) — implementations differ in binning and mode-estimation details, so this
     * won't necessarily match another app's number exactly, only the same general scale.
     */
    private fun stressIndex(cleanIbiMs: List<Long>): Double {
        val binWidthMs = 50.0
        val bins = cleanIbiMs.groupingBy { (it / binWidthMs).toInt() }.eachCount()
        val modalBin = bins.maxByOrNull { it.value } ?: return 0.0
        val moSec = (modalBin.key + 0.5) * binWidthMs / 1000.0
        val amoPercent = 100.0 * modalBin.value / cleanIbiMs.size
        val mxDMnSec = (cleanIbiMs.max() - cleanIbiMs.min()) / 1000.0
        if (moSec <= 0.0 || mxDMnSec <= 0.0) return 0.0
        return amoPercent / (2.0 * moSec * mxDMnSec)
    }
}
