package com.hrvrm.app.data

import com.hrvrm.app.hrv.HrvMetrics
import com.hrvrm.app.hrv.HrvScoreCalculator
import com.hrvrm.app.network.IntervalsIcuRepository
import com.hrvrm.app.network.UploadResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt
import kotlin.random.Random

/** Coordinates local storage of measurements with computing the HRV score and, optionally, upload. */
class MeasurementRepository(
    private val dao: MeasurementDao,
    private val intervalsRepository: IntervalsIcuRepository,
    private val settingsStore: SettingsStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun observeAll(): Flow<List<MeasurementEntity>> = dao.observeAll()

    suspend fun saveMeasurement(
        timestampEpochMs: Long,
        durationSec: Int,
        metrics: HrvMetrics,
        cleanIbiMs: List<Long>,
        rejectedBeatCount: Int,
    ): MeasurementEntity {
        val priorRmssd = dao.getPriorMeasurements(timestampEpochMs, HrvScoreCalculator.BASELINE_WINDOW_SIZE)
            .map { it.rmssdMs }
        val scoreResult = HrvScoreCalculator.compute(metrics.rmssdMs, priorRmssd)

        val entity = MeasurementEntity(
            timestampEpochMs = timestampEpochMs,
            durationSec = durationSec,
            meanHrBpm = metrics.meanHrBpm,
            sdnnMs = metrics.sdnnMs,
            rmssdMs = metrics.rmssdMs,
            pnn50Percent = metrics.pnn50Percent,
            normalizedHrvPercent = metrics.normalizedHrvPercent,
            beatCount = metrics.beatCount,
            rejectedBeatCount = rejectedBeatCount,
            hrvScore = scoreResult.score,
            withinNormalRange = scoreResult.withinNormalRange,
            altiniScaleValue = scoreResult.altiniScaleValue,
            normalRangeLowAltiniScale = scoreResult.normalRangeLowAltiniScale,
            normalRangeHighAltiniScale = scoreResult.normalRangeHighAltiniScale,
            ibiSeriesJson = json.encodeToString(cleanIbiMs),
        )

        val id = dao.insert(entity)
        var saved = entity.copy(id = id)

        if (settingsStore.autoUpload.first()) {
            saved = uploadMeasurement(saved)
        }
        return saved
    }

    suspend fun uploadMeasurement(measurement: MeasurementEntity): MeasurementEntity {
        val result = intervalsRepository.uploadHrv(
            measurementEpochMs = measurement.timestampEpochMs,
            rmssdMs = measurement.rmssdMs,
            sdnnMs = measurement.sdnnMs,
            hrvRmValue = measurement.altiniScaleValue,
            restingHrBpm = measurement.meanHrBpm.roundToInt(),
        )
        val updated = when (result) {
            is UploadResult.Success -> measurement.copy(uploadedToIntervals = true, uploadError = null)
            is UploadResult.MissingCredentials -> measurement.copy(uploadedToIntervals = false, uploadError = result.message)
            is UploadResult.Failure -> measurement.copy(uploadedToIntervals = false, uploadError = result.message)
        }
        dao.update(updated)
        return updated
    }

    /**
     * Inserts [days] of plausible-looking past measurements (oldest first) so the trend
     * chart and baseline can be tried out without waiting for real history to build up.
     * Runs through the same scoring path as a real measurement (each day's score sees
     * only the earlier synthetic days as its baseline) but never touches Intervals.icu —
     * this is local-only test data, not something that should show up as real wellness
     * entries on the server.
     */
    suspend fun seedSampleHistory(days: Int): Int {
        val now = System.currentTimeMillis()
        val random = Random(now)
        var rmssd = 46.0

        for (daysAgo in days downTo 1) {
            val timestampEpochMs = now - daysAgo * 86_400_000L
            rmssd = (rmssd + (random.nextDouble() - 0.5) * 6.0).coerceIn(26.0, 95.0)
            val meanHr = (58.0 - (rmssd - 46.0) * 0.25 + (random.nextDouble() - 0.5) * 3.0).coerceIn(42.0, 80.0)
            val meanIbi = 60_000.0 / meanHr

            val metrics = HrvMetrics(
                meanHrBpm = meanHr,
                meanIbiMs = meanIbi,
                sdnnMs = rmssd * 1.3,
                rmssdMs = rmssd,
                pnn50Percent = (rmssd / 2.0).coerceIn(0.0, 55.0),
                beatCount = 55,
                normalizedHrvPercent = 100.0 * rmssd / meanIbi,
            )

            val priorRmssd = dao.getPriorMeasurements(timestampEpochMs, HrvScoreCalculator.BASELINE_WINDOW_SIZE)
                .map { it.rmssdMs }
            val scoreResult = HrvScoreCalculator.compute(metrics.rmssdMs, priorRmssd)

            dao.insert(
                MeasurementEntity(
                    timestampEpochMs = timestampEpochMs,
                    durationSec = 60,
                    meanHrBpm = metrics.meanHrBpm,
                    sdnnMs = metrics.sdnnMs,
                    rmssdMs = metrics.rmssdMs,
                    pnn50Percent = metrics.pnn50Percent,
                    normalizedHrvPercent = metrics.normalizedHrvPercent,
                    beatCount = metrics.beatCount,
                    rejectedBeatCount = 0,
                    hrvScore = scoreResult.score,
                    withinNormalRange = scoreResult.withinNormalRange,
                    altiniScaleValue = scoreResult.altiniScaleValue,
                    normalRangeLowAltiniScale = scoreResult.normalRangeLowAltiniScale,
                    normalRangeHighAltiniScale = scoreResult.normalRangeHighAltiniScale,
                    ibiSeriesJson = "[]",
                ),
            )
        }
        return days
    }

    suspend fun clearAllHistory() = dao.deleteAll()
}
