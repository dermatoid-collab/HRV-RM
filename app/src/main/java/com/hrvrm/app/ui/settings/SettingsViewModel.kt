package com.hrvrm.app.ui.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hrvrm.app.HrvRmApp
import com.hrvrm.app.backup.notifyBackupResult
import com.hrvrm.app.network.IntervalsIcuRepository
import com.hrvrm.app.network.UploadResult
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val apiKey: String = "",
    val athleteId: String = "",
    val autoUpload: Boolean = true,
    val saved: Boolean = false,
    val testInProgress: Boolean = false,
    val testResult: String? = null,
    val testHrvInProgress: Boolean = false,
    val testHrvResult: String? = null,
    val backupInProgress: Boolean = false,
    val backupResult: String? = null,
    val backupFolderName: String? = null,
    val keepRawData: Boolean = false,
    /** Set once a backup file is picked, cleared once the user confirms or cancels applying
     * it -- see [SettingsViewModel.confirmImport]. Importing overwrites the saved Intervals.icu
     * credentials when the backup carries them, so it isn't applied immediately on picking. */
    val pendingImportUri: Uri? = null,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (getApplication<Application>() as HrvRmApp).container
    private val settingsStore = container.settingsStore
    private val intervalsRepository = IntervalsIcuRepository(settingsStore)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(settingsStore.apiKey, settingsStore.athleteId, settingsStore.autoUpload) { key, id, auto ->
                Triple(key.orEmpty(), id.orEmpty(), auto)
            }.collect { (key, id, auto) ->
                _uiState.update { it.copy(apiKey = key, athleteId = id, autoUpload = auto) }
            }
        }
        viewModelScope.launch {
            settingsStore.backupFolderUri.collect { uriString ->
                val name = uriString?.let { folderDisplayName(Uri.parse(it)) }
                _uiState.update { it.copy(backupFolderName = name) }
            }
        }
        viewModelScope.launch {
            settingsStore.keepRawData.collect { enabled ->
                _uiState.update { it.copy(keepRawData = enabled) }
            }
        }
    }

    fun onKeepRawDataChanged(value: Boolean) {
        viewModelScope.launch { settingsStore.setKeepRawData(value) }
    }

    /**
     * The picked folder's own display name. DocumentFile's name lookup (a query against the
     * tree-rooted document URI) is the normal path and works for most providers -- local
     * storage, Drive, Dropbox -- but some third-party providers don't answer it; a couple
     * were seen answering the same COLUMN_DISPLAY_NAME query against the bare tree URI
     * instead, so that's tried too. The provider's own document id is never shown as a last
     * resort: for some it's a readable path ("primary:Download/HRV-RM-Backup"), but for
     * others it's an opaque account-scoped token ("acc=1;doc=encoded=...") with no folder
     * name in it at all -- a generic label beats leaking either kind of internal id.
     */
    private fun folderDisplayName(uri: Uri): String {
        queryDisplayName(uri)?.let { return it }
        val documentUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        }.getOrNull()
        documentUri?.let { docUri -> queryDisplayName(docUri)?.let { return it } }
        return "Selected folder"
    }

    private fun queryDisplayName(uri: Uri): String? =
        runCatching {
            getApplication<Application>().contentResolver
                .query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Called after the user picks a folder via [androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree]. */
    fun pickBackupFolder(uri: Uri) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            settingsStore.setBackupFolderUri(uri.toString())
            container.folderSync.writeSettingsFile(uri)
        }
    }

    fun forgetBackupFolder() {
        viewModelScope.launch { settingsStore.setBackupFolderUri(null) }
    }

    fun onApiKeyChanged(value: String) {
        _uiState.value = _uiState.value.copy(apiKey = value, saved = false, testResult = null)
    }

    fun onAthleteIdChanged(value: String) {
        _uiState.value = _uiState.value.copy(athleteId = value, saved = false, testResult = null)
    }

    fun onAutoUploadChanged(value: Boolean) {
        viewModelScope.launch { settingsStore.setAutoUpload(value) }
    }

    fun save() {
        val state = _uiState.value
        viewModelScope.launch {
            settingsStore.setCredentials(state.apiKey, state.athleteId)
            _uiState.value = _uiState.value.copy(saved = true)
        }
    }

    /**
     * Saves the current fields, then makes a read-only API call to check they actually
     * work — an intervals.icu API key only works for the athlete who generated it, so a
     * mismatched Athlete ID looks exactly like a "wrong key" 403 on upload. This isolates
     * that without needing a full measurement to find out.
     */
    fun testConnection() {
        val state = _uiState.value
        viewModelScope.launch {
            settingsStore.setCredentials(state.apiKey, state.athleteId)
            _uiState.update { it.copy(saved = true, testInProgress = true, testResult = null) }

            val result = intervalsRepository.testConnection()
            val message = when (result) {
                is UploadResult.Success -> "Connected — API key and Athlete ID match."
                is UploadResult.MissingCredentials -> result.message
                is UploadResult.Failure -> result.message
            }
            _uiState.update { it.copy(testInProgress = false, testResult = message) }
        }
    }

    /** Writes a placeholder value to today's HRVRM field — lets you confirm it accepts writes. */
    fun sendTestHrvValue() {
        viewModelScope.launch {
            _uiState.update { it.copy(testHrvInProgress = true, testHrvResult = null) }
            val result = intervalsRepository.sendTestHrvScore(TEST_HRV_VALUE)
            val message = when (result) {
                is UploadResult.Success -> "Sent $TEST_HRV_VALUE to today's HRV-RM field — check Intervals.icu."
                is UploadResult.MissingCredentials -> result.message
                is UploadResult.Failure -> result.message
            }
            _uiState.update { it.copy(testHrvInProgress = false, testHrvResult = message) }
        }
    }

    /**
     * Writes the full local measurement history (computed metrics only, no raw samples —
     * see [com.hrvrm.app.data.MeasurementBackup]) gzip-compressed to a cache file and
     * returns a content:// [Uri] for it, or null if there's nothing to back up yet. Caller
     * (the Settings screen) turns this into a share intent, same pattern as the Result
     * screen's raw-data export.
     */
    fun exportBackupFile(onResult: (Uri?) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(backupInProgress = true, backupResult = null) }
            val json = container.measurementRepository.exportBackupJson()
            val context = getApplication<Application>()
            val dir = File(context.cacheDir, "exports/backups").apply { mkdirs() }
            val timestamp = LocalDateTime.now().format(BACKUP_FILENAME_FORMATTER)
            val file = File(dir, "hrv_rm_backup_$timestamp.json.gz")
            GZIPOutputStream(file.outputStream()).use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            notifyBackupResult(context, "Backup exported", file.name)
            _uiState.update { it.copy(backupInProgress = false) }
            onResult(uri)
        }
    }

    /**
     * Called right after the user picks a backup file -- stages it behind the confirmation
     * dialog (Settings screen shows it whenever [SettingsUiState.pendingImportUri] is set)
     * rather than importing immediately, since this overwrites the saved Intervals.icu
     * credentials when the backup carries them.
     */
    fun onBackupFilePicked(uri: Uri) {
        _uiState.update { it.copy(pendingImportUri = uri) }
    }

    fun cancelImport() {
        _uiState.update { it.copy(pendingImportUri = null) }
    }

    fun confirmImport() {
        val uri = _uiState.value.pendingImportUri ?: return
        _uiState.update { it.copy(pendingImportUri = null) }
        importBackup(uri)
    }

    /**
     * Restores measurements (and Intervals.icu credentials, if present) from a backup file —
     * see [confirmImport], which is the only caller. Reads gzip-compressed backups (the
     * current format) and plain-JSON ones (backups made before compression was added) alike,
     * by sniffing the gzip magic bytes rather than trusting the file extension.
     */
    private fun importBackup(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(backupInProgress = true, backupResult = null) }
            val context = getApplication<Application>()
            val message = try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Couldn't read the selected file.")
                val text = decodeBackupBytes(bytes)
                val result = container.measurementRepository.importBackupJson(text)
                val summary = "Imported ${result.imported} measurements" +
                    if (result.skippedAlreadyPresent > 0) " (${result.skippedAlreadyPresent} already present, skipped)." else "."
                notifyBackupResult(context, "Backup imported", summary)
                summary
            } catch (t: Throwable) {
                "Couldn't import that file: ${t.message}"
            }
            _uiState.update { it.copy(backupInProgress = false, backupResult = message) }
        }
    }

    private fun decodeBackupBytes(bytes: ByteArray): String {
        val isGzip = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        return if (isGzip) {
            GZIPInputStream(bytes.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
        } else {
            bytes.toString(Charsets.UTF_8)
        }
    }

    private companion object {
        const val TEST_HRV_VALUE = 8.5
        val BACKUP_FILENAME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("ddMMyy_HHmm")
    }
}
