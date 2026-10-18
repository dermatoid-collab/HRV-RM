package com.hrvrm.app.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
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
import com.hrvrm.app.ui.theme.ChartGridLine
import com.hrvrm.app.ui.theme.RangeAbove
import com.hrvrm.app.ui.theme.RangeBelow
import com.hrvrm.app.ui.theme.RangeWithin
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

data class DailyHrvPoint(
    val date: LocalDate,
    val altiniScaleValue: Double,
    val baselineAltiniScaleValue: Double,
    val hrvScore: Int?,
    val normalRangeLowAltiniScale: Double?,
    val normalRangeHighAltiniScale: Double?,
    val withinNormalRange: Boolean?,
    val cv7: Double?,
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
                baselineAltiniScaleValue = m.baselineAltiniScaleValue,
                hrvScore = m.hrvScore,
                normalRangeLowAltiniScale = m.normalRangeLowAltiniScale,
                normalRangeHighAltiniScale = m.normalRangeHighAltiniScale,
                withinNormalRange = m.withinNormalRange,
                cv7 = m.cv7,
            )
        }
}

private enum class TrendMetric { LN_SCALE, SCORE }

private const val MIN_WINDOW_DAYS = 4f
private const val DEFAULT_WINDOW_DAYS = 30
private const val DEFAULT_RANGE_LABEL = "30D"
private const val AXIS_WIDTH_DP = 34f
/** Right-hand axis width, reserved only when CV7 is present -- its labels ("15%") are shorter
 * than the left axis's, so it's narrower than [AXIS_WIDTH_DP]. */
private const val RIGHT_AXIS_WIDTH_DP = 28f
private val dayFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val rangeOptions = listOf("7D" to 7, "30D" to 30, "90D" to 90, "All" to null)
/** Shared sizing for the metric/range pills -- matches the original (pre-spec) Button/
 * OutlinedButton proportions (11sp label, 4dp gaps) but on a plain Surface, which has no
 * Material3 minimum-width constraint -- that constraint, not the proportions, was what let
 * "All" get clipped by the card edge once the row grew to 6 pills. */
private val CHIP_HEIGHT = 32.dp
private val CHIP_PADDING_H = 8.dp
private val CHIP_GAP = 2.dp
private val CHIP_FONT_SIZE = 11.sp

private fun startIndexForLastDays(points: List<DailyHrvPoint>, days: Int): Float {
    val cutoff = points.last().date.minusDays((days - 1).toLong())
    val idx = points.indexOfFirst { it.date >= cutoff }
    return (if (idx < 0) 0 else idx).toFloat()
}

