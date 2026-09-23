package com.hrvrm.app.ui.history

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hrvrm.app.HrvRmApp
import com.hrvrm.app.data.MeasurementEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FolderSyncUiState(
    val folderConfigured: Boolean = false,
    val inProgress: Boolean = false,
    val result: String? = null,
)

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val container get() = (getApplication<Application>() as HrvRmApp).container

    val measurements: StateFlow<List<MeasurementEntity>> = container.measurementRepository
        .observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** One point per day (latest measurement wins) for the trend chart, oldest first. */
    val dailyTrend: StateFlow<List<DailyHrvPoint>> = container.measurementRepository
        .observeAll()
        .map(::buildDailyPoints)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Same day-per-latest-measurement rule as [dailyTrend], but for resting heart rate. */
    val dailyRhrTrend: StateFlow<List<DailyRhrPoint>> = container.measurementRepository
        .observeAll()
        .map(::buildDailyRhrPoints)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _folderSyncState = MutableStateFlow(FolderSyncUiState())
    val folderSyncState: StateFlow<FolderSyncUiState> = _folderSyncState.asStateFlow()

    init {
        viewModelScope.launch {
            container.settingsStore.backupFolderUri.collect { uri ->
                _folderSyncState.update { it.copy(folderConfigured = uri != null) }
            }
        }
    }

    /**
     * Reconciles the configured backup folder against the local database — the main use
     * being restoring the whole history after a reinstall, by importing every measurement
     * file the folder has that the (now-empty) local database doesn't. See [FolderSync.syncAll].
     */
    fun syncFolder() {
        viewModelScope.launch {
            val folderUriString = container.settingsStore.backupFolderUri.first()
            if (folderUriString == null) {
                _folderSyncState.update { it.copy(result = "No backup folder set — configure one in Settings.") }
                return@launch
            }
            _folderSyncState.update { it.copy(inProgress = true, result = null) }
            val message = try {
                val result = container.folderSync.syncAll(Uri.parse(folderUriString))
                "Synced: ${result.imported} imported, ${result.exported} exported."
            } catch (t: Throwable) {
                "Couldn't sync: ${t.message}"
            }
            _folderSyncState.update { it.copy(inProgress = false, result = message) }
        }
    }
}
