package com.hrvrm.app.ui.settings

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hrvrm.app.HrvRmApp
import com.hrvrm.app.network.IntervalsIcuRepository
import com.hrvrm.app.network.UploadResult
import java.io.File
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
     * see [com.hrvrm.app.data.MeasurementBackup]) to a cache file and returns a
     * content:// [Uri] for it, or null if there's nothing to back up yet. Caller (the
     * Settings screen) turns this into a share intent, same pattern as the Result
     * screen's raw-data export.
     */
    fun exportBackupFile(onResult: (Uri?) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(backupInProgress = true, backupResult = null) }
            val json = container.measurementRepository.exportBackupJson()
            val context = getApplication<Application>()
            val dir = File(context.cacheDir, "exports/backups").apply { mkdirs() }
            val file = File(dir, "hrv-rm-backup-${System.currentTimeMillis()}.json")
            file.writeText(json)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            _uiState.update { it.copy(backupInProgress = false) }
            onResult(uri)
        }
    }

    /** Restores measurements from a backup file picked via the system file/document UI. */
    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(backupInProgress = true, backupResult = null) }
            val context = getApplication<Application>()
            val message = try {
                val text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: throw IllegalStateException("Couldn't read the selected file.")
                val result = container.measurementRepository.importBackupJson(text)
                "Imported ${result.imported} measurements" +
                    if (result.skippedAlreadyPresent > 0) " (${result.skippedAlreadyPresent} already present, skipped)." else "."
            } catch (t: Throwable) {
                "Couldn't import that file: ${t.message}"
            }
            _uiState.update { it.copy(backupInProgress = false, backupResult = message) }
        }
    }

    private companion object {
        const val TEST_HRV_VALUE = 8.5
    }
}
