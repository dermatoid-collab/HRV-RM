package com.hrvrm.app.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hrvrm.app.data.MeasurementEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

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
private const val RHR_AXIS_WIDTH_DP = 26f
private val rhrDayFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

private fun rhrStartIndexForLastDays(points: List<DailyRhrPoint>, days: Int): Int {
    val cutoff = points.last().date.minusDays((days - 1).toLong())
    val idx = points.indexOfFirst { it.date >= cutoff }
    return if (idx < 0) 0 else idx
}

/**
 * Resting heart rate day-by-day, one point per calendar day (latest measurement wins).
 * Deliberately simpler than [HrvTrendChart]: no pinch-zoom, no tap-to-select, no
 * normal-range band, no ln-scale/score toggle — just the 7/30/90-day presets and a plain
 * line. RHR here is a different vital sign from HRV, not another view of the same one, so
 * it gets its own small card instead of another entry in the HRV chart's toggle.
 */
@Composable
fun RhrTrendChart(points: List<DailyRhrPoint>, modifier: Modifier = Modifier) {
    if (points.size < 2) return // nothing to trend yet

    var rangeStart by remember(points.size) { mutableStateOf(rhrStartIndexForLastDays(points, RHR_DEFAULT_WINDOW_DAYS)) }
    val rangeEnd = points.lastIndex

    Column(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Resting HR", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            RhrRangePresets { days -> rangeStart = if (days == null) 0 else rhrStartIndexForLastDays(points, days) }
        }

        val latest = points.last()
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Text(latest.date.format(rhrDayFormatter), style = MaterialTheme.typography.labelSmall)
            Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "${latest.meanHrBpm.roundToInt()} bpm",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }

        RhrCanvas(
            points = points,
            startIndex = rangeStart,
            endIndex = rangeEnd,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2.6f),
        )
    }
}

@Composable
private fun RhrRangePresets(onSelect: (Int?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("7D" to 7, "30D" to 30, "90D" to 90).forEach { (label, days) ->
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
private fun RhrCanvas(points: List<DailyRhrPoint>, startIndex: Int, endIndex: Int, modifier: Modifier) {
    val textMeasurer = rememberTextMeasurer()
    val lineColor = MaterialTheme.colorScheme.tertiary
    val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(modifier = modifier) {
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

        // start/end date labels — this chart has no tap-to-select to read a date from otherwise
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
