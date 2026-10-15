package com.hrvrm.app.ui.asi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.hrvrm.app.asi.AsiParameterResult
import com.hrvrm.app.asi.AsiResult

/**
 * Compact "ASI {score}" badge for the main/Today dashboard -- spec 15's "aggiungi ASI a fianco di
 * HR score, stesso font, colore diverso": [fontSize]/[fontWeight] are passed in by the caller so
 * this matches whatever HRV score text it sits next to, while the color always comes from the
 * result's own status instead of following the caller's HRV color.
 */
@Composable
fun AsiMiniBadge(result: AsiResult, fontSize: TextUnit, fontWeight: FontWeight = FontWeight.Bold, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(
            result.score?.toString() ?: "—",
            fontSize = fontSize,
            fontWeight = fontWeight,
            color = result.color.toComposeColor(),
        )
        Text("ASI", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Full ASI breakdown for the daily/per-measurement screen (MeasureScreen's result view and
 * MeasurementDetailScreen both use this) -- spec 15's required top-to-bottom order from "valore
 * ASI" through "Confidence": score+status, the five parameter states, the synthetic
 * interpretation sentence (only when one was detected -- never a generic substitute, per spec 9),
 * exceptions (only when present, per spec 15.1), then confidence.
 */
@Composable
fun AsiDetailSection(result: AsiResult) {
    Column(modifier = Modifier.fillMaxWidth()) {
        AsiScoreAndStatus(result)
        Spacer(Modifier.height(10.dp))
        AsiParameterStates(result)

        result.interpretation?.let { interpretation ->
            Spacer(Modifier.height(10.dp))
            Text(interpretation, style = MaterialTheme.typography.bodyMedium)
        }

        if (result.exceptions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            result.exceptions.forEach { exception ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        asiIconFor(exception.icon),
                        contentDescription = null,
                        tint = exception.color.toComposeColor(),
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Text(
                        asiExceptionLabel(exception),
                        style = MaterialTheme.typography.bodySmall,
                        color = exception.color.toComposeColor(),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        AsiConfidenceRow(result)
    }
}

@Composable
private fun AsiScoreAndStatus(result: AsiResult) {
    val color = result.color.toComposeColor()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            result.score?.toString() ?: "—",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Column(modifier = Modifier.padding(start = 10.dp)) {
            Text("ASI", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    asiIconFor(result.icon),
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.padding(end = 4.dp),
                )
                Text(result.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = color)
            }
        }
    }
}

@Composable
private fun AsiParameterStates(result: AsiResult) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        result.parameters.forEach { param -> AsiParameterChip(param) }
    }
}

@Composable
private fun AsiParameterChip(param: AsiParameterResult) {
    val color = param.color.toComposeColor()
    val statusLabel = asiParameterStatusLabel(param.statusName)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            asiIconFor(param.icon),
            contentDescription = "${param.label}: $statusLabel",
            tint = color,
        )
        Text(param.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(statusLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
private fun AsiConfidenceRow(result: AsiResult) {
    val color = result.confidence.color.toComposeColor()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            asiIconFor(result.confidence.icon),
            contentDescription = null,
            tint = color,
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            "Confidence: ${asiConfidenceLabel(result.confidence.status)}",
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}
