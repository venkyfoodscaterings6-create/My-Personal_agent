package com.example.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.R
import com.example.ThemePreference

// Top-level cached singleton FontFamily: bundled locally for instant offline rendering
// and zero reloads during scrolling or typing.
val GeminiSansFontFamily: FontFamily = FontFamily(
    Font(R.font.google_sans_flex, FontWeight.Normal),
    Font(R.font.google_sans_flex, FontWeight.Medium),
    Font(R.font.google_sans_flex, FontWeight.SemiBold),
    Font(R.font.google_sans_flex, FontWeight.Bold)
)

val FallbackSansFontFamily: FontFamily = FontFamily(
    Font(R.font.plus_jakarta_sans, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans, FontWeight.Bold)
)

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
    val geminiGradient: List<Color>
)

val LocalAgentSemanticColors = staticCompositionLocalOf {
    AgentSemanticColors(
        lowRiskContainer = RiskLowContainerLight,
        lowRiskContent = RiskLowContentLight,
        mediumRiskContainer = RiskMediumContainerLight,
        mediumRiskContent = RiskMediumContentLight,
        highRiskContainer = RiskHighContainerLight,
        highRiskContent = RiskHighContentLight,
        successContainer = RiskLowContainerLight,
        successContent = RiskLowContentLight,
        geminiGradient = GeminiGradientLight
    )
}

private val LightFallbackColorScheme = lightColorScheme(
    primary = SlatePrimaryLight,
    onPrimary = SlateOnPrimaryLight,
    primaryContainer = SlatePrimaryContainerLight,
    onPrimaryContainer = SlateOnPrimaryContainerLight,
    secondary = SlateSecondaryLight,
    onSecondary = SlateOnSecondaryLight,
    secondaryContainer = SlateSecondaryContainerLight,
    onSecondaryContainer = SlateOnSecondaryContainerLight,
    tertiary = SlateTertiaryLight,
    onTertiary = SlateOnTertiaryLight,
    tertiaryContainer = SlateTertiaryContainerLight,
    onTertiaryContainer = SlateOnTertiaryContainerLight,
    background = SlateBackgroundLight,
    onBackground = SlateOnBackgroundLight,
    surface = SlateSurfaceLight,
    onSurface = SlateOnSurfaceLight,
    surfaceVariant = SlateSurfaceVariantLight,
    onSurfaceVariant = SlateOnSurfaceVariantLight,
    outline = SlateOutlineLight,
    outlineVariant = SlateOutlineVariantLight
)

private val DarkFallbackColorScheme = darkColorScheme(
    primary = SlatePrimaryDark,
    onPrimary = SlateOnPrimaryDark,
    primaryContainer = SlatePrimaryContainerDark,
    onPrimaryContainer = SlateOnPrimaryContainerDark,
    secondary = SlateSecondaryDark,
    onSecondary = SlateOnSecondaryDark,
    secondaryContainer = SlateSecondaryContainerDark,
    onSecondaryContainer = SlateOnSecondaryContainerDark,
    tertiary = SlateTertiaryDark,
    onTertiary = SlateOnTertiaryDark,
    tertiaryContainer = SlateTertiaryContainerDark,
    onTertiaryContainer = SlateOnTertiaryContainerDark,
    background = SlateBackgroundDark,
    onBackground = SlateOnBackgroundDark,
    surface = SlateSurfaceDark,
    onSurface = SlateOnSurfaceDark,
    surfaceVariant = SlateSurfaceVariantDark,
    onSurfaceVariant = SlateOnSurfaceVariantDark,
    outline = SlateOutlineDark,
    outlineVariant = SlateOutlineVariantDark
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
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themePreference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkFallbackColorScheme
        else -> LightFallbackColorScheme
    }

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
            geminiGradient = GeminiGradientDark
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
            geminiGradient = GeminiGradientLight
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

    CompositionLocalProvider(LocalAgentSemanticColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
