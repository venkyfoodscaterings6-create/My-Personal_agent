package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

enum class Agent3DState {
    IDLE,
    LISTENING,
    THINKING,
    SEARCHING,
    SPEAKING
}

private data class Vec3(val x: Float, val y: Float, val z: Float)

private data class ProjectedPoint(val x: Float, val y: Float, val z: Float, val scale: Float)

private fun rotateVec3(v: Vec3, yaw: Float, pitch: Float): Vec3 {
    // Rotate around Y (yaw)
    val cy = cos(yaw)
    val sy = sin(yaw)
    val x1 = v.x * cy + v.z * sy
    val z1 = -v.x * sy + v.z * cy

    // Rotate around X (pitch)
    val cp = cos(pitch)
    val sp = sin(pitch)
    val y2 = v.y * cp - z1 * sp
    val z2 = v.y * sp + z1 * cp
    return Vec3(x1, y2, z2)
}

private fun project3D(
    v: Vec3,
    centerX: Float,
    centerY: Float,
    unitPx: Float,
    cameraDistance: Float = 4.6f
): ProjectedPoint {
    val perspective = cameraDistance / (cameraDistance - v.z).coerceAtLeast(1.2f)
    return ProjectedPoint(
        x = centerX + v.x * unitPx * perspective,
        y = centerY - v.y * unitPx * perspective,
        z = v.z,
        scale = perspective
    )
}

/**
 * Interactive 60fps 3D Procedural Personal Agent Scene.
 * Features a composite 10-part articulated glossy robot companion with dual-axis 3D orbital rings,
 * satellite nodes (SQL Memory, Web Search, On-Device Control), studio key + hemisphere lighting,
 * soft ground contact shadow, audio-reactive visor & halo, and exponentially damped finger orbit.
 */
