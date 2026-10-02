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
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingFlat
import androidx.compose.material.icons.filled.TrendingUp
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val BEAT_LOG_ROW_HEIGHT = 24.dp
private const val BEAT_LOG_VISIBLE_ROWS = 3
private const val SIGNAL_QUALITY_MAX_BARS = 5

@Composable
fun MeasureScreen(viewModel: MeasurementViewModel = viewModel(), onMeasurementClick: (Long) -> Unit = {}) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val dashboard by viewModel.dashboard.collectAsStateWithLifecycle()
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
            .padding(PaddingValues(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 24.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        when (val state = uiState) {
            is MeasureUiState.Idle, is MeasureUiState.NeedsPermission ->
                TodayDashboardContent(
                    dashboard = dashboard,
                    onStart = ::startWithPermissionCheck,
                    onMeasurementClick = onMeasurementClick,
                )

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

private val measureInstructions =
    "Cover both the rear camera lens and the flash with your fingertip. " +
        "Hold still and breathe normally for about a minute."

/**
 * Idle-state "Today" dashboard: the latest reading plus a 7-day trend, with a shortcut into
 * its detail and the big circular measure button below. Before the first-ever measurement
 * ([TodayDashboard.latest] is null) there's nothing to show yet, so it falls back to the
 * original plain instructions-and-button layout.
 */
@Composable
private fun TodayDashboardContent(dashboard: TodayDashboard, onStart: () -> Unit, onMeasurementClick: (Long) -> Unit) {
    val latest = dashboard.latest
    if (latest == null) {
        Text("Measure HRV", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(measureInstructions, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(32.dp))
        MeasureButton(onStart)
        return
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            val dateText = Instant.ofEpochMilli(latest.timestampEpochMs)
                .atZone(ZoneId.systemDefault())
                .format(todayCardDateFormatter)
            val isToday = Instant.ofEpochMilli(latest.timestampEpochMs).atZone(ZoneId.systemDefault()).toLocalDate() ==
                LocalDate.now()
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (isToday) "TODAY" else "LAST MEASUREMENT",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(dateText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("HRV score", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "%.1f".format(latest.altiniScaleValue),
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold,
                            color = withinRangeColor(latest.withinNormalRange),
                        )
                    }
                    val statusText = when (latest.withinNormalRange) {
                        true -> "Within range"
                        false -> "Outside range"
                        null -> "Building baseline"
                    }
                    Text(statusText, style = MaterialTheme.typography.bodyMedium, color = withinRangeColor(latest.withinNormalRange))
                }
                Column(horizontalAlignment = Alignment.End) {
                    CompactStat("RMSSD", "${latest.rmssdMs.roundToInt()} ms")
                    Spacer(Modifier.height(10.dp))
                    CompactStat("Resting HR", "${latest.meanHrBpm.roundToInt()} bpm")
                }
            }

            val low = latest.normalRangeLowAltiniScale
            val high = latest.normalRangeHighAltiniScale
            if (low != null && high != null) {
                Spacer(Modifier.height(20.dp))
                NormalRangeSlider(value = latest.altiniScaleValue, rangeLow = low, rangeHigh = high)
            }
        }
    }

    dashboard.trend?.let { trend ->
        Spacer(Modifier.height(12.dp))
        TrendCard(trend)
    }

    Spacer(Modifier.height(12.dp))
    Card(
        onClick = { onMeasurementClick(latest.id) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Last measurement", style = MaterialTheme.typography.labelLarge)
                val timeText = Instant.ofEpochMilli(latest.timestampEpochMs)
                    .atZone(ZoneId.systemDefault())
                    .format(todayCardTimeFormatter)
                Text(timeText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Open measurement")
        }
    }

    Spacer(Modifier.height(32.dp))
    MeasureButton(onStart)
    Spacer(Modifier.height(16.dp))
    Text(measureInstructions, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
}

/** A right-aligned label/value pair that sizes to its own content — unlike [MetricRow], which
 * deliberately stretches full-width for its own (single-column) list layout. */
@Composable
private fun CompactStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.End) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

private val todayCardDateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private val todayCardTimeFormatter = DateTimeFormatter.ofPattern("'Today' HH:mm", Locale.ENGLISH)

@Composable
private fun withinRangeColor(withinNormalRange: Boolean?) = when (withinNormalRange) {
    true -> MaterialTheme.colorScheme.secondary
    false -> MaterialTheme.colorScheme.error
    null -> MaterialTheme.colorScheme.onSurface
}

/** Big circular call-to-action, replacing the old rectangular "Start measurement" button. */
@Composable
private fun MeasureButton(onStart: () -> Unit) {
    Box(
        modifier = Modifier
            .size(140.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onStart),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.height(4.dp))
            Text(
                "Measure HRV",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/**
 * Horizontal gauge showing [value] positioned within a domain padded around
 * [rangeLow]-[rangeHigh], with that normal-range band highlighted — lets you see at a
 * glance not just the number but where it sits against your own recent normal.
 */
@Composable
private fun NormalRangeSlider(value: Double, rangeLow: Double, rangeHigh: Double) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val bandColor = MaterialTheme.colorScheme.secondary
    val dotColor = MaterialTheme.colorScheme.primary
    val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    val span = (rangeHigh - rangeLow).coerceAtLeast(0.1)
    val pad = span * 0.4
    val domainLo = minOf(rangeLow - pad, value - pad * 0.3)
    val domainHi = maxOf(rangeHigh + pad, value + pad * 0.3)
    val domainSpan = (domainHi - domainLo).coerceAtLeast(0.001)

    Column {
        Canvas(modifier = Modifier.fillMaxWidth().height(28.dp)) {
            val trackHeight = 10.dp.toPx()
            val centerY = size.height / 2f
            fun xAt(v: Double): Float = ((v - domainLo) / domainSpan).toFloat().coerceIn(0f, 1f) * size.width

            drawRoundRect(
                color = trackColor,
                topLeft = Offset(0f, centerY - trackHeight / 2f),
                size = Size(size.width, trackHeight),
                cornerRadius = CornerRadius(trackHeight / 2f),
            )
            val bandStart = xAt(rangeLow)
            val bandEnd = xAt(rangeHigh)
            drawRoundRect(
                color = bandColor.copy(alpha = 0.45f),
                topLeft = Offset(bandStart, centerY - trackHeight / 2f),
                size = Size((bandEnd - bandStart).coerceAtLeast(1f), trackHeight),
                cornerRadius = CornerRadius(trackHeight / 2f),
            )
            val dotX = xAt(value)
            drawCircle(color = dotColor, radius = 9.dp.toPx(), center = Offset(dotX, centerY))
            drawCircle(color = trackColor, radius = 4.dp.toPx(), center = Offset(dotX, centerY))
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("%.1f".format(domainLo.coerceAtLeast(0.0)), style = MaterialTheme.typography.labelSmall, color = axisTextColor)
            Text("Your normal range", style = MaterialTheme.typography.labelSmall, color = axisTextColor)
            Text("%.1f".format(domainHi), style = MaterialTheme.typography.labelSmall, color = axisTextColor)
        }
    }
}

@Composable
private fun TrendCard(trend: Trend7d) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Trend (7D)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val (icon, label, color) = when (trend.direction) {
                    TrendDirection.RISING -> Triple(Icons.Filled.TrendingUp, "Rising", MaterialTheme.colorScheme.secondary)
                    TrendDirection.FALLING -> Triple(Icons.Filled.TrendingDown, "Falling", MaterialTheme.colorScheme.error)
                    TrendDirection.STABLE -> Triple(Icons.Filled.TrendingFlat, "Stable", MaterialTheme.colorScheme.onSurface)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(icon, contentDescription = null, tint = color)
                    Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "vs 7D baseline",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val sign = if (trend.deltaAltiniScale >= 0) "+" else ""
                Text(
                    "$sign%.2f ($sign%.1f%%)".format(trend.deltaAltiniScale, trend.deltaPercent),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
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
    // Everything here is sized to fit on one screen without scrolling -- the ring, the
    // waveform's aspect ratio, and the gaps between elements are all deliberately more
    // compact than a "could scroll if it had to" layout would use.
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Column {
            Text("Measuring…", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Keep still and breathe naturally",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onCancel) {
            Text("Cancel")
        }
    }
    Spacer(Modifier.height(8.dp))

    PpgWaveform(
        samples = state.waveform,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2.8f),
    )

    Spacer(Modifier.height(12.dp))
    MeasuringRing(state.remainingSec, state.totalSec, state.liveBpm)

    Spacer(Modifier.height(8.dp))
    SignalQualityIndicator(state.signalQualityBars)

    Spacer(Modifier.height(8.dp))
    LiveBeatLog(state.beatLog, modifier = Modifier.fillMaxWidth())
}

/**
 * "Signal quality" readout for the Measuring screen: 5 ascending bars plus a plain-language
 * label, bucketed from the artifact-rejection rate — see
 * [MeasurementViewModel.signalQualityBars] for how [bars] is derived. Null (not yet enough
 * beats in the rolling window to judge) shows "Detecting…" with all bars dim, the same
 * fallback language already used for the live bpm reading.
 */
@Composable
private fun SignalQualityIndicator(bars: Int?) {
    val label = when (bars) {
        null -> "Detecting…"
        5, 4 -> "Good"
        3 -> "Fair"
        else -> "Poor"
    }
    val labelColor = when (bars) {
        null -> MaterialTheme.colorScheme.onSurfaceVariant
        5, 4 -> MaterialTheme.colorScheme.secondary
        3 -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Signal quality",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (i in 1..SIGNAL_QUALITY_MAX_BARS) {
                val filled = bars != null && i <= bars
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height(8.dp + (i * 4).dp)
                        .background(
                            color = if (filled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(2.dp),
                        ),
                ) {}
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = labelColor)
    }
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
        modifier = Modifier.size(176.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidthPx = 12.dp.toPx()
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
            Icon(Icons.Filled.Favorite, contentDescription = null, tint = progressColor, modifier = Modifier.size(16.dp))
            Spacer(Modifier.height(2.dp))
            Text(
                liveBpm?.let { "${it.roundToInt()}" } ?: "--",
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (liveBpm != null) "bpm" else "detecting pulse…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${remainingSec}s left",
                style = MaterialTheme.typography.titleSmall,
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
