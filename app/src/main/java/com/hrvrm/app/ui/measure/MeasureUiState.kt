package com.hrvrm.app.ui.measure

import com.hrvrm.app.data.MeasurementEntity

sealed interface MeasureUiState {
    data object Idle : MeasureUiState
    data object NeedsPermission : MeasureUiState
    data object NoFlash : MeasureUiState
    data class Stabilizing(val remainingSec: Int, val totalSec: Int) : MeasureUiState
    data class Measuring(
        val remainingSec: Int,
        val totalSec: Int,
        val liveBpm: Double?,
        val waveform: List<Double>,
    ) : MeasureUiState
    data object Processing : MeasureUiState
    data class Result(val measurement: MeasurementEntity, val uploadInProgress: Boolean) : MeasureUiState
    data class Error(val message: String) : MeasureUiState
}
