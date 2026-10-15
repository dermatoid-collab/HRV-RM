package com.hrvrm.app.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hrvrm.app.asi.AsiConfig
import com.hrvrm.app.asi.AsiHistoryAdapter
import com.hrvrm.app.asi.AsiResult
import com.hrvrm.app.data.MeasurementEntity
import com.hrvrm.app.ui.asi.toComposeColor
import com.hrvrm.app.ui.theme.ChartGridLine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

data class DailyAsiPoint(val date: LocalDate, val result: AsiResult)

/**
 * One point per calendar day (latest measurement wins, same rule as [buildDailyPoints]/
 * [buildDailyRhrPoints]). ASI itself is computed per original reading (via [AsiHistoryAdapter],
 * using each reading's own preceding history) and only deduplicated to one-per-day afterwards,
 * for display -- same split between "computed per reading" and "shown per day" the HRV/RHR
 * baselines already use.
 */
fun buildDailyAsiPoints(measurementsNewestFirst: List<MeasurementEntity>): List<DailyAsiPoint> {
    val resultsById = AsiHistoryAdapter.computeForEach(measurementsNewestFirst)
    val zone = ZoneId.systemDefault()
    val latestPerDay = LinkedHashMap<LocalDate, MeasurementEntity>()
    for (m in measurementsNewestFirst) {
        val day = Instant.ofEpochMilli(m.timestampEpochMs).atZone(zone).toLocalDate()
        latestPerDay.putIfAbsent(day, m)
    }
    return latestPerDay.entries
        .sortedBy { it.key }
        .mapNotNull { (day, m) -> resultsById[m.id]?.let { DailyAsiPoint(day, it) } }
}

private const val ASI_DEFAULT_WINDOW_DAYS = 30
private const val ASI_DEFAULT_RANGE_LABEL = "30D"
private const val ASI_AXIS_WIDTH_DP = 26f
private val asiDayFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val asiRangeOptions = listOf("7D" to 7, "30D" to 30, "90D" to 90, "All" to null)
private val ASI_CHIP_HEIGHT = 32.dp
private val ASI_CHIP_PADDING_H = 8.dp
private val ASI_CHIP_GAP = 2.dp
private val ASI_CHIP_FONT_SIZE = 11.sp

private fun asiStartIndexForLastDays(points: List<DailyAsiPoint>, days: Int): Int {
    val cutoff = points.last().date.minusDays((days - 1).toLong())
    val idx = points.indexOfFirst { it.date >= cutoff }
    return if (idx < 0) 0 else idx
}

