package com.hrvrm.app.ui.measure

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

private const val VERTICAL_MARGIN_FRACTION = 0.12f
private const val STROKE_WIDTH_PX = 8f

/** Live PPG trace: the recent filtered signal, auto-scaled to fill the canvas, scrolling left. */
@Composable
fun PpgWaveform(samples: List<Double>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (samples.size < 2) return@Canvas

        val min = samples.min()
        val max = samples.max()
        val range = (max - min).takeIf { it > 0.0001 } ?: 1.0

        val topMargin = size.height * VERTICAL_MARGIN_FRACTION
        val plotHeight = size.height * (1f - 2 * VERTICAL_MARGIN_FRACTION)
        val stepX = size.width / (samples.size - 1).coerceAtLeast(1)

        val path = Path()
        for (i in samples.indices) {
            val normalized = ((samples[i] - min) / range).toFloat()
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
