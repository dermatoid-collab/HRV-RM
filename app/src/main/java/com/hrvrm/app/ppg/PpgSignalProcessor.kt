package com.hrvrm.app.ppg

import kotlin.math.abs
import kotlin.math.roundToInt

/** Why a beat was dropped — surfaced in the live log so a rejection spike is diagnosable. */
enum class BeatRejectionReason {
    /** Faster or slower than the plausible [PpgSignalProcessor] heart-rate range. */
    OUT_OF_RANGE,

    /** Within the plausible range, but too far from this person's own recent rhythm. */
    IRREGULAR,
}

/**
 * One inter-beat interval as it was decided, for a live/diagnostic log: when the beat that
 * closes it landed, how long the interval was, and whether/why it survived artifact rejection.
 */
data class IbiEvent(val atMs: Long, val ibiMs: Long, val rejectionReason: BeatRejectionReason?) {
    val accepted: Boolean get() = rejectionReason == null
}

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
    /** Every raw interval with its accept/reject outcome, in detection order. */
    val ibiEvents: List<IbiEvent>,
)

/**
 * Turns a raw camera red-channel trace into clean RR-like inter-beat intervals.
 *
 * Pipeline: detrend (remove slow drift from finger pressure / respiration) -> smooth
 * (reduce sensor/quantization noise) -> Elgendi-method peak-pick (see [findPeaks], excludes
 * the dicrotic notch by shape/width rather than timing alone) -> reject beats outside a
 * plausible heart-rate range or that jump too far from the local running rhythm. This is
 * the same family of technique commercial camera-PPG apps use; it is not a reproduction of
 * any proprietary vendor algorithm.
 */
