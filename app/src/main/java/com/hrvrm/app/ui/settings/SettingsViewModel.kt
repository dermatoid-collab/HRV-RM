package com.hrvrm.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hrvrm.app.HrvRmApp
import com.hrvrm.app.network.IntervalsIcuRepository
import com.hrvrm.app.network.UploadResult
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
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = (getApplication<Application>() as HrvRmApp).container.settingsStore
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

    /**
     * Writes a placeholder value (50) to today's HRVRM field only — lets you confirm
     * the custom field itself accepts writes without waiting for a real 7-measurement
     * baseline. Leaves hrv/hrvSDNN/restingHR alone, so today's real values are untouched.
     */
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

    private companion object {
        const val TEST_HRV_VALUE = 50
    }
}
