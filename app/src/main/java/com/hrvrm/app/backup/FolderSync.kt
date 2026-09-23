package com.hrvrm.app.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.hrvrm.app.data.MeasurementDao
import com.hrvrm.app.data.MeasurementEntity
import com.hrvrm.app.data.SettingsStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val MEASUREMENT_FILENAME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("ddMMyy_HHmmss")
private const val MEASUREMENT_FILE_PREFIX = "hrv_rm_measurement_"
private const val SETTINGS_FILE_NAME = "hrv_rm_settings.json"

@Serializable
private data class FolderSettingsFile(
    val apiKey: String? = null,
    val athleteId: String? = null,
    val autoUpload: Boolean? = null,
)

data class FolderSyncResult(val exported: Int, val imported: Int)

/**
 * Keeps a user-chosen Storage-Access-Framework folder — can be a Google Drive, Dropbox, or
 * any other document-provider-backed folder, not just local storage, since SAF hands back
 * whatever tree the user picks from any installed provider — mirroring the local
 * measurement history as one small JSON file per measurement, plus a settings file with
 * the Intervals.icu credentials. Two write paths:
 *  - automatic, one file at a time, right after each measurement finishes (see
 *    [com.hrvrm.app.data.MeasurementRepository.saveMeasurement]) — keeps the folder current
 *    as you go, same idea as the existing auto-upload to Intervals.icu;
 *  - manual, full reconciliation via [syncAll], triggered from the History screen's sync
 *    icon. Its main purpose isn't catching up a few missed files — it's restoring the
 *    whole history onto a fresh install: it imports every measurement file in the folder
 *    that isn't in the local (now-empty) database yet, the same dedup-by-timestamp rule as
 *    "Import backup" uses, just spread across many small per-measurement files instead of
 *    one big one.
 *
 * Every write is best-effort: a folder that's since been deleted, unmounted, or had its
 * permission revoked must not crash a measurement save or a manual sync — it just leaves
 * that file (or this whole pass) unsynced for the next attempt to pick up.
 */
class FolderSync(
    private val context: Context,
    private val dao: MeasurementDao,
    private val settingsStore: SettingsStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Called right after a measurement is saved locally — see [writeMeasurementFileUnchecked]. */
    suspend fun writeMeasurementFile(folderUri: Uri, measurement: MeasurementEntity) = runCatching {
        withContext(Dispatchers.IO) {
            val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext
            val name = measurementFileName(measurement.timestampEpochMs)
            if (folder.findFile(name) != null) return@withContext
            writeMeasurementFileUnchecked(folder, name, measurement)
        }
    }

    suspend fun writeSettingsFile(folderUri: Uri) = runCatching {
        withContext(Dispatchers.IO) {
            val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext
            val settings = FolderSettingsFile(
                apiKey = settingsStore.apiKey.first(),
                athleteId = settingsStore.athleteId.first(),
                autoUpload = settingsStore.autoUpload.first(),
            )
            val file = folder.findFile(SETTINGS_FILE_NAME)
                ?: folder.createFile("application/json", SETTINGS_FILE_NAME)
                ?: return@withContext
            context.contentResolver.openOutputStream(file.uri, "wt")?.use {
                it.write(json.encodeToString(settings).toByteArray(Charsets.UTF_8))
            }
        }
    }

    /**
     * Full bidirectional reconciliation: writes any local measurement missing from the
     * folder, imports any folder file whose timestamp isn't in the local database yet, and
     * refreshes the settings file. Safe to call repeatedly — everything is keyed by
     * [MeasurementEntity.timestampEpochMs], so nothing is exported or imported twice.
     */
    suspend fun syncAll(folderUri: Uri): FolderSyncResult = withContext(Dispatchers.IO) {
        val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext FolderSyncResult(0, 0)
        writeSettingsFile(folderUri)

        val remoteFiles = folder.listFiles().filter { it.name?.startsWith(MEASUREMENT_FILE_PREFIX) == true }
        val remoteTimestamps = mutableSetOf<Long>()
        val remoteMeasurements = mutableListOf<MeasurementEntity>()
        for (remoteFile in remoteFiles) {
            val measurement = readMeasurementFile(remoteFile) ?: continue
            remoteTimestamps.add(measurement.timestampEpochMs)
            remoteMeasurements.add(measurement)
        }

        val localMeasurements = dao.getAllOnce()
        val localTimestamps = localMeasurements.map { it.timestampEpochMs }.toSet()

        var exported = 0
        for (measurement in localMeasurements) {
            if (measurement.timestampEpochMs !in remoteTimestamps) {
                val name = measurementFileName(measurement.timestampEpochMs)
                runCatching { writeMeasurementFileUnchecked(folder, name, measurement) }
                exported++
            }
        }

        val toImport = remoteMeasurements
            .filter { it.timestampEpochMs !in localTimestamps }
            .map { it.copy(id = 0) }
        if (toImport.isNotEmpty()) dao.insertAll(toImport)

        FolderSyncResult(exported = exported, imported = toImport.size)
    }

    private fun writeMeasurementFileUnchecked(folder: DocumentFile, name: String, measurement: MeasurementEntity) {
        val file = folder.createFile("application/json", name) ?: return
        context.contentResolver.openOutputStream(file.uri)?.use {
            it.write(json.encodeToString(measurement).toByteArray(Charsets.UTF_8))
        }
    }

    private fun readMeasurementFile(file: DocumentFile): MeasurementEntity? = runCatching {
        val text = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
            ?.toString(Charsets.UTF_8) ?: return null
        json.decodeFromString<MeasurementEntity>(text)
    }.getOrNull()

    private fun measurementFileName(timestampEpochMs: Long): String {
        val dateTime = Instant.ofEpochMilli(timestampEpochMs).atZone(ZoneId.systemDefault())
        return "$MEASUREMENT_FILE_PREFIX${dateTime.format(MEASUREMENT_FILENAME_FORMATTER)}.json"
    }
}