class PpgSignalProcessor(
    private val minBpm: Double = 35.0,
    private val maxBpm: Double = 200.0,
    /**
     * Backup safety net, not the primary notch defense (that's the width check in
     * [findPeaks]): guards against two adjacent above-threshold blocks from a single true
     * beat. 400ms (150 bpm) is comfortably below the plausible max heart rate.
     */
    private val peakRefractoryMs: Long = 400L,
) {
    private companion object {
        /** Elgendi's W1: moving-average window sized to a systolic peak's own width. */
        const val ELGENDI_PEAK_WINDOW_MS = 111.0
        /** Elgendi's W2: moving-average window sized to a whole beat, tracking the envelope. */
        const val ELGENDI_BEAT_WINDOW_MS = 667.0
        /**
         * Elgendi's beta, scaling the statistical-mean offset added to the beat-envelope
         * threshold. The published value is a starting point, not independently re-derived
         * here — worth tuning against real recordings if beat detection still misbehaves.
         */
        const val ELGENDI_THRESHOLD_BETA = 0.02

        const val ARTIFACT_BASELINE_BEATS = 3
        const val ARTIFACT_RECENT_WINDOW = 5
        /**
         * Floor on the accept/reject band regardless of how uniform the recent beats look,
         * so a short uniform stretch (MAD momentarily ~0) doesn't make the filter reject
         * on essentially no tolerance at all.
         */
        const val ARTIFACT_TOLERANCE_FLOOR_MS = 80.0
        /**
         * Scales the local median absolute deviation into an accept/reject band. A fixed
         * percentage-of-median tolerance (this app's original approach, and still common
         * in HRV tooling) assumes everyone has similar beat-to-beat variability — but the
         * HRV artifact-correction literature (e.g. Lipponen & Tarvainen 2019's
         * distribution-based thresholds; Altini's own writing on PPG artifact removal)
         * flags that as too strict for genuinely high-HRV people, where large swings
         * between beats are normal rather than noise: fixed 20-30% bands routinely
         * over-reject for athletes, and over-rejection biases RMSSD down, since RMSSD is
         * itself a measure of the very swings being discarded. Scaling the band by this
         * person's own recent MAD instead lets someone with high genuine variability keep
         * a wider band automatically, without hand-tuning a per-person percentage. 1.4826
         * converts MAD to an SD-equivalent for normally-distributed data; ~3 SD is a
         * standard outlier cutoff — this constant is an approximation of that combination,
         * not an independently re-derived constant from a specific paper.
         */
        const val ARTIFACT_MAD_MULTIPLIER = 4.5
    }

    fun process(samples: List<PpgSample>): PpgProcessingResult {
        if (samples.size < 8) {
            return PpgProcessingResult(emptyList(), emptyList(), emptyList(), emptyList(), 0, emptyList())
        }

        val durationMs = (samples.last().timestampMs - samples.first().timestampMs).toDouble()
        val avgDtMs = durationMs / (samples.size - 1).coerceAtLeast(1)
        val sampleRateHz = if (avgDtMs > 0) 1000.0 / avgDtMs else 30.0

        val detrended = detrend(samples, windowMs = 800.0, sampleRateHz = sampleRateHz)
        val smoothed = movingAverage(detrended, windowSamples = maxOf(1, (sampleRateHz / 10).toInt()))

        val beatTimestamps = findPeaks(samples, smoothed, sampleRateHz)

        val rawIbis = beatTimestamps.zipWithNext { a, b -> b - a }

        val rejectionReasons = rejectArtifacts(rawIbis)
        val cleanIbis = rawIbis.filterIndexed { i, _ -> rejectionReasons[i] == null }
        val rejected = rejectionReasons.count { it != null }
        val ibiEvents = rawIbis.indices.map { i ->
            IbiEvent(atMs = beatTimestamps[i + 1], ibiMs = rawIbis[i], rejectionReason = rejectionReasons[i])
        }

        return PpgProcessingResult(
            filteredSignal = smoothed,
            beatTimestampsMs = beatTimestamps,
            rawIbiMs = rawIbis,
            cleanIbiMs = cleanIbis,
            rejectedBeatCount = rejected,
            ibiEvents = ibiEvents,
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
     * Elgendi et al. (2013), "Systolic Peak Detection in Acceleration Photoplethysmograms
     * Measured from Emergency Responders in Tropical Conditions" (PLoS ONE 8(10): e76585)
     * — the most-cited PPG peak detector, ~99.8% sensitivity/positive-predictivity on its
     * validation set. Clipping the signal to its positive part and squaring it amplifies
     * the (larger) systolic peak far more than the (smaller) dicrotic notch bump, since
     * squaring grows superlinearly with amplitude. Two moving averages — a short one
     * (~111ms) tracking individual peaks, a long one (~667ms) tracking the overall beat
     * envelope — define "blocks of interest" wherever the short one rises above the long
     * one; a block only counts as a beat if it's at least as wide as a real systolic peak.
     * That width check is what actually excludes the notch: a fixed refractory period
     * alone can't, since notch timing (~300-450ms after the true peak) falls on either
     * side of a 400ms cutoff depending on heart rate.
     *
     * [movingAverage] is a *centered* average: right at the trailing edge of whatever
     * window this tick was given, it has no "future" samples to average against, so its
     * value there is computed from fewer, asymmetric samples — biased, exactly like the
     * displayed waveform's trailing edge (see [EDGE_TRIM_SAMPLES] in MeasurementViewModel).
     * For maBeat specifically, that bias pulls the long-window threshold down right where
     * it matters least (data that hasn't settled yet), which was spuriously opening
     * "blocks of interest" every tick near the freshest samples — a real device recording
     * showed this exactly: a plausible accepted beat followed by a burst of "irregular"
     * phantom beats spaced ~100ms apart, matching the reprocessing tick rate, not a heart
     * rate. Excluding the unstable trailing half of the *long* window from the scan (not
     * from the moving averages themselves, which still need full context for earlier
     * points) is the fix — a beat there is simply picked up a tick or two later instead.
     */
    private fun findPeaks(samples: List<PpgSample>, signal: List<Double>, sampleRateHz: Double): List<Long> {
        if (signal.size < 3) return emptyList()

        val squaredPositive = signal.map { v -> if (v > 0) v * v else 0.0 }
        val peakWindowSamples = maxOf(1, (ELGENDI_PEAK_WINDOW_MS / 1000.0 * sampleRateHz).roundToInt())
        val beatWindowSamples = maxOf(peakWindowSamples + 1, (ELGENDI_BEAT_WINDOW_MS / 1000.0 * sampleRateHz).roundToInt())

        val maPeak = movingAverage(squaredPositive, peakWindowSamples)
        val maBeat = movingAverage(squaredPositive, beatWindowSamples)
        // Statistical-mean offset (Elgendi's alpha): keeps flat/silent stretches (no finger
        // contact, signal dropout) from tripping the threshold on noise alone.
        val alpha = ELGENDI_THRESHOLD_BETA * squaredPositive.average()

        val peaks = mutableListOf<Long>()
        var lastPeakMs = Long.MIN_VALUE / 2

        fun closeBlock(start: Int, endInclusive: Int) {
            if (endInclusive - start + 1 < peakWindowSamples) return // narrower than a real systolic peak
            var maxIdx = start
            for (j in start..endInclusive) if (signal[j] > signal[maxIdx]) maxIdx = j
            val ts = samples[maxIdx].timestampMs
            if (ts - lastPeakMs >= peakRefractoryMs) {
                peaks.add(ts)
                lastPeakMs = ts
            }
        }

        val trailingEdgeGuard = beatWindowSamples / 2
        val scanLimit = signal.size - 1 - trailingEdgeGuard

        var blockStart = -1
        for (i in 0..scanLimit) {
            val aboveThreshold = maPeak[i] > maBeat[i] + alpha
            if (aboveThreshold) {
                if (blockStart == -1) blockStart = i
            } else if (blockStart != -1) {
                closeBlock(blockStart, i - 1)
                blockStart = -1
            }
        }
        if (blockStart != -1 && scanLimit >= 0) closeBlock(blockStart, scanLimit)

        return peaks
    }

    /**
     * Drops beats outside the plausible heart-rate range and beats whose interval jumps
     * too far from the local rhythm (motion artifact / missed or doubled beat). "Too far"
     * is judged against this person's own recent beat-to-beat variability (median absolute
     * deviation), not a fixed percentage — see [ARTIFACT_MAD_MULTIPLIER]. Returns one
     * outcome per entry in [rawIbis] (null = accepted), same order — a rejected interval
     * doesn't join the running "recent" window used to judge the ones after it. Splitting
     * out *why* each beat was dropped (range vs. locally irregular) is what makes a
     * persistently high rejection rate diagnosable instead of a single opaque count.
     */
    private fun rejectArtifacts(rawIbis: List<Long>): List<BeatRejectionReason?> {
        val minIbiMs = (60_000.0 / maxBpm).toLong()
        val maxIbiMs = (60_000.0 / minBpm).toLong()

        val accepted = mutableListOf<Long>()
        val reasons = MutableList<BeatRejectionReason?>(rawIbis.size) { null }

        for ((i, ibi) in rawIbis.withIndex()) {
            if (ibi < minIbiMs || ibi > maxIbiMs) {
                reasons[i] = BeatRejectionReason.OUT_OF_RANGE
                continue
            }
            if (accepted.size < ARTIFACT_BASELINE_BEATS) {
                accepted.add(ibi)
                continue
            }
            val recent = accepted.takeLast(ARTIFACT_RECENT_WINDOW).sorted()
            val median = recent[recent.size / 2]
            val mad = recent.map { abs(it - median) }.sorted()[recent.size / 2]
            val tolerance = maxOf(ARTIFACT_TOLERANCE_FLOOR_MS, mad * ARTIFACT_MAD_MULTIPLIER)
            if (abs(ibi - median) <= tolerance) {
                accepted.add(ibi)
            } else {
                reasons[i] = BeatRejectionReason.IRREGULAR
            }
        }
        return reasons
    }
}
