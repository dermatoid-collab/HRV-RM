package com.hrvrm.app.ui.measure

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

/** Live PPG trace: the recent camera-luma samples, auto-scaled to fill the canvas. */
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

        val stepX = size.width / (samples.size - 1).coerceAtLeast(1)
        var previous: Offset? = null

        for (i in samples.indices) {
            val normalized = ((samples[i] - min) / range).toFloat()
            val x = i * stepX
            val y = size.height * (1f - normalized)
            val point = Offset(x, y)
            previous?.let { start ->
                drawLine(
                    color = lineColor,
                    start = start,
                    end = point,
                    strokeWidth = 4f,
                    cap = StrokeCap.Round,
                )
            }
            previous = point
        }
    }
}
