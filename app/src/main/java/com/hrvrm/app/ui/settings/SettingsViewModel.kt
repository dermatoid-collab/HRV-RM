package com.hrvrm.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hrvrm.app.HrvRmApp
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
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = (getApplication<Application>() as HrvRmApp).container.settingsStore

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
        _uiState.value = _uiState.value.copy(apiKey = value, saved = false)
    }

    fun onAthleteIdChanged(value: String) {
        _uiState.value = _uiState.value.copy(athleteId = value, saved = false)
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
}
