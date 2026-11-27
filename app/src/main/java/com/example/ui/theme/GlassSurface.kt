package com.example.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spatial frosted-glass (visionOS-style) tokens for dark glassmorphism and matching light frosted-ice.
 * Zero live Modifier.blur() is used anywhere on scrolling lists or inputs — depth is achieved via
 * a cached ambient drawBehind backdrop, translucent alpha fills, and luminous gradient borders.
 */
@Immutable
data class AgentGlassColors(
    val isDark: Boolean,
    val backdropTop: Color,
    val backdropMid: Color,
    val backdropBottom: Color,
    val glowPrimary: Color,
    val glowSecondary: Color,
    val glowTertiary: Color,
    val panelFill: Color,
    val topBarFill: Color,
    val sheetFill: Color,
    val dialogFill: Color,
    val inputFill: Color,
    val pillFill: Color,
    val aiBubbleFill: Color,
    val aiBubbleBorder: Color,
    val userBubbleFill: Color,
    val userBubbleText: Color,
    val userBubbleBorderStart: Color,
    val userBubbleBorderEnd: Color,
    val nestedCardFill: Color,
    val nestedCardBorder: Color,
    val borderStart: Color,
    val borderEnd: Color,
    val hairlineDivider: Color,
    val accentBlue: Color
) {
    fun luminousBorderBrush(): Brush = Brush.linearGradient(
        colors = listOf(borderStart, borderEnd)
    )

    fun userBubbleBorderBrush(): Brush = Brush.linearGradient(
        colors = listOf(userBubbleBorderStart, userBubbleBorderEnd)
    )
}

val DarkAgentGlassColors = AgentGlassColors(
    isDark = true,
    backdropTop = Color(0xFF141519),
    backdropMid = Color(0xFF22242A),
    backdropBottom = Color(0xFF181A20),
    glowPrimary = Color(0xFF3B82F6).copy(alpha = 0.14f),
    glowSecondary = Color(0xFF6366F1).copy(alpha = 0.10f),
    glowTertiary = Color(0xFF38BDF8).copy(alpha = 0.08f),
    panelFill = Color(0xFF2B2D33).copy(alpha = 0.58f),
    topBarFill = Color(0xFF1D1F25).copy(alpha = 0.72f),
    sheetFill = Color(0xFF1C1E24).copy(alpha = 0.93f),
    dialogFill = Color(0xFF22252C).copy(alpha = 0.96f),
    inputFill = Color(0xFF2B2D33).copy(alpha = 0.64f),
    pillFill = Color.White.copy(alpha = 0.09f),
    aiBubbleFill = Color.White.copy(alpha = 0.09f),
    aiBubbleBorder = Color.White.copy(alpha = 0.12f),
    userBubbleFill = Color(0xFF3B82F6).copy(alpha = 0.85f),
    userBubbleText = Color.White,
    userBubbleBorderStart = Color.White.copy(alpha = 0.34f),
    userBubbleBorderEnd = Color.White.copy(alpha = 0.08f),
    nestedCardFill = Color.Black.copy(alpha = 0.28f),
    nestedCardBorder = Color.White.copy(alpha = 0.14f),
    borderStart = Color.White.copy(alpha = 0.22f),
    borderEnd = Color.White.copy(alpha = 0.06f),
    hairlineDivider = Color.White.copy(alpha = 0.12f),
    accentBlue = Color(0xFF3B82F6)
)

val LightAgentGlassColors = AgentGlassColors(
    isDark = false,
    backdropTop = Color(0xFFE7EEF8),
    backdropMid = Color(0xFFF3F6FC),
    backdropBottom = Color(0xFFE4ECF7),
    glowPrimary = Color(0xFF3B82F6).copy(alpha = 0.12f),
    glowSecondary = Color(0xFF38BDF8).copy(alpha = 0.10f),
    glowTertiary = Color(0xFF818CF8).copy(alpha = 0.08f),
    panelFill = Color.White.copy(alpha = 0.68f),
    topBarFill = Color.White.copy(alpha = 0.74f),
    sheetFill = Color(0xFFF7FAFF).copy(alpha = 0.94f),
    dialogFill = Color(0xFFF8FAFE).copy(alpha = 0.97f),
    inputFill = Color.White.copy(alpha = 0.78f),
    pillFill = Color.White.copy(alpha = 0.64f),
    aiBubbleFill = Color.White.copy(alpha = 0.72f),
    aiBubbleBorder = Color(0xFF94A3B8).copy(alpha = 0.28f),
    userBubbleFill = Color(0xFF3B82F6).copy(alpha = 0.90f),
    userBubbleText = Color.White,
    userBubbleBorderStart = Color.White.copy(alpha = 0.50f),
    userBubbleBorderEnd = Color.White.copy(alpha = 0.12f),
    nestedCardFill = Color(0xFF0F172A).copy(alpha = 0.055f),
    nestedCardBorder = Color(0xFF0F172A).copy(alpha = 0.11f),
    borderStart = Color.White.copy(alpha = 0.88f),
    borderEnd = Color(0xFF94A3B8).copy(alpha = 0.26f),
    hairlineDivider = Color(0xFF0F172A).copy(alpha = 0.10f),
    accentBlue = Color(0xFF3B82F6)
)

