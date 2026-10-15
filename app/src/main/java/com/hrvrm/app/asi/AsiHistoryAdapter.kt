package com.hrvrm.app.asi

import com.hrvrm.app.data.MeasurementEntity

/**
 * Bridges this app's already-persisted [MeasurementEntity] history to [AsiEngine] -- the "Data
 * preparation" responsibility (spec section 16.A) as far as real app data is concerned. Pure
 * Kotlin, no UI/Compose dependency, so screens (Phase 2) call this instead of touching
 * [AsiEngine]/[AsiInput] directly.
 */
object AsiHistoryAdapter {

    private fun toReading(entity: MeasurementEntity) = AsiReading(
        timestampEpochMs = entity.timestampEpochMs,
        hrvScore = entity.altiniScaleValue,
        rhr = entity.meanHrBpm,
        cv7 = entity.cv7,
    )

    /**
     * ASI for [target], using whichever of [precedingNewestFirst] come immediately before it --
     * same most-recent-first convention and [AsiConfig.BASELINE_WINDOW_SIZE] cap already used for
     * the HRV score baseline (see [com.hrvrm.app.data.MeasurementDao.getPriorMeasurements]).
     */
    fun compute(target: MeasurementEntity, precedingNewestFirst: List<MeasurementEntity>): AsiResult {
        val input = AsiInput(
            today = toReading(target),
            precedingNewestFirst = precedingNewestFirst.take(AsiConfig.BASELINE_WINDOW_SIZE).map(::toReading),
        )
        return AsiEngine.compute(input)
    }

    /**
     * ASI for every entry in [measurementsNewestFirst] (same newest-first order
     * [com.hrvrm.app.data.MeasurementDao.observeAll] already returns), keyed by
     * [MeasurementEntity.id] -- for a screen that needs it per-row (History) rather than for a
     * single measurement (Measure/Detail, which can call [compute] directly).
     */
    fun computeForEach(measurementsNewestFirst: List<MeasurementEntity>): Map<Long, AsiResult> {
        val results = LinkedHashMap<Long, AsiResult>(measurementsNewestFirst.size)
        for (i in measurementsNewestFirst.indices) {
            val target = measurementsNewestFirst[i]
            val preceding = measurementsNewestFirst.subList(i + 1, measurementsNewestFirst.size)
            results[target.id] = compute(target, preceding)
        }
        return results
    }
}
