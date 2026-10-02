package com.hrvrm.app.ui.measure

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

private const val VERTICAL_MARGIN_FRACTION = 0.12f
private const val STROKE_WIDTH_PX = 8f

/**
 * How much each redraw's fresh min/max is allowed to move the *displayed* scale, per
 * tick. Snapping straight to the instantaneous window's min/max (as a naive auto-scale
 * would) makes the whole axis visibly "breathe" on every tiny amplitude change — this
 * eases toward it instead, so the scale drifts slowly and the trace reads as stable,
 * the same impression HRV4Training's own live trace gives.
 */
private const val SCALE_SMOOTHING = 0.15

/** Live PPG trace: the recent filtered signal, scrolling left with a slowly-adapting vertical scale. */
@Composable
fun PpgWaveform(samples: List<Double>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary

    var displayMin by remember { mutableStateOf<Double?>(null) }
    var displayMax by remember { mutableStateOf<Double?>(null) }

    if (samples.size >= 2) {
        val currentMin = samples.min()
        val currentMax = samples.max()
        displayMin = displayMin?.let { it + (currentMin - it) * SCALE_SMOOTHING } ?: currentMin
        displayMax = displayMax?.let { it + (currentMax - it) * SCALE_SMOOTHING } ?: currentMax
    }

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (samples.size < 2) return@Canvas

        val min = displayMin ?: samples.min()
        val max = displayMax ?: samples.max()
        val range = (max - min).takeIf { it > 0.0001 } ?: 1.0

        val topMargin = size.height * VERTICAL_MARGIN_FRACTION
        val plotHeight = size.height * (1f - 2 * VERTICAL_MARGIN_FRACTION)
        val stepX = size.width / (samples.size - 1).coerceAtLeast(1)

        val path = Path()
        for (i in samples.indices) {
            // Clamp: the eased scale can lag a genuine amplitude change for a few
            // ticks, during which a point might briefly fall outside [min, max].
            val normalized = (((samples[i] - min) / range).toFloat()).coerceIn(0f, 1f)
            val x = i * stepX
            val y = topMargin + plotHeight * (1f - normalized)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = STROKE_WIDTH_PX, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
