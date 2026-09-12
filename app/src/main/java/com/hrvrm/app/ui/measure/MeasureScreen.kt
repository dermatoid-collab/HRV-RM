package com.hrvrm.app.ui.measure

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hrvrm.app.data.MeasurementEntity
import com.hrvrm.app.hrv.HrvScoreCalculator
import kotlin.math.roundToInt

/** How much of the gap to a new BPM reading to close per recomposition tick — see [MeasuringRing]. */
private const val BPM_SMOOTHING = 0.35

@Composable
fun MeasureScreen(viewModel: MeasurementViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onPermissionResult(granted, lifecycleOwner) }

    fun startWithPermissionCheck() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.start(lifecycleOwner)
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Without this the screen times out mid-measurement, the activity pauses, and
    // CameraX tears down the session — killing the torch and the reading with it.
    val keepScreenOn = uiState is MeasureUiState.Stabilizing || uiState is MeasureUiState.Measuring
    val view = LocalView.current
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(PaddingValues(24.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (val state = uiState) {
            is MeasureUiState.Idle, is MeasureUiState.NeedsPermission ->
                IdleContent(onStart = ::startWithPermissionCheck)

            is MeasureUiState.NoFlash ->
                ErrorContent(
                    "This device has no flash on the rear camera, which is required for a PPG reading.",
                    onRetry = viewModel::reset,
                )

            is MeasureUiState.Stabilizing -> StabilizingContent(state)

            is MeasureUiState.Measuring -> MeasuringContent(state, onCancel = viewModel::cancel)

            is MeasureUiState.Processing -> ProcessingContent()

            is MeasureUiState.Result -> ResultContent(
                state = state,
                onNewMeasurement = viewModel::reset,
                onRetryUpload = viewModel::retryUpload,
            )

            is MeasureUiState.Error -> ErrorContent(state.message, onRetry = viewModel::reset)
        }
    }
}

@Composable
private fun IdleContent(onStart: () -> Unit) {
    Text(
        "Measure HRV",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(16.dp))
    Text(
        "Cover both the rear camera lens and the flash with your fingertip. " +
            "Hold still and breathe normally for about a minute.",
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(32.dp))
    Button(onClick = onStart) {
        Text("Start measurement")
    }
}

@Composable
private fun StabilizingContent(state: MeasureUiState.Stabilizing) {
    Text("Getting ready…", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))
    Text(
        "Cover the camera and flash with your finger, hold still.",
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(24.dp))
    Text(
        "${state.remainingSec}",
        style = MaterialTheme.typography.displayLarge,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(16.dp))
    CircularProgressIndicator(
        progress = { 1f - state.remainingSec / state.totalSec.toFloat() },
    )
}

@Composable
private fun MeasuringContent(state: MeasureUiState.Measuring, onCancel: () -> Unit) {
    Text("Measuring…", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))

    PpgWaveform(
        samples = state.waveform,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2f),
    )

    Spacer(Modifier.height(32.dp))
    MeasuringRing(state.remainingSec, state.totalSec, state.liveBpm)

    Spacer(Modifier.height(32.dp))
    OutlinedButton(onClick = onCancel) {
        Text("Cancel")
    }
}

/**
 * A single progress ring replaces the old countdown text + linear bar: the BPM reading
 * (the number people keep glancing at) gets a much bigger font, and the remaining time
 * lives right below it instead of competing for its own line.
 */
@Composable
private fun MeasuringRing(remainingSec: Int, totalSec: Int, liveBpm: Double?) {
    val progress = 1f - remainingSec / totalSec.toFloat()
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val progressColor = MaterialTheme.colorScheme.primary

    // The raw estimate only moves once per detected beat, and real beats don't land on a
    // regular clock — so snapping straight to it makes the number hop unevenly. Easing
    // toward each new reading instead (same idea as the waveform's smoothed scale) turns
    // those hops into a steady glide, settling on the new value within half a second or so.
    var displayBpm by remember { mutableStateOf<Double?>(null) }
    if (liveBpm != null) {
        displayBpm = displayBpm?.let { it + (liveBpm - it) * BPM_SMOOTHING } ?: liveBpm
    }

    Box(
        modifier = Modifier.size(220.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidthPx = 14.dp.toPx()
            val diameter = size.minDimension - strokeWidthPx
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)

            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
            )
            drawArc(
                color = progressColor,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                displayBpm?.let { "${it.roundToInt()}" } ?: "--",
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (displayBpm != null) "bpm" else "detecting pulse…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${remainingSec}s left",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ProcessingContent() {
    CircularProgressIndicator()
    Spacer(Modifier.height(16.dp))
    Text("Computing HRV…", style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun ResultContent(
    state: MeasureUiState.Result,
    onNewMeasurement: () -> Unit,
    onRetryUpload: () -> Unit,
) {
    val measurement = state.measurement

    Text("Result", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(16.dp))

    ScoreBadge(measurement)

    Spacer(Modifier.height(24.dp))

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricRow("Average heart rate", "${measurement.meanHrBpm.roundToInt()} bpm")
            MetricRow("RMSSD", "${measurement.rmssdMs.roundToInt()} ms")
            MetricRow("Normalized HRV", "%.1f %%".format(measurement.normalizedHrvPercent))
            MetricRow("SDNN", "${measurement.sdnnMs.roundToInt()} ms")
            MetricRow("pNN50", "${measurement.pnn50Percent.roundToInt()} %")
            MetricRow("Valid beats", "${measurement.beatCount} (${measurement.rejectedBeatCount} rejected)")
        }
    }

    Spacer(Modifier.height(16.dp))
    UploadStatus(measurement, state.uploadInProgress, onRetryUpload)

    Spacer(Modifier.height(24.dp))
    Button(onClick = onNewMeasurement) {
        Text("New measurement")
    }
}

@Composable
private fun ScoreBadge(measurement: MeasurementEntity) {
    val altiniValueText = "%.1f".format(measurement.altiniScaleValue)
    val score = measurement.hrvScore

    if (score == null) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(altiniValueText, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text("HRV (ln scale, HRV4Training-style)", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            Text(
                "Building baseline: at least ${HrvScoreCalculator.MIN_BASELINE_SAMPLES} measurements " +
                    "are needed for the score and normal range.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    val color = when {
        score >= 70 -> MaterialTheme.colorScheme.primary
        score >= 40 -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.error
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "$score",
            fontSize = 64.sp,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Text("HRV Score", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(10.dp))
        Text(
            "$altiniValueText (ln scale, HRV4Training-style)",
            style = MaterialTheme.typography.bodySmall,
        )
        val low = measurement.normalRangeLowAltiniScale
        val high = measurement.normalRangeHighAltiniScale
        if (low != null && high != null) {
            Text(
                "Normal range: %.1f – %.1f".format(low, high),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val within = measurement.withinNormalRange
        if (within != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (within) "Within normal range" else "Outside normal range",
                color = if (within) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun UploadStatus(measurement: MeasurementEntity, uploadInProgress: Boolean, onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            uploadInProgress -> {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Uploading to Intervals.icu…")
            }
            measurement.uploadedToIntervals -> {
                Icon(Icons.Filled.CloudDone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Uploaded to Intervals.icu")
            }
            else -> {
                Icon(Icons.Filled.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Not uploaded" + (measurement.uploadError?.let { ": $it" } ?: ""))
                    OutlinedButton(onClick = onRetry) {
                        Icon(Icons.Filled.CloudUpload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Retry upload")
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Text("Oops", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))
    Text(message, textAlign = TextAlign.Center)
    Spacer(Modifier.height(24.dp))
    Button(onClick = onRetry) {
        Text("Retry")
    }
}
