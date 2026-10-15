package com.hrvrm.app.asi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers spec section 17 items 5-8, 11-13, 17-22 and 26 against [AsiEngine.compute] -- the number
 * crunching (z-scores, penalties, 0-100 score, main status, "no partial/no imputed" guarantees).
 * Parameter classification / combinations / confidence (items 9, 10, 14-16, 24, 25) are covered in
 * [AsiInterpretationTest] instead, against [AsiInterpretation] directly.
 */
class AsiEngineTest {

    companion object {
        private const val DAY_MS = 86_400_000L
    }

    /**
     * 60 preceding readings, one per day, most-recent-first:
     *  - hrvScore alternates 9.5/8.5 -> mean exactly 9.0, sample sd = sqrt(15/59).
     *  - rhr alternates 55.0/45.0 -> mean exactly 50.0, sample sd = sqrt(1500/59).
     *  - cv7 set to 1.0..30.0 on the 30 most recent entries (null further back, as the app's own
     *    CV7 field would be before its own 7-reading window was ready) -> 30 historical values.
     * Today's own reading (hrvScore=9.0, rhr=50.0) plus this alternating pattern's first 6 entries
     * average out to exactly the baseline mean for both HRV7 and RHR7 too (see class doc below) --
     * a deliberately "right in the middle of normal" fixture that [compute] on its own should score 100.
     */
    private fun healthyPreceding(): List<AsiReading> = (0 until 60).map { i ->
        AsiReading(
            timestampEpochMs = -((i + 1) * DAY_MS),
            hrvScore = if (i % 2 == 0) 9.5 else 8.5,
            rhr = if (i % 2 == 0) 55.0 else 45.0,
            cv7 = if (i < 30) (i + 1).toDouble() else null,
        )
    }

    private fun healthyToday(hrvScore: Double = 9.0, rhr: Double = 50.0, cv7: Double? = 15.0) =
        AsiReading(timestampEpochMs = 0L, hrvScore = hrvScore, rhr = rhr, cv7 = cv7)

    private val cv7P25 = AsiEngine.percentile((1..30).map { it.toDouble() }, 0.25) // 8.25
    private val cv7P75 = AsiEngine.percentile((1..30).map { it.toDouble() }, 0.75) // 22.75
    private val cv7P95 = AsiEngine.percentile((1..30).map { it.toDouble() }, 0.95) // 28.55

    // -- Formula-level checks on the shared math helpers (item 5 part 2, item 6) --------------------

    @Test fun penaltyIsZeroBeforeStartAndMaxedAfterFull() {
        assertEquals(0.0, AsiEngine.penalty(x = 0.2, start = 0.5, full = 2.0, maxPoints = 25.0), 1e-9)
        assertEquals(25.0, AsiEngine.penalty(x = 3.0, start = 0.5, full = 2.0, maxPoints = 25.0), 1e-9)
    }

    @Test fun penaltyIsLinearBetweenStartAndFull() {
        // Halfway between start=0.5 and full=2.0 is x=1.25 -> half of maxPoints.
        assertEquals(12.5, AsiEngine.penalty(x = 1.25, start = 0.5, full = 2.0, maxPoints = 25.0), 1e-9)
    }

    @Test fun clampRespectsBothBounds() {
        assertEquals(0.0, AsiEngine.clamp(-5.0, 0.0, 100.0), 1e-9)
        assertEquals(100.0, AsiEngine.clamp(500.0, 0.0, 100.0), 1e-9)
        assertEquals(42.0, AsiEngine.clamp(42.0, 0.0, 100.0), 1e-9)
    }

    @Test fun percentileInterpolatesLinearlyBetweenOrderedObservations() {
        val sorted = (1..30).map { it.toDouble() }
        assertEquals(8.25, AsiEngine.percentile(sorted, 0.25), 1e-9)
        assertEquals(22.75, AsiEngine.percentile(sorted, 0.75), 1e-9)
        assertEquals(28.55, AsiEngine.percentile(sorted, 0.95), 1e-9)
    }

