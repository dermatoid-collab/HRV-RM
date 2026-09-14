package com.hrvrm.app.ui.measure

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.hrvrm.app.HrvRmApp
import com.hrvrm.app.hrv.HrvMetricsCalculator
import com.hrvrm.app.ppg.PpgCameraSource
import com.hrvrm.app.ppg.PpgProcessingResult
import com.hrvrm.app.ppg.PpgSample
import com.hrvrm.app.ppg.PpgSignalProcessor
import java.io.File
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Shape of a raw-sample export file — see [MeasurementViewModel.exportRawSamplesFile]. */
@Serializable
private data class RawSampleExport(
    val exportedAtEpochMs: Long,
    val samples: List<PpgSample>,
)

class MeasurementViewModel(application: Application) : AndroidViewModel(application) {

    private val container get() = (getApplication<Application>() as HrvRmApp).container
    private val cameraSource = PpgCameraSource(application)
    private val processor = PpgSignalProcessor()
    private val samples = Collections.synchronizedList(mutableListOf<PpgSample>())

    // Raw samples behind the most recently finished measurement, kept only in memory so the
    // Result screen can offer a one-off export (see exportRawSamplesFile()) for offline
    // analysis of the actual PpgSignalProcessor algorithm against real data instead of
    // tuning its constants blind, from summary counts alone. Not persisted anywhere.
    private var lastRawSamples: List<PpgSample> = emptyList()

    private var collecting = false
    private var measureJob: Job? = null

    // Live beat log for the Measuring screen: the processor reprocesses a sliding window
    // from scratch every tick, so the same beat would otherwise get re-emitted on every
    // tick it's still inside that window — track the newest beat already logged and only
    // append ones past it.
    private val beatLog = mutableListOf<BeatLogEntry>()
    private var loggedUpToMs = Long.MIN_VALUE

    private val _uiState = MutableStateFlow<MeasureUiState>(MeasureUiState.Idle)
    val uiState: StateFlow<MeasureUiState> = _uiState.asStateFlow()

