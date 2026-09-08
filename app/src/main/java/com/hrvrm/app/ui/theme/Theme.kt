package com.hrvrm.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Coral = Color(0xFFFF4D6A)
private val CoralDark = Color(0xFFFF7A90)

private val LightColors = lightColorScheme(
    primary = Coral,
    secondary = Color(0xFF2DD4BF),
)

private val DarkColors = darkColorScheme(
    primary = CoralDark,
    secondary = Color(0xFF2DD4BF),
)

@Composable
fun HrvRmTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
