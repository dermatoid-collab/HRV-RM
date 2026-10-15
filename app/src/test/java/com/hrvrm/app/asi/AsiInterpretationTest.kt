package com.hrvrm.app.asi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers spec section 17 items 9, 10, 14-16, 24 and 25 against [AsiInterpretation] directly, using
 * hand-built [AsiContext] fixtures (an internal type, visible to this same-module test) instead of
 * driving full measurement histories through [AsiEngine] -- precise control over each z-score/CV7
 * percentile without needing to reverse-engineer them from a synthetic history.
 */
class AsiInterpretationTest {

    private fun reading(cv7: Double? = 15.0) = AsiReading(timestampEpochMs = 0L, hrvScore = 9.0, rhr = 50.0, cv7 = cv7)

    /** All-neutral by default (every z at 0, CV7 mid-range) -- callers override only what a test needs. */
    private fun context(
        status: AsiStatus = AsiStatus.FAVORABLE,
        score: Int? = 90,
        zHrv: Double? = 0.0,
        zHrv7: Double? = 0.0,
        zRhr: Double? = 0.0,
        zRhr7: Double? = 0.0,
        hrv7: Double? = 9.0,
        rhr7: Double? = 50.0,
        cv7Today: Double? = 15.0,
        cv7P25: Double? = 8.0,
        cv7P75: Double? = 20.0,
        cv7P95: Double? = 28.0,
        precedingHrvCount: Int = 60,
        precedingRhrCount: Int = 60,
        spanDays: Int? = 5,
        unusualResponse: Boolean = false,
    ): AsiContext = AsiContext(
        version = "test",
        today = reading(cv7 = cv7Today),
        status = status,
        score = score,
        zHrv = zHrv, zHrv7 = zHrv7, zRhr = zRhr, zRhr7 = zRhr7,
        hrv7 = hrv7, rhr7 = rhr7,
        hrvBaselineMean = 9.0, hrvBaselineSd = 0.5,
        cv7P25 = cv7P25, cv7P75 = cv7P75, cv7P95 = cv7P95,
        precedingHrvCount = precedingHrvCount, precedingRhrCount = precedingRhrCount,
        spanDays = spanDays,
        penalties = AsiPenalties(0.0, 0.0, 0.0, 0.0, 0.0),
        unusualResponse = unusualResponse,
    )

    private fun AsiResult.param(key: AsiParameterKey) = parameters.first { it.key == key }

    // -- Item 9: every state of each of the five parameters, at their spec-exact boundaries ---------

    @Test fun hrvDailyAllFourStates() {
        assertEquals("LOW", AsiInterpretation.build(context(zHrv = -1.5)).param(AsiParameterKey.HRV_DAILY).statusName)
        assertEquals("NORMAL", AsiInterpretation.build(context(zHrv = -1.0)).param(AsiParameterKey.HRV_DAILY).statusName)
        assertEquals("NORMAL", AsiInterpretation.build(context(zHrv = 1.0)).param(AsiParameterKey.HRV_DAILY).statusName)
        assertEquals("HIGH", AsiInterpretation.build(context(zHrv = 1.5)).param(AsiParameterKey.HRV_DAILY).statusName)
        assertEquals("HIGH", AsiInterpretation.build(context(zHrv = 2.0)).param(AsiParameterKey.HRV_DAILY).statusName)
        assertEquals("UNUSUAL_HIGH", AsiInterpretation.build(context(zHrv = 2.5)).param(AsiParameterKey.HRV_DAILY).statusName)
    }

    @Test fun hrv7AllFourStates() {
        assertEquals("LOW", AsiInterpretation.build(context(zHrv7 = -0.6)).param(AsiParameterKey.HRV7).statusName)
        assertEquals("NORMAL", AsiInterpretation.build(context(zHrv7 = -0.5)).param(AsiParameterKey.HRV7).statusName)
        assertEquals("NORMAL", AsiInterpretation.build(context(zHrv7 = 0.5)).param(AsiParameterKey.HRV7).statusName)
        assertEquals("HIGH", AsiInterpretation.build(context(zHrv7 = 1.0)).param(AsiParameterKey.HRV7).statusName)
        assertEquals("UNUSUAL_HIGH", AsiInterpretation.build(context(zHrv7 = 2.1)).param(AsiParameterKey.HRV7).statusName)
    }

