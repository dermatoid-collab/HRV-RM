package com.hrvrm.app.ppg

import kotlin.math.abs
import kotlin.math.sqrt

/** Result of turning a raw intensity trace into a set of beat-to-beat intervals. */
data class PpgProcessingResult(
    /** Detrended, smoothed signal — only used to draw the live waveform. */
    val filteredSignal: List<Double>,
    /** Timestamps (ms from measurement start) of every detected beat. */
    val beatTimestampsMs: List<Long>,
    /** Inter-beat intervals in ms, before artifact rejection. */
    val rawIbiMs: List<Long>,
    /** Inter-beat intervals in ms with physiologically implausible / noisy beats removed. */
    val cleanIbiMs: List<Long>,
    val rejectedBeatCount: Int,
)

/**
 * Turns a raw camera red-channel trace into clean RR-like inter-beat intervals.
 *
 * Pipeline: detrend (remove slow drift from finger pressure / respiration) -> smooth
 * (reduce sensor/quantization noise) -> peak-pick with a refractory period -> reject
 * beats outside a plausible heart-rate range or that jump too far from the local
 * running rhythm. This is the same family of technique commercial camera-PPG apps use;
 * it is not a reproduction of any proprietary vendor algorithm.
 */
class PpgSignalProcessor(
    private val minBpm: Double = 35.0,
    private val maxBpm: Double = 200.0,
    /** Reject a beat if its IBI differs from the local median by more than this fraction. */
    private val artifactTolerance: Double = 0.20,
    /**
     * Minimum gap enforced between accepted peaks. Deliberately larger than
     * 60_000/maxBpm: a PPG pulse has a secondary "dicrotic notch" bump ~300-450ms after
     * the real systolic peak, which a refractory period sized only for the max
     * plausible heart rate doesn't exclude — it gets picked up as a second, spurious
     * peak. 400ms (150 bpm) filters that out while still allowing genuinely fast
     * consecutive beats.
     */
    private val peakRefractoryMs: Long = 400L,
) {

    fun process(samples: List<PpgSample>): PpgProcessingResult {
        if (samples.size < 8) {
            return PpgProcessingResult(emptyList(), emptyList(), emptyList(), emptyList(), 0)
        }

        val durationMs = (samples.last().timestampMs - samples.first().timestampMs).toDouble()
        val avgDtMs = durationMs / (samples.size - 1).coerceAtLeast(1)
        val sampleRateHz = if (avgDtMs > 0) 1000.0 / avgDtMs else 30.0

        val detrended = detrend(samples, windowMs = 800.0, sampleRateHz = sampleRateHz)
        val smoothed = movingAverage(detrended, windowSamples = maxOf(1, (sampleRateHz / 10).toInt()))

        val beatTimestamps = findPeaks(samples, smoothed, peakRefractoryMs)

        val rawIbis = beatTimestamps.zipWithNext { a, b -> b - a }

        val (cleanIbis, rejected) = rejectArtifacts(rawIbis)

        return PpgProcessingResult(
            filteredSignal = smoothed,
            beatTimestampsMs = beatTimestamps,
            rawIbiMs = rawIbis,
            cleanIbiMs = cleanIbis,
            rejectedBeatCount = rejected,
        )
    }

    /** Subtracts a centered rolling mean to remove slow baseline drift. */
    private fun detrend(samples: List<PpgSample>, windowMs: Double, sampleRateHz: Double): List<Double> {
        val windowSamples = maxOf(3, (windowMs / 1000.0 * sampleRateHz).toInt())
        val half = windowSamples / 2
        val values = samples.map { it.intensity }
        val out = DoubleArray(values.size)
        var runningSum = 0.0
        var count = 0
        for (i in values.indices) {
            val lo = maxOf(0, i - half)
            val hi = minOf(values.size - 1, i + half)
            if (i == 0) {
                for (j in lo..hi) runningSum += values[j]
                count = hi - lo + 1
            } else {
                val prevLo = maxOf(0, (i - 1) - half)
                val prevHi = minOf(values.size - 1, (i - 1) + half)
                if (prevLo < lo) {
                    runningSum -= values[prevLo]
                    count--
                }
                if (hi > prevHi) {
                    runningSum += values[hi]
                    count++
                }
            }
            val mean = if (count > 0) runningSum / count else values[i]
            out[i] = values[i] - mean
        }
        return out.toList()
    }

    private fun movingAverage(values: List<Double>, windowSamples: Int): List<Double> {
        if (windowSamples <= 1) return values
        val out = DoubleArray(values.size)
        var sum = 0.0
        val half = windowSamples / 2
        for (i in values.indices) {
            val lo = maxOf(0, i - half)
            val hi = minOf(values.size - 1, i + half)
            sum = 0.0
            for (j in lo..hi) sum += values[j]
            out[i] = sum / (hi - lo + 1)
        }
        return out.toList()
    }

    /**
     * Local-maxima peak picker with a refractory period so a single beat can't be
     * counted twice, plus an adaptive amplitude threshold based on the recent signal's RMS
     * so it self-calibrates to whatever contact quality the finger placement gives.
     */
    private fun findPeaks(samples: List<PpgSample>, signal: List<Double>, minDistanceMs: Long): List<Long> {
        if (signal.size < 3) return emptyList()

        val rms = sqrt(signal.sumOf { it * it } / signal.size)
        val threshold = rms * 0.35

        val peaks = mutableListOf<Long>()
        var lastPeakMs = Long.MIN_VALUE / 2

        for (i in 1 until signal.size - 1) {
            val v = signal[i]
            val ts = samples[i].timestampMs
            val isLocalMax = v > signal[i - 1] && v >= signal[i + 1]
            if (isLocalMax && v > threshold && ts - lastPeakMs >= minDistanceMs) {
                peaks.add(ts)
                lastPeakMs = ts
            }
        }
        return peaks
    }

    /**
     * Drops beats outside the plausible heart-rate range and beats whose interval jumps
     * too far from the local rhythm (motion artifact / missed or doubled beat).
     */
    private fun rejectArtifacts(rawIbis: List<Long>): Pair<List<Long>, Int> {
        val minIbiMs = (60_000.0 / maxBpm).toLong()
        val maxIbiMs = (60_000.0 / minBpm).toLong()

        val accepted = mutableListOf<Long>()
        var rejected = 0

        for (ibi in rawIbis) {
            if (ibi < minIbiMs || ibi > maxIbiMs) {
                rejected++
                continue
            }
            if (accepted.size < 3) {
                accepted.add(ibi)
                continue
            }
            val recent = accepted.takeLast(5).sorted()
            val median = recent[recent.size / 2]
            val deviation = abs(ibi - median).toDouble() / median
            if (deviation <= artifactTolerance) {
                accepted.add(ibi)
            } else {
                rejected++
            }
        }
        return accepted to rejected
    }
}
