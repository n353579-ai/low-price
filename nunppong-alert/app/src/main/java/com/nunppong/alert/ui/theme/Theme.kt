package com.nunppong.alert.ui.theme

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

// 운전 환경에 최적화된 고대비 색상 (밝은 햇빛 및 야간 운전 시인성 확보)
val WarningYellow = Color(0xFFFFC400)
val DangerRed = Color(0xFFFF2A1A)
val SafeGreen = Color(0xFF10B981)
val InfoBlue = Color(0xFF3B82F6)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFC400),
    onPrimary = Color(0xFF1E1700),
    primaryContainer = Color(0xFF3E2D04),
    onPrimaryContainer = Color(0xFFFFE082),
    secondary = Color(0xFF60A5FA),
    onSecondary = Color(0xFF0F172A),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFF3F4F6),
    surface = Color(0xFF181A20),
    onSurface = Color(0xFFF9FAFB),
    surfaceVariant = Color(0xFF232630),
    onSurfaceVariant = Color(0xFFD1D5DB),
    outline = Color(0xFF3E4352),
    error = Color(0xFFFF4D4D),
    onError = Color(0xFFFFFFFF),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFD97706),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFEF3C7),
    onPrimaryContainer = Color(0xFF78350F),
    secondary = Color(0xFF2563EB),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFEEF1F6),
    onSurfaceVariant = Color(0xFF374151),
    outline = Color(0xFFD1D5DB),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
)

val NunppongTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        lineHeight = 26.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
)

@Composable
fun NunppongTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = NunppongTypography,
        content = content,
    )
}
