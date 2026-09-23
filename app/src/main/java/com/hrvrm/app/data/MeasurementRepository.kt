package com.hrvrm.app.data

import android.net.Uri
import com.hrvrm.app.backup.FolderSync
import com.hrvrm.app.hrv.HrvMetrics
import com.hrvrm.app.hrv.HrvScoreCalculator
import com.hrvrm.app.network.IntervalsIcuRepository
import com.hrvrm.app.network.UploadResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Coordinates local storage of measurements with computing the HRV score and, optionally, upload. */
class MeasurementRepository(
    private val dao: MeasurementDao,
    private val intervalsRepository: IntervalsIcuRepository,
    private val settingsStore: SettingsStore,
    private val folderSync: FolderSync,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun observeAll(): Flow<List<MeasurementEntity>> = dao.observeAll()

    suspend fun getById(id: Long): MeasurementEntity? = dao.getById(id)

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
            meanIbiMs = metrics.meanIbiMs,
            sd1Ms = metrics.sd1Ms,
            sd2Ms = metrics.sd2Ms,
            stressIndex = metrics.stressIndex,
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

        settingsStore.backupFolderUri.first()?.let { folderUriString ->
            folderSync.writeMeasurementFile(Uri.parse(folderUriString), saved)
        }

        return saved
    }

    suspend fun uploadMeasurement(measurement: MeasurementEntity): MeasurementEntity {
        val result = intervalsRepository.uploadHrv(
            measurementEpochMs = measurement.timestampEpochMs,
            hrvRmValue = measurement.altiniScaleValue,
        )
        val updated = when (result) {
            is UploadResult.Success -> measurement.copy(uploadedToIntervals = true, uploadError = null)
            is UploadResult.MissingCredentials -> measurement.copy(uploadedToIntervals = false, uploadError = result.message)
            is UploadResult.Failure -> measurement.copy(uploadedToIntervals = false, uploadError = result.message)
        }
        dao.update(updated)
        return updated
    }

    /** The full local history as a JSON [MeasurementBackup] — see that type's doc comment. */
    suspend fun exportBackupJson(): String {
        val backup = MeasurementBackup(
            exportedAtEpochMs = System.currentTimeMillis(),
            apiKey = settingsStore.apiKey.first(),
            athleteId = settingsStore.athleteId.first(),
            autoUpload = settingsStore.autoUpload.first(),
            measurements = dao.getAllOnce(),
        )
        return json.encodeToString(backup)
    }

    /**
     * Restores measurements from a JSON [MeasurementBackup], skipping any whose
     * [MeasurementEntity.timestampEpochMs] already exists locally (re-importing the same
     * backup, or importing onto a device that isn't fully empty, must not duplicate rows).
     * Imported rows get a fresh, locally-assigned id — the backup's own ids are only
     * meaningful on the device that produced them. Also restores the Intervals.icu
     * credentials and auto-upload preference when the backup carries them, overwriting
     * whatever is currently saved.
     */
    suspend fun importBackupJson(jsonText: String): BackupImportResult {
        val backup = json.decodeFromString<MeasurementBackup>(jsonText)

        if (backup.apiKey != null && backup.athleteId != null) {
            settingsStore.setCredentials(backup.apiKey, backup.athleteId)
        }
        backup.autoUpload?.let { settingsStore.setAutoUpload(it) }

        val existingTimestamps = dao.getAllTimestamps().toSet()
        val toInsert = backup.measurements
            .filter { it.timestampEpochMs !in existingTimestamps }
            .map { it.copy(id = 0) }

        if (toInsert.isNotEmpty()) dao.insertAll(toInsert)

        return BackupImportResult(
            imported = toInsert.size,
            skippedAlreadyPresent = backup.measurements.size - toInsert.size,
        )
    }
}
