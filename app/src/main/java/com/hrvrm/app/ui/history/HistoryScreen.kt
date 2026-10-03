package com.hrvrm.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hrvrm.app.data.MeasurementEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun HistoryScreen(viewModel: HistoryViewModel = viewModel(), onMeasurementClick: (Long) -> Unit) {
    val measurements by viewModel.measurements.collectAsStateWithLifecycle()
    val dailyTrend by viewModel.dailyTrend.collectAsStateWithLifecycle()
    val dailyRhrTrend by viewModel.dailyRhrTrend.collectAsStateWithLifecycle()
    val folderSyncState by viewModel.folderSyncState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        FolderSyncHeader(folderSyncState, onSyncClick = viewModel::syncFolder)

        if (measurements.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No measurements yet. Go to \"Measure\" to get started.")
            }
            return@Column
        }

        if (dailyTrend.size >= 2) {
            OutlinedCard(modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp, 16.dp, 0.dp)) {
                HrvTrendChart(dailyTrend, modifier = Modifier.padding(16.dp))
            }
        }

        if (dailyRhrTrend.size >= 2) {
            OutlinedCard(modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp, 16.dp, 0.dp)) {
                RhrTrendChart(dailyRhrTrend, modifier = Modifier.padding(16.dp))
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(measurements, key = { it.id }) { measurement ->
                HistoryRow(measurement, onClick = { onMeasurementClick(measurement.id) })
            }
        }
    }
}

/**
 * Sync-with-backup-folder status + trigger, always visible (even with zero local
 * measurements) since restoring the whole history after a reinstall — the main reason this
 * button exists — starts from exactly that empty state. See [HistoryViewModel.syncFolder].
 */
@Composable
private fun FolderSyncHeader(state: FolderSyncUiState, onSyncClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp, 2.dp, 16.dp, 0.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "History",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            )
            IconButton(onClick = onSyncClick, enabled = !state.inProgress) {
                if (state.inProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Filled.Sync,
                        contentDescription = "Sync with backup folder",
                        tint = if (state.folderConfigured) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
        state.result?.let { result ->
            Spacer(Modifier.height(8.dp))
            Text(
                result,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val dateFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ENGLISH)

@Composable
private fun HistoryRow(measurement: MeasurementEntity, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(0.68f)) {
                val dateText = Instant.ofEpochMilli(measurement.timestampEpochMs)
                    .atZone(ZoneId.systemDefault())
                    .format(dateFormatter)
                Text(dateText, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    "RMSSD ${measurement.rmssdMs.roundToInt()} ms · ${measurement.meanHrBpm.roundToInt()} bpm",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val scoreColor = when (measurement.withinNormalRange) {
                true -> MaterialTheme.colorScheme.secondary
                false -> MaterialTheme.colorScheme.error
                null -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                "%.1f".format(measurement.altiniScaleValue),
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = scoreColor,
                modifier = Modifier.weight(0.17f),
            )

            Row(
                modifier = Modifier.weight(0.15f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (measurement.uploadedToIntervals) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
                    contentDescription = null,
                    tint = if (measurement.uploadedToIntervals) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.size(24.dp),
                )
                Icon(
                    Icons.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