    /**
     * Same shape as [healthyPreceding] but with a wider day-to-day HRV swing (11.0/7.0 instead of
     * 9.5/8.5) -- the raw daily series' own SD is much larger here, while the 7-reading trailing
     * average's SD stays comparatively tight (averaging damps the alternation). Built specifically
     * to exercise the zHRV7 regression below: a real, meaningful dip in the smoothed HRV7 value
     * should register as a smoothed-HRV7 deviation scored against the smoothed series' own
     * (tighter) variance, not get diluted by the much wider daily-reading variance.
     */
    private fun wideSwingPreceding(): List<AsiReading> = (0 until 60).map { i ->
        AsiReading(
            timestampEpochMs = -((i + 1) * DAY_MS),
            hrvScore = if (i % 2 == 0) 11.0 else 7.0,
            rhr = 50.0,
            cv7 = if (i < 30) (i + 1).toDouble() else null,
        )
    }

    // -- Regression: zHRV7/zRHR7 must be scored against the smoothed series' own variance, not the
    // daily series' wider one (a real bug: it silently zeroed out almost every HRV7/RHR7 penalty,
    // pushing scores to 100 far more often than the underlying data warranted). ---------------------

    @Test fun smoothedHrv7DeviationIsPenalizedEvenWhenDailyVarianceIsWide() {
        // today=6.0 pulls hrv7 to (6+11+7+11+7+11+7)/7 = 60/7 ≈ 8.571 -- a real ~0.43 dip below the
        // smoothed series' own mean of 9.0, but tiny next to the raw daily swing's own SD (~2.0).
        // Scored against the daily SD, this z-score would be far too small to cross the HRV7
        // penalty's own 0.3 "start" threshold at all -- see the old formula's result below.
        val result = AsiEngine.compute(AsiInput(healthyToday(hrvScore = 6.0), wideSwingPreceding()))
        val pHrv7 = result.penalties.hrv7
        assertTrue("expected a real HRV7 penalty, got $pHrv7", pHrv7 != null && pHrv7 > 10.0)

        // What the old (reverted) formula would have computed, for comparison: dividing the exact
        // same ~0.43 deviation by the much wider *daily* SD instead of the smoothed series' own.
        val dailyMean = wideSwingPreceding().map { it.hrvScore }.average()
        val dailyVariance = wideSwingPreceding().sumOf { (it.hrvScore - dailyMean) * (it.hrvScore - dailyMean) } / 59
        val dailySd = kotlin.math.sqrt(dailyVariance)
        val hrv7Today = (6.0 + 11.0 + 7.0 + 11.0 + 7.0 + 11.0 + 7.0) / 7.0
        val zUsingDailySd = (hrv7Today - dailyMean) / dailySd
        val oldPenalty = AsiEngine.penalty(-zUsingDailySd, AsiConfig.HRV7_PENALTY_START, AsiConfig.HRV7_PENALTY_FULL, AsiConfig.HRV7_PENALTY_MAX)
        assertEquals(0.0, oldPenalty, 1e-9) // confirms this really would have been missed before the fix
    }

    @Test fun smoothedReferenceNeedsTwoFullNonOverlappingBlocksNotJustFourteenReadings() {
        // 13 preceding readings is only one full non-overlapping 7-block (and 6 leftover, dropped
        // rather than averaged short) -- below AsiConfig.MIN_SMOOTHED_BASELINE_BLOCKS (2), so HRV7
        // can't be scored yet even though the daily parameter (needing only 3) is long since ready.
        val thirteen = AsiEngine.compute(AsiInput(healthyToday(), healthyPreceding().take(13)))
        assertEquals(AsiMissingReason.INSUFFICIENT_HISTORY.name, thirteen.parameters.first { it.key == AsiParameterKey.HRV7 }.statusName)

        // 14 is exactly two full blocks -- just enough.
        val fourteen = AsiEngine.compute(AsiInput(healthyToday(), healthyPreceding().take(14)))
        assertTrue(fourteen.parameters.first { it.key == AsiParameterKey.HRV7 }.available)
    }

    // -- Item 8: all main ASI status bands, item 7: 0-100 limits ------------------------------------

