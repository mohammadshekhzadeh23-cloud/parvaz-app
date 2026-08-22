package com.example.vray.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// A deep indigo/teal identity instead of Material's stock purple defaults.
private val Indigo = Color(0xFF3B5BFE)
private val IndigoDark = Color(0xFF8AA0FF)
private val Teal = Color(0xFF00C2A8)
private val Danger = Color(0xFFE5484D)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    secondary = Teal,
    onSecondary = Color.White,
    error = Danger,
    background = Color(0xFFF7F8FC),
    surface = Color.White,
    surfaceVariant = Color(0xFFE9ECFB)
)

private val DarkColors = darkColorScheme(
    primary = IndigoDark,
    onPrimary = Color(0xFF00174D),
    secondary = Teal,
    onSecondary = Color(0xFF00201B),
    error = Danger,
    background = Color(0xFF0E0F1A),
    surface = Color(0xFF161826),
    surfaceVariant = Color(0xFF232538)
)

val AppTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp)
)

val StatusConnected = Color(0xFF2ECC71)
val StatusConnecting = Color(0xFFF5A623)
val StatusError = Danger
val StatusDisconnected = Color(0xFF9CA3AF)

@Composable
fun VRayTheme(themeMode: com.example.vray.data.ThemeMode = com.example.vray.data.ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val useDark = when (themeMode) {
        com.example.vray.data.ThemeMode.LIGHT -> false
        com.example.vray.data.ThemeMode.DARK -> true
        com.example.vray.data.ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colors = if (useDark) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}
