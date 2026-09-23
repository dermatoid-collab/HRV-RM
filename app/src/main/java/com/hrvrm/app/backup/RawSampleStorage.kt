package com.hrvrm.app.backup

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.hrvrm.app.ppg.PpgSample
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Same shape as the Result screen's one-off share export (MeasurementViewModel.RawSampleExport). */
@Serializable
private data class RawSampleFile(val exportedAtEpochMs: Long, val samples: List<PpgSample>)

/**
 * Opt-in (see [com.hrvrm.app.data.SettingsStore.keepRawData], default off) private-storage
 * persistence of the raw PPG samples behind a measurement, so "Export raw data" — until now
 * only available for the measurement just taken, kept purely in memory (see
 * [com.hrvrm.app.ui.measure.MeasurementViewModel.exportRawSamplesFile]) — also works from a
 * past measurement's History detail screen. Off by default: unlike everything else this app
 * persists, these files are meaningfully sized (~11KB gzip-compressed per 60s measurement,
 * measured against real recordings — ~79KB uncompressed) and are the one kind of data this
 * app's own backup formats deliberately exclude, so keeping them is a size/privacy tradeoff
 * the user opts into rather than a default.
 */
class RawSampleStorage(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val dir get() = File(context.filesDir, "raw_samples").apply { mkdirs() }
    private fun storedFile(measurementId: Long) = File(dir, "raw_$measurementId.json.gz")

    suspend fun save(measurementId: Long, samples: List<PpgSample>) = runCatching {
        withContext(Dispatchers.IO) {
            val payload = RawSampleFile(exportedAtEpochMs = System.currentTimeMillis(), samples = samples)
            GZIPOutputStream(storedFile(measurementId).outputStream()).use {
                it.write(json.encodeToString(payload).toByteArray(Charsets.UTF_8))
            }
        }
    }

    suspend fun hasStoredSamples(measurementId: Long): Boolean =
        withContext(Dispatchers.IO) { storedFile(measurementId).exists() }

    suspend fun delete(measurementId: Long) = withContext(Dispatchers.IO) { storedFile(measurementId).delete() }

    /**
     * Decompresses the stored file into the same plain-JSON share format used by the
     * Result screen's one-off export, and returns a content:// [Uri] for it (via
     * [FileProvider]) — or null if nothing was ever stored for this measurement (the
     * setting was off at the time, or it predates this feature).
     */
    suspend fun exportSharedFile(measurementId: Long): Uri? = withContext(Dispatchers.IO) {
        val stored = storedFile(measurementId)
        if (!stored.exists()) return@withContext null
        val text = GZIPInputStream(stored.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(exportsDir, "hrv-rm-raw-$measurementId.json")
        file.writeText(text)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}