/**
 * Daily HRV trend. One finger drags to select a point (fires immediately on touch and
 * follows the finger while it's down); two fingers pinch/pan to zoom. Tap a range preset to
 * jump to it — the active preset stays highlighted until a pinch/pan moves the view away
 * from an exact preset window.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HrvTrendChart(
    points: List<DailyHrvPoint>,
    onShownColorChanged: (Color) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (points.size < 2) return // nothing to trend yet — the row below already shows the one reading

    val maxIndex = (points.size - 1).toFloat()
    var metric by remember { mutableStateOf(TrendMetric.LN_SCALE) }
    var viewStart by remember(points.size) { mutableFloatStateOf(startIndexForLastDays(points, DEFAULT_WINDOW_DAYS)) }
    var viewEnd by remember(points.size) { mutableFloatStateOf(maxIndex) }
    var selectedIndex by remember(points.size) { mutableStateOf<Int?>(null) }
    var selectedRangeLabel by remember(points.size) { mutableStateOf<String?>(DEFAULT_RANGE_LABEL) }

    val hasScore = points.any { it.hrvScore != null }
    val hasCv7 = points.any { it.cv7 != null }
    val effectiveMetric = when {
        metric == TrendMetric.SCORE && !hasScore -> TrendMetric.LN_SCALE
        else -> metric
    }

    Column(modifier = modifier) {
        // Material3's clickable Surface (used by every chip below) pads itself out to a
        // 48dp minimum touch target by default -- invisible, but it inflates the *gap*
        // between any two chips narrower than 48dp (7D/30D/90D/All all are; HRV score/
        // Score % aren't, which is why only the range chips looked wrongly spaced).
        // Disabling it here is the same fix already used for the sync IconButton in
        // HistoryScreen.kt.
        CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (hasScore) {
                    MetricToggle(effectiveMetric, onChange = { metric = it })
                } else {
                    Text("HRV score", style = MaterialTheme.typography.labelSmall)
                }
                RangePresets(selectedRangeLabel) { label, days ->
                    viewStart = if (days == null) 0f else startIndexForLastDays(points, days)
                    viewEnd = maxIndex
                    selectedIndex = null
                    selectedRangeLabel = label
                }
            }
        }

        val selected = selectedIndex?.let { points.getOrNull(it) } ?: points.last()
        SelectionReadout(selected, effectiveMetric, hasCv7 = hasCv7)

        // Same fixed clinical-signal colors (RangeWithin/Above/Below) the bars themselves use
        // (see TrendCanvas's own statusColor below) -- reported up so the card wrapping this
        // chart (ChartsScreen) can give its own border the same color as the selected bar.
        val selectedColor = when {
            selected.normalRangeLowAltiniScale == null || selected.normalRangeHighAltiniScale == null ->
                MaterialTheme.colorScheme.onSurfaceVariant
            selected.altiniScaleValue > selected.normalRangeHighAltiniScale -> RangeAbove
            selected.altiniScaleValue < selected.normalRangeLowAltiniScale -> RangeBelow
            else -> RangeWithin
        }
        SideEffect { onShownColorChanged(selectedColor) }

        TrendCanvas(
            points = points,
            metric = effectiveMetric,
            hasCv7 = hasCv7,
            viewStart = viewStart,
            viewEnd = viewEnd,
            selectedIndex = selectedIndex,
            onViewChange = { start, end -> viewStart = start; viewEnd = end; selectedRangeLabel = null },
            onSelect = { selectedIndex = it },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2.5f),
        )
    }
}

@Composable
private fun MetricToggle(metric: TrendMetric, onChange: (TrendMetric) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(CHIP_GAP)) {
        ToggleChip("HRV score", metric == TrendMetric.LN_SCALE) { onChange(TrendMetric.LN_SCALE) }
        ToggleChip("Score %", metric == TrendMetric.SCORE) { onChange(TrendMetric.SCORE) }
    }
}

@Composable
private fun ToggleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        color = bg,
        contentColor = fg,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.height(CHIP_HEIGHT),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxHeight()) {
            Text(
                label,
                fontSize = CHIP_FONT_SIZE,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.padding(horizontal = CHIP_PADDING_H),
            )
        }
    }
}

@Composable
private fun RangePresets(selected: String?, modifier: Modifier = Modifier, onSelect: (label: String, days: Int?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(CHIP_GAP), modifier = modifier) {
        rangeOptions.forEach { (label, days) ->
            RangePresetButton(label, selected = label == selected) { onSelect(label, days) }
        }
    }
}

/** Same pill treatment as the metric [ToggleChip] above -- a plain Surface (no Material3
 * minimum-width constraint, unlike Button/OutlinedButton) so 4 of these plus the metric
 * toggle fit the card's width without scrolling or clipping. */
@Composable
private fun RangePresetButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        color = bg,
        contentColor = fg,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.height(CHIP_HEIGHT),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxHeight()) {
            Text(
                label,
                fontSize = CHIP_FONT_SIZE,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.padding(horizontal = CHIP_PADDING_H),
            )
        }
    }
}