    @Test fun healthyInputScoresMaximumAndFavorable() {
        val result = AsiEngine.compute(AsiInput(healthyToday(), healthyPreceding()))
        assertEquals(100, result.score)
        assertEquals(AsiStatus.FAVORABLE, result.status)
    }

    @Test fun lowHrvDailyPullsScoreIntoMarkedDeviation() {
        // z = (todayHrv - 9.0) / sqrt(15/59). Pushing today's HRV far below baseline maxes pHRV (25pts)
        // and, since zHRV < -0.5, also gates CV7's own penalty -- driving the total well under 50.
        val result = AsiEngine.compute(AsiInput(healthyToday(hrvScore = 3.0, cv7 = 29.0), healthyPreceding()))
        assertEquals(AsiStatus.MARKED_DEVIATION, result.status)
        assertTrue(result.score!! < AsiConfig.CAUTION_MIN)
    }

    @Test fun scoreNeverExceedsOneHundredOrGoesNegative() {
        // Extremely favorable daily reading (and 7-window, since it enters it) can't push score past 100.
        val result = AsiEngine.compute(AsiInput(healthyToday(hrvScore = 20.0), healthyPreceding()))
        assertTrue(result.score!! <= 100)
        assertTrue(result.score!! >= 0)
    }

    // -- Item 11: CV7 gate -- no penalty when HRV isn't unfavorable, even if CV7 itself is very high --

    @Test fun highCv7WithoutUnfavorableHrvCarriesNoPenalty() {
        val result = AsiEngine.compute(AsiInput(healthyToday(cv7 = 29.0), healthyPreceding())) // cv7=29 > P95≈28.55
        assertEquals(0.0, result.penalties.cv7)
        assertEquals(100, result.score) // nothing else is unfavorable either
    }

    @Test fun highCv7WithUnfavorableHrvDoesCarryAPenalty() {
        // zHRV = (8.0 - 9.0) / sqrt(15/59) ≈ -1.29, well past the -0.5 gate.
        val result = AsiEngine.compute(AsiInput(healthyToday(hrvScore = 8.0, cv7 = 29.0), healthyPreceding()))
        assertTrue(result.penalties.cv7!! > 0.0)
    }

    // -- Item 12/13: no bonus for very favorable HRV or very low RHR -- score caps at 100, never above --

    @Test fun veryHighHrvGivesNoBonusAboveTheCap() {
        val result = AsiEngine.compute(AsiInput(healthyToday(hrvScore = 50.0), healthyPreceding()))
        assertEquals(100, result.score)
    }

    @Test fun veryLowRhrGivesNoBonusAboveTheCap() {
        val result = AsiEngine.compute(AsiInput(healthyToday(rhr = 1.0), healthyPreceding()))
        assertEquals(100, result.score)
    }

    // -- Item 17: missing data --------------------------------------------------------------------

    @Test fun nonFiniteHrvTodayYieldsInsufficientData() {
        val result = AsiEngine.compute(AsiInput(healthyToday(hrvScore = Double.NaN), healthyPreceding()))
        assertEquals(AsiStatus.INSUFFICIENT_DATA, result.status)
        assertNull(result.score)
    }

    @Test fun missingCv7TodayStillScoresAsiWithoutImputing() {
        val result = AsiEngine.compute(AsiInput(healthyToday(cv7 = null), healthyPreceding()))
        assertEquals(100, result.score) // CV7 missing contributes exactly zero, not an imputed guess.
        assertEquals(0.0, result.penalties.cv7) // gate isn't even triggered here (zHRV=0), so penalty is a real zero
        assertEquals(AsiMissingReason.MISSING.name, result.parameters.first { it.key == AsiParameterKey.CV7 }.statusName)
    }

    // -- Item 18: insufficient history -> CALIBRATING, and parameters still classify where possible --

    @Test fun shortHistoryYieldsCalibratingWithNullScore() {
        val result = AsiEngine.compute(AsiInput(healthyToday(), healthyPreceding().take(10)))
        assertEquals(AsiStatus.CALIBRATING, result.status)
        assertNull(result.score)
    }