val LocalAgentGlassColors = staticCompositionLocalOf { DarkAgentGlassColors }

/**
 * Full-screen moody ambient spatial canvas drawn strictly once via drawBehind with 3 soft
 * diffused radial glow blobs so frosted-glass panels float with rich optical depth at 60/120fps.
 */
fun Modifier.spatialBackdrop(): Modifier = composed {
    val glass = LocalAgentGlassColors.current
    this.drawBehind {
        val w = size.width
        val h = size.height
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(glass.backdropTop, glass.backdropMid, glass.backdropBottom)
            )
        )
        val maxDim = maxOf(w, h)
        // Top-right soft cobalt glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(glass.glowPrimary, Color.Transparent),
                center = Offset(w * 0.85f, h * 0.14f),
                radius = maxDim * 0.56f
            ),
            radius = maxDim * 0.56f,
            center = Offset(w * 0.85f, h * 0.14f)
        )
        // Bottom-left soft slate-indigo glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(glass.glowSecondary, Color.Transparent),
                center = Offset(w * 0.12f, h * 0.78f),
                radius = maxDim * 0.58f
            ),
            radius = maxDim * 0.58f,
            center = Offset(w * 0.12f, h * 0.78f)
        )
        // Mid-left gentle ice-blue ambient haze
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(glass.glowTertiary, Color.Transparent),
                center = Offset(w * 0.28f, h * 0.36f),
                radius = maxDim * 0.44f
            ),
            radius = maxDim * 0.44f,
            center = Offset(w * 0.28f, h * 0.36f)
        )
    }
}

/**
 * Reusable frosted-glass panel modifier: translucent fill + generous rounded corners +
 * crisp 1.dp luminous top-left-to-bottom-right gradient border.
 */
fun Modifier.glassPanel(
    cornerRadius: Dp = 18.dp,
    fillColor: Color? = null,
    borderColorOverride: Color? = null
): Modifier = composed {
    val glass = LocalAgentGlassColors.current
    val shape = RoundedCornerShape(cornerRadius)
    val bg = fillColor ?: glass.panelFill
    val borderMod = if (borderColorOverride != null) {
        Modifier.border(1.dp, borderColorOverride, shape)
    } else {
        Modifier.border(1.dp, glass.luminousBorderBrush(), shape)
    }
    this
        .clip(shape)
        .background(bg)
        .then(borderMod)
}

/**
 * Darker nested glass card modifier for Approval Cards, Generated File Cards,
 * and inline recommendations seated inside AI bubbles or frosted sheets.
 */
fun Modifier.nestedGlassCard(
    cornerRadius: Dp = 18.dp,
    borderColorOverride: Color? = null
): Modifier = composed {
    val glass = LocalAgentGlassColors.current
    val shape = RoundedCornerShape(cornerRadius)
    val borderMod = if (borderColorOverride != null) {
        Modifier.border(1.dp, borderColorOverride, shape)
    } else {
        Modifier.border(
            1.dp,
            Brush.linearGradient(
                colors = listOf(
                    glass.nestedCardBorder,
                    glass.nestedCardBorder.copy(alpha = glass.nestedCardBorder.alpha * 0.45f)
                )
            ),
            shape
        )
    }
    this
        .clip(shape)
        .background(glass.nestedCardFill)
        .then(borderMod)
}

/**
 * Frosted pill modifier for chips, model selector pill, and secondary translucent action buttons.
 */
fun Modifier.frostedPill(
    cornerRadius: Dp = 20.dp,
    active: Boolean = false
): Modifier = composed {
    val glass = LocalAgentGlassColors.current
    val shape = RoundedCornerShape(cornerRadius)
    val bg = if (active) glass.accentBlue.copy(alpha = 0.26f) else glass.pillFill
    val borderBrush = if (active) {
        Brush.linearGradient(
            colors = listOf(
                glass.accentBlue.copy(alpha = 0.72f),
                glass.accentBlue.copy(alpha = 0.30f)
            )
        )
    } else {
        glass.luminousBorderBrush()
    }
    this
        .clip(shape)
        .background(bg)
        .border(1.dp, borderBrush, shape)
}

@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    fillColor: Color? = null,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier.glassPanel(cornerRadius = cornerRadius, fillColor = fillColor),
        content = content
    )
}
