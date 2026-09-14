package com.hrvrm.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hrvrm.app.HrvRmApp
import com.hrvrm.app.data.MeasurementEntity
import com.hrvrm.app.ui.measure.MetricRow
import com.hrvrm.app.ui.measure.ScoreBadge
import com.hrvrm.app.ui.measure.UploadStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val detailDateFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ENGLISH)

/** Read-only detail view for a single past measurement, opened by tapping a History row. */
@Composable
fun MeasurementDetailScreen(measurementId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { (context.applicationContext as HrvRmApp).container.measurementRepository }
    val scope = rememberCoroutineScope()

    var measurement by remember(measurementId) { mutableStateOf<MeasurementEntity?>(null) }
    var loading by remember(measurementId) { mutableStateOf(true) }
    var uploadInProgress by remember(measurementId) { mutableStateOf(false) }

    LaunchedEffect(measurementId) {
        loading = true
        measurement = repository.getById(measurementId)
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Measurement", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
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

                    ScoreBadge(current)

                    Spacer(Modifier.height(24.dp))

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MetricRow("Average heart rate", "${current.meanHrBpm.roundToInt()} bpm")
                            MetricRow("RMSSD", "${current.rmssdMs.roundToInt()} ms")
                            MetricRow("Normalized HRV", "%.1f %%".format(current.normalizedHrvPercent))
                            MetricRow("SDNN", "${current.sdnnMs.roundToInt()} ms")
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
                }
            }
        }
    }
}