/**
 * ASI day-by-day, as its own chart -- deliberately not overlaid on the HRV chart (spec 15.1:
 * "Non sovrapporre ASI e HRV nello stesso grafico senza gestire correttamente le differenti
 * scale numeriche"; ASI's own 0-100 scale is fixed regardless of the visible range, unlike HRV's
 * floating ln-scale domain, so the two never share an axis here). One finger drags to select a
 * day; no pinch-zoom, matching RhrTrendChart's simpler interaction model.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AsiTrendChart(points: List<DailyAsiPoint>, modifier: Modifier = Modifier) {
    if (points.size < 2) return // nothing to trend yet

    var rangeStart by remember(points.size) { mutableStateOf(asiStartIndexForLastDays(points, ASI_DEFAULT_WINDOW_DAYS)) }
    var selectedRangeLabel by remember(points.size) { mutableStateOf<String?>(ASI_DEFAULT_RANGE_LABEL) }
    var selectedIndex by remember(points.size) { mutableStateOf<Int?>(null) }
    val rangeEnd = points.lastIndex

    Column(modifier = modifier) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("ASI", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                AsiRangePresets(selectedRangeLabel) { label, days ->
                    rangeStart = if (days == null) 0 else asiStartIndexForLastDays(points, days)
                    selectedRangeLabel = label
                    selectedIndex = null
                }
            }
        }

        val shownIndex = selectedIndex ?: points.lastIndex
        val shown = points[shownIndex]
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Text(shown.date.format(asiDayFormatter), fontSize = ASI_CHIP_FONT_SIZE)
            Text("·", fontSize = ASI_CHIP_FONT_SIZE, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                shown.result.score?.toString() ?: "—",
                fontSize = ASI_CHIP_FONT_SIZE,
                fontWeight = FontWeight.Bold,
                color = shown.result.color.toComposeColor(),
            )
            Text("·", fontSize = ASI_CHIP_FONT_SIZE, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                shown.result.title,
                fontSize = ASI_CHIP_FONT_SIZE,
                color = shown.result.color.toComposeColor(),
                fontWeight = FontWeight.SemiBold,
            )
        }

        AsiCanvas(
            points = points,
            startIndex = rangeStart,
            endIndex = rangeEnd,
            selectedIndex = selectedIndex,
            onSelect = { selectedIndex = it },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3.0f),
        )
    }
}

@Composable
private fun AsiRangePresets(selected: String?, modifier: Modifier = Modifier, onSelect: (label: String, days: Int?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(ASI_CHIP_GAP), modifier = modifier) {
        asiRangeOptions.forEach { (label, days) ->
            AsiRangePresetButton(label, selected = label == selected) { onSelect(label, days) }
        }
    }
}

@Composable
private fun AsiRangePresetButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        color = bg,
        contentColor = fg,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.height(ASI_CHIP_HEIGHT),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxHeight()) {
            Text(
                label,
                fontSize = ASI_CHIP_FONT_SIZE,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.padding(horizontal = ASI_CHIP_PADDING_H),
            )
        }
    }
}

@Composable
private fun AsiCanvas(
    points: List<DailyAsiPoint>,
    startIndex: Int,
    endIndex: Int,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridLineColor = ChartGridLine
    val selectionColor = MaterialTheme.colorScheme.primary

    val pointsState = rememberUpdatedState(points)
    val startIndexState = rememberUpdatedState(startIndex)
    val endIndexState = rememberUpdatedState(endIndex)
    val onSelectState = rememberUpdatedState(onSelect)

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val axisPx = ASI_AXIS_WIDTH_DP.dp.toPx()
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
        val axisPx = ASI_AXIS_WIDTH_DP.dp.toPx()
        val plotLeft = axisPx
        val plotRight = size.width
        val plotTop = 8.dp.toPx()
        val plotBottom = size.height - 14.dp.toPx()

        val i0 = startIndex.coerceIn(0, points.size - 1)
        val i1 = endIndex.coerceIn(i0, points.size - 1)
        val span = (i1 - i0).coerceAtLeast(1)

        fun xAt(index: Int): Float = plotLeft + (index - i0).toFloat() / span * (plotRight - plotLeft)
        // ASI's scale is fixed 0-100 (spec 15.1: never share a floating-domain axis with HRV).
        fun yAt(value: Double): Float = (plotBottom - (value / 100.0) * (plotBottom - plotTop)).toFloat()

        // Threshold gridlines at the spec's own main-status band boundaries (section 6), so a bar's
        // position against these lines doubles as a visual legend for which band it falls in.
        val thresholds = listOf(
            AsiConfig.CAUTION_MIN,
            AsiConfig.MILD_DEVIATION_MIN,
            AsiConfig.FAVORABLE_MIN,
        )
        for (t in thresholds) {
            val y = yAt(t.toDouble())
            drawLine(
                color = gridLineColor.copy(alpha = 0.55f),
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = 1.dp.toPx(),
            )
            val layout = textMeasurer.measure(t.toString(), style = TextStyle(fontSize = 9.sp, color = axisTextColor))
            drawText(layout, topLeft = Offset(0f, y - layout.size.height / 2f))
        }

        // bars -- one per visible day with a score, colored by that day's own status; days
        // without a score (CALIBRATING/INSUFFICIENT_DATA/STALE_WINDOW) are left as a gap rather
        // than plotted as zero, since a null score is not the same thing as a bad one.
        val barSlotWidth = (plotRight - plotLeft) / span
        val barWidth = (barSlotWidth * 0.62f).coerceIn(1.5.dp.toPx(), 10.dp.toPx())
        val barCorner = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        for (i in i0..i1) {
            val score = points[i].result.score ?: continue
            val x = xAt(i); val y = yAt(score.toDouble())
            drawRoundRect(
                color = points[i].result.color.toComposeColor(),
                topLeft = Offset(x - barWidth / 2f, y),
                size = Size(barWidth, (plotBottom - y).coerceAtLeast(1f)),
                cornerRadius = barCorner,
            )
        }

        val idx = selectedIndex ?: i1
        if (idx in i0..i1) {
            val x = xAt(idx)
            drawLine(color = selectionColor, start = Offset(x, plotTop), end = Offset(x, plotBottom), strokeWidth = 1.5.dp.toPx())
        }

        val startLabel = textMeasurer.measure(
            points[i0].date.format(asiDayFormatter),
            style = TextStyle(fontSize = 9.sp, color = axisTextColor),
        )
        drawText(startLabel, topLeft = Offset(plotLeft, plotBottom + 4.dp.toPx()))
        val endLabel = textMeasurer.measure(
            points[i1].date.format(asiDayFormatter),
            style = TextStyle(fontSize = 9.sp, color = axisTextColor),
        )
        drawText(endLabel, topLeft = Offset(plotRight - endLabel.size.width, plotBottom + 4.dp.toPx()))
    }
}