    @Test fun cv7AllFourStates() {
        // p25=8, p75=20, p95=28 (context() defaults).
        assertEquals("LOW", AsiInterpretation.build(context(cv7Today = 5.0)).param(AsiParameterKey.CV7).statusName)
        assertEquals("NORMAL", AsiInterpretation.build(context(cv7Today = 8.0)).param(AsiParameterKey.CV7).statusName)
        assertEquals("NORMAL", AsiInterpretation.build(context(cv7Today = 20.0)).param(AsiParameterKey.CV7).statusName)
        assertEquals("HIGH", AsiInterpretation.build(context(cv7Today = 25.0)).param(AsiParameterKey.CV7).statusName)
        assertEquals("VERY_HIGH", AsiInterpretation.build(context(cv7Today = 29.0)).param(AsiParameterKey.CV7).statusName)
    }

    @Test fun rhrDailyAllFiveStates() {
        assertEquals("UNUSUAL_LOW", AsiInterpretation.build(context(zRhr = -2.5)).param(AsiParameterKey.RHR_DAILY).statusName)
        assertEquals("LOW", AsiInterpretation.build(context(zRhr = -1.5)).param(AsiParameterKey.RHR_DAILY).statusName)
        assertEquals("NORMAL", AsiInterpretation.build(context(zRhr = 0.0)).param(AsiParameterKey.RHR_DAILY).statusName)
        assertEquals("HIGH", AsiInterpretation.build(context(zRhr = 1.5)).param(AsiParameterKey.RHR_DAILY).statusName)
        assertEquals("VERY_HIGH", AsiInterpretation.build(context(zRhr = 2.5)).param(AsiParameterKey.RHR_DAILY).statusName)
    }

    @Test fun rhr7AllFiveStates() {
        assertEquals("UNUSUAL_LOW", AsiInterpretation.build(context(zRhr7 = -2.5)).param(AsiParameterKey.RHR7).statusName)
        assertEquals("LOW", AsiInterpretation.build(context(zRhr7 = -1.0)).param(AsiParameterKey.RHR7).statusName)
        assertEquals("NORMAL", AsiInterpretation.build(context(zRhr7 = 0.0)).param(AsiParameterKey.RHR7).statusName)
        assertEquals("HIGH", AsiInterpretation.build(context(zRhr7 = 1.0)).param(AsiParameterKey.RHR7).statusName)
        assertEquals("VERY_HIGH", AsiInterpretation.build(context(zRhr7 = 2.0)).param(AsiParameterKey.RHR7).statusName)
    }

    // -- Item 10: status/icon/color consistency, pinned exactly against the spec's own tables -------

    @Test fun mainStatusIconColorMatchesSpecTable() {
        assertEquals(Triple("circle-check", AsiColor.SUCCESS, "Regolare"), Triple(AsiStatus.FAVORABLE.icon, AsiStatus.FAVORABLE.color, AsiStatus.FAVORABLE.label))
        assertEquals(Triple("info", AsiColor.INFO, "Lievi scostamenti"), Triple(AsiStatus.MILD_DEVIATION.icon, AsiStatus.MILD_DEVIATION.color, AsiStatus.MILD_DEVIATION.label))
        assertEquals(Triple("triangle-alert", AsiColor.WARNING, "Attenzione"), Triple(AsiStatus.CAUTION.icon, AsiStatus.CAUTION.color, AsiStatus.CAUTION.label))
        assertEquals(Triple("octagon-alert", AsiColor.DANGER, "Scostamenti marcati"), Triple(AsiStatus.MARKED_DEVIATION.icon, AsiStatus.MARKED_DEVIATION.color, AsiStatus.MARKED_DEVIATION.label))
        assertEquals(Triple("hourglass", AsiColor.MUTED, "Calibrazione"), Triple(AsiStatus.CALIBRATING.icon, AsiStatus.CALIBRATING.color, AsiStatus.CALIBRATING.label))
        assertEquals(Triple("circle-help", AsiColor.MUTED, "Dati insufficienti"), Triple(AsiStatus.INSUFFICIENT_DATA.icon, AsiStatus.INSUFFICIENT_DATA.color, AsiStatus.INSUFFICIENT_DATA.label))
        assertEquals(Triple("calendar-clock", AsiColor.MUTED, "Dati non recenti"), Triple(AsiStatus.STALE_WINDOW.icon, AsiStatus.STALE_WINDOW.color, AsiStatus.STALE_WINDOW.label))
    }

    @Test fun parameterResultCarriesTheClassifiedEnumsOwnIconAndColor() {
        val result = AsiInterpretation.build(context(zHrv = -1.5)) // -> HrvDailyStatus.LOW
        val hrvDaily = result.param(AsiParameterKey.HRV_DAILY)
        assertEquals(HrvDailyStatus.LOW.icon, hrvDaily.icon)
        assertEquals(HrvDailyStatus.LOW.color, hrvDaily.color)
    }