@Composable
fun Agent3DSceneCanvas(
    agentState: Agent3DState,
    voiceEnergy: Float = 0f,
    searchActive: Boolean = false,
    onTapAgent: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var timeSec by remember { mutableFloatStateOf(0f) }
    var targetYaw by remember { mutableFloatStateOf(0.18f) }
    var targetPitch by remember { mutableFloatStateOf(-0.08f) }
    var smoothYaw by remember { mutableFloatStateOf(0.18f) }
    var smoothPitch by remember { mutableFloatStateOf(-0.08f) }
    var smoothEnergy by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableFloatStateOf(0f) }

    val latestVoiceEnergy by rememberUpdatedState(voiceEnergy)

    // 60fps lossless frame loop with exponential damping (1 - exp(-k * dt)).
    // Never key on voiceEnergy so 30Hz live audio RMS never restarts the frame loop.
    LaunchedEffect(agentState) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { frameNanos ->
                val dt = if (lastNanos == 0L) 0.016f else {
                    ((frameNanos - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                }
                lastNanos = frameNanos
                timeSec += dt

                // Gentle auto-return toward front-facing hero angle when user isn't dragging
                if (isDragging < 0.5f) {
                    val autoTargetYaw = sin(timeSec * 0.55f) * 0.24f
                    val autoTargetPitch = when (agentState) {
                        Agent3DState.LISTENING -> 0.06f
                        Agent3DState.THINKING, Agent3DState.SEARCHING -> -0.10f
                        else -> -0.04f + sin(timeSec * 0.8f) * 0.04f
                    }
                    val returnDamp = 1f - exp(-2.2f * dt)
                    targetYaw += (autoTargetYaw - targetYaw) * returnDamp
                    targetPitch += (autoTargetPitch - targetPitch) * returnDamp
                }

                val camDamp = 1f - exp(-10f * dt)
                smoothYaw += (targetYaw - smoothYaw) * camDamp
                smoothPitch += (targetPitch - smoothPitch) * camDamp

                val liveEnergy = latestVoiceEnergy.coerceIn(0f, 1f)
                val targetEnergy = when (agentState) {
                    Agent3DState.SPEAKING -> {
                        if (liveEnergy > 0.04f) {
                            (0.20f + liveEnergy * 0.80f).coerceIn(0.20f, 1f)
                        } else {
                            // Subtle natural cadence when on-device fallback TTS has no PCM tap
                            0.42f + 0.34f * abs(sin(timeSec * 8.5f)) * (0.65f + 0.35f * sin(timeSec * 3.4f))
                        }
                    }
                    Agent3DState.LISTENING -> (0.18f + liveEnergy * 0.82f).coerceIn(0.15f, 1f)
                    Agent3DState.THINKING, Agent3DState.SEARCHING -> 0.35f + 0.20f * sin(timeSec * 5.2f)
                    Agent3DState.IDLE -> 0.08f
                }
                val energyDamp = 1f - exp(-16f * dt)
                smoothEnergy += (targetEnergy - smoothEnergy) * energyDamp
            }
        }
    }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTapAgent() })
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragging = 1f },
                    onDragEnd = { isDragging = 0f },
                    onDragCancel = { isDragging = 0f },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        targetYaw = (targetYaw + dragAmount.x * 0.0085f).coerceIn(-1.15f, 1.15f)
                        targetPitch = (targetPitch - dragAmount.y * 0.0065f).coerceIn(-0.55f, 0.55f)
                    }
                )
            }
    ) {
        val cx = size.width * 0.5f
        val cy = size.height * 0.47f
        val unitPx = (size.minDimension * 0.255f).coerceIn(58f, 158f)

        val primaryAccent = when (agentState) {
            Agent3DState.LISTENING -> Color(0xFF00E5FF)
            Agent3DState.SPEAKING -> Color(0xFF38BDF8)
            Agent3DState.SEARCHING -> Color(0xFF60A5FA)
            Agent3DState.THINKING -> Color(0xFF818CF8)
            Agent3DState.IDLE -> Color(0xFF22D3EE)
        }
        val secondaryAccent = when {
            searchActive || agentState == Agent3DState.SEARCHING -> Color(0xFF34D399)
            agentState == Agent3DState.THINKING -> Color(0xFFC084FC)
            else -> Color(0xFF3B82F6)
        }

        // 1. Studio atmospheric gradient backdrop + subtle radial spotlight + horizon grid
        drawStudioAtmosphere(cx, cy, unitPx, primaryAccent, secondaryAccent, smoothEnergy, timeSec)

        val bobY = sin(timeSec * 2.1f) * 0.09f + (smoothEnergy * 0.03f)
        val yaw = smoothYaw
        val pitch = smoothPitch

        // 2. Soft ground contact shadow flush below the levitating agent
        val floorCenter = project3D(rotateVec3(Vec3(0f, -1.55f, 0f), yaw * 0.25f, pitch * 0.25f), cx, cy, unitPx)
        val shadowRadiusX = unitPx * (0.98f - bobY * 0.35f)
        val shadowRadiusY = shadowRadiusX * 0.26f
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF020617).copy(alpha = 0.62f),
                    primaryAccent.copy(alpha = 0.14f + smoothEnergy * 0.10f),
                    Color.Transparent
                ),
                center = Offset(floorCenter.x, floorCenter.y),
                radius = shadowRadiusX * 1.15f
            ),
            topLeft = Offset(floorCenter.x - shadowRadiusX, floorCenter.y - shadowRadiusY),
            size = Size(shadowRadiusX * 2f, shadowRadiusY * 2f)
        )

        // 3. Back halves of dual 3D gimbal holographic rings + back satellites (z < 0)
        val orbitSpeed = when (agentState) {
            Agent3DState.THINKING, Agent3DState.SEARCHING -> 2.1f
            Agent3DState.SPEAKING -> 1.35f
            Agent3DState.LISTENING -> 1.0f
            Agent3DState.IDLE -> 0.52f
        }
        draw3DOrbitalRing(
            cx = cx, cy = cy, unitPx = unitPx,
            radius = 1.62f + smoothEnergy * 0.12f,
            tiltX = 0.42f, tiltZ = 0.28f,
            spinAngle = timeSec * orbitSpeed,
            yaw = yaw, pitch = pitch, bobY = bobY,
            color = primaryAccent,
            drawBackHalf = true
        )
        draw3DOrbitalRing(
            cx = cx, cy = cy, unitPx = unitPx,
            radius = 1.92f + smoothEnergy * 0.08f,
            tiltX = -0.35f, tiltZ = -0.38f,
            spinAngle = -timeSec * orbitSpeed * 0.72f,
            yaw = yaw, pitch = pitch, bobY = bobY,
            color = secondaryAccent,
            drawBackHalf = true
        )

        // 4. Composite 3D Satellite Nodes (back pass: z < 0)
        val satellites = buildSatellites(timeSec * orbitSpeed, yaw, pitch, bobY, cx, cy, unitPx, primaryAccent, secondaryAccent)
        satellites.filter { it.point.z < 0f }.sortedBy { it.point.z }.forEach { sat ->
            drawSatelliteNode(sat)
        }

        // 5. Levitation Metallic Neck Collar + Plasma Thruster Glow
        val collarPos = project3D(rotateVec3(Vec3(0f, -0.84f + bobY, 0f), yaw, pitch), cx, cy, unitPx)
        val collarW = unitPx * 0.56f * collarPos.scale
        val collarH = unitPx * 0.17f * collarPos.scale
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(
                    primaryAccent.copy(alpha = 0.55f + smoothEnergy * 0.35f),
                    Color.Transparent
                ),
                center = Offset(collarPos.x, collarPos.y + collarH * 0.65f),
                radius = collarW * 1.15f
            ),
            topLeft = Offset(collarPos.x - collarW, collarPos.y - collarH * 0.2f),
            size = Size(collarW * 2f, collarH * 2.2f)
        )
        drawOval(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF94A3B8), Color(0xFFE2E8F0), Color(0xFF475569)),
                start = Offset(collarPos.x - collarW * 0.65f, collarPos.y),
                end = Offset(collarPos.x + collarW * 0.65f, collarPos.y)
            ),
            topLeft = Offset(collarPos.x - collarW * 0.62f, collarPos.y - collarH * 0.5f),
            size = Size(collarW * 1.24f, collarH)
        )

        // 6. Articulated Ear Pods (depth-sorted: draw whichever ear is further back BEFORE head)
        val leftEarWorld = rotateVec3(Vec3(-0.96f, 0.04f + bobY, 0f), yaw, pitch)
        val rightEarWorld = rotateVec3(Vec3(0.96f, 0.04f + bobY, 0f), yaw, pitch)
        val leftEar = project3D(leftEarWorld, cx, cy, unitPx)
        val rightEar = project3D(rightEarWorld, cx, cy, unitPx)

        if (leftEar.z < rightEar.z) {
            drawEarPod(leftEar, unitPx, primaryAccent, isFront = false)
        } else {
            drawEarPod(rightEar, unitPx, primaryAccent, isFront = false)
        }

        // 7. Primary Hero Silhouette: Beveled Glossy White Ceramic 3D Robot Head
        val headCenter = project3D(rotateVec3(Vec3(0f, 0.04f + bobY, 0f), yaw, pitch), cx, cy, unitPx)
        val headW = unitPx * 1.66f * headCenter.scale
        val headH = unitPx * 1.38f * headCenter.scale
        val headCorner = headH * 0.42f

        // Subtle atmospheric rim aura behind head
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    primaryAccent.copy(alpha = 0.22f + smoothEnergy * 0.22f),
                    secondaryAccent.copy(alpha = 0.08f),
                    Color.Transparent
                ),
                center = Offset(headCenter.x, headCenter.y),
                radius = headW * 0.92f
            ),
            radius = headW * 0.92f,
            center = Offset(headCenter.x, headCenter.y)
        )

        // Warm studio key light top-left, cool hemisphere fill bottom-right
        val keyLightOffset = Offset(
            headCenter.x - headW * (0.28f + yaw * 0.14f),
            headCenter.y - headH * (0.32f - pitch * 0.16f)
        )
        drawRoundRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFFFFFFFF),
                    Color(0xFFEAF2FA),
                    Color(0xFFCBD5E1),
                    Color(0xFF94A3B8)
                ),
                center = keyLightOffset,
                radius = headW * 1.05f
            ),
            topLeft = Offset(headCenter.x - headW * 0.5f, headCenter.y - headH * 0.5f),
            size = Size(headW, headH),
            cornerRadius = CornerRadius(headCorner, headCorner)
        )
        // Metallic specular rim highlight
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.9f),
                    primaryAccent.copy(alpha = 0.45f),
                    Color(0xFF475569).copy(alpha = 0.5f)
                ),
                start = Offset(headCenter.x - headW * 0.45f, headCenter.y - headH * 0.5f),
                end = Offset(headCenter.x + headW * 0.45f, headCenter.y + headH * 0.5f)
            ),
            topLeft = Offset(headCenter.x - headW * 0.5f, headCenter.y - headH * 0.5f),
            size = Size(headW, headH),
            cornerRadius = CornerRadius(headCorner, headCorner),
            style = Stroke(width = (unitPx * 0.028f).coerceAtLeast(1.8f))
        )

        // 8. Curved Dark-Glass Visor Faceplate (moves with 3D yaw & pitch perspective!)
        val visorWorld = rotateVec3(Vec3(0f, 0.02f + bobY, 0.58f), yaw, pitch)
        val visorCenter = project3D(visorWorld, cx, cy, unitPx)
        val facingFactor = cos(yaw).coerceIn(0.25f, 1f)
        val visorW = unitPx * 1.24f * visorCenter.scale * facingFactor
        val visorH = unitPx * 0.86f * visorCenter.scale
        val visorCorner = visorH * 0.38f

        if (visorWorld.z > -0.1f) {
            // Recessed metallic bezel
            drawRoundRect(
                color = Color(0xFF1E293B),
                topLeft = Offset(visorCenter.x - visorW * 0.52f, visorCenter.y - visorH * 0.52f),
                size = Size(visorW * 1.04f, visorH * 1.04f),
                cornerRadius = CornerRadius(visorCorner * 1.04f, visorCorner * 1.04f)
            )
            // Deep obsidian glass surface
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF0F172A),
                        Color(0xFF020617),
                        Color(0xFF0B1120)
                    ),
                    start = Offset(visorCenter.x, visorCenter.y - visorH * 0.5f),
                    end = Offset(visorCenter.x, visorCenter.y + visorH * 0.5f)
                ),
                topLeft = Offset(visorCenter.x - visorW * 0.5f, visorCenter.y - visorH * 0.5f),
                size = Size(visorW, visorH),
                cornerRadius = CornerRadius(visorCorner, visorCorner)
            )
            // Curved studio glass reflection arc
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                    startY = visorCenter.y - visorH * 0.46f,
                    endY = visorCenter.y - visorH * 0.05f
                ),
                topLeft = Offset(visorCenter.x - visorW * 0.43f, visorCenter.y - visorH * 0.44f),
                size = Size(visorW * 0.86f, visorH * 0.36f),
                cornerRadius = CornerRadius(visorCorner * 0.8f, visorCorner * 0.8f)
            )

            // 9. Expressive Glowing Cyan LED Eyes + Audio-Reactive Equalizer Mouth
            drawVisorFaceExpression(
                visorCenter = visorCenter,
                visorW = visorW,
                visorH = visorH,
                agentState = agentState,
                accent = primaryAccent,
                energy = smoothEnergy,
                timeSec = timeSec
            )
        }

        // Front ear pod drawn AFTER head for proper 3D occlusion
        if (leftEar.z >= rightEar.z) {
            drawEarPod(leftEar, unitPx, primaryAccent, isFront = true)
        } else {
            drawEarPod(rightEar, unitPx, primaryAccent, isFront = true)
        }

        // 10. Floating Overhead Magnetic Halo Antenna Ring
        drawFloatingHaloRing(cx, cy, unitPx, yaw, pitch, bobY, primaryAccent, smoothEnergy, timeSec)

        // Front halves of dual 3D holographic orbital rings + front satellites (z >= 0)
        draw3DOrbitalRing(
            cx = cx, cy = cy, unitPx = unitPx,
            radius = 1.62f + smoothEnergy * 0.12f,
            tiltX = 0.42f, tiltZ = 0.28f,
            spinAngle = timeSec * orbitSpeed,
            yaw = yaw, pitch = pitch, bobY = bobY,
            color = primaryAccent,
            drawBackHalf = false
        )
        draw3DOrbitalRing(
            cx = cx, cy = cy, unitPx = unitPx,
            radius = 1.92f + smoothEnergy * 0.08f,
            tiltX = -0.35f, tiltZ = -0.38f,
            spinAngle = -timeSec * orbitSpeed * 0.72f,
            yaw = yaw, pitch = pitch, bobY = bobY,
            color = secondaryAccent,
            drawBackHalf = false
        )
        satellites.filter { it.point.z >= 0f }.sortedBy { it.point.z }.forEach { sat ->
            drawSatelliteNode(sat)
        }
    }
}

