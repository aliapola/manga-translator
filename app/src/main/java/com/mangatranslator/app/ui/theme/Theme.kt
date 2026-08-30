package com.mangatranslator.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PrimaryColor = Color(0xFF2F6F5E)
private val SecondaryColor = Color(0xFFE0A94E)

private val DarkColors = darkColorScheme(
    primary = PrimaryColor,
    secondary = SecondaryColor
)

private val LightColors = lightColorScheme(
    primary = PrimaryColor,
    secondary = SecondaryColor
)

@Composable
fun MangaTranslatorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
