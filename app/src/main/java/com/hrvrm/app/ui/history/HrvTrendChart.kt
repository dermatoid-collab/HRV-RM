package com.hrvrm.app.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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

data class DailyHrvPoint(
    val date: LocalDate,
    val altiniScaleValue: Double,
    val hrvScore: Int?,
    val normalRangeLowAltiniScale: Double?,
    val normalRangeHighAltiniScale: Double?,
    val withinNormalRange: Boolean?,
)

/**
 * One point per calendar day: when a day has more than one measurement, the most recent one
 * wins (matches how the result screen and Intervals.icu upload already treat "today's" reading).
 */
fun buildDailyPoints(measurementsNewestFirst: List<MeasurementEntity>): List<DailyHrvPoint> {
    val zone = ZoneId.systemDefault()
    val latestPerDay = LinkedHashMap<LocalDate, MeasurementEntity>()
    for (m in measurementsNewestFirst) {
        val day = Instant.ofEpochMilli(m.timestampEpochMs).atZone(zone).toLocalDate()
        latestPerDay.putIfAbsent(day, m) // input is newest-first, so the first hit per day is the latest
    }
    return latestPerDay.entries
        .sortedBy { it.key }
        .map { (day, m) ->
            DailyHrvPoint(
                date = day,
                altiniScaleValue = m.altiniScaleValue,
                hrvScore = m.hrvScore,
                normalRangeLowAltiniScale = m.normalRangeLowAltiniScale,
                normalRangeHighAltiniScale = m.normalRangeHighAltiniScale,
                withinNormalRange = m.withinNormalRange,
            )
        }
}

private enum class TrendMetric { LN_SCALE, SCORE }

private const val MIN_WINDOW_DAYS = 4f
private const val DEFAULT_WINDOW_DAYS = 30
private const val AXIS_WIDTH_DP = 34f
private val dayFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

private fun startIndexForLastDays(points: List<DailyHrvPoint>, days: Int): Float {
    val cutoff = points.last().date.minusDays((days - 1).toLong())
    val idx = points.indexOfFirst { it.date >= cutoff }
    return (if (idx < 0) 0 else idx).toFloat()
}

/** Daily HRV trend: pinch/drag to zoom and pan, tap a point to read its value, double-tap to reset. */
@Composable
fun HrvTrendChart(points: List<DailyHrvPoint>, modifier: Modifier = Modifier) {
    if (points.size < 2) return // nothing to trend yet — the row below already shows the one reading

    val maxIndex = (points.size - 1).toFloat()
    var metric by remember { mutableStateOf(TrendMetric.LN_SCALE) }
    var viewStart by remember(points.size) { mutableFloatStateOf(startIndexForLastDays(points, DEFAULT_WINDOW_DAYS)) }
    var viewEnd by remember(points.size) { mutableFloatStateOf(maxIndex) }
    var selectedIndex by remember(points.size) { mutableStateOf<Int?>(null) }

    val hasScore = points.any { it.hrvScore != null }
    val effectiveMetric = if (metric == TrendMetric.SCORE && !hasScore) TrendMetric.LN_SCALE else metric

    Column(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (hasScore) {
                MetricToggle(effectiveMetric, onChange = { metric = it })
            } else {
                Text("ln scale", style = MaterialTheme.typography.labelSmall)
            }
            RangePresets { days ->
                viewStart = if (days == null) 0f else startIndexForLastDays(points, days)
                viewEnd = maxIndex
                selectedIndex = null
            }
        }

        val selected = selectedIndex?.let { points.getOrNull(it) } ?: points.last()
        SelectionReadout(selected, effectiveMetric)

        TrendCanvas(
            points = points,
            metric = effectiveMetric,
            viewStart = viewStart,
            viewEnd = viewEnd,
            selectedIndex = selectedIndex,
            onViewChange = { start, end -> viewStart = start; viewEnd = end },
            onSelect = { selectedIndex = it },
            onReset = {
                viewStart = startIndexForLastDays(points, DEFAULT_WINDOW_DAYS)
                viewEnd = maxIndex
                selectedIndex = null
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2.1f),
        )

        Legend(hasScore)
    }
}

@Composable
private fun MetricToggle(metric: TrendMetric, onChange: (TrendMetric) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ToggleChip("ln scale", metric == TrendMetric.LN_SCALE) { onChange(TrendMetric.LN_SCALE) }
        ToggleChip("score 0-100", metric == TrendMetric.SCORE) { onChange(TrendMetric.SCORE) }
    }
}

