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
        /**
         * A detected block only counts as a beat if its peak is at least this fraction of
         * the EWMA of recent *accepted* peak amplitudes — see the root-cause note on
         * [findPeaks]. Found by exporting a real recording (dev-tools/ppg_processor.py) that
         * was rejecting ~70% of beats: nearly every "beat" alternated between a genuine
         * systolic peak and a much smaller block a few hundred ms later that turned out to
         * be the dicrotic notch, not a second heartbeat. 0.35 rejected every notch-sized
         * block in that recording while keeping every genuine one (checked against a clean
         * synthetic signal too, where it changes nothing).
         */
        const val PEAK_MIN_RELATIVE_AMPLITUDE = 0.35
        /** How fast the recent-peak-amplitude reference (for the check above) adapts. */
        const val PEAK_AMPLITUDE_SMOOTHING = 0.3

        const val ARTIFACT_BASELINE_BEATS = 3
        /**
         * How much of the gap between the smoothed rhythm level and each newly-accepted
         * beat to close. High enough to track a real trend (e.g. respiratory sinus
         * arrhythmia) within a couple of beats; well short of 1.0 so one noisy beat can't
         * become the entire reference the way an unsmoothed previous-beat compare did.
         */
        const val ARTIFACT_LEVEL_SMOOTHING = 0.6
        /** How many recent accepted step sizes (not raw IBIs) set the current tolerance. */
        const val ARTIFACT_RECENT_WINDOW = 5
        /**
         * Floor on the accept/reject band, as a fraction of the *current rhythm level*
         * rather than a fixed ms value: the same ms swing is a much bigger deal, in percent,
         * at a slow heart rate (long IBIs) than a fast one — a flat floor tuned for one
         * regime over- or under-rejects in the other. 0.25 was checked (same real recording
         * as above, after the notch fix): once the spurious notch-beats were gone, the
         * remaining genuine beat-to-beat steps were ~4% of the rhythm level at the median
         * and ~12% at the 90th percentile — 25% leaves headroom for real physiological
         * variability (this app's target users include endurance athletes, who can have
         * pronounced respiratory sinus arrhythmia) while a true missed/doubled beat still
         * jumps by roughly +-100% and gets caught.
         */
        const val ARTIFACT_TOLERANCE_FLOOR_FRACTION = 0.25
        /**
         * Scales the median absolute deviation of recent successive-differences into an
         * accept/reject band, added on top of the typical (median) step itself. A fixed
         * percentage-of-level tolerance (this app's original approach, and still common in
         * HRV tooling) assumes everyone has similar beat-to-beat variability — but the HRV
         * artifact-correction literature (e.g. Lipponen & Tarvainen 2019's distribution-based
         * thresholds; Altini's own writing on PPG artifact removal) flags that as too strict
         * for genuinely high-HRV people, where large swings between beats are normal rather
         * than noise: fixed 20-30% bands routinely over-reject for athletes, and
         * over-rejection biases RMSSD down, since RMSSD is itself a measure of the very
         * swings being discarded. Scaling the band by this person's own recent step size
         * instead lets someone with high genuine variability (or currently mid-swing, e.g.
         * respiratory sinus arrhythmia) keep a wider band automatically, without
         * hand-tuning a per-person percentage. 1.4826 converts MAD to an SD-equivalent for
         * normally-distributed data; ~3 SD is a standard outlier cutoff — this constant is
         * an approximation of that combination, not an independently re-derived constant
         * from a specific paper.
         */
        const val ARTIFACT_MAD_MULTIPLIER = 4.5
        /**
         * After this many consecutive rejections, [rejectArtifacts] gives up defending the
         * old reference level and resyncs it — see the doc comment on [rejectArtifacts] for
         * the real-recording evidence behind this. Low enough to break a lockout within a
         * beat or two of it starting, high enough that one or two genuinely artifactual
         * beats in a row don't immediately drag the reference onto them.
         */
        const val ARTIFACT_RESYNC_AFTER_REJECTS = 3
        /** How many recent raw (not just accepted) IBIs the resync median is taken over. */
        const val ARTIFACT_RESYNC_WINDOW = 5
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
     *
     * A second, separate failure mode of the same moving-average mechanism: right after a
     * real systolic peak, the signal plunges well below zero (the trough between beats) and
     * stays there for a good fraction of [ELGENDI_BEAT_WINDOW_MS] — so as the *centered*
     * window slides forward past the peak, maBeat (built from the squared, positive-clipped
     * signal) can collapse toward zero *during that trough*, well before the next real beat.
     * With maBeat and the alpha offset both tiny at that moment, even the dicrotic notch's
     * small bump clears `maPeak > maBeat + alpha` and opens its own "block of interest" —
     * a second detected beat per cardiac cycle, exactly at the point in the cycle where the
     * notch sits. This showed up as a real recording alternating between plausible IBIs and
     * a much shorter one, block width included: the notch's block can be just as wide as a
     * real peak's, so Elgendi's own width check doesn't exclude it here, only *how tall* the
     * block is does — see the amplitude gate in [closeBlock] below.
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
        // EWMA of recent *accepted* peak amplitudes — the reference a candidate block's own
        // peak must clear a fraction of (see PEAK_MIN_RELATIVE_AMPLITUDE) to count as a beat
        // rather than a dicrotic notch.
        var recentPeakLevel: Double? = null

        fun closeBlock(start: Int, endInclusive: Int) {
            if (endInclusive - start + 1 < peakWindowSamples) return // narrower than a real systolic peak
            var maxIdx = start
            for (j in start..endInclusive) if (signal[j] > signal[maxIdx]) maxIdx = j
            val amplitude = signal[maxIdx]
            val ts = samples[maxIdx].timestampMs
            if (ts - lastPeakMs < peakRefractoryMs) return
            val currentPeakLevel = recentPeakLevel
            if (currentPeakLevel != null && amplitude < PEAK_MIN_RELATIVE_AMPLITUDE * currentPeakLevel) {
                return // too small next to recent real beats -- a dicrotic notch, not a beat
            }
            peaks.add(ts)
            lastPeakMs = ts
            recentPeakLevel = currentPeakLevel?.let { it + (amplitude - it) * PEAK_AMPLITUDE_SMOOTHING } ?: amplitude
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
     * too far from this person's *recent smoothed rhythm* (motion artifact / missed or
     * doubled beat). That reference has been through two shapes already, both wrong in
     * opposite directions on real device recordings:
     *  - a 5-beat window **median** lags behind a real, sustained trend (e.g. respiratory
     *    sinus arrhythmia gradually slowing the heart rate through exhale), so beats late
     *    in an otherwise-smooth drift got flagged "irregular" once they'd moved far enough
     *    from a median computed a few seconds earlier;
     *  - comparing to only the **immediately preceding** raw beat swung too far the other
     *    way: with no smoothing at all, one noisy detection becomes the sole reference for
     *    the next comparison, and a single bad beat can cascade into rejecting a whole run
     *    of otherwise-fine beats until one happens to land close to the bad reference by
     *    chance — measurably worse in practice (rejection rate went up, not down).
     * An exponentially-smoothed level splits the difference: it moves with a real trend
     * within a couple of beats (unlike the stale median) while still being an average of
     * history rather than one raw sample (unlike the previous-beat compare), so a single
     * noisy point only nudges it partway. Same idea already used for the waveform's
     * display scale (see PpgWaveform.kt's SCALE_SMOOTHING). "Too far" is still sized by
     * this person's own recent step-to-step variability, not a fixed percentage — see
     * [ARTIFACT_MAD_MULTIPLIER] and, for the floor under it, [ARTIFACT_TOLERANCE_FLOOR_FRACTION].
     *
     * That still leaves one lockout the EWMA level doesn't fix on its own, found by
     * exporting a real recording where rejections clustered specifically on beats where
     * the RR interval was *lengthening* (heart rate slowing — the exhale half of
     * respiratory sinus arrhythmia): the heart-rate-asymmetry literature confirms
     * decelerations are typically steeper/more sustained than accelerations (vagal
     * reactivation is fast; withdrawal is comparatively slow), so a real deceleration
     * often runs for several beats in a row. [level] only moves on an *accepted* beat —
     * if the first beat of that run is (correctly or not) rejected as too big a jump, the
     * level freezes right there, so the second beat of the same real trend looks like an
     * even bigger jump from the same stale reference, and so on: a self-reinforcing
     * lockout that only breaks once the trend happens to loop back near the frozen value.
     * The exported recording showed exactly this signature — 2-6 consecutive rejections
     * sharing the identical `level`, all on lengthening beats, all part of one smooth
     * real deceleration. After [ARTIFACT_RESYNC_AFTER_REJECTS] consecutive **lengthening**
     * rejections (IBI above the reference), resyncing the level to the *median* of the
     * last [ARTIFACT_RESYNC_WINDOW] raw (unfiltered) IBIs breaks the lockout: a real
     * sustained trend has several mutually consistent raw values for the median to lock
     * onto, while a single true artifact (no consistent neighbors) doesn't drag the
     * median far. Verified against that recording (13 of 66 beats rejected -> 8), an
     * earlier one from this app (6 of 57 -> 3), and a clean synthetic signal (unaffected,
     * as it should be — never triggers 3 consecutive rejections in the first place).
     *
     * Deliberately **not** applied to shortening rejections (IBI below the reference):
     * both recordings above show lengthening-rejection streaks up to 6 long but *zero*
     * shortening streaks of even 2, so there's no real-world evidence a shortening
     * lockout is a real problem here — and a real motion artifact (finger movement,
     * pressure change) is far more likely to produce a run of spuriously *short* IBIs
     * than long ones, so resyncing onto such a run would mean adopting the artifact as
     * the new normal instead of continuing to (correctly) reject it. Only extend this to
     * shortening once a real recording actually shows the same lockout signature there.
     *
     * The resync above still pays a fixed cost of [ARTIFACT_RESYNC_AFTER_REJECTS] rejected
     * beats at the *start* of every new deceleration, since that's how many rejections it
     * takes to trigger it — a recording with several separate RSA cycles (several
     * decelerations) pays that toll once per cycle. A beat-by-beat trace of a third real
     * recording (3 deceleration episodes, 3 rejects each, 9 of 48 beats) showed each
     * episode's *second* beat overshoots the same stale `level` by even more, yet sits only
     * a normal step away from the *previous raw beat* and keeps lengthening relative to it
     * — smoothly continuing the same real trend rather than a fresh discontinuity — so it
     * doesn't need to wait for the resync to be recognized as genuine. A rejected beat that
     * merely bounces around near the frozen level without continuing to lengthen (e.g. a
     * true artifact, or the trend already reversing) doesn't get this pass: in one real
     * recording a genuine 550 ms jump is immediately followed by a beat that's *closer* to
     * `level` again (no longer lengthening relative to the previous raw beat), so it
     * correctly falls through to the untouched multi-reject-then-resync path below. So: a
     * rejected **lengthening** beat is accepted anyway if it's still lengthening relative
     * to the *previous raw beat* (continuing, not reversing) and close to it by the same
     * tolerance that already governs the level comparison — cutting the per-episode toll
     * from 3 rejects to 1 without weakening detection of an actual isolated jump. Verified
     * on all three real recordings (13/66 -> 8 already verified above; second, 8/51 -> 4/51;
     * third, 9/48 -> 3/48) and the clean synthetic signal (still 0 rejected).
     *
     * A fourth real recording (very fit, low resting HR: mean 45 bpm) showed one more
     * variant of the same underlying issue, this time starting from an actual
     * [BeatRejectionReason.OUT_OF_RANGE] beat rather than an IRREGULAR one: a single
     * interval landed just past `maxIbiMs` (a plausible deep sinus-arrhythmia trough for
     * this person, not necessarily a bad detection), and because `level` never moves on an
     * excluded beat, it stayed frozen through that gap — so the *next two* beats, which
     * were simply settling onto the new, genuinely slower rhythm, each looked like a fresh
     * large jump and were rejected too. Letting a too-long OOR beat also nudge `level`
     * toward it (see the OOR branch below) removed both of those without changing anything
     * on the first three recordings or the synthetic signal — confirmed by re-running all
     * four plus the synthetic through the Python port before this Kotlin change.
     *
     * Returns one outcome per entry in [rawIbis] (null = accepted), same order.
     */
    private fun rejectArtifacts(rawIbis: List<Long>): List<BeatRejectionReason?> {
        val minIbiMs = (60_000.0 / maxBpm).toLong()
        val maxIbiMs = (60_000.0 / minBpm).toLong()

        val reasons = MutableList<BeatRejectionReason?>(rawIbis.size) { null }
        val recentSteps = mutableListOf<Double>()
        var level: Double? = null
        var acceptedCount = 0
        var consecutiveLengthenRejects = 0
        var prevRaw: Long? = null

        for ((i, ibi) in rawIbis.withIndex()) {
            if (ibi < minIbiMs || ibi > maxIbiMs) {
                reasons[i] = BeatRejectionReason.OUT_OF_RANGE
                consecutiveLengthenRejects = 0
                // A too-long OOR beat (implausibly slow HR) still nudges the level toward it,
                // same as an accepted beat would -- see the doc comment above for why: without
                // this, a genuine deep RSA trough that happens to land just past maxIbiMs
                // freezes the level through the excluded beat, and the next 1-2 real beats
                // settling onto the new (genuinely slower) rhythm look like fresh jumps from a
                // now-stale reference instead of the trend they actually are. Not applied to a
                // too-short OOR beat: an implausibly fast single interval is far more likely a
                // detection glitch (double-counted beat) than a real HR spike, so there's no
                // similar reason to trust it enough to move the reference.
                level?.let { if (ibi > maxIbiMs) level = it + (ibi - it) * ARTIFACT_LEVEL_SMOOTHING }
                prevRaw = ibi
                continue
            }

            val currentLevel = level
            if (currentLevel == null || acceptedCount < ARTIFACT_BASELINE_BEATS) {
                level = currentLevel?.let { it + (ibi - it) * ARTIFACT_LEVEL_SMOOTHING } ?: ibi.toDouble()
                acceptedCount++
                consecutiveLengthenRejects = 0
                prevRaw = ibi
                continue
            }

            val step = abs(ibi - currentLevel)
            val recent = recentSteps.takeLast(ARTIFACT_RECENT_WINDOW).sorted()
            val typicalStep = if (recent.isEmpty()) 0.0 else recent[recent.size / 2]
            val stepMad = if (recent.isEmpty()) 0.0 else recent.map { abs(it - typicalStep) }.sorted()[recent.size / 2]
            val floor = currentLevel * ARTIFACT_TOLERANCE_FLOOR_FRACTION
            val tolerance = maxOf(floor, typicalStep + stepMad * ARTIFACT_MAD_MULTIPLIER)
            val successiveStep = prevRaw?.let { abs(ibi - it) }?.toDouble() ?: step
            val isTrendContinuation = prevRaw != null && ibi > currentLevel &&
                ibi >= prevRaw && successiveStep <= tolerance

            if (step <= tolerance || isTrendContinuation) {
                recentSteps.add(if (isTrendContinuation && step > tolerance) successiveStep else step)
                level = currentLevel + (ibi - currentLevel) * ARTIFACT_LEVEL_SMOOTHING
                acceptedCount++
                consecutiveLengthenRejects = 0
            } else {
                reasons[i] = BeatRejectionReason.IRREGULAR
                if (ibi > currentLevel) {
                    consecutiveLengthenRejects++
                    if (consecutiveLengthenRejects >= ARTIFACT_RESYNC_AFTER_REJECTS) {
                        val windowStart = maxOf(0, i - ARTIFACT_RESYNC_WINDOW + 1)
                        level = medianOf(rawIbis.subList(windowStart, i + 1))
                        recentSteps.clear()
                        consecutiveLengthenRejects = 0
                    }
                } else {
                    consecutiveLengthenRejects = 0
                }
            }
            prevRaw = ibi
        }
        return reasons
    }

    private fun medianOf(values: List<Long>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid].toDouble()
    }
}
