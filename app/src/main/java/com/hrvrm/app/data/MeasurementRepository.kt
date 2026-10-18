package com.hrvrm.app.data

import android.net.Uri
import com.hrvrm.app.asi.AsiConfig
import com.hrvrm.app.asi.AsiHistoryAdapter
import com.hrvrm.app.backup.FolderSync
import com.hrvrm.app.backup.RawSampleStorage
import com.hrvrm.app.hrv.HrvMetrics
import com.hrvrm.app.hrv.HrvScoreCalculator
import com.hrvrm.app.network.IntervalsIcuRepository
import com.hrvrm.app.network.UploadResult
import com.hrvrm.app.ppg.PpgSample
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay
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
    private val rawSampleStorage: RawSampleStorage,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun observeAll(): Flow<List<MeasurementEntity>> = dao.observeAll()

    suspend fun getById(id: Long): MeasurementEntity? = dao.getById(id)

    /** Prior readings before [beforeEpochMs], most recent first -- used by the ASI module (see
     * [com.hrvrm.app.asi.AsiHistoryAdapter]) the same way [saveMeasurement] already uses it for
     * the HRV score baseline. */
    suspend fun getPriorMeasurements(beforeEpochMs: Long, limit: Int): List<MeasurementEntity> =
        dao.getPriorMeasurements(beforeEpochMs, limit)

    suspend fun saveMeasurement(
        timestampEpochMs: Long,
        durationSec: Int,
        metrics: HrvMetrics,
        cleanIbiMs: List<Long>,
        rejectedBeatCount: Int,
        rawSamples: List<PpgSample>,
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
            baselineAltiniScaleValue = scoreResult.baselineAltiniScaleValue,
            normalRangeLowAltiniScale = scoreResult.normalRangeLowAltiniScale,
            normalRangeHighAltiniScale = scoreResult.normalRangeHighAltiniScale,
            cv7 = scoreResult.cv7,
            ibiSeriesJson = json.encodeToString(cleanIbiMs),
        )

        val id = dao.insert(entity)
        var saved = entity.copy(id = id)

        settingsStore.rollThemeAfterMeasurement()

        if (settingsStore.autoUpload.first()) {
            saved = uploadMeasurement(saved)
        }

        settingsStore.backupFolderUri.first()?.let { folderUriString ->
            folderSync.writeMeasurementFile(Uri.parse(folderUriString), saved)
        }

        if (settingsStore.keepRawData.first()) {
            rawSampleStorage.save(saved.id, rawSamples)
        }

        return saved
    }

    /**
     * Deletes a measurement everywhere it could otherwise reappear from: the local row, its
     * raw-sample file if one was kept, and its file in the backup folder if one is
     * configured -- skipping that last part would mean the next "sync" tap re-imports the
     * very measurement just deleted, since the folder would still have its file.
     */
    suspend fun deleteMeasurement(id: Long) {
        val measurement = dao.getById(id) ?: return
        dao.deleteById(id)
        rawSampleStorage.delete(id)
        settingsStore.backupFolderUri.first()?.let { folderUriString ->
            folderSync.deleteMeasurementFile(Uri.parse(folderUriString), measurement.timestampEpochMs)
        }
    }

    suspend fun uploadMeasurement(measurement: MeasurementEntity): MeasurementEntity {
        // ASI needs its own preceding-history window (same AsiConfig.BASELINE_WINDOW_SIZE cap
        // the detail/result screens already use) -- it's null whenever ASI itself is
        // CALIBRATING/INSUFFICIENT_DATA/STALE_WINDOW, in which case ASIRM is simply omitted
        // below rather than sent as a guessed value (see WellnessUpdate's own doc comment).
        val preceding = dao.getPriorMeasurements(measurement.timestampEpochMs, AsiConfig.BASELINE_WINDOW_SIZE)
        val asi = AsiHistoryAdapter.compute(measurement, preceding)

        val result = intervalsRepository.uploadHrv(
            measurementEpochMs = measurement.timestampEpochMs,
            hrvRmValue = measurement.altiniScaleValue,
            rhrValue = measurement.meanHrBpm,
            asiValue = asi.score?.toDouble(),
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
     * One day's outcome within a [resyncHistoryToIntervals] run -- the date it covers (not the
     * individual measurement's own id, since same-day measurements collapse to one upload) and
     * whether that upload succeeded.
     */
    data class ResyncDayResult(val date: LocalDate, val succeeded: Boolean)

    /** Full outcome of a [resyncHistoryToIntervals] run. */
    data class ResyncResult(val days: List<ResyncDayResult>, val missingCredentials: Boolean) {
        val total: Int get() = days.size
        val succeeded: Int get() = days.count { it.succeeded }
        val failed: Int get() = total - succeeded
    }

    /**
     * Re-uploads the entire local history to Intervals.icu -- one request per CALENDAR DAY, not
     * per measurement: a day with more than one reading already collapses to its most recent one
     * for upload purposes (same "latest wins" rule [saveMeasurement]'s own auto-upload already
     * follows for that day, and the same rule the trend charts use to go from measurements to
     * daily points), so re-uploading every individual measurement would just mean redundant
     * requests overwriting each other for the same date. Every day is attempted even after a
     * failure (a transient network error on one day says nothing about the rest), and each
     * day's own [MeasurementEntity.uploadedToIntervals]/[MeasurementEntity.uploadError] is left
     * exactly as [uploadMeasurement] (which this calls) always leaves them.
     *
     * [onProgress] fires after each day (1-indexed "done" count, against the final total) so a
     * caller can show a live "N / total" readout -- this can take a while on a large history,
     * one request at a time ([RESYNC_DELAY_MS] apart, deliberately paced rather than firing every
     * request back to back).
     */
    suspend fun resyncHistoryToIntervals(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): ResyncResult {
        val apiKey = settingsStore.apiKey.first()
        val athleteId = settingsStore.athleteId.first()
        if (apiKey.isNullOrBlank() || athleteId.isNullOrBlank()) {
            return ResyncResult(days = emptyList(), missingCredentials = true)
        }

        val zone = ZoneId.systemDefault()
        val latestPerDay = LinkedHashMap<LocalDate, MeasurementEntity>()
        for (m in dao.getAllOnce().sortedByDescending { it.timestampEpochMs }) {
            val day = Instant.ofEpochMilli(m.timestampEpochMs).atZone(zone).toLocalDate()
            latestPerDay.putIfAbsent(day, m) // input is newest-first, so the first hit per day is the latest
        }
        val dayEntries = latestPerDay.entries.sortedBy { it.key }

        val results = mutableListOf<ResyncDayResult>()
        for ((index, entry) in dayEntries.withIndex()) {
            val (date, measurement) = entry
            val updated = uploadMeasurement(measurement)
            results += ResyncDayResult(date, updated.uploadedToIntervals)
            onProgress(index + 1, dayEntries.size)
            if (index < dayEntries.lastIndex) delay(RESYNC_DELAY_MS)
        }
        return ResyncResult(days = results, missingCredentials = false)
    }

    /**
     * One-time repair after an HrvScoreCalculator formula change (currently: the daily score
     * no longer smoothed, baseline split into its own field, the normal-range SD multiplier
     * widened from 0.5 to 1, and CV7 added -- see HrvScoreCalculator.NORMAL_RANGE_SD_MULTIPLIER
     * and HrvScoreResult.cv7): recomputes every existing row's derived HRV fields (score,
     * normal range, baseline, CV7) from its own already-stored [MeasurementEntity.rmssdMs],
     * walking the full history in chronological order exactly like a live save would. No raw
     * measurement is touched or dropped -- this only rewrites the derived columns so old
     * entries read correctly under the corrected formula instead of showing a stale one. Safe
     * to call on every app start: no-ops once already done. Bump the backfill flag's version
     * suffix (in SettingsStore) whenever the formula changes again, so this reruns for the
     * new constants -- also used (V8 -> V9) as a one-off full recompute to repair imports made
     * before [importBackupJson] started forcing its own recompute, which could otherwise leave
     * imported rows missing newer derived fields (e.g. CV7) the backup predated.
     */
    suspend fun backfillHrvScoresIfNeeded() {
        if (settingsStore.hrvScoreBackfillV9Done.first()) return

        val chronological = dao.getAllOnce().sortedBy { it.timestampEpochMs }
        val priorRmssd = ArrayDeque<Double>() // most-recent-first, capped at BASELINE_WINDOW_SIZE

        for (m in chronological) {
            val scoreResult = HrvScoreCalculator.compute(m.rmssdMs, priorRmssd.toList())
            dao.update(
                m.copy(
                    hrvScore = scoreResult.score,
                    withinNormalRange = scoreResult.withinNormalRange,
                    altiniScaleValue = scoreResult.altiniScaleValue,
                    baselineAltiniScaleValue = scoreResult.baselineAltiniScaleValue,
                    normalRangeLowAltiniScale = scoreResult.normalRangeLowAltiniScale,
                    normalRangeHighAltiniScale = scoreResult.normalRangeHighAltiniScale,
                    cv7 = scoreResult.cv7,
                ),
            )
            priorRmssd.addFirst(m.rmssdMs)
            while (priorRmssd.size > HrvScoreCalculator.BASELINE_WINDOW_SIZE) priorRmssd.removeLast()
        }

        settingsStore.setHrvScoreBackfillV9Done(true)
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

        if (toInsert.isNotEmpty()) {
            dao.insertAll(toInsert)
            // Imported rows carry whatever derived fields (score, normal range, CV7...) the
            // source device had already computed -- which, for a backup made before a field
            // like CV7 existed, means that field comes in null. Force a full recompute now
            // rather than leaving imported rows inconsistent with ones measured on this
            // device, instead of waiting on backfillHrvScoresIfNeeded's own app-start trigger
            // (whose done-flag is already set once this device has backfilled before).
            settingsStore.setHrvScoreBackfillV9Done(false)
            backfillHrvScoresIfNeeded()
        }

        return BackupImportResult(
            imported = toInsert.size,
            skippedAlreadyPresent = backup.measurements.size - toInsert.size,
        )
    }

    private companion object {
        /** Deliberate pause between requests in [resyncHistoryToIntervals] -- a potentially
         * long run of per-day uploads, paced rather than fired back to back. */
        const val RESYNC_DELAY_MS = 150L
    }
}
