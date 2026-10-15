package com.hrvrm.app.hrv

import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No test infrastructure existed in this project before the ASI module (spec section 17 asks for
 * automated tests of "Formula HRV Score", "Baseline HRV7" and "Normal Range 60" -- ASI reuses this
 * calculator as-is rather than reimplementing it, so these tests cover it directly instead of only
 * indirectly through ASI).
 */
class HrvScoreCalculatorTest {

    // -- Spec 17 item 1: HRV Score formula (2 x ln(RMSSD)) --
    @Test fun dailyScoreIsTwiceLnOfRmssd() {
        val result = HrvScoreCalculator.compute(todayRmssdMs = 40.0, priorRmssdMs = emptyList())
        assertEquals(2.0 * ln(40.0), result.altiniScaleValue, 1e-9)
    }

    @Test fun dailyScoreFloorsRmssdAtOneMillisecond() {
        val result = HrvScoreCalculator.compute(todayRmssdMs = 0.2, priorRmssdMs = emptyList())
        assertEquals(2.0 * ln(1.0), result.altiniScaleValue, 1e-9) // ln(1) = 0, not ln(0.2) (negative)
    }

    // -- Spec 17 item 2: HRV7 baseline = mean of last 7 scores including today --
    @Test fun baseline7IsMeanOfLastSevenIncludingToday() {
        // today=40ms, 6 priors all at 20ms -> ln(40) once, ln(20) six times, x2 scale.
        val priors = List(6) { 20.0 }
        val result = HrvScoreCalculator.compute(todayRmssdMs = 40.0, priorRmssdMs = priors)
        val expectedMeanLn = (ln(40.0) + 6 * ln(20.0)) / 7.0
        assertEquals(2.0 * expectedMeanLn, result.baselineAltiniScaleValue, 1e-9)
    }

    @Test fun baseline7IgnoresReadingsBeyondTheSeventh() {
        val priors = List(6) { 20.0 } + List(50) { 999.0 } // should never enter the 7-window
        val result = HrvScoreCalculator.compute(todayRmssdMs = 40.0, priorRmssdMs = priors)
        val expectedMeanLn = (ln(40.0) + 6 * ln(20.0)) / 7.0
        assertEquals(2.0 * expectedMeanLn, result.baselineAltiniScaleValue, 1e-9)
    }

    // -- Spec 17 item 3: Normal Range 60, excluding today --
    @Test fun normalRangeExcludesTodaysOwnReading() {
        val priors = List(60) { 20.0 } // constant -> sd would be 0 without the floor, but never includes today
        val result = HrvScoreCalculator.compute(todayRmssdMs = 9999.0, priorRmssdMs = priors)
        // mean/sd computed purely from the 60 priors (all ln(20)*2), today's wild value must not leak in.
        assertEquals(2.0 * ln(20.0), result.baselineMeanLnRmssd!! * 2.0, 1e-9)
    }

    @Test fun normalRangeBuildingBelowThreePriorReadings() {
        val result = HrvScoreCalculator.compute(todayRmssdMs = 40.0, priorRmssdMs = List(2) { 20.0 })
        assertEquals(BaselineStatus.BUILDING, result.status)
        assertNull(result.score)
        assertNull(result.withinNormalRange)
    }

    @Test fun normalRangeReadyAtThreePriorReadings() {
        val result = HrvScoreCalculator.compute(todayRmssdMs = 40.0, priorRmssdMs = List(3) { 20.0 })
        assertEquals(BaselineStatus.READY, result.status)
        assertTrue(result.score != null)
    }
}
