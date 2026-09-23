package com.hrvrm.app.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hrvrm.app.data.MeasurementEntity
import com.hrvrm.app.hrv.HrvScoreCalculator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class DailyRhrPoint(val date: LocalDate, val meanHrBpm: Double)

/** One point per calendar day, latest measurement wins — same rule as [buildDailyPoints]. */
fun buildDailyRhrPoints(measurementsNewestFirst: List<MeasurementEntity>): List<DailyRhrPoint> {
    val zone = ZoneId.systemDefault()
    val latestPerDay = LinkedHashMap<LocalDate, MeasurementEntity>()
    for (m in measurementsNewestFirst) {
        val day = Instant.ofEpochMilli(m.timestampEpochMs).atZone(zone).toLocalDate()
        latestPerDay.putIfAbsent(day, m)
    }
    return latestPerDay.entries.sortedBy { it.key }.map { (day, m) -> DailyRhrPoint(day, m.meanHrBpm) }
}

private const val RHR_DEFAULT_WINDOW_DAYS = 30
private const val RHR_DEFAULT_RANGE_LABEL = "30D"
private const val RHR_AXIS_WIDTH_DP = 26f
private val rhrDayFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val rhrRangeOptions = listOf("7D" to 7, "30D" to 30, "90D" to 90)

private fun rhrStartIndexForLastDays(points: List<DailyRhrPoint>, days: Int): Int {
    val cutoff = points.last().date.minusDays((days - 1).toLong())
    val idx = points.indexOfFirst { it.date >= cutoff }
    return if (idx < 0) 0 else idx
}

/**
 * Trailing personal-baseline band for the day at [index]: mean +- [HrvScoreCalculator]'s own
 * "smallest worthwhile change" width, from up to [HrvScoreCalculator.BASELINE_WINDOW_SIZE]
 * days *before* it (never including the day itself). Reuses the same constants as the HRV
 * score's baseline so the two charts' bands mean the same thing ("personal normal range"),
 * even though RHR has no server-computed baseline of its own — this is purely a display-time
 * statistic, not persisted or used anywhere else. Null until at least
 * [HrvScoreCalculator.MIN_BASELINE_SAMPLES] prior days exist.
 */
private fun rhrBandAt(points: List<DailyRhrPoint>, index: Int): ClosedFloatingPointRange<Double>? {
    val windowStart = (index - HrvScoreCalculator.BASELINE_WINDOW_SIZE).coerceAtLeast(0)
    if (windowStart >= index) return null
    val baseline = (windowStart until index).map { points[it].meanHrBpm }
    if (baseline.size < HrvScoreCalculator.MIN_BASELINE_SAMPLES) return null
    val mean = baseline.average()
    val variance = baseline.sumOf { (it - mean) * (it - mean) } / (baseline.size - 1)
    val sd = sqrt(variance).coerceAtLeast(1e-6)
    val half = HrvScoreCalculator.NORMAL_RANGE_SD_MULTIPLIER * sd
    return (mean - half)..(mean + half)
}

/**
 * Resting heart rate day-by-day, one point per calendar day (latest measurement wins). One
 * finger drags to select a day (fires immediately on touch and follows the finger while it's
 * down); no pinch-zoom here — RHR is a different vital sign from HRV, not another view of the
 * same one, so it gets its own small card with just the 7/30/90-day presets instead of
 * another entry in the HRV chart's toggle.
 */
@Composable
fun RhrTrendChart(points: List<DailyRhrPoint>, modifier: Modifier = Modifier) {
    if (points.size < 2) return // nothing to trend yet

    var rangeStart by remember(points.size) { mutableStateOf(rhrStartIndexForLastDays(points, RHR_DEFAULT_WINDOW_DAYS)) }
    var selectedRangeLabel by remember(points.size) { mutableStateOf<String?>(RHR_DEFAULT_RANGE_LABEL) }
    var selectedIndex by remember(points.size) { mutableStateOf<Int?>(null) }
    val rangeEnd = points.lastIndex

    Column(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Resting HR", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            RhrRangePresets(selectedRangeLabel) { label, days ->
                rangeStart = if (days == null) 0 else rhrStartIndexForLastDays(points, days)
                selectedRangeLabel = label
                selectedIndex = null
            }
        }

        val shown = selectedIndex?.let { points.getOrNull(it) } ?: points.last()
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Text(shown.date.format(rhrDayFormatter), style = MaterialTheme.typography.labelSmall)
            Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "${shown.meanHrBpm.roundToInt()} bpm",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }

        RhrCanvas(
            points = points,
            startIndex = rangeStart,
            endIndex = rangeEnd,
            selectedIndex = selectedIndex,
            onSelect = { selectedIndex = it },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2.6f),
        )
    }
}

@Composable
private fun RhrRangePresets(selected: String?, onSelect: (label: String, days: Int?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        rhrRangeOptions.forEach { (label, days) ->
            RhrRangePresetButton(label, selected = label == selected) { onSelect(label, days) }
        }
    }
}

