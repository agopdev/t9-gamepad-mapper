package com.t9mapper.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary          = Color(0xFF4FC3F7),
    onPrimary        = Color(0xFF003549),
    primaryContainer = Color(0xFF004C68),
    secondary        = Color(0xFF80CBC4),
    background       = Color(0xFF0D1117),
    surface          = Color(0xFF161B22),
    onBackground     = Color(0xFFE6EDF3),
    onSurface        = Color(0xFFE6EDF3),
)

private val LightColorScheme = lightColorScheme(
    primary          = Color(0xFF0288D1),
    onPrimary        = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB3E5FC),
    secondary        = Color(0xFF009688),
    background       = Color(0xFFF6F8FA),
    surface          = Color(0xFFFFFFFF),
    onBackground     = Color(0xFF1F2328),
    onSurface        = Color(0xFF1F2328),
)

@Composable
fun T9GamepadTheme(
    darkTheme: Boolean = true, // Default oscuro — mejor para gaming
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