    init {
        cameraSource.onSample = { sample -> if (collecting) samples.add(sample) }
        cameraSource.onError = { throwable ->
            _uiState.value = MeasureUiState.Error(throwable.message ?: "Camera error.")
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
                "Camera permission is required to measure HRV.",
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
        beatLog.clear()
        loggedUpToMs = Long.MIN_VALUE
        cameraSource.start(lifecycleOwner)

        measureJob?.cancel()
        measureJob = viewModelScope.launch {
            try {
                for (remaining in STABILIZE_SEC downTo 1) {
                    _uiState.value = MeasureUiState.Stabilizing(remaining, STABILIZE_SEC)
                    delay(1000)
                }

                samples.clear()
                beatLog.clear()
                loggedUpToMs = Long.MIN_VALUE
                collecting = true
                // Exposure has had the whole stabilization countdown to converge on the
                // lit fingertip; lock it now so auto-exposure doesn't fight the reading.
                cameraSource.lockExposure()

                val measurementStartMs = System.currentTimeMillis()
                val totalMs = MEASURE_SEC * 1000L

                while (true) {
                    delay(TICK_INTERVAL_MS)
                    val elapsedMs = System.currentTimeMillis() - measurementStartMs
                    val remainingSec = ((totalMs - elapsedMs).coerceAtLeast(0) + 999) / 1000

                    val snapshot = synchronized(samples) { samples.toList() }
                    val cutoff = (snapshot.lastOrNull()?.timestampMs ?: 0L) - WAVEFORM_WINDOW_MS
                    val windowed = snapshot.filter { it.timestampMs >= cutoff }
                    val result = if (windowed.size >= 8) processor.process(windowed) else null

                    // The detrend filter is recomputed from scratch each tick on a fresh
                    // window, so its trailing edge (the freshest samples) is its least
                    // stable part — a centered moving average has nothing to average
                    // against yet right at the edge. Dropping a small tail before taking
                    // the display slice trades a few hundred ms of latency for a trace
                    // that doesn't visibly wobble.
                    val waveformSource = result?.filteredSignal?.dropLast(EDGE_TRIM_SAMPLES)

                    if (result != null) {
                        val measurementOriginMs = snapshot.first().timestampMs
                        val newEvents = result.ibiEvents.filter { it.atMs > loggedUpToMs }
                        if (newEvents.isNotEmpty()) {
                            loggedUpToMs = newEvents.last().atMs
                            newEvents.forEach { event ->
                                beatLog.add(
                                    BeatLogEntry(
                                        elapsedMs = event.atMs - measurementOriginMs,
                                        ibiMs = event.ibiMs,
                                        rejectionReason = event.rejectionReason,
                                    ),
                                )
                            }
                            while (beatLog.size > MAX_BEAT_LOG_ENTRIES) beatLog.removeAt(0)
                        }
                    }

                    _uiState.value = MeasureUiState.Measuring(
                        remainingSec = remainingSec.toInt(),
                        totalSec = MEASURE_SEC,
                        liveBpm = result?.let { estimateBpm(it) },
                        // Show the detrended/smoothed trace, not the raw camera signal: the raw
                        // red-channel value has enough baseline drift and quantization noise to
                        // look "unstable" even when the underlying pulse is clean.
                        waveform = waveformSource?.takeLast(WAVEFORM_POINTS) ?: emptyList(),
                        beatLog = beatLog.toList(),
                    )

                    if (elapsedMs >= totalMs) break
                }

                collecting = false
                cameraSource.stop()
                finishMeasurement()
            } catch (c: CancellationException) {
                throw c // cancel() already resets state; don't turn that into an error.
            } catch (t: Throwable) {
                collecting = false
                cameraSource.stop()
                _uiState.value = MeasureUiState.Error(t.message ?: "Something went wrong during the measurement.")
            }
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

    private fun estimateBpm(result: PpgProcessingResult): Double? {
        val ibis = result.cleanIbiMs
        if (ibis.isEmpty()) return null
        val meanIbi = ibis.takeLast(5).average()
        return 60_000.0 / meanIbi
    }

    /**
     * Writes the raw samples behind the last finished measurement to a cache file and
     * returns a content:// [Uri] for it (via [FileProvider]), or null if there is nothing
     * to export yet. Caller (the Result screen) turns this into a share intent.
     */
    fun exportRawSamplesFile(): Uri? {
        val toExport = lastRawSamples
        if (toExport.isEmpty()) return null

        val export = RawSampleExport(exportedAtEpochMs = System.currentTimeMillis(), samples = toExport)
        val json = Json.encodeToString(export)

        val context = getApplication<Application>()
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "hrv-rm-raw-${export.exportedAtEpochMs}.json")
        file.writeText(json)

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private suspend fun finishMeasurement() {
        _uiState.value = MeasureUiState.Processing
        val snapshot = synchronized(samples) { samples.toList() }
        lastRawSamples = snapshot
        val result = processor.process(snapshot)
        val metrics = HrvMetricsCalculator.compute(result.cleanIbiMs)

        if (metrics == null) {
            _uiState.value = MeasureUiState.Error(
                "Not enough signal to compute HRV. Hold your finger still over the camera and flash, then try again.",
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
        /** Waveform/BPM refresh cadence — fast enough to read as a continuously scrolling trace. */
        const val TICK_INTERVAL_MS = 100L
        const val WAVEFORM_WINDOW_MS = 9000L
        const val WAVEFORM_POINTS = 220
        /** ~10 samples at 30fps ≈ 330ms trimmed off the unstable trailing edge before display. */
        const val EDGE_TRIM_SAMPLES = 10
        /** Generous cap for a ~65s measurement (~1 beat/sec) — bounds memory, not visible cadence. */
        const val MAX_BEAT_LOG_ENTRIES = 200
    }
}
