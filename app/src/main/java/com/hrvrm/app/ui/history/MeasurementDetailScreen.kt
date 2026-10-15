package com.hrvrm.app.ui.history

import android.content.Intent
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hrvrm.app.HrvRmApp
import com.hrvrm.app.asi.AsiConfig
import com.hrvrm.app.asi.AsiHistoryAdapter
import com.hrvrm.app.asi.AsiResult
import com.hrvrm.app.data.MeasurementEntity
import com.hrvrm.app.ui.asi.AsiDetailSection
import com.hrvrm.app.ui.measure.MetricRow
import com.hrvrm.app.ui.measure.ScoreBadge
import com.hrvrm.app.ui.measure.UploadStatus
import com.hrvrm.app.ui.nav.AppResumeSignal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val detailDateFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ENGLISH)

/** Read-only detail view for a single past measurement, opened by tapping a History row. Swiping
 * left/right moves to the next/previous measurement chronologically -- handled entirely with
 * local state (see [currentId]) rather than pushing a new back-stack entry per swipe, so the
 * back button still returns straight to History however many swipes happened in between. */
@Composable
fun MeasurementDetailScreen(measurementId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as HrvRmApp).container }
    val repository = remember { container.measurementRepository }
    val rawSampleStorage = remember { container.rawSampleStorage }
    val scope = rememberCoroutineScope()

    var currentId by remember(measurementId) { mutableStateOf(measurementId) }
    // Newest-first, same order History shows them in -- used only to find currentId's
    // chronological neighbors for swipe navigation.
    val measurementsFlow = remember { repository.observeAll() }
    val measurements by measurementsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val currentIndex = measurements.indexOfFirst { it.id == currentId }
    // Index 0 is the most recent, so a lower index is chronologically newer ("next").
    val nextId = measurements.getOrNull(currentIndex - 1)?.id
    val previousId = measurements.getOrNull(currentIndex + 1)?.id

    var measurement by remember(currentId) { mutableStateOf<MeasurementEntity?>(null) }
    var asi by remember(currentId) { mutableStateOf<AsiResult?>(null) }
    var loading by remember(currentId) { mutableStateOf(true) }
    var uploadInProgress by remember(currentId) { mutableStateOf(false) }
    var hasRawData by remember(currentId) { mutableStateOf(false) }
    var showDeleteConfirm by remember(currentId) { mutableStateOf(false) }

    LaunchedEffect(currentId) {
        loading = true
        val current = repository.getById(currentId)
        measurement = current
        loading = false
        hasRawData = rawSampleStorage.hasStoredSamples(currentId)
        asi = current?.let {
            val preceding = repository.getPriorMeasurements(it.timestampEpochMs, AsiConfig.BASELINE_WINDOW_SIZE)
            AsiHistoryAdapter.compute(it, preceding)
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this measurement?") },
            text = { Text("This removes it from your device, including its file in the backup folder if one is configured. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    scope.launch {
                        repository.deleteMeasurement(currentId)
                        onBack()
                    }
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Same fix as the main tab bar: a custom top row under enableEdgeToEdge()
                // sits under the status bar unless it claims that inset itself.
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Measurement",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (measurement != null) {
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete measurement", tint = MaterialTheme.colorScheme.error)
                }
            }
        }

        val nextIdState = rememberUpdatedState(nextId)
        val previousIdState = rememberUpdatedState(previousId)
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Swipe left -> next (newer) measurement, swipe right -> previous (older) one --
                // pointerInput(Unit) never restarts, so the current next/previous ids are read
                // fresh via rememberUpdatedState instead (same pattern the trend charts use for
                // their own drag gestures).
                .pointerInput(Unit) {
                    var dragTotal = 0f
                    val threshold = 80.dp.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = { dragTotal = 0f },
                        onDragEnd = {
                            if (dragTotal <= -threshold) {
                                nextIdState.value?.let { currentId = it }
                            } else if (dragTotal >= threshold) {
                                previousIdState.value?.let { currentId = it }
                            }
                            dragTotal = 0f
                        },
                        onDragCancel = { dragTotal = 0f },
                    ) { change, dragAmount ->
                        change.consume()
                        dragTotal += dragAmount
                    }
                }
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val current = measurement
            when {
                loading -> CircularProgressIndicator()
                current == null -> Text("Measurement not found — it may have been deleted.")
                else -> {
                    val dateText = Instant.ofEpochMilli(current.timestampEpochMs)
                        .atZone(ZoneId.systemDefault())
                        .format(detailDateFormatter)
                    Text(
                        dateText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))

                    // HRV score (in scale) and ASI, big, side by side -- ScoreBadge itself puts
                    // them at the top; everything else is secondary detail below.
                    ScoreBadge(current, asi)

                    // ASI's main status, per-parameter states, synthetic comment, exceptions and
                    // confidence -- the score number itself already showed above.
                    asi?.let {
                        Spacer(Modifier.height(16.dp))
                        AsiDetailSection(it)
                    }

                    Spacer(Modifier.height(16.dp))

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MetricRow("Average heart rate", "${current.meanHrBpm.roundToInt()} bpm")
                            MetricRow("RMSSD", "${current.rmssdMs.roundToInt()} ms")
                            MetricRow("HRV baseline (7-reading avg)", "%.1f".format(current.baselineAltiniScaleValue))
                            MetricRow("CV7 (7-reading variability)", current.cv7?.let { "%.1f %%".format(it) } ?: "Building…")
                            MetricRow("Normalized HRV", "%.1f %%".format(current.normalizedHrvPercent))
                            MetricRow("Mean RR", "${current.meanIbiMs.roundToInt()} ms")
                            MetricRow("SDNN", "${current.sdnnMs.roundToInt()} ms")
                            MetricRow("Poincaré SD1", "${current.sd1Ms.roundToInt()} ms")
                            MetricRow("Poincaré SD2", "${current.sd2Ms.roundToInt()} ms")
                            MetricRow("Stress index", "%.1f".format(current.stressIndex))
                            MetricRow("pNN50", "${current.pnn50Percent.roundToInt()} %")
                            MetricRow("Duration", "${current.durationSec}s")
                            MetricRow("Valid beats", "${current.beatCount} (${current.rejectedBeatCount} rejected)")
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    UploadStatus(
                        measurement = current,
                        uploadInProgress = uploadInProgress,
                        onRetry = {
                            scope.launch {
                                uploadInProgress = true
                                measurement = repository.uploadMeasurement(current)
                                uploadInProgress = false
                            }
                        },
                    )

                    if (hasRawData) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val uri = rawSampleStorage.exportSharedFile(current.id, current.timestampEpochMs)
                                    if (uri != null) {
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "application/json"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        AppResumeSignal.suppressNextResume = true
                                        context.startActivity(Intent.createChooser(intent, "Export raw HRV data"))
                                    }
                                }
                            },
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Export raw data")
                        }
                    }
                }
            }
        }
    }
}
