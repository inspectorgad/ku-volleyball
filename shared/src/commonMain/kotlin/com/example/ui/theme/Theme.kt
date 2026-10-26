package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// KU-branded schemes; dynamic (wallpaper) color is intentionally not used so
// the app always shows Jayhawk blue and crimson.
private val DarkColorScheme =
  darkColorScheme(
    primary = KuBlueLight,
    secondary = KuCrimsonLight,
    tertiary = KuYellowLight,
  )

private val LightColorScheme =
  lightColorScheme(
    primary = KuBlue,
    secondary = KuCrimson,
    tertiary = KuYellow,
    primaryContainer = KuBlueContainer,
    onPrimaryContainer = KuBlueOnContainer,
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