@Composable
private fun ToggleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        color = bg,
        contentColor = fg,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun RangePresets(onSelect: (Int?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("7D" to 7, "30D" to 30, "90D" to 90, "All" to null).forEach { (label, days) ->
            OutlinedButton(
                onClick = { onSelect(days) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun SelectionReadout(point: DailyHrvPoint, metric: TrendMetric) {
    val statusLabel = when (point.withinNormalRange) {
        true -> "Within range"
        false -> "Outside range"
        null -> "Building baseline"
    }
    val statusColor = when (point.withinNormalRange) {
        true -> MaterialTheme.colorScheme.secondary
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val valueText = when (metric) {
        TrendMetric.LN_SCALE -> "%.1f".format(point.altiniScaleValue)
        TrendMetric.SCORE -> point.hrvScore?.toString() ?: "–"
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(point.date.format(dayFormatter), style = MaterialTheme.typography.labelSmall)
        Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(valueText, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = statusColor)
    }
}

@Composable
private fun Legend(hasScore: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        LegendDot(MaterialTheme.colorScheme.secondary, "Within range")
        LegendDiamond(MaterialTheme.colorScheme.error, "Outside range")
        if (!hasScore) LegendRing(MaterialTheme.colorScheme.onSurfaceVariant, "Building baseline")
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(modifier = Modifier.size(8.dp)) { drawCircle(color = color) }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LegendDiamond(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(modifier = Modifier.size(8.dp)) {
            val path = Path().apply {
                moveTo(size.width / 2f, 0f)
                lineTo(size.width, size.height / 2f)
                lineTo(size.width / 2f, size.height)
                lineTo(0f, size.height / 2f)
                close()
            }
            drawPath(path, color = color)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LegendRing(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(modifier = Modifier.size(8.dp)) {
            drawCircle(color = color, style = Stroke(width = 1.5.dp.toPx()))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TrendCanvas(
    points: List<DailyHrvPoint>,
    metric: TrendMetric,
    viewStart: Float,
    viewEnd: Float,
    selectedIndex: Int?,
    onViewChange: (Float, Float) -> Unit,
    onSelect: (Int?) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val bandColor = MaterialTheme.colorScheme.secondary
    val okColor = MaterialTheme.colorScheme.secondary
    val outColor = MaterialTheme.colorScheme.error
    val buildingColor = MaterialTheme.colorScheme.onSurfaceVariant
    val selectionColor = MaterialTheme.colorScheme.primary
    val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceColor = MaterialTheme.colorScheme.surface
    val maxIndex = (points.size - 1).toFloat()

    Canvas(
        modifier = modifier
            .pointerInput(points.size) {
                detectTapGestures(
                    onTap = { offset ->
                        val idx = indexAtOffset(offset, size.width.toFloat(), AXIS_WIDTH_DP.dp.toPx(), viewStart, viewEnd)
                        onSelect(idx.roundToInt().coerceIn(0, points.size - 1))
                    },
                    onDoubleTap = { onReset() },
                )
            }
            .pointerInput(points.size) {
                detectTransformGestures(panZoomLock = false) { centroid, pan, zoom, _ ->
                    val axisPx = AXIS_WIDTH_DP.dp.toPx()
                    val plotWidth = (size.width - axisPx).coerceAtLeast(1f)
                    val span = (viewEnd - viewStart).coerceAtLeast(MIN_WINDOW_DAYS)
                    val focalFraction = ((centroid.x - axisPx) / plotWidth).coerceIn(0f, 1f)
                    val focalDay = viewStart + focalFraction * span

                    val newSpan = (span / zoom).coerceIn(MIN_WINDOW_DAYS, maxIndex.coerceAtLeast(MIN_WINDOW_DAYS))
                    var newStart = focalDay - focalFraction * newSpan
                    var newEnd = newStart + newSpan

                    val dayPerPx = newSpan / plotWidth
                    val shift = pan.x * dayPerPx
                    newStart -= shift
                    newEnd -= shift

                    if (newStart < 0f) { newEnd -= newStart; newStart = 0f }
                    if (newEnd > maxIndex) { newStart -= (newEnd - maxIndex); newEnd = maxIndex }
                    newStart = newStart.coerceAtLeast(0f)
                    newEnd = newEnd.coerceAtMost(maxIndex)

                    onViewChange(newStart, newEnd)
                }
            },
    ) {
        val axisPx = AXIS_WIDTH_DP.dp.toPx()
        val plotLeft = axisPx
        val plotRight = size.width
        val plotTop = 8.dp.toPx()
        val plotBottom = size.height - 4.dp.toPx()
        val span = (viewEnd - viewStart).coerceAtLeast(MIN_WINDOW_DAYS)

        fun xAt(index: Int): Float = plotLeft + (index - viewStart) / span * (plotRight - plotLeft)
        fun valueOf(p: DailyHrvPoint): Double? = if (metric == TrendMetric.LN_SCALE) p.altiniScaleValue else p.hrvScore?.toDouble()

        val i0 = viewStart.roundToInt().coerceIn(0, points.size - 1)
        val i1 = viewEnd.roundToInt().coerceIn(0, points.size - 1)

        val domain = if (metric == TrendMetric.SCORE) {
            0.0..100.0
        } else {
            var lo = Double.POSITIVE_INFINITY
            var hi = Double.NEGATIVE_INFINITY
            for (i in i0..i1) {
                val p = points[i]
                lo = minOf(lo, p.altiniScaleValue, p.normalRangeLowAltiniScale ?: p.altiniScaleValue)
                hi = maxOf(hi, p.altiniScaleValue, p.normalRangeHighAltiniScale ?: p.altiniScaleValue)
            }
            if (!lo.isFinite() || !hi.isFinite() || lo == hi) {
                (points[i0].altiniScaleValue - 1.0)..(points[i0].altiniScaleValue + 1.0)
            } else {
                val pad = (hi - lo) * 0.18
                (lo - pad)..(hi + pad)
            }
        }

        fun yAt(value: Double): Float =
            (plotBottom - (value - domain.start) / (domain.endInclusive - domain.start) * (plotBottom - plotTop)).toFloat()

        // y-axis gridlines + labels
        val steps = 3
        for (s in 0..steps) {
            val v = domain.start + (domain.endInclusive - domain.start) * s / steps
            val y = yAt(v)
            drawLine(
                color = axisTextColor.copy(alpha = 0.25f),
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = 1.dp.toPx(),
            )
            val label = if (metric == TrendMetric.SCORE) v.roundToInt().toString() else "%.1f".format(v)
            val layout = textMeasurer.measure(label, style = TextStyle(fontSize = 9.sp, color = axisTextColor))
            drawText(layout, topLeft = Offset(0f, y - layout.size.height / 2f))
        }

        // normal-range band
        if (metric == TrendMetric.SCORE) {
            val half = HrvScoreCalculator.NORMAL_RANGE_SD_MULTIPLIER * HrvScoreCalculator.SCORE_Z_SCALE
            val top = yAt(HrvScoreCalculator.SCORE_CENTER + half)
            val bottom = yAt(HrvScoreCalculator.SCORE_CENTER - half)
            drawRect(
                color = bandColor.copy(alpha = 0.16f),
                topLeft = Offset(plotLeft, top),
                size = Size(plotRight - plotLeft, bottom - top),
            )
        } else {
            val bandPath = Path()
            var started = false
            for (i in i0..i1) {
                val high = points[i].normalRangeHighAltiniScale ?: continue
                val x = xAt(i); val y = yAt(high)
                if (!started) { bandPath.moveTo(x, y); started = true } else bandPath.lineTo(x, y)
            }
            for (i in i1 downTo i0) {
                val low = points[i].normalRangeLowAltiniScale ?: continue
                bandPath.lineTo(xAt(i), yAt(low))
            }
            bandPath.close()
            drawPath(bandPath, color = bandColor.copy(alpha = 0.16f))
        }

        // trend line
        val linePath = Path()
        var lineStarted = false
        for (i in i0..i1) {
            val v = valueOf(points[i]) ?: continue
            val x = xAt(i); val y = yAt(v)
            if (!lineStarted) { linePath.moveTo(x, y); lineStarted = true } else linePath.lineTo(x, y)
        }
        drawPath(
            linePath,
            color = lineColor.copy(alpha = 0.9f),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // status markers, thinned out so dense windows don't smear into a blob
        val visibleCount = (i1 - i0 + 1).coerceAtLeast(1)
        val markerEveryN = (visibleCount / 60).coerceAtLeast(1)
        val ringPx = 5.dp.toPx()
        val markerPx = 3.5.dp.toPx()
        var i = i0
        while (i <= i1) {
            val p = points[i]
            val v = valueOf(p)
            if (v != null) {
                val x = xAt(i); val y = yAt(v)
                drawCircle(color = surfaceColor, radius = ringPx, center = Offset(x, y))
                when (p.withinNormalRange) {
                    true -> drawCircle(color = okColor, radius = markerPx, center = Offset(x, y))
                    false -> {
                        val d = markerPx
                        val diamond = Path().apply {
                            moveTo(x, y - d); lineTo(x + d, y); lineTo(x, y + d); lineTo(x - d, y); close()
                        }
                        drawPath(diamond, color = outColor)
                    }
                    null -> drawCircle(
                        color = buildingColor,
                        radius = markerPx * 0.9f,
                        style = Stroke(width = 1.4.dp.toPx()),
                        center = Offset(x, y),
                    )
                }
            }
            i += markerEveryN
        }

        // selection crosshair
        val idx = selectedIndex ?: points.lastIndex
        if (idx in i0..i1) {
            val x = xAt(idx)
            drawLine(color = selectionColor, start = Offset(x, plotTop), end = Offset(x, plotBottom), strokeWidth = 1.dp.toPx())
        }
    }
}

private fun indexAtOffset(offset: Offset, canvasWidth: Float, axisPx: Float, viewStart: Float, viewEnd: Float): Float {
    val plotWidth = (canvasWidth - axisPx).coerceAtLeast(1f)
    val fraction = ((offset.x - axisPx) / plotWidth).coerceIn(0f, 1f)
    return viewStart + fraction * (viewEnd - viewStart)
}