private data class Satellite3D(
    val point: ProjectedPoint,
    val color: Color,
    val baseRadiusPx: Float
)

private fun buildSatellites(
    phase: Float,
    yaw: Float,
    pitch: Float,
    bobY: Float,
    cx: Float,
    cy: Float,
    unitPx: Float,
    accentA: Color,
    accentB: Color
): List<Satellite3D> {
    val configs = listOf(
        Triple(1.62f, 0f, accentA),
        Triple(1.62f, (2.0 * PI / 3.0).toFloat(), accentB),
        Triple(1.62f, (4.0 * PI / 3.0).toFloat(), Color(0xFF38BDF8)),
        Triple(1.94f, 1.1f, Color(0xFF34D399)),
        Triple(1.94f, 4.2f, Color(0xFFA78BFA))
    )
    return configs.mapIndexed { idx, (radius, offset, color) ->
        val angle = if (idx < 3) phase + offset else -phase * 0.72f + offset
        val x = cos(angle) * radius
        val z = sin(angle) * radius
        val y = sin(angle * 1.5f + idx) * 0.42f + bobY
        val rotated = rotateVec3(Vec3(x, y, z), yaw, pitch)
        val proj = project3D(rotated, cx, cy, unitPx)
        Satellite3D(
            point = proj,
            color = color,
            baseRadiusPx = unitPx * (if (idx < 3) 0.075f else 0.06f)
        )
    }
}

