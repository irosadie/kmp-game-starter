package com.binarydev.startergame.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Single-accent theme configuration so developers can re-skin the entire starter game
 * by changing `accentColor`, `titleFontFamily`, or `appName` without touching core or ViewModels.
 */
data class StarterThemeConfig(
    val appName: String = "Kata Nusantara Puzzle",
    val accentColor: Color = Color(0xFF0D9488),
    val secondaryAccentColor: Color = Color(0xFF0F766E),
    val backgroundColor: Color = Color(0xFFF8FAFC),
    val surfaceColor: Color = Color(0xFFFFFFFF),
    val fontFamily: FontFamily = FontFamily.Default
)

fun buildStarterTypography(fontFamily: FontFamily = FontFamily.Default): Typography = Typography(
    headlineMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp
    ),
    titleLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp
    ),
    titleMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp
    )
)

@Composable
fun StarterGameTheme(
    themeConfig: StarterThemeConfig = StarterThemeConfig(),
    content: @Composable () -> Unit
) {
    val colorScheme = lightColorScheme(
        primary = themeConfig.accentColor,
        onPrimary = Color.White,
        primaryContainer = themeConfig.accentColor.copy(alpha = 0.14f),
        onPrimaryContainer = themeConfig.secondaryAccentColor,
        secondary = themeConfig.secondaryAccentColor,
        onSecondary = Color.White,
        background = themeConfig.backgroundColor,
        surface = themeConfig.surfaceColor
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = buildStarterTypography(themeConfig.fontFamily),
        content = content
    )
}
