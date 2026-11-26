package com.example.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ActionPlan
import com.example.GroundingSource
import com.example.LiveVoicePhase
import com.example.VoiceLanguage

/**
 * Full-screen Real-Time Voice Conversation screen powered by Gemini AI + Neural Voice
 * + Google Search Grounding + Full Phone Actions + SQL Personal Memory, anchored by the
 * interactive 3D Personal Agent Companion scene.
 */
@Composable
fun LiveVoiceScreen(
    phase: LiveVoicePhase,
    liveAudioEnergy: Float,
    liveUserTranscript: String,
    liveAgentCaption: String,
    groundingSources: List<GroundingSource>,
    voiceLanguage: VoiceLanguage,
    voicePersonaLabel: String,
    usingNeuralVoice: Boolean,
    googleSearchEnabled: Boolean,
    continuousHandsFree: Boolean,
    memoryCount: Int,
    pendingApprovalPlan: ActionPlan?,
    onToggleMicOrBargeIn: () -> Unit,
    onToggleGoogleSearch: () -> Unit,
    onToggleLanguage: () -> Unit,
    onToggleContinuous: () -> Unit,
    onApprovePendingPlan: (String) -> Unit,
    onCancelPendingPlan: (String) -> Unit,
    onEndSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val visual3DState = when (phase) {
        LiveVoicePhase.IDLE -> Agent3DState.IDLE
        LiveVoicePhase.LISTENING -> Agent3DState.LISTENING
        LiveVoicePhase.THINKING -> if (googleSearchEnabled) Agent3DState.SEARCHING else Agent3DState.THINKING
        LiveVoicePhase.SPEAKING -> Agent3DState.SPEAKING
    }

    val statusLabel = when (phase) {
        LiveVoicePhase.IDLE -> "Tap Mic or 3D Agent to talk"
        LiveVoicePhase.LISTENING -> when (voiceLanguage) {
            VoiceLanguage.TE_IN -> "వింటున్నాను… (తెలుగు)"
            VoiceLanguage.AUTO -> "Listening live (EN + తెలుగు)…"
            VoiceLanguage.EN_IN -> "Listening live…"
        }
        LiveVoicePhase.THINKING -> if (googleSearchEnabled) "Searching & thinking…" else "Thinking with Gemini…"
        LiveVoicePhase.SPEAKING -> "Speaking ($voicePersonaLabel) · tap to interrupt"
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF030712))
    ) {
        // Full-bleed interactive 3D Agent Scene driven by live mic & PCM playback energy
        Agent3DSceneCanvas(
            agentState = visual3DState,
            voiceEnergy = liveAudioEnergy,
            searchActive = googleSearchEnabled,
            onTapAgent = onToggleMicOrBargeIn,
            modifier = Modifier
                .fillMaxSize()
                .testTag("agent_3d_canvas")
        )

        // Top studio HUD bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = onEndSession,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xFF0F172A).copy(alpha = 0.78f))
                    .testTag("live_voice_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back to Chat",
                    tint = Color.White
                )
            }

            // Live status pill
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF0F172A).copy(alpha = 0.82f))
                    .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = if (usingNeuralVoice) "$voicePersonaLabel · Neural" else "$voicePersonaLabel · Live",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
                if (memoryCount > 0) {
                    Text(
                        text = "· $memoryCount SQL",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF34D399)
                    )
                }
            }

            // EN / తెలుగు toggle pill
            Box(
                modifier = Modifier
                    .height(38.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(Color(0xFF0F172A).copy(alpha = 0.82f))
                    .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(19.dp))
                    .clickable(onClick = onToggleLanguage)
                    .padding(horizontal = 12.dp)
                    .testTag("live_voice_lang_toggle"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = voiceLanguage.shortChipLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Bottom overlay: Live captions + Grounded web source chips + compact floating dock
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0xFF030712).copy(alpha = 0.88f),
                            Color(0xFF030712)
                        )
                    )
                )
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Drag 3D Agent to orbit · $statusLabel",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF94A3B8),
                textAlign = TextAlign.Center
            )

            if (liveUserTranscript.isNotBlank()) {
                Text(
                    text = "\"$liveUserTranscript\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF7DD3FC),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }

            if (liveAgentCaption.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFF0F172A).copy(alpha = 0.86f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        Color(0xFF38BDF8).copy(alpha = 0.28f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = liveAgentCaption,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFF1F5F9),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }

            if (groundingSources.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    groundingSources.take(5).forEach { source ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF0F172A).copy(alpha = 0.9f))
                                .border(1.dp, Color(0xFF34D399).copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                                .clickable {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(source.uri))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Public,
                                contentDescription = null,
                                tint = Color(0xFF34D399),
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = source.title.take(26),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFE2E8F0),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            pendingApprovalPlan?.let { plan ->
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFF111827).copy(alpha = 0.94f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        Color(0xFFF59E0B).copy(alpha = 0.65f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("live_pending_approval_card")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = plan.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${plan.riskLevel.name.lowercase().replaceFirstChar { it.uppercase() }} Risk",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFBBF24),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (plan.steps.isNotEmpty()) {
                            Text(
                                text = plan.steps.take(2).joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFCBD5E1),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onCancelPendingPlan(plan.actionId) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("live_cancel_approval_button")
                            ) {
                                Text("Cancel", color = Color(0xFFE2E8F0))
                            }
                            Button(
                                onClick = { onApprovePendingPlan(plan.actionId) },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF06B6D4),
                                    contentColor = Color.White
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("live_approve_action_button")
                            ) {
                                Text("Approve")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Compact floating control dock (solid rgba surface, no backdrop-filter)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color(0xFF0F172A).copy(alpha = 0.92f))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(32.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Google Search Grounding toggle
                DockToggleCircle(
                    icon = Icons.Rounded.Public,
                    contentDescription = "Toggle Google Search Grounding",
                    active = googleSearchEnabled,
                    activeColor = Color(0xFF34D399),
                    onClick = onToggleGoogleSearch,
                    testTag = "live_toggle_search"
                )

                // Auto-listen continuous loop toggle
                DockToggleCircle(
                    icon = Icons.Rounded.Repeat,
                    contentDescription = "Toggle Continuous Conversation",
                    active = continuousHandsFree,
                    activeColor = Color(0xFF818CF8),
                    onClick = onToggleContinuous,
                    testTag = "live_toggle_continuous"
                )

                // Primary Mic / Stop / Barge-in button
                val primaryColor = when (phase) {
                    LiveVoicePhase.LISTENING -> Color(0xFFEF4444)
                    LiveVoicePhase.SPEAKING -> Color(0xFF38BDF8)
                    LiveVoicePhase.THINKING -> Color(0xFF818CF8)
                    LiveVoicePhase.IDLE -> Color(0xFF06B6D4)
                }
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .clip(CircleShape)
                        .background(primaryColor)
                        .clickable(onClick = onToggleMicOrBargeIn)
                        .testTag("live_primary_mic_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (phase) {
                            LiveVoicePhase.LISTENING -> Icons.Rounded.Stop
                            LiveVoicePhase.SPEAKING -> Icons.Rounded.MicOff
                            else -> Icons.Rounded.Mic
                        },
                        contentDescription = "Speak or Interrupt",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // End Live Call button
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFDC2626).copy(alpha = 0.22f))
                        .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.65f), CircleShape)
                        .clickable(onClick = onEndSession)
                        .testTag("live_end_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CallEnd,
                        contentDescription = "End Live Voice",
                        tint = Color(0xFFF87171),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DockToggleCircle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    testTag: String
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                if (active) activeColor.copy(alpha = 0.22f)
                else Color.White.copy(alpha = 0.08f)
            )
            .border(
                width = 1.dp,
                color = if (active) activeColor.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.16f),
                shape = CircleShape
            )
            .clickable(onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) activeColor else Color(0xFF94A3B8),
            modifier = Modifier.size(22.dp)
        )
    }
}