@Composable
private fun SelectionReadout(point: DailyHrvPoint, metric: TrendMetric, hasCv7: Boolean) {
    val valueText = when (metric) {
        TrendMetric.LN_SCALE -> "%.1f".format(point.altiniScaleValue)
        TrendMetric.SCORE -> point.hrvScore?.toString() ?: "–"
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(point.date.format(dayFormatter), fontSize = CHIP_FONT_SIZE)
        Text("·", fontSize = CHIP_FONT_SIZE, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(valueText, fontSize = CHIP_FONT_SIZE, fontWeight = FontWeight.Bold)
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
        Text("·", fontSize = CHIP_FONT_SIZE, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(statusLabel, fontSize = CHIP_FONT_SIZE, color = statusColor, fontWeight = FontWeight.SemiBold)
        // CV7 is overlaid on this same chart now (no switch), so its reading rides alongside
        // the HRV value/status here too. No within/above/below judgement attached to it --
        // see its doc comment: no universal threshold, only meaningful against your own history.
        if (hasCv7) {
            Text("·", fontSize = CHIP_FONT_SIZE, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                point.cv7?.let { "CV7 %.1f%%".format(it) } ?: "CV7 –",
                fontSize = CHIP_FONT_SIZE,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun TrendCanvas(
    points: List<DailyHrvPoint>,
    metric: TrendMetric,
    hasCv7: Boolean,
    viewStart: Float,
    viewEnd: Float,
    selectedIndex: Int?,
    onViewChange: (Float, Float) -> Unit,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val gridLineColor = ChartGridLine
    val bandColor = MaterialTheme.colorScheme.secondary
    val buildingColor = MaterialTheme.colorScheme.onSurfaceVariant
    val selectionColor = MaterialTheme.colorScheme.primary
    val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    // Gesture state is read fresh via rememberUpdatedState rather than as plain captured
    // parameters: the pointerInput coroutine below is keyed on Unit (never restarts for the
    // lifetime of this composable), so without this indirection it would keep using whatever
    // viewStart/viewEnd/points happened to be current the first time it launched.
    val pointsState = rememberUpdatedState(points)
    val viewStartState = rememberUpdatedState(viewStart)
    val viewEndState = rememberUpdatedState(viewEnd)
    val onViewChangeState = rememberUpdatedState(onViewChange)
    val onSelectState = rememberUpdatedState(onSelect)

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val axisPx = AXIS_WIDTH_DP.dp.toPx()
                    val rightAxisPx = if (hasCv7) RIGHT_AXIS_WIDTH_DP.dp.toPx() else 0f
                    val effectiveWidth = size.width.toFloat() - rightAxisPx
                    val down = awaitFirstDown()
                    val idx = indexAtOffset(down.position, effectiveWidth, axisPx, viewStartState.value, viewEndState.value)
                    onSelectState.value(idx.roundToInt().coerceIn(0, pointsState.value.size - 1))

                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zoom != 1f || pan != Offset.Zero) {
                                val centroid = event.calculateCentroid()
                                val currentPoints = pointsState.value
                                val maxIdx = (currentPoints.size - 1).toFloat()
                                val currentStart = viewStartState.value
                                val currentEnd = viewEndState.value
                                val plotWidth = (effectiveWidth - axisPx).coerceAtLeast(1f)
                                val span = (currentEnd - currentStart).coerceAtLeast(MIN_WINDOW_DAYS)
                                val focalFraction = ((centroid.x - axisPx) / plotWidth).coerceIn(0f, 1f)
                                val focalDay = currentStart + focalFraction * span

                                val newSpan = (span / zoom).coerceIn(MIN_WINDOW_DAYS, maxIdx.coerceAtLeast(MIN_WINDOW_DAYS))
                                var newStart = focalDay - focalFraction * newSpan
                                var newEnd = newStart + newSpan

                                val dayPerPx = newSpan / plotWidth
                                newStart -= pan.x * dayPerPx
                                newEnd -= pan.x * dayPerPx

                                if (newStart < 0f) { newEnd -= newStart; newStart = 0f }
                                if (newEnd > maxIdx) { newStart -= (newEnd - maxIdx); newEnd = maxIdx }
                                newStart = newStart.coerceAtLeast(0f)
                                newEnd = newEnd.coerceAtMost(maxIdx)

                                onViewChangeState.value(newStart, newEnd)
                            }
                            event.changes.forEach { it.consume() }
                        } else if (pressed.size == 1) {
                            val change = pressed[0]
                            val currentPoints = pointsState.value
                            val newIdx = indexAtOffset(change.position, effectiveWidth, axisPx, viewStartState.value, viewEndState.value)
                            onSelectState.value(newIdx.roundToInt().coerceIn(0, currentPoints.size - 1))
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        val axisPx = AXIS_WIDTH_DP.dp.toPx()
        val rightAxisPx = if (hasCv7) RIGHT_AXIS_WIDTH_DP.dp.toPx() else 0f
        val plotLeft = axisPx
        val plotRight = size.width - rightAxisPx
        val plotTop = 8.dp.toPx()
        val plotBottom = size.height - 14.dp.toPx() // leaves room for the x-axis date labels below
        val span = (viewEnd - viewStart).coerceAtLeast(MIN_WINDOW_DAYS)

        fun xAt(index: Int): Float = plotLeft + (index - viewStart) / span * (plotRight - plotLeft)
        fun valueOf(p: DailyHrvPoint): Double? = when (metric) {
            TrendMetric.LN_SCALE -> p.altiniScaleValue
            TrendMetric.SCORE -> p.hrvScore?.toDouble()
        }

        val i0 = viewStart.roundToInt().coerceIn(0, points.size - 1)
        val i1 = viewEnd.roundToInt().coerceIn(0, points.size - 1)

        // CV7's own domain -- always computed over the visible window when it exists anywhere
        // in the dataset, independent of whichever primary metric (LN_SCALE/SCORE) is showing,
        // since it's an always-on overlay now, not a selectable alternative.
        val cv7Domain = if (hasCv7) {
            var lo = Double.POSITIVE_INFINITY
            var hi = Double.NEGATIVE_INFINITY
            for (i in i0..i1) {
                val v = points[i].cv7 ?: continue
                lo = minOf(lo, v)
                hi = maxOf(hi, v)
            }
            if (!lo.isFinite() || !hi.isFinite() || lo == hi) {
                val v = (i0..i1).firstNotNullOfOrNull { points[it].cv7 } ?: 0.0
                (v - 1.0)..(v + 1.0)
            } else {
                val pad = (hi - lo) * 0.18
                (lo - pad)..(hi + pad)
            }
        } else {
            0.0..1.0
        }
        fun cv7YAt(value: Double): Float =
            (plotBottom - (value - cv7Domain.start) / (cv7Domain.endInclusive - cv7Domain.start) * (plotBottom - plotTop)).toFloat()

        val domain = when (metric) {
            TrendMetric.SCORE -> 0.0..100.0
            TrendMetric.LN_SCALE -> {
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
        }

        fun yAt(value: Double): Float =
            (plotBottom - (value - domain.start) / (domain.endInclusive - domain.start) * (plotBottom - plotTop)).toFloat()

        // y-axis gridlines + labels
        val steps = 3
        for (s in 0..steps) {
            val v = domain.start + (domain.endInclusive - domain.start) * s / steps
            val y = yAt(v)
            drawLine(
                color = gridLineColor.copy(alpha = 0.55f),
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = 1.dp.toPx(),
            )
            val label = if (metric == TrendMetric.SCORE) v.roundToInt().toString() else "%.1f".format(v)
            val layout = textMeasurer.measure(label, style = TextStyle(fontSize = 9.sp, color = axisTextColor))
            drawText(layout, topLeft = Offset(0f, y - layout.size.height / 2f))
        }

        // CV7 right-hand axis -- its own scale, independent of the primary metric's left axis,
        // since the two series share a plot but not a domain.
        if (hasCv7) {
            for (s in 0..steps) {
                val v = cv7Domain.start + (cv7Domain.endInclusive - cv7Domain.start) * s / steps
                val y = cv7YAt(v)
                val label = "%.0f%%".format(v)
                val layout = textMeasurer.measure(label, style = TextStyle(fontSize = 9.sp, color = buildingColor))
                drawText(layout, topLeft = Offset(plotRight + 4.dp.toPx(), y - layout.size.height / 2f))
            }
        }

        // normal-range band -- CV7 has none (no universal threshold, see its doc comment),
        // so it's the one metric here with no band at all.
        when (metric) {
            TrendMetric.SCORE -> {
                val half = HrvScoreCalculator.NORMAL_RANGE_SD_MULTIPLIER * HrvScoreCalculator.SCORE_Z_SCALE
                val top = yAt(HrvScoreCalculator.SCORE_CENTER + half)
                val bottom = yAt(HrvScoreCalculator.SCORE_CENTER - half)
                drawRect(
                    color = bandColor.copy(alpha = 0.16f),
                    topLeft = Offset(plotLeft, top),
                    size = Size(plotRight - plotLeft, bottom - top),
                )
            }
            TrendMetric.LN_SCALE -> {
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
        }

        // Fixed clinical-signal colors (RangeWithin/Above/Below), never themed -- see their
        // doc comments in Theme.kt. Above/below split uses the normal-range bounds directly
        // rather than just p.withinNormalRange, which only says in/out, not which side. Null
        // (building baseline) for a point with no band yet. Shared by both the trend line and
        // the status markers below so they always agree.
        fun statusColor(p: DailyHrvPoint): Color? = when {
            p.normalRangeLowAltiniScale == null || p.normalRangeHighAltiniScale == null -> null
            p.altiniScaleValue > p.normalRangeHighAltiniScale -> RangeAbove
            p.altiniScaleValue < p.normalRangeLowAltiniScale -> RangeBelow
            else -> RangeWithin
        }

        // trend bars -- one per reading, colored by its own status (replaces the former
        // dot+connecting-line rendering entirely). Each bar rises from the bottom of the
        // current domain (literal zero for SCORE, whose domain is fixed 0..100; the visible
        // window's own floor for LN_SCALE, which has no meaningful zero) up to its value --
        // yAt(domain.start) is always plotBottom by construction, so "plotBottom" already is
        // that floor for either metric. Width is a fraction of the per-point slot so bars
        // stay reasonably sized whether a handful or hundreds are visible at once.
        val barSlotWidth = (plotRight - plotLeft) / span
        val barWidth = (barSlotWidth * 0.62f).coerceIn(1.5.dp.toPx(), 10.dp.toPx())
        val barCorner = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        for (i in i0..i1) {
            val v = valueOf(points[i]) ?: continue
            val x = xAt(i); val y = yAt(v)
            drawRoundRect(
                color = statusColor(points[i]) ?: buildingColor,
                topLeft = Offset(x - barWidth / 2f, y),
                size = Size(barWidth, plotBottom - y),
                cornerRadius = barCorner,
            )
        }

        // CV7 overlay -- always drawn alongside the primary metric's line when present (no
        // switch: see its doc comment, it's a second signal shown together with the HRV line,
        // never swapped in for it). Dashed and on its own right-hand scale (cv7YAt above) so
        // it's visually distinct from the solid, status-colored HRV line even where they cross.
        if (hasCv7) {
            val cv7Path = Path()
            var cv7Started = false
            for (i in i0..i1) {
                val v = points[i].cv7 ?: continue
                val x = xAt(i); val y = cv7YAt(v)
                if (!cv7Started) { cv7Path.moveTo(x, y); cv7Started = true } else cv7Path.lineTo(x, y)
            }
            drawPath(
                cv7Path,
                color = buildingColor.copy(alpha = 0.9f),
                style = Stroke(
                    width = 1.25.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f)),
                ),
            )
        }

        // selection crosshair -- also the spec's "latest vertical reference" when nothing is
        // selected, since it already defaults to the most recent point.
        val idx = selectedIndex ?: points.lastIndex
        if (idx in i0..i1) {
            val x = xAt(idx)
            drawLine(color = selectionColor, start = Offset(x, plotTop), end = Offset(x, plotBottom), strokeWidth = 1.5.dp.toPx())
        }

        // x-axis start/end date labels
        val startLabel = textMeasurer.measure(points[i0].date.format(dayFormatter), style = TextStyle(fontSize = 9.sp, color = axisTextColor))
        drawText(startLabel, topLeft = Offset(plotLeft, plotBottom + 4.dp.toPx()))
        val endLabel = textMeasurer.measure(points[i1].date.format(dayFormatter), style = TextStyle(fontSize = 9.sp, color = axisTextColor))
        drawText(endLabel, topLeft = Offset(plotRight - endLabel.size.width, plotBottom + 4.dp.toPx()))
    }
}

private fun indexAtOffset(offset: Offset, canvasWidth: Float, axisPx: Float, viewStart: Float, viewEnd: Float): Float {
    val plotWidth = (canvasWidth - axisPx).coerceAtLeast(1f)
    val fraction = ((offset.x - axisPx) / plotWidth).coerceIn(0f, 1f)
    return viewStart + fraction * (viewEnd - viewStart)
}