private fun DrawScope.drawSatelliteNode(sat: Satellite3D) {
    val r = sat.baseRadiusPx * sat.point.scale
    val alpha = ((sat.point.z + 2.2f) / 4.2f).coerceIn(0.35f, 1f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(sat.color.copy(alpha = 0.55f * alpha), Color.Transparent),
            center = Offset(sat.point.x, sat.point.y),
            radius = r * 2.8f
        ),
        radius = r * 2.8f,
        center = Offset(sat.point.x, sat.point.y)
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = alpha), sat.color.copy(alpha = alpha)),
            center = Offset(sat.point.x - r * 0.25f, sat.point.y - r * 0.25f),
            radius = r * 1.25f
        ),
        radius = r,
        center = Offset(sat.point.x, sat.point.y)
    )
}

private fun DrawScope.drawStudioAtmosphere(
    cx: Float,
    cy: Float,
    unitPx: Float,
    primary: Color,
    secondary: Color,
    energy: Float,
    timeSec: Float
) {
    // Deep studio radial glow
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(
                primary.copy(alpha = 0.14f + energy * 0.12f),
                secondary.copy(alpha = 0.07f),
                Color(0xFF060B16),
                Color(0xFF030712)
            ),
            center = Offset(cx, cy),
            radius = size.maxDimension * 0.72f
        )
    )

    // Subtle acoustic sonar ripple rings when listening or speaking
    if (energy > 0.18f) {
        val ringCount = 3
        for (i in 0 until ringCount) {
            val phase = ((timeSec * 0.65f + i / ringCount.toFloat()) % 1f)
            val radius = unitPx * (0.95f + phase * (1.85f + energy * 0.55f))
            val alpha = (1f - phase) * (0.28f * energy)
            drawCircle(
                color = primary.copy(alpha = alpha.coerceIn(0f, 0.35f)),
                radius = radius,
                center = Offset(cx, cy),
                style = Stroke(width = 2f)
            )
        }
    }
}

