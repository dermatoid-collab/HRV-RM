package com.hrvrm.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hrvrm.app.asi.AsiResult
import com.hrvrm.app.data.MeasurementEntity
import com.hrvrm.app.ui.asi.toComposeColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel = viewModel(),
    onMeasurementClick: (Long) -> Unit,
    /** Bumped by MainTabsScreen each time this tab becomes visible again after the user
     * switched away to another tab and back -- snaps the list back to the latest measurement
     * (the top row, since [measurements] is newest-first) instead of wherever it was left
     * scrolled, since LazyColumn's own state otherwise survives that round trip via its
     * default rememberSaveable behavior. */
    resetSignal: Int = 0,
) {
    val measurements by viewModel.measurements.collectAsStateWithLifecycle()
    val dailyRhrTrend by viewModel.dailyRhrTrend.collectAsStateWithLifecycle()
    val asiByMeasurementId by viewModel.asiByMeasurementId.collectAsStateWithLifecycle()
    val folderSyncState by viewModel.folderSyncState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(resetSignal) {
        if (resetSignal > 0) listState.scrollToItem(0)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        FolderSyncHeader(folderSyncState, onSyncClick = viewModel::syncFolder)

        if (measurements.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No measurements yet. Go to \"Measure\" to get started.")
            }
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(measurements, key = { it.id }) { measurement ->
                HistoryRow(
                    measurement,
                    rhrStatus = rhrStatusFor(measurement, dailyRhrTrend),
                    asi = asiByMeasurementId[measurement.id],
                    onClick = { onMeasurementClick(measurement.id) },
                )
            }
        }
    }
}

/**
 * Sync-with-backup-folder status + trigger, always visible (even with zero local
 * measurements) since restoring the whole history after a reinstall — the main reason this
 * button exists — starts from exactly that empty state. See [HistoryViewModel.syncFolder].
 */
@OptIn(ExperimentalMaterial3Api::class)
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
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            // Material3's IconButton otherwise enforces a 48dp touch target regardless of its
            // own modifier size, which was what kept this header tall even after shrinking
            // everything else -- disabling that enforcement here (just for this one small
            // utility icon) is what actually lets the header shrink.
            CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
                IconButton(onClick = onSyncClick, enabled = !state.inProgress, modifier = Modifier.size(32.dp)) {
                    if (state.inProgress) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Filled.Sync,
                            contentDescription = "Sync with backup folder",
                            tint = if (state.folderConfigured) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        state.result?.let { result ->
            Text(
                result,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val dateFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ENGLISH)

/**
 * Whether [measurement]'s own RHR falls inside its calendar day's [rhrBandAt] band within
 * [dailyRhrTrend] -- tested against the measurement's own value rather than inheriting its
 * day's representative (latest-of-day) reading, so two same-day measurements can show
 * different statuses if that's genuinely what happened.
 */
private fun rhrStatusFor(measurement: MeasurementEntity, dailyRhrTrend: List<DailyRhrPoint>): Boolean? {
    val day = Instant.ofEpochMilli(measurement.timestampEpochMs).atZone(ZoneId.systemDefault()).toLocalDate()
    val dayIndex = dailyRhrTrend.indexOfFirst { it.date == day }
    if (dayIndex < 0) return null
    val band = rhrBandAt(dailyRhrTrend, dayIndex) ?: return null
    return measurement.meanHrBpm in band
}

@Composable
private fun HistoryRow(measurement: MeasurementEntity, rhrStatus: Boolean?, asi: AsiResult?, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(0.68f)) {
                val dateText = Instant.ofEpochMilli(measurement.timestampEpochMs)
                    .atZone(ZoneId.systemDefault())
                    .format(dateFormatter)
                Text(dateText, style = MaterialTheme.typography.bodyMedium)
                val rhrColor = when (rhrStatus) {
                    true -> MaterialTheme.colorScheme.secondary
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row {
                    Text(
                        "RMSSD ${measurement.rmssdMs.roundToInt()} ms · ",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "${measurement.meanHrBpm.roundToInt()} bpm",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = rhrColor,
                    )
                    measurement.cv7?.let { cv7 ->
                        Text(
                            " · CV7 %.1f%%".format(cv7),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Own line, not appended to the row above -- that row is already tight enough
                // that "ASI" wrapped awkwardly onto a second line by itself when it was appended
                // there instead.
                asi?.let {
                    Text(
                        "ASI ${it.score ?: "—"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = it.color.toComposeColor(),
                    )
                }
            }

            val scoreColor = when (measurement.withinNormalRange) {
                true -> MaterialTheme.colorScheme.secondary
                false -> MaterialTheme.colorScheme.error
                null -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                "%.1f".format(measurement.altiniScaleValue),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
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
