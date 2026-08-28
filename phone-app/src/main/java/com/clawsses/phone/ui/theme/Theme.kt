package com.clawsses.phone.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF4EC9B0),      // Claude teal
    secondary = Color(0xFF569CD6),     // Blue
    tertiary = Color(0xFFDCDCAA),      // Yellow
    background = Color(0xFF1E1E1E),    // Dark background
    surface = Color(0xFF252526),       // Slightly lighter surface
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onTertiary = Color.Black,
    onBackground = Color(0xFFD4D4D4),
    onSurface = Color(0xFFD4D4D4),
    error = Color(0xFFF14C4C)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0D7377),
    secondary = Color(0xFF2B5797),
    tertiary = Color(0xFF8B7355),
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
)

@Composable
fun ClawssesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Always use dark theme for terminal app
    val colorScheme = DarkColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