private fun DrawScope.drawEarPod(
    ear: ProjectedPoint,
    unitPx: Float,
    accent: Color,
    isFront: Boolean
) {
    val w = unitPx * 0.24f * ear.scale
    val h = unitPx * 0.54f * ear.scale
    val alpha = if (isFront) 1f else 0.72f
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(
                Color(0xFFE2E8F0).copy(alpha = alpha),
                Color(0xFF94A3B8).copy(alpha = alpha),
                Color(0xFF475569).copy(alpha = alpha)
            ),
            start = Offset(ear.x - w, ear.y - h * 0.5f),
            end = Offset(ear.x + w, ear.y + h * 0.5f)
        ),
        topLeft = Offset(ear.x - w * 0.5f, ear.y - h * 0.5f),
        size = Size(w, h),
        cornerRadius = CornerRadius(w * 0.5f, w * 0.5f)
    )
    // Glowing cyan accent strip on the ear pod
    drawRoundRect(
        color = accent.copy(alpha = 0.85f * alpha),
        topLeft = Offset(ear.x - w * 0.18f, ear.y - h * 0.24f),
        size = Size(w * 0.36f, h * 0.48f),
        cornerRadius = CornerRadius(w * 0.2f, w * 0.2f)
    )
}

private fun DrawScope.drawFloatingHaloRing(
    cx: Float,
    cy: Float,
    unitPx: Float,
    yaw: Float,
    pitch: Float,
    bobY: Float,
    accent: Color,
    energy: Float,
    timeSec: Float
) {
    val segments = 36
    val haloRadius = 0.54f + energy * 0.05f
    val haloY = 0.94f + bobY + sin(timeSec * 3.1f) * 0.03f
    val path = Path()
    for (i in 0..segments) {
        val t = (i.toFloat() / segments) * (2f * PI.toFloat())
        val v = Vec3(cos(t) * haloRadius, haloY, sin(t) * haloRadius)
        val p = project3D(rotateVec3(v, yaw, pitch), cx, cy, unitPx)
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    drawPath(
        path = path,
        color = accent.copy(alpha = 0.28f + energy * 0.25f),
        style = Stroke(width = (unitPx * 0.085f).coerceAtLeast(4f), cap = StrokeCap.Round)
    )
    drawPath(
        path = path,
        color = Color.White.copy(alpha = 0.88f),
        style = Stroke(width = (unitPx * 0.032f).coerceAtLeast(1.8f), cap = StrokeCap.Round)
    )
}

private fun DrawScope.draw3DOrbitalRing(
    cx: Float,
    cy: Float,
    unitPx: Float,
    radius: Float,
    tiltX: Float,
    tiltZ: Float,
    spinAngle: Float,
    yaw: Float,
    pitch: Float,
    bobY: Float,
    color: Color,
    drawBackHalf: Boolean
) {
    val steps = 48
    val cxT = cos(tiltX)
    val sxT = sin(tiltX)
    val czT = cos(tiltZ)
    val szT = sin(tiltZ)

    var prev: ProjectedPoint? = null
    for (i in 0..steps) {
        val theta = spinAngle + (i.toFloat() / steps) * (2f * PI.toFloat())
        val lx = cos(theta) * radius
        val lz = sin(theta) * radius
        // Tilt ring in local space
        val ty = -lz * sxT
        val tz = lz * cxT
        val rx = lx * czT - ty * szT
        val ry = lx * szT + ty * czT + bobY

        val world = rotateVec3(Vec3(rx, ry, tz), yaw, pitch)
        val curr = project3D(world, cx, cy, unitPx)

        val p = prev
        if (p != null) {
            val midZ = (p.z + curr.z) * 0.5f
            val shouldDraw = if (drawBackHalf) midZ < 0f else midZ >= 0f
            if (shouldDraw) {
                val depthAlpha = if (drawBackHalf) 0.22f else 0.55f
                drawLine(
                    color = color.copy(alpha = depthAlpha),
                    start = Offset(p.x, p.y),
                    end = Offset(curr.x, curr.y),
                    strokeWidth = if (drawBackHalf) 2f else 3f,
                    cap = StrokeCap.Round
                )
            }
        }
        prev = curr
    }
}

private fun DrawScope.drawVisorFaceExpression(
    visorCenter: ProjectedPoint,
    visorW: Float,
    visorH: Float,
    agentState: Agent3DState,
    accent: Color,
    energy: Float,
    timeSec: Float
) {
    val eyeSpacing = visorW * 0.24f
    val eyeY = visorCenter.y - visorH * 0.08f

    // Periodic natural blink every ~4.2 seconds
    val blinkCycle = timeSec % 4.2f
    val blinkScale = if (blinkCycle > 4.02f && agentState == Agent3DState.IDLE) 0.14f else 1f

    val eyeRadiusX = visorW * 0.095f
    val eyeRadiusY = visorH * (
        when (agentState) {
            Agent3DState.LISTENING -> 0.165f
            Agent3DState.SPEAKING -> 0.135f
            else -> 0.145f
        }
    ) * blinkScale

    listOf(-1f, 1f).forEach { dir ->
        val ex = visorCenter.x + dir * eyeSpacing
        // Soft LED bloom around eye
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.55f), Color.Transparent),
                center = Offset(ex, eyeY),
                radius = eyeRadiusX * 2.4f
            ),
            radius = eyeRadiusX * 2.4f,
            center = Offset(ex, eyeY)
        )
        drawOval(
            color = accent,
            topLeft = Offset(ex - eyeRadiusX, eyeY - eyeRadiusY),
            size = Size(eyeRadiusX * 2f, eyeRadiusY * 2f)
        )
        if (blinkScale > 0.4f) {
            // Cute specular catchlight dot in each eye
            drawCircle(
                color = Color.White.copy(alpha = 0.92f),
                radius = eyeRadiusX * 0.34f,
                center = Offset(ex - eyeRadiusX * 0.28f, eyeY - eyeRadiusY * 0.30f)
            )
        }
    }

    // Mouth / Audio-Reactive Waveform Equalizer
    val mouthY = visorCenter.y + visorH * 0.22f
    if (agentState == Agent3DState.SPEAKING || agentState == Agent3DState.LISTENING || agentState == Agent3DState.THINKING || agentState == Agent3DState.SEARCHING) {
        val barCount = 7
        val totalBarW = visorW * 0.44f
        val stepX = totalBarW / (barCount - 1)
        val startX = visorCenter.x - totalBarW * 0.5f
        for (i in 0 until barCount) {
            val centerWeight = 1f - abs(i - (barCount - 1) / 2f) / ((barCount + 1) / 2f)
            val wave = abs(sin(timeSec * 11f + i * 0.95f))
            val barH = (visorH * 0.045f + visorH * 0.22f * energy * centerWeight * (0.4f + 0.6f * wave))
                .coerceAtLeast(visorH * 0.035f)
            val bx = startX + i * stepX
            drawLine(
                color = accent,
                start = Offset(bx, mouthY - barH * 0.5f),
                end = Offset(bx, mouthY + barH * 0.5f),
                strokeWidth = (visorW * 0.032f).coerceAtLeast(3f),
                cap = StrokeCap.Round
            )
        }
    } else {
        // Warm smiling arc when idle
        val smileW = visorW * 0.24f
        val smilePath = Path().apply {
            moveTo(visorCenter.x - smileW * 0.5f, mouthY - visorH * 0.02f)
            quadraticTo(
                visorCenter.x,
                mouthY + visorH * 0.12f,
                visorCenter.x + smileW * 0.5f,
                mouthY - visorH * 0.02f
            )
        }
        drawPath(
            path = smilePath,
            color = accent,
            style = Stroke(width = (visorW * 0.032f).coerceAtLeast(2.8f), cap = StrokeCap.Round)
        )
    }
}