    @Test fun calibratingStillClassifiesParametersThatHaveEnoughOfTheirOwnHistory() {
        // 10 preceding readings is short of the 30-reading ASI-wide minimum, but plenty for the
        // HRV_DAILY parameter's own 3-reading baseline -- spec 14's "conservare le classificazioni
        // anche quando ASI non può essere calcolato, se i singoli parametri sono disponibili."
        val result = AsiEngine.compute(AsiInput(healthyToday(), healthyPreceding().take(10)))
        val hrvDaily = result.parameters.first { it.key == AsiParameterKey.HRV_DAILY }
        assertTrue(hrvDaily.available)
    }

    @Test fun degenerateCv7DistributionAlsoYieldsCalibrating() {
        val flatCv7History = healthyPreceding().map { it.copy(cv7 = if (it.cv7 != null) 10.0 else null) }
        val result = AsiEngine.compute(AsiInput(healthyToday(), flatCv7History))
        assertEquals(AsiStatus.CALIBRATING, result.status) // P95 <= P75 when every historical CV7 is identical.
        assertNull(result.score)
    }

    // -- Item 19: stale recent window ----------------------------------------------------------------

    @Test fun recentWindowSpanningOverTenDaysYieldsStaleWindow() {
        val preceding = healthyPreceding().toMutableList()
        // Space the six most recent entries 2 days apart instead of 1 -> 12-day span, over the limit.
        for (i in 0 until 6) {
            preceding[i] = preceding[i].copy(timestampEpochMs = -((i + 1) * 2 * DAY_MS))
        }
        val result = AsiEngine.compute(AsiInput(healthyToday(), preceding))
        assertEquals(AsiStatus.STALE_WINDOW, result.status)
        assertNull(result.score)
    }

    // -- Item 20: no partial scores for any of the three null-score states --------------------------

    @Test fun nullScoreStatesNeverCarryAPartialNumber() {
        val insufficientData = AsiEngine.compute(AsiInput(healthyToday(hrvScore = Double.NaN), healthyPreceding()))
        val calibrating = AsiEngine.compute(AsiInput(healthyToday(), healthyPreceding().take(5)))
        val stale = run {
            val preceding = healthyPreceding().toMutableList()
            for (i in 0 until 6) preceding[i] = preceding[i].copy(timestampEpochMs = -((i + 1) * 2 * DAY_MS))
            AsiEngine.compute(AsiInput(healthyToday(), preceding))
        }
        assertNull(insufficientData.score)
        assertNull(calibrating.score)
        assertNull(stale.score)
    }

    // -- Item 21: no imputation -- a parameter with no usable data stays unavailable, not defaulted --

    @Test fun insufficientHistoryParametersAreMarkedUnavailableNotDefaulted() {
        val result = AsiEngine.compute(AsiInput(healthyToday(), healthyPreceding().take(2)))
        val hrvDaily = result.parameters.first { it.key == AsiParameterKey.HRV_DAILY }
        assertTrue(!hrvDaily.available)
        assertEquals(AsiMissingReason.INSUFFICIENT_HISTORY.name, hrvDaily.statusName)
        assertNull(hrvDaily.value) // never filled in with a guessed number
    }

    // -- Item 22: no RR/measurement-quality gating inside ASI itself --------------------------------

    @Test fun engineHasNoRrQualityConceptAtAll() {
        // AsiReading only carries hrvScore/rhr/cv7/timestamp -- there is no beat-count, rejected-beat
        // or quality field for ASI to gate on; quality is verified upstream (spec 0's "vincolo"), and
        // ASI treats every reading handed to it as already valid, however it looks.
        val result = AsiEngine.compute(AsiInput(healthyToday(hrvScore = 0.0001, rhr = 0.0001), healthyPreceding()))
        assertNotEquals(AsiStatus.INSUFFICIENT_DATA, result.status) // finite, however implausible -- still accepted.
    }

    // -- Item 26: deterministic output ---------------------------------------------------------------

    @Test fun identicalInputProducesIdenticalOutput() {
        val input = AsiInput(healthyToday(hrvScore = 7.0, cv7 = 25.0), healthyPreceding())
        val first = AsiEngine.compute(input)
        val second = AsiEngine.compute(input)
        assertEquals(first, second)
    }
}
