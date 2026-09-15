package com.hrvrm.app.ui.measure

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hrvrm.app.data.MeasurementEntity
import com.hrvrm.app.hrv.HrvScoreCalculator
import com.hrvrm.app.ppg.BeatRejectionReason
import kotlin.math.roundToInt

private val BEAT_LOG_ROW_HEIGHT = 24.dp
private const val BEAT_LOG_VISIBLE_ROWS = 3

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
            .verticalScroll(rememberScrollState())
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
                onExportRawData = viewModel::exportRawSamplesFile,
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

    Spacer(Modifier.height(20.dp))
    LiveBeatLog(state.beatLog, modifier = Modifier.fillMaxWidth())
}

/**
 * A terminal-style log of detected beats: always 3 rows tall, auto-scrolling as new beats
 * come in. Shows the same clean/rejected intervals the score is built from, exposed
 * instead of staying only inside the final calculation — useful to see signal quality
 * live rather than just the beat-count summary after the fact.
 */
@Composable
private fun LiveBeatLog(entries: List<BeatLogEntry>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex)
    }

    Card(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "LIVE BEATS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LiveIndicatorDot(MaterialTheme.colorScheme.secondary)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(BEAT_LOG_ROW_HEIGHT * BEAT_LOG_VISIBLE_ROWS)) {
                Text(
                    "Waiting for the first beat…",
                    modifier = Modifier.padding(horizontal = 12.dp).align(Alignment.CenterStart),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                userScrollEnabled = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(BEAT_LOG_ROW_HEIGHT * BEAT_LOG_VISIBLE_ROWS),
            ) {
                items(entries) { entry -> BeatLogRow(entry) }
            }
        }
    }
}

@Composable
private fun LiveIndicatorDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "liveDot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "liveDotAlpha",
    )
    Box(
        modifier = Modifier
            .size(6.dp)
            .background(color.copy(alpha = alpha), CircleShape),
    )
}

@Composable
private fun BeatLogRow(entry: BeatLogEntry) {
    val statusColor = if (entry.accepted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(BEAT_LOG_ROW_HEIGHT)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            formatBeatLogElapsed(entry.elapsedMs),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(36.dp),
        )
        Text(
            "RR ${entry.ibiMs} ms",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = if (entry.accepted) MaterialTheme.colorScheme.onSurface else statusColor,
            modifier = Modifier.width(76.dp),
        )
        Text(
            when (entry.rejectionReason) {
                null -> "${(60_000L / entry.ibiMs.coerceAtLeast(1))} bpm"
                BeatRejectionReason.OUT_OF_RANGE -> "out of range"
                BeatRejectionReason.IRREGULAR -> "irregular"
            },
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = statusColor,
        )
    }
}

private fun formatBeatLogElapsed(ms: Long): String {
    val totalTenths = ms / 100
    val minutes = totalTenths / 600
    val seconds = (totalTenths / 10) % 60
    val tenth = totalTenths % 10
    return "$minutes:${seconds.toString().padStart(2, '0')}.$tenth"
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
                liveBpm?.let { "${it.roundToInt()}" } ?: "--",
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (liveBpm != null) "bpm" else "detecting pulse…",
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
    onExportRawData: () -> Uri?,
) {
    val measurement = state.measurement
    val context = LocalContext.current

    Text("Result", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(16.dp))

    ScoreBadge(measurement)

    Spacer(Modifier.height(24.dp))

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricRow("Average heart rate", "${measurement.meanHrBpm.roundToInt()} bpm")
            MetricRow("RMSSD", "${measurement.rmssdMs.roundToInt()} ms")
            MetricRow("Normalized HRV", "%.1f %%".format(measurement.normalizedHrvPercent))
            MetricRow("Mean RR", "${measurement.meanIbiMs.roundToInt()} ms")
            MetricRow("SDNN", "${measurement.sdnnMs.roundToInt()} ms")
            MetricRow("Poincaré SD1", "${measurement.sd1Ms.roundToInt()} ms")
            MetricRow("Poincaré SD2", "${measurement.sd2Ms.roundToInt()} ms")
            MetricRow("Stress index", "%.1f".format(measurement.stressIndex))
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

    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = {
            val uri = onExportRawData()
            if (uri != null) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Export raw HRV data"))
            }
        },
    ) {
        Icon(Icons.Filled.Share, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Export raw data")
    }
}

@Composable
fun ScoreBadge(measurement: MeasurementEntity) {
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
fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun UploadStatus(measurement: MeasurementEntity, uploadInProgress: Boolean, onRetry: () -> Unit) {
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