@Composable
private fun RhrRangePresetButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
    if (selected) {
        Button(onClick = onClick, contentPadding = contentPadding) {
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    } else {
        OutlinedButton(onClick = onClick, contentPadding = contentPadding) {
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RhrCanvas(
    points: List<DailyRhrPoint>,
    startIndex: Int,
    endIndex: Int,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val lineColor = MaterialTheme.colorScheme.tertiary
    val bandColor = MaterialTheme.colorScheme.tertiary
    val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val selectionColor = MaterialTheme.colorScheme.primary

    // Read fresh via rememberUpdatedState: the pointerInput below is keyed on Unit so it
    // never restarts, and would otherwise keep using whichever points/bounds were current
    // the first time it launched — see the identical pattern (with the reasoning) in HrvTrendChart.
    val pointsState = rememberUpdatedState(points)
    val startIndexState = rememberUpdatedState(startIndex)
    val endIndexState = rememberUpdatedState(endIndex)
    val onSelectState = rememberUpdatedState(onSelect)

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val axisPx = RHR_AXIS_WIDTH_DP.dp.toPx()
                    val down = awaitFirstDown()
                    fun select(x: Float) {
                        val currentPoints = pointsState.value
                        val i0 = startIndexState.value.coerceIn(0, currentPoints.size - 1)
                        val i1 = endIndexState.value.coerceIn(i0, currentPoints.size - 1)
                        val span = (i1 - i0).coerceAtLeast(1)
                        val plotWidth = (size.width - axisPx).coerceAtLeast(1f)
                        val fraction = ((x - axisPx) / plotWidth).coerceIn(0f, 1f)
                        val idx = (i0 + fraction * span).roundToInt().coerceIn(i0, i1)
                        onSelectState.value(idx)
                    }
                    select(down.position.x)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isNotEmpty()) {
                            select(pressed[0].position.x)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        val axisPx = RHR_AXIS_WIDTH_DP.dp.toPx()
        val plotLeft = axisPx
        val plotRight = size.width
        val plotTop = 8.dp.toPx()
        val plotBottom = size.height - 14.dp.toPx() // leaves room for the date labels below

        val i0 = startIndex.coerceIn(0, points.size - 1)
        val i1 = endIndex.coerceIn(i0, points.size - 1)
        val span = (i1 - i0).coerceAtLeast(1)

        fun xAt(index: Int): Float = plotLeft + (index - i0).toFloat() / span * (plotRight - plotLeft)

        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (i in i0..i1) {
            lo = minOf(lo, points[i].meanHrBpm)
            hi = maxOf(hi, points[i].meanHrBpm)
            rhrBandAt(points, i)?.let { band -> lo = minOf(lo, band.start); hi = maxOf(hi, band.endInclusive) }
        }
        if (lo == hi) { lo -= 5.0; hi += 5.0 }
        val pad = (hi - lo) * 0.2
        val domainLo = lo - pad
        val domainHi = hi + pad

        fun yAt(value: Double): Float =
            (plotBottom - (value - domainLo) / (domainHi - domainLo) * (plotBottom - plotTop)).toFloat()

        // y-axis gridlines + labels
        val steps = 3
        for (s in 0..steps) {
            val v = domainLo + (domainHi - domainLo) * s / steps
            val y = yAt(v)
            drawLine(
                color = axisTextColor.copy(alpha = 0.25f),
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = 1.dp.toPx(),
            )
            val layout = textMeasurer.measure(v.roundToInt().toString(), style = TextStyle(fontSize = 9.sp, color = axisTextColor))
            drawText(layout, topLeft = Offset(0f, y - layout.size.height / 2f))
        }

        // trailing personal-baseline band (see rhrBandAt)
        val bandPath = Path()
        var bandStarted = false
        for (i in i0..i1) {
            val band = rhrBandAt(points, i) ?: continue
            val x = xAt(i)
            if (!bandStarted) { bandPath.moveTo(x, yAt(band.endInclusive)); bandStarted = true } else bandPath.lineTo(x, yAt(band.endInclusive))
        }
        for (i in i1 downTo i0) {
            val band = rhrBandAt(points, i) ?: continue
            bandPath.lineTo(xAt(i), yAt(band.start))
        }
        if (bandStarted) {
            bandPath.close()
            drawPath(bandPath, color = bandColor.copy(alpha = 0.16f))
        }

        // area fill + line
        val linePath = Path()
        val areaPath = Path()
        for (i in i0..i1) {
            val x = xAt(i)
            val y = yAt(points[i].meanHrBpm)
            if (i == i0) {
                linePath.moveTo(x, y)
                areaPath.moveTo(x, plotBottom)
                areaPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                areaPath.lineTo(x, y)
            }
        }
        areaPath.lineTo(xAt(i1), plotBottom)
        areaPath.close()
        drawPath(areaPath, color = lineColor.copy(alpha = 0.16f))
        drawPath(
            linePath,
            color = lineColor,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // dots, the most recent one emphasized
        for (i in i0..i1) {
            val x = xAt(i)
            val y = yAt(points[i].meanHrBpm)
            val radius = if (i == i1) 4.5.dp.toPx() else 3.dp.toPx()
            drawCircle(color = lineColor, radius = radius, center = Offset(x, y))
        }

        // selection crosshair
        val idx = selectedIndex ?: i1
        if (idx in i0..i1) {
            val x = xAt(idx)
            drawLine(color = selectionColor, start = Offset(x, plotTop), end = Offset(x, plotBottom), strokeWidth = 1.dp.toPx())
        }

        // start/end date labels
        val startLabel = textMeasurer.measure(
            points[i0].date.format(rhrDayFormatter),
            style = TextStyle(fontSize = 9.sp, color = axisTextColor),
        )
        drawText(startLabel, topLeft = Offset(plotLeft, plotBottom + 4.dp.toPx()))
        val endLabel = textMeasurer.measure(
            points[i1].date.format(rhrDayFormatter),
            style = TextStyle(fontSize = 9.sp, color = axisTextColor),
        )
        drawText(endLabel, topLeft = Offset(plotRight - endLabel.size.width, plotBottom + 4.dp.toPx()))
    }
}
