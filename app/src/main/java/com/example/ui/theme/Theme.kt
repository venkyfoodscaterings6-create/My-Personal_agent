package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.ThemePreference

@Immutable
data class AgentSemanticColors(
    val lowRiskContainer: Color,
    val lowRiskContent: Color,
    val mediumRiskContainer: Color,
    val mediumRiskContent: Color,
    val highRiskContainer: Color,
    val highRiskContent: Color,
    val successContainer: Color,
    val successContent: Color,
    val geminiGradient: List<Color>,
    val pdfBadgeContainer: Color = Color(0xFFFEE2E2),
    val pdfBadgeContent: Color = Color(0xFFDC2626),
    val xlsxBadgeContainer: Color = Color(0xFFDCFCE7),
    val xlsxBadgeContent: Color = Color(0xFF15803D),
    val docxBadgeContainer: Color = Color(0xFFDBEAFE),
    val docxBadgeContent: Color = Color(0xFF1D4ED8),
    val csvBadgeContainer: Color = Color(0xFFCCFBF1),
    val csvBadgeContent: Color = Color(0xFF0F766E)
)

val LocalAgentSemanticColors = staticCompositionLocalOf {
    AgentSemanticColors(
        lowRiskContainer = RiskLowContainerDark,
        lowRiskContent = RiskLowContentDark,
        mediumRiskContainer = RiskMediumContainerDark,
        mediumRiskContent = RiskMediumContentDark,
        highRiskContainer = RiskHighContainerDark,
        highRiskContent = RiskHighContentDark,
        successContainer = RiskLowContainerDark,
        successContent = RiskLowContentDark,
        geminiGradient = GeminiGradientDark
    )
}

private val SpatialLightColorScheme = lightColorScheme(
    primary = SpatialPrimaryLight,
    onPrimary = SpatialOnPrimaryLight,
    primaryContainer = SpatialPrimaryContainerLight,
    onPrimaryContainer = SpatialOnPrimaryContainerLight,
    secondary = SpatialSecondaryLight,
    onSecondary = SpatialOnSecondaryLight,
    secondaryContainer = SpatialSecondaryContainerLight,
    onSecondaryContainer = SpatialOnSecondaryContainerLight,
    tertiary = SpatialTertiaryLight,
    onTertiary = SpatialOnTertiaryLight,
    tertiaryContainer = SpatialTertiaryContainerLight,
    onTertiaryContainer = SpatialOnTertiaryContainerLight,
    background = SpatialBackgroundLight,
    onBackground = SpatialOnBackgroundLight,
    surface = SpatialSurfaceLight,
    onSurface = SpatialOnSurfaceLight,
    surfaceVariant = SpatialSurfaceVariantLight,
    onSurfaceVariant = SpatialOnSurfaceVariantLight,
    surfaceContainerHigh = SpatialSurfaceContainerHighLight,
    surfaceContainerHighest = SpatialSurfaceContainerHighestLight,
    outline = SpatialOutlineLight,
    outlineVariant = SpatialOutlineVariantLight
)

private val SpatialDarkColorScheme = darkColorScheme(
    primary = SpatialPrimaryDark,
    onPrimary = SpatialOnPrimaryDark,
    primaryContainer = SpatialPrimaryContainerDark,
    onPrimaryContainer = SpatialOnPrimaryContainerDark,
    secondary = SpatialSecondaryDark,
    onSecondary = SpatialOnSecondaryDark,
    secondaryContainer = SpatialSecondaryContainerDark,
    onSecondaryContainer = SpatialOnSecondaryContainerDark,
    tertiary = SpatialTertiaryDark,
    onTertiary = SpatialOnTertiaryDark,
    tertiaryContainer = SpatialTertiaryContainerDark,
    onTertiaryContainer = SpatialOnTertiaryContainerDark,
    background = SpatialBackgroundDark,
    onBackground = SpatialOnBackgroundDark,
    surface = SpatialSurfaceDark,
    onSurface = SpatialOnSurfaceDark,
    surfaceVariant = SpatialSurfaceVariantDark,
    onSurfaceVariant = SpatialOnSurfaceVariantDark,
    surfaceContainerHigh = SpatialSurfaceContainerHighDark,
    surfaceContainerHighest = SpatialSurfaceContainerHighestDark,
    outline = SpatialOutlineDark,
    outlineVariant = SpatialOutlineVariantDark
)

val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun PersonalAgentTheme(
    themePreference: ThemePreference = ThemePreference.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themePreference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }

    val colorScheme = if (darkTheme) SpatialDarkColorScheme else SpatialLightColorScheme
    val glassColors = if (darkTheme) DarkAgentGlassColors else LightAgentGlassColors

    val semanticColors = if (darkTheme) {
        AgentSemanticColors(
            lowRiskContainer = RiskLowContainerDark,
            lowRiskContent = RiskLowContentDark,
            mediumRiskContainer = RiskMediumContainerDark,
            mediumRiskContent = RiskMediumContentDark,
            highRiskContainer = RiskHighContainerDark,
            highRiskContent = RiskHighContentDark,
            successContainer = RiskLowContainerDark,
            successContent = RiskLowContentDark,
            geminiGradient = GeminiGradientDark,
            pdfBadgeContainer = Color(0xFF451A1A).copy(alpha = 0.85f),
            pdfBadgeContent = Color(0xFFFCA5A5),
            xlsxBadgeContainer = Color(0xFF143824).copy(alpha = 0.85f),
            xlsxBadgeContent = Color(0xFF86EFAC),
            docxBadgeContainer = Color(0xFF172B4D).copy(alpha = 0.85f),
            docxBadgeContent = Color(0xFF93C5FD),
            csvBadgeContainer = Color(0xFF123836).copy(alpha = 0.85f),
            csvBadgeContent = Color(0xFF5EEAD4)
        )
    } else {
        AgentSemanticColors(
            lowRiskContainer = RiskLowContainerLight,
            lowRiskContent = RiskLowContentLight,
            mediumRiskContainer = RiskMediumContainerLight,
            mediumRiskContent = RiskMediumContentLight,
            highRiskContainer = RiskHighContainerLight,
            highRiskContent = RiskHighContentLight,
            successContainer = RiskLowContainerLight,
            successContent = RiskLowContentLight,
            geminiGradient = GeminiGradientLight,
            pdfBadgeContainer = Color(0xFFFEE2E2),
            pdfBadgeContent = Color(0xFFDC2626),
            xlsxBadgeContainer = Color(0xFFDCFCE7),
            xlsxBadgeContent = Color(0xFF15803D),
            docxBadgeContainer = Color(0xFFDBEAFE),
            docxBadgeContent = Color(0xFF1D4ED8),
            csvBadgeContainer = Color(0xFFCCFBF1),
            csvBadgeContent = Color(0xFF0F766E)
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalAgentSemanticColors provides semanticColors,
        LocalAgentGlassColors provides glassColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
