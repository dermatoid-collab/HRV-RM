package com.hrvrm.app.ui.measure

import com.hrvrm.app.data.MeasurementEntity
import com.hrvrm.app.ppg.BeatRejectionReason

/** One row in the Measuring screen's live beat log — see [MeasureUiState.Measuring.beatLog]. */
data class BeatLogEntry(val elapsedMs: Long, val ibiMs: Long, val rejectionReason: BeatRejectionReason?) {
    val accepted: Boolean get() = rejectionReason == null
}

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
        val beatLog: List<BeatLogEntry> = emptyList(),
    ) : MeasureUiState
    data object Processing : MeasureUiState
    data class Result(val measurement: MeasurementEntity, val uploadInProgress: Boolean) : MeasureUiState
    data class Error(val message: String) : MeasureUiState
}
