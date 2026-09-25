package com.andreagrossetti.training.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andreagrossetti.training.R

/** Display font: headings and big numbers. Static instances: variable-font weights are ignored on some devices. */
val Outfit = FontFamily(
    Font(R.font.outfit_regular, FontWeight.Normal),
    Font(R.font.outfit_medium, FontWeight.Medium),
    Font(R.font.outfit_semibold, FontWeight.SemiBold),
    Font(R.font.outfit_bold, FontWeight.Bold),
)

/** Text font: body, labels. */
val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
)

val Brand = Color(0xFFFF4000)
val OnBrand = Color.White
val Sky = Color(0xFF6CC7FF)
val Amber = Color(0xFFFFB020)

private val DarkColors = darkColorScheme(
    primary = Brand,
    onPrimary = OnBrand,
    primaryContainer = Color(0xFF4A1705),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Sky,
    onSecondary = Color(0xFF00223A),
    secondaryContainer = Color(0xFF12304A),
    onSecondaryContainer = Color(0xFFCFEAFF),
    tertiary = Amber,
    onTertiary = Color(0xFF2E1D00),
    tertiaryContainer = Color(0xFF3F2C00),
    onTertiaryContainer = Color(0xFFFFE3A8),
    background = Color(0xFF0A0C0F),
    onBackground = Color(0xFFF2F4F7),
    surface = Color(0xFF0A0C0F),
    onSurface = Color(0xFFF2F4F7),
    surfaceVariant = Color(0xFF1E232A),
    onSurfaceVariant = Color(0xFF98A2B3),
    surfaceContainerLowest = Color(0xFF07080A),
    surfaceContainerLow = Color(0xFF101318),
    surfaceContainer = Color(0xFF151920),
    surfaceContainerHigh = Color(0xFF1C2129),
    surfaceContainerHighest = Color(0xFF252B34),
    surfaceBright = Color(0xFF2C323C),
    inverseSurface = Color(0xFFF2F4F7),
    inverseOnSurface = Color(0xFF15181D),
    outline = Color(0xFF3A414C),
    outlineVariant = Color(0xFF252B34),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0000),
)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = OnBrand,
    primaryContainer = Color(0xFFFFDBCF),
    onPrimaryContainer = Color(0xFF3A0B00),
    secondary = Color(0xFF00629B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2E9FF),
    onSecondaryContainer = Color(0xFF001D33),
    tertiary = Color(0xFFB07800),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE3A8),
    onTertiaryContainer = Color(0xFF2E1D00),
    background = Color(0xFFF4F5F7),
    onBackground = Color(0xFF12151A),
    surface = Color(0xFFF4F5F7),
    onSurface = Color(0xFF12151A),
    surfaceVariant = Color(0xFFE4E7EC),
    onSurfaceVariant = Color(0xFF5B6472),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEEF0F3),
    surfaceContainerHighest = Color(0xFFE6E9ED),
    outline = Color(0xFFC3C8D0),
    outlineVariant = Color(0xFFE2E5EA),
)

private fun TextStyle.with(family: FontFamily, weight: FontWeight, spacing: Float? = null) =
    copy(fontFamily = family, fontWeight = weight, letterSpacing = spacing?.sp ?: letterSpacing)

private val base = Typography()

private val AppTypography = Typography(
    displayLarge = base.displayLarge.with(Outfit, FontWeight.Bold, -1.5f),
    displayMedium = base.displayMedium.with(Outfit, FontWeight.Bold, -1f),
    displaySmall = base.displaySmall.with(Outfit, FontWeight.Bold, -0.5f),
    headlineLarge = base.headlineLarge.with(Outfit, FontWeight.Bold, -0.5f),
    headlineMedium = base.headlineMedium.with(Outfit, FontWeight.Bold, -0.25f),
    headlineSmall = base.headlineSmall.with(Outfit, FontWeight.SemiBold),
    titleLarge = base.titleLarge.with(Outfit, FontWeight.SemiBold),
    titleMedium = base.titleMedium.with(Manrope, FontWeight.Bold),
    titleSmall = base.titleSmall.with(Manrope, FontWeight.Bold),
    bodyLarge = base.bodyLarge.with(Manrope, FontWeight.Medium),
    bodyMedium = base.bodyMedium.with(Manrope, FontWeight.Medium),
    bodySmall = base.bodySmall.with(Manrope, FontWeight.Medium),
    labelLarge = base.labelLarge.with(Manrope, FontWeight.Bold),
    labelMedium = base.labelMedium.with(Manrope, FontWeight.Bold),
    labelSmall = base.labelSmall.with(Manrope, FontWeight.Bold, 0.8f),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun TrainingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
