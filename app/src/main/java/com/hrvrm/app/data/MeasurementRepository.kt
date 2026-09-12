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
}