    @Test fun unavailableParameterCarriesItsMissingReasonsIconAndColor() {
        val result = AsiInterpretation.build(context(zHrv = null))
        val hrvDaily = result.param(AsiParameterKey.HRV_DAILY)
        assertEquals(AsiMissingReason.INSUFFICIENT_HISTORY.icon, hrvDaily.icon)
        assertEquals(AsiMissingReason.INSUFFICIENT_HISTORY.color, hrvDaily.color)
    }

    // -- Item 14: all ten interpretive combinations are individually detectable ----------------------

    @Test fun combo01PersistentConcordantSignals() {
        val r = AsiInterpretation.build(context(zHrv7 = -1.0, zRhr7 = 1.0))
        assertTrue(r.detectedCombinations.contains(AsiCombination.COMBO_01))
        assertEquals("HRV7 ridotta e RHR7 elevata: segnali concordanti.", AsiCombination.COMBO_01.comment)
    }

    @Test fun combo02IsolatedAcuteHrvDrop() {
        val r = AsiInterpretation.build(context(zHrv = -2.0, zHrv7 = 0.0, zRhr = 0.0))
        assertTrue(r.detectedCombinations.contains(AsiCombination.COMBO_02))
    }

    @Test fun combo03PersistentlyReducedHrvWithLowCv7() {
        val r = AsiInterpretation.build(context(zHrv7 = -1.0, cv7Today = 5.0)) // p25 default = 8.0
        assertTrue(r.detectedCombinations.contains(AsiCombination.COMBO_03))
    }

    @Test fun combo04AcuteHighRhrAlone() {
        val r = AsiInterpretation.build(context(zRhr = 2.0, zHrv = 0.0, zHrv7 = 0.0))
        assertTrue(r.detectedCombinations.contains(AsiCombination.COMBO_04))
    }

    @Test fun combo05PossibleNormalization() {
        val r = AsiInterpretation.build(context(zHrv7 = -1.0, zHrv = 0.0, zRhr = 0.0))
        assertTrue(r.detectedCombinations.contains(AsiCombination.COMBO_05))
    }

    @Test fun combo06RegularProfile() {
        val r = AsiInterpretation.build(context(status = AsiStatus.MILD_DEVIATION, cv7Today = 15.0)) // all-neutral default, cv7 <= p75=20
        assertTrue(r.detectedCombinations.contains(AsiCombination.COMBO_06))
    }

    @Test fun combo07UnusualResponse() {
        val r = AsiInterpretation.build(context(zHrv = 3.0, zHrv7 = 0.0, zRhr = 0.0, zRhr7 = 0.0))
        assertTrue(r.detectedCombinations.contains(AsiCombination.COMBO_07))
    }

    @Test fun combo08HrvInstability() {
        val r = AsiInterpretation.build(context(cv7Today = 30.0, zHrv7 = 0.0)) // p95 default = 28.0
        assertTrue(r.detectedCombinations.contains(AsiCombination.COMBO_08))
    }

    @Test fun combo09ElevatedRhr7() {
        val r = AsiInterpretation.build(context(zRhr7 = 2.0, zHrv7 = 0.0))
        assertTrue(r.detectedCombinations.contains(AsiCombination.COMBO_09))
    }

    @Test fun combo10ConcordantAcuteAnomaly() {
        val r = AsiInterpretation.build(context(zHrv = -2.0, zRhr = 2.0))
        assertTrue(r.detectedCombinations.contains(AsiCombination.COMBO_10))
    }

    // -- Item 15/16: priority among simultaneous combinations, with every detected code retained -----

    @Test fun combo01OutranksCombo10WhenBothFire() {
        val r = AsiInterpretation.build(context(zHrv = -2.0, zHrv7 = -1.0, zRhr = 2.0, zRhr7 = 1.0))
        assertTrue(r.detectedCombinations.containsAll(listOf(AsiCombination.COMBO_01, AsiCombination.COMBO_10)))
        assertEquals(AsiCombination.COMBO_01, r.primaryCombination)
        assertEquals(AsiCombination.COMBO_01.comment, r.interpretation)
    }

    @Test fun combo06IsSuppressedAsPrimaryWhenMainStatusIsFavorable() {
        val r = AsiInterpretation.build(context(status = AsiStatus.FAVORABLE))
        assertTrue(r.detectedCombinations.contains(AsiCombination.COMBO_06)) // still retained...
        assertNull(r.primaryCombination) // ...but never shown as primary under FAVORABLE
        assertNull(r.interpretation)
    }

