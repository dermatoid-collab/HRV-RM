package com.hrvrm.app.ui.measure

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.hrvrm.app.HrvRmApp
import com.hrvrm.app.hrv.HrvMetricsCalculator
import com.hrvrm.app.ppg.PpgCameraSource
import com.hrvrm.app.ppg.PpgSample
import com.hrvrm.app.ppg.PpgSignalProcessor
import java.util.Collections
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MeasurementViewModel(application: Application) : AndroidViewModel(application) {

    private val container get() = (getApplication<Application>() as HrvRmApp).container
    private val cameraSource = PpgCameraSource(application)
    private val processor = PpgSignalProcessor()
    private val samples = Collections.synchronizedList(mutableListOf<PpgSample>())

    private var collecting = false
    private var measureJob: Job? = null

    private val _uiState = MutableStateFlow<MeasureUiState>(MeasureUiState.Idle)
    val uiState: StateFlow<MeasureUiState> = _uiState.asStateFlow()

    init {
        cameraSource.onSample = { sample -> if (collecting) samples.add(sample) }
        cameraSource.onError = { throwable ->
            _uiState.value = MeasureUiState.Error(throwable.message ?: "Errore della fotocamera.")
        }
    }

    fun requestPermission() {
        _uiState.value = MeasureUiState.NeedsPermission
    }

    fun onPermissionResult(granted: Boolean, lifecycleOwner: LifecycleOwner) {
        if (granted) {
            start(lifecycleOwner)
        } else {
            _uiState.value = MeasureUiState.Error(
                "Serve il permesso fotocamera per misurare l'HRV.",
            )
        }
    }

    fun start(lifecycleOwner: LifecycleOwner) {
        if (!cameraSource.hasFlash()) {
            _uiState.value = MeasureUiState.NoFlash
            return
        }

        samples.clear()
        collecting = false
        cameraSource.start(lifecycleOwner)

        measureJob?.cancel()
        measureJob = viewModelScope.launch {
            for (remaining in STABILIZE_SEC downTo 1) {
                _uiState.value = MeasureUiState.Stabilizing(remaining, STABILIZE_SEC)
                delay(1000)
            }

            samples.clear()
            collecting = true

            for (remaining in MEASURE_SEC downTo 1) {
                delay(1000)
                val snapshot = synchronized(samples) { samples.toList() }
                val liveBpm = estimateLiveBpm(snapshot)
                val waveformTail = snapshot.takeLast(150).map { it.intensity }
                _uiState.value = MeasureUiState.Measuring(
                    remainingSec = remaining - 1,
                    totalSec = MEASURE_SEC,
                    liveBpm = liveBpm,
                    waveform = waveformTail,
                )
            }

            collecting = false
            cameraSource.stop()
            finishMeasurement()
        }
    }

    fun cancel() {
        measureJob?.cancel()
        collecting = false
        cameraSource.stop()
        _uiState.value = MeasureUiState.Idle
    }

    fun reset() {
        _uiState.value = MeasureUiState.Idle
    }

    fun retryUpload() {
        viewModelScope.launch {
            val current = _uiState.value
            if (current is MeasureUiState.Result) {
                _uiState.value = current.copy(uploadInProgress = true)
                val measurement = container.measurementRepository.uploadMeasurement(current.measurement)
                _uiState.value = MeasureUiState.Result(measurement, uploadInProgress = false)
            }
        }
    }

    private fun estimateLiveBpm(snapshot: List<PpgSample>): Double? {
        if (snapshot.size < 30) return null
        val recentWindowMs = 8000L
        val cutoff = snapshot.last().timestampMs - recentWindowMs
        val windowed = snapshot.filter { it.timestampMs >= cutoff }
        val result = processor.process(windowed)
        val ibis = result.cleanIbiMs
        if (ibis.isEmpty()) return null
        val meanIbi = ibis.takeLast(5).average()
        return 60_000.0 / meanIbi
    }

    private suspend fun finishMeasurement() {
        _uiState.value = MeasureUiState.Processing
        val snapshot = synchronized(samples) { samples.toList() }
        val result = processor.process(snapshot)
        val metrics = HrvMetricsCalculator.compute(result.cleanIbiMs)

        if (metrics == null) {
            _uiState.value = MeasureUiState.Error(
                "Segnale insufficiente per calcolare l'HRV. Tieni fermo il dito su camera e flash e riprova.",
            )
            return
        }

        val saved = container.measurementRepository.saveMeasurement(
            timestampEpochMs = System.currentTimeMillis(),
            durationSec = MEASURE_SEC,
            metrics = metrics,
            cleanIbiMs = result.cleanIbiMs,
            rejectedBeatCount = result.rejectedBeatCount,
        )

        _uiState.value = MeasureUiState.Result(saved, uploadInProgress = false)
    }

    override fun onCleared() {
        super.onCleared()
        cameraSource.shutdown()
    }

    companion object {
        const val STABILIZE_SEC = 5
        const val MEASURE_SEC = 60
    }
}
