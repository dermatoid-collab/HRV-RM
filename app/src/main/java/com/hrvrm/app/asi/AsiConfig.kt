package com.hrvrm.app.asi

/**
 * Every weight and threshold the ASI (Autonomic Status Index) engine uses, centralized here so
 * they're easy to find and adjust together -- the spec this was built from calls for exactly
 * that ("pesi e soglie devono essere configurabili").
 *
 * Ported from a detailed spec originally written for this app; VERSION is this app's own
 * identifier (the spec's own JSON example carried "montis-asi-1.6", a sibling project's version
 * string, not an HRV-RM one).
 *
 * Explicitly experimental, per the spec's own calibration section: these are initial heuristics,
 * not clinically validated coefficients. The spec itself calls for 8-12 weeks of real data,
 * checked for false alarms and classification stability, before trusting ASI for training
 * decisions -- nothing here changes that.
 *
 * Deliberate simplification from the original spec: the daily and 7-reading-smoothed z-scores for
 * a metric are drawn from the SAME up-to-[BASELINE_WINDOW_SIZE] pooled window of preceding
 * readings, rather than two separate reference windows -- but within that shared window, the
 * smoothed variant still gets its own mean/SD, from genuinely non-overlapping 7-reading blocks
 * (see AsiEngine.smoothedSeries), per the spec's own "le finestre non devono sovrapporsi".
 */
object AsiConfig {
    const val VERSION = "hrvrm-asi-1.0"

    /** Same baseline window HrvScoreCalculator.BASELINE_WINDOW_SIZE already uses. */
    const val BASELINE_WINDOW_SIZE = 60
    const val SMOOTHING_WINDOW_SIZE = 7

    // -- Data-availability gates --
    // Lowered from the spec's own 30/28 (user-requested, 2026-10-15): with real daily use and
    // CV7 only populating from each reading's own 3rd sample onward, 30 preceding + 28 historical
    // CV7 values meant ASI stayed in CALIBRATING for 5-6+ weeks before ever showing a first score
    // -- longer than felt useful. 14/10 is still enough for a baseline mean/SD and a handful of
    // CV7 percentile points, at the cost of a less statistically representative early baseline
    // (see Confidence, which reports exactly that -- MODERATE, not HIGH, until 60 readings exist).
    const val MIN_PRECEDING_READINGS = 14
    const val MIN_BASELINE_SAMPLES = 3
    const val MIN_CV7_HISTORICAL_VALUES = 10
    const val MAX_RECENT_WINDOW_SPAN_DAYS = 10

    /**
     * Minimum number of non-overlapping 7-reading blocks (see AsiEngine.smoothedSeries) needed
     * before HRV7/RHR7 get their own mean/SD to be scored against -- deliberately lower than
     * [MIN_BASELINE_SAMPLES]: a non-overlapping design needs 7x as many readings per reference
     * point as the single-reading daily baseline does, so requiring the same sample *count* would
     * need 21+ preceding readings just for HRV7/RHR7 to ever activate -- well past
     * [MIN_PRECEDING_READINGS]. 2 blocks (= 14 readings) lines up with that threshold instead, at
     * the cost of an admittedly very rough 2-point SD estimate early on (Confidence reports this
     * honestly as MODERATE, not HIGH).
     */
    const val MIN_SMOOTHED_BASELINE_BLOCKS = 2

    // -- Numerical floors for z-score denominators -- experimental guards, not physiological
    // thresholds (the spec is explicit about this distinction).
    const val HRV_SD_FLOOR = 0.15
    const val RHR_SD_FLOOR = 1.0

    // -- Penalty curve parameters: penalty(x, start, full, maxPoints) --
    const val HRV_DAILY_PENALTY_START = 0.5
    const val HRV_DAILY_PENALTY_FULL = 2.0
    const val HRV_DAILY_PENALTY_MAX = 25.0

    const val HRV7_PENALTY_START = 0.3
    const val HRV7_PENALTY_FULL = 1.5
    const val HRV7_PENALTY_MAX = 20.0

    const val RHR_DAILY_PENALTY_START = 0.5
    const val RHR_DAILY_PENALTY_FULL = 2.0
    const val RHR_DAILY_PENALTY_MAX = 25.0

    const val RHR7_PENALTY_START = 0.3
    const val RHR7_PENALTY_FULL = 1.5
    const val RHR7_PENALTY_MAX = 20.0

    const val CV7_PENALTY_MAX = 10.0
    const val CV7_GATE_HRV_DAILY_Z = -0.5
    const val CV7_GATE_HRV7_Z = -0.3

    // -- Main ASI status bands (score is null outside FAVORABLE..MARKED_DEVIATION) --
    const val FAVORABLE_MIN = 85
    const val MILD_DEVIATION_MIN = 70
    const val CAUTION_MIN = 50

    // -- Per-parameter z thresholds --
    const val HRV_DAILY_LOW_Z = -1.0
    const val HRV_DAILY_HIGH_Z = 1.0
    const val HRV_DAILY_UNUSUAL_HIGH_Z = 2.0

    const val HRV7_LOW_Z = -0.5
    const val HRV7_HIGH_Z = 0.5
    const val HRV7_UNUSUAL_HIGH_Z = 2.0

    const val RHR_DAILY_UNUSUAL_LOW_Z = -2.0
    const val RHR_DAILY_LOW_Z = -1.0
    const val RHR_DAILY_HIGH_Z = 1.0
    const val RHR_DAILY_VERY_HIGH_Z = 2.0

    const val RHR7_UNUSUAL_LOW_Z = -2.0
    const val RHR7_LOW_Z = -0.5
    const val RHR7_HIGH_Z = 0.5
    const val RHR7_VERY_HIGH_Z = 1.5

    // -- Confidence (section 12) --
    const val CONFIDENCE_HIGH_MIN_READINGS = 60
}