    @Test fun combo06CanBePrimaryWhenMainStatusIsNotFavorable() {
        val r = AsiInterpretation.build(context(status = AsiStatus.MILD_DEVIATION))
        assertEquals(AsiCombination.COMBO_06, r.primaryCombination)
    }

    @Test fun noDetectedCombinationLeavesInterpretationNull() {
        // z's chosen so none of the ten conditions hold (mild, mixed signals); cv7=25 sits above
        // p75=20 specifically to also keep COMBO_06 (which needs cv7 <= p75) from firing here.
        val r = AsiInterpretation.build(context(zHrv = 0.3, zHrv7 = 0.2, zRhr = 0.3, zRhr7 = 0.2, cv7Today = 25.0))
        assertNull(r.primaryCombination)
        assertNull(r.interpretation)
        assertTrue(r.detectedCombinations.isEmpty())
    }

    // -- Confidence (spec 12) --------------------------------------------------------------------------

    @Test fun confidenceUnavailableWhenStatusIsInsufficientData() {
        val r = AsiInterpretation.build(context(status = AsiStatus.INSUFFICIENT_DATA, score = null))
        assertEquals(AsiConfidenceStatus.UNAVAILABLE, r.confidence.status)
    }

    @Test fun confidenceLowWhenCalibratingWithAtLeastAMinimalBaseline() {
        val r = AsiInterpretation.build(context(status = AsiStatus.CALIBRATING, score = null, precedingHrvCount = 5, precedingRhrCount = 5))
        assertEquals(AsiConfidenceStatus.LOW, r.confidence.status)
    }

    @Test fun confidenceUnavailableWhenCalibratingWithoutEvenAMinimalBaseline() {
        val r = AsiInterpretation.build(context(status = AsiStatus.CALIBRATING, score = null, precedingHrvCount = 1, precedingRhrCount = 1))
        assertEquals(AsiConfidenceStatus.UNAVAILABLE, r.confidence.status)
    }

    @Test fun confidenceHighWithAFullSixtyReadingBaselineOnBothSeries() {
        val r = AsiInterpretation.build(context(precedingHrvCount = 60, precedingRhrCount = 60))
        assertEquals(AsiConfidenceStatus.HIGH, r.confidence.status)
    }

    @Test fun confidenceModerateWhenScoredButUnderSixtyReadings() {
        val r = AsiInterpretation.build(context(precedingHrvCount = 40, precedingRhrCount = 60))
        assertEquals(AsiConfidenceStatus.MODERATE, r.confidence.status)
    }

    // -- Item 24: synthetic comment never repeats itself ------------------------------------------

    @Test fun summaryHeadlineNeverRepeatsTheInterpretationSentence() {
        val r = AsiInterpretation.build(context(zHrv = -2.0, zRhr = 2.0, status = AsiStatus.MARKED_DEVIATION, score = 20)) // COMBO_10
        val summary = AsiInterpretation.buildSummaryText(r)
        assertTrue(summary.interpretation!!.isNotBlank())
        assertFalse(summary.headline.contains(summary.interpretation!!))
        assertFalse(summary.parametersLine.contains(summary.interpretation!!))
    }

    @Test fun noComboCommentDuplicatesAnyStatusComment() {
        val statusComments = AsiStatus.entries.map { it.baseComment }.toSet()
        val comboComments = AsiCombination.entries.map { it.comment }.toSet()
        assertTrue(statusComments.intersect(comboComments).isEmpty())
    }

    // -- Item 25: every icon-bearing enum has a non-blank icon name (accessibility mapping requires one) --

    @Test fun everyIconAndLabelIsNonBlank() {
        AsiStatus.entries.forEach { assertTrue(it.icon.isNotBlank()); assertTrue(it.label.isNotBlank()) }
        HrvDailyStatus.entries.forEach { assertTrue(it.icon.isNotBlank()) }
        Hrv7Status.entries.forEach { assertTrue(it.icon.isNotBlank()) }
        Cv7Status.entries.forEach { assertTrue(it.icon.isNotBlank()) }
        RhrDailyStatus.entries.forEach { assertTrue(it.icon.isNotBlank()) }
        Rhr7Status.entries.forEach { assertTrue(it.icon.isNotBlank()) }
        AsiMissingReason.entries.forEach { assertTrue(it.icon.isNotBlank()) }
        AsiCombination.entries.forEach { assertTrue(it.icon.isNotBlank()); assertTrue(it.comment.isNotBlank()) }
        AsiException.entries.forEach { assertTrue(it.icon.isNotBlank()) }
        AsiConfidenceStatus.entries.forEach { assertTrue(it.icon.isNotBlank()) }
        AsiParameterKey.entries.forEach { assertTrue(it.label.isNotBlank()) }
    }
}
