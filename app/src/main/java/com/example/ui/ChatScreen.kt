package com.example.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ActionPlan
import com.example.AgentMode
import com.example.AiProvider
import com.example.ApprovalStatus
import com.example.ChatMessage
import com.example.MessageSender
import com.example.R
import com.example.RiskLevel
import com.example.VoiceLanguage
import com.example.ui.theme.LocalAgentSemanticColors
import com.example.ui.theme.MonoCodeStyle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun Modifier.pressScale(interactionSource: MutableInteractionSource): Modifier = composed {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing),
        label = "press_scale"
    )
    this.graphicsLayer {
        val s = scale.value
        scaleX = s
        scaleY = s
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    providers: List<AiProvider>,
    activeProvider: AiProvider?,
    agentMode: AgentMode,
    quickActionChips: List<String>,
    showStepPlan: Boolean,
    isSending: Boolean,
    isAgentLoopRunning: Boolean,
    liveAgentStatusText: String,
    livePhaseStartMillis: Long,
    isListening: Boolean,
    isSpeakingTts: Boolean,
    voiceLanguage: VoiceLanguage,
    voicePartialText: String,
    voiceFinalEvent: Pair<Long, String>?,
    onToggleMic: () -> Unit,
    onToggleVoiceLanguage: () -> Unit,
    onStopSpeaking: () -> Unit,
    onConsumeVoiceFinal: () -> Unit,
    onStopAgent: () -> Unit,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    onSelectProvider: (String) -> Unit,
    onToggleMode: (AgentMode) -> Unit,
    onSendMessage: (String) -> Unit,
    onApproveAction: (String) -> Unit,
    onEditActionInstruction: (String, String) -> Unit,
    onCancelAction: (String) -> Unit,
    onOpenSettingsProviders: () -> Unit,
    onCopyMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showProviderSheet by remember { mutableStateOf(false) }
    var editingPlan by remember { mutableStateOf<ActionPlan?>(null) }

    // Scaffold with zero automatic insets prevents double IME padding / black keyboard gaps.
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            MinimalChatTopBar(
                activeProvider = activeProvider,
                isSpeakingTts = isSpeakingTts,
                onStopSpeaking = onStopSpeaking,
                onOpenDrawer = onOpenDrawer,
                onOpenProviderPicker = { showProviderSheet = true },
                onNewChat = onNewChat
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (messages.isEmpty() && !isSending) {
                    MinimalEmptyGreeting(
                        chips = quickActionChips.take(3),
                        onTapChip = onSendMessage
                    )
                } else {
                    ChatMessageFeed(
                        messages = messages,
                        showStepPlan = showStepPlan,
                        isSending = isSending,
                        livePhaseText = liveAgentStatusText,
                        livePhaseStartMillis = livePhaseStartMillis,
                        onCopyMessage = onCopyMessage,
                        onApprove = onApproveAction,
                        onEditStep = { editingPlan = it },
                        onCancel = onCancelAction
                    )
                }
            }

            if (isAgentLoopRunning) {
                StopAgentFloatingBar(
                    statusText = liveAgentStatusText.ifBlank { "Running on-device agent…" },
                    startMillis = livePhaseStartMillis,
                    onStop = onStopAgent
                )
            }

            // Strictly isolated bottom input bar with unified navBars.union(ime) inset handling
            BottomPillInputBar(
                agentMode = agentMode,
                isSending = isSending || isAgentLoopRunning,
                isListening = isListening,
                voiceLanguage = voiceLanguage,
                voicePartialText = voicePartialText,
                voiceFinalEvent = voiceFinalEvent,
                onToggleMic = onToggleMic,
                onToggleVoiceLanguage = onToggleVoiceLanguage,
                onConsumeVoiceFinal = onConsumeVoiceFinal,
                onToggleMode = onToggleMode,
                onSend = onSendMessage
            )
        }
    }

    if (showProviderSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showProviderSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
        ) {
            ProviderPickerSheetContent(
                providers = providers,
                activeProviderId = activeProvider?.id,
                onSelect = { id ->
                    showProviderSheet = false
                    onSelectProvider(id)
                },
                onManageProviders = {
                    showProviderSheet = false
                    onOpenSettingsProviders()
                }
            )
        }
    }

    editingPlan?.let { plan ->
        EditStepDialog(
            plan = plan,
            onDismiss = { editingPlan = null },
            onSave = { updated ->
                onEditActionInstruction(plan.actionId, updated)
                editingPlan = null
            }
        )
    }
}

@Composable
private fun MinimalChatTopBar(
    activeProvider: AiProvider?,
    isSpeakingTts: Boolean,
    onStopSpeaking: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenProviderPicker: () -> Unit,
    onNewChat: () -> Unit
) {
    val pillInteraction = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(
            onClick = onOpenDrawer,
            modifier = Modifier.testTag("drawer_menu_button")
        ) {
            Icon(
                imageVector = Icons.Rounded.Menu,
                contentDescription = "Open menu",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        // Centered compact tappable model pill with subtle 0.96f press-scale feedback
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(horizontal = 8.dp)
                .pressScale(pillInteraction)
                .clip(RoundedCornerShape(20.dp))
                .clickable(
                    interactionSource = pillInteraction,
                    indication = androidx.compose.material3.ripple(),
                    onClick = onOpenProviderPicker
                )
                .minimumInteractiveComponentSize()
                .testTag("provider_selector_pill")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = activeProvider?.displayName
                        ?: stringResource(R.string.no_provider_selected),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 175.dp)
                )
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "Switch provider",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isSpeakingTts) {
                IconButton(
                    onClick = onStopSpeaking,
                    modifier = Modifier.testTag("stop_tts_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.VolumeOff,
                        contentDescription = stringResource(R.string.action_stop_speaking),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            IconButton(
                onClick = onNewChat,
                modifier = Modifier.testTag("new_chat_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "New chat",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MinimalEmptyGreeting(
    chips: List<String>,
    onTapChip: (String) -> Unit
) {
    val geminiGradient = LocalAgentSemanticColors.current.geminiGradient
    val greetingBrush = remember(geminiGradient) {
        Brush.horizontalGradient(colors = geminiGradient)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "Hello, how can I help?",
            style = MaterialTheme.typography.headlineMedium.copy(brush = greetingBrush)
        )

        if (chips.isNotEmpty()) {
            Spacer(modifier = Modifier.height(22.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                chips.forEach { chipText ->
                    val chipInteraction = remember(chipText) { MutableInteractionSource() }
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f),
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                        ),
                        modifier = Modifier
                            .pressScale(chipInteraction)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(
                                interactionSource = chipInteraction,
                                indication = androidx.compose.material3.ripple(),
                                onClick = { onTapChip(chipText) }
                            )
                            .minimumInteractiveComponentSize()
                            .testTag("quick_chip_${chipText.lowercase().replace(" ", "_")}")
                    ) {
                        Text(
                            text = chipText,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatMessageFeed(
    messages: List<ChatMessage>,
    showStepPlan: Boolean,
    isSending: Boolean,
    livePhaseText: String,
    livePhaseStartMillis: Long,
    onCopyMessage: (String) -> Unit,
    onApprove: (String) -> Unit,
    onEditStep: (ActionPlan) -> Unit,
    onCancel: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    // Track session mount time & already-animated message IDs so history rows never animate when scrolling.
    val sessionMountMillis = remember { System.currentTimeMillis() }
    val animatedMessageIds = remember { hashSetOf<String>() }

    LaunchedEffect(messages.size, isSending) {
        val target = messages.size + if (isSending) 1 else 0
        if (target > 0) {
            listState.scrollToItem((target - 1).coerceAtLeast(0))
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(
            items = messages,
            key = { it.id },
            contentType = { msg ->
                when {
                    msg.sender == MessageSender.USER -> "user"
                    msg.isBlockedBySafety && msg.actionPlan == null -> "safety_block"
                    msg.actionPlan != null -> "agent_approval"
                    else -> "agent_text"
                }
            }
        ) { message ->
            val shouldAnimateEntrance = message.sender != MessageSender.USER &&
                message.timestampMillis >= sessionMountMillis &&
                !animatedMessageIds.contains(message.id)

            ChatMessageRow(
                message = message,
                showStepPlan = showStepPlan,
                livePhaseText = livePhaseText,
                livePhaseStartMillis = livePhaseStartMillis,
                shouldAnimateEntrance = shouldAnimateEntrance,
                onEntranceAnimated = { animatedMessageIds.add(message.id) },
                onCopy = {
                    clipboard.setText(AnnotatedString(message.text))
                    onCopyMessage("Copied to clipboard")
                },
                onApprove = { actionId -> onApprove(actionId) },
                onEditStep = { plan -> onEditStep(plan) },
                onCancel = { actionId -> onCancel(actionId) }
            )
        }

        if (isSending) {
            item(key = "gemini_thinking_row", contentType = "thinking") {
                GeminiThinkingRow(
                    livePhaseText = livePhaseText,
                    livePhaseStartMillis = livePhaseStartMillis
                )
            }
        }
    }
}

@Composable
private fun GeminiThinkingRow(
    livePhaseText: String = "",
    livePhaseStartMillis: Long = 0L
) {
    val semantic = LocalAgentSemanticColors.current
    val gradientColors = semantic.geminiGradient
    val baseBarColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    val shimmerHighlight = gradientColors[1].copy(alpha = 0.28f)
    val shimmerAccent = gradientColors[0].copy(alpha = 0.22f)

    // Single infinite transition; all animated values are read strictly in graphicsLayer / drawBehind.
    val infiniteTransition = rememberInfiniteTransition(label = "gemini_thinking")
    val rotation = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sparkle_rotation"
    )
    val pulseScale = infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sparkle_pulse"
    )
    val shimmerPhase = infiniteTransition.animateFloat(
        initialValue = -0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1350, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_phase"
    )

    val sparklePath = remember { Path() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .testTag("gemini_thinking_indicator"),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Custom 4-point Gemini sparkle pulsing & rotating strictly on GPU
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(22.dp)
                .graphicsLayer {
                    rotationZ = rotation.value
                    val s = pulseScale.value
                    scaleX = s
                    scaleY = s
                }
                .drawBehind {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val r = size.minDimension / 2f
                    val inner = r * 0.22f
                    sparklePath.reset()
                    sparklePath.moveTo(cx, cy - r)
                    sparklePath.quadraticTo(cx + inner * 0.6f, cy - inner * 0.6f, cx + r, cy)
                    sparklePath.quadraticTo(cx + inner * 0.6f, cy + inner * 0.6f, cx, cy + r)
                    sparklePath.quadraticTo(cx - inner * 0.6f, cy + inner * 0.6f, cx - r, cy)
                    sparklePath.quadraticTo(cx - inner * 0.6f, cy - inner * 0.6f, cx, cy - r)
                    sparklePath.close()

                    drawPath(
                        path = sparklePath,
                        brush = Brush.linearGradient(
                            colors = gradientColors,
                            start = Offset.Zero,
                            end = Offset(size.width, size.height)
                        )
                    )
                }
        )

        // Two sleek shimmering gradient skeleton bars drawn strictly inside drawBehind
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.76f)
                    .height(12.dp)
                    .drawBehind {
                        val radius = CornerRadius(size.height / 2f, size.height / 2f)
                        drawRoundRect(color = baseBarColor, cornerRadius = radius)
                        val phase = shimmerPhase.value
                        val bandWidth = size.width * 0.55f
                        val startX = phase * size.width - bandWidth * 0.5f
                        drawRoundRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(Color.Transparent, shimmerAccent, shimmerHighlight, Color.Transparent),
                                startX = startX,
                                endX = startX + bandWidth
                            ),
                            cornerRadius = radius
                        )
                    }
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.48f)
                    .height(12.dp)
                    .drawBehind {
                        val radius = CornerRadius(size.height / 2f, size.height / 2f)
                        drawRoundRect(color = baseBarColor, cornerRadius = radius)
                        val phase = shimmerPhase.value
                        val bandWidth = size.width * 0.65f
                        val startX = (phase - 0.15f) * size.width - bandWidth * 0.5f
                        drawRoundRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(Color.Transparent, shimmerAccent, shimmerHighlight, Color.Transparent),
                                startX = startX,
                                endX = startX + bandWidth
                            ),
                            cornerRadius = radius
                        )
                    }
            )
            if (livePhaseText.isNotBlank() && livePhaseStartMillis > 0L) {
                LivePhaseTimerText(
                    phase = livePhaseText,
                    startMillis = livePhaseStartMillis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("planning_elapsed_timer")
                )
            }
        }
    }
}

@Composable
private fun LivePhaseTimerText(
    phase: String,
    startMillis: Long,
    color: Color,
    modifier: Modifier = Modifier
) {
    // Strictly isolated 100ms timer tick so only this single Text recomposes.
    var elapsedSec by remember(startMillis) {
        mutableStateOf(
            if (startMillis > 0L) ((System.currentTimeMillis() - startMillis).coerceAtLeast(0L)) / 1000.0
            else 0.0
        )
    }

    LaunchedEffect(startMillis) {
        if (startMillis <= 0L) return@LaunchedEffect
        while (true) {
            elapsedSec = ((System.currentTimeMillis() - startMillis).coerceAtLeast(0L)) / 1000.0
            delay(100L)
        }
    }

    val label = if (startMillis > 0L) {
        "$phase ${String.format(Locale.US, "%.1fs", elapsedSec)}"
    } else {
        phase
    }

    Text(
        text = label,
        style = MonoCodeStyle.copy(color = color),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

@Composable
private fun ChatMessageRow(
    message: ChatMessage,
    showStepPlan: Boolean,
    livePhaseText: String,
    livePhaseStartMillis: Long,
    shouldAnimateEntrance: Boolean,
    onEntranceAnimated: () -> Unit,
    onCopy: () -> Unit,
    onApprove: (String) -> Unit,
    onEditStep: (ActionPlan) -> Unit,
    onCancel: (String) -> Unit
) {
    val timeLabel = remember(message.timestampMillis) {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(message.timestampMillis))
    }

    if (message.sender == MessageSender.USER) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 310.dp)
                    .clip(RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    } else {
        // Soft 220ms fade-in + 12.dp upward glide strictly via graphicsLayer for newly arriving replies
        val entranceProgress = remember(message.id) {
            Animatable(if (shouldAnimateEntrance) 0f else 1f)
        }
        val glidePx = with(LocalDensity.current) { 12.dp.toPx() }

        LaunchedEffect(message.id) {
            if (shouldAnimateEntrance) {
                onEntranceAnimated()
                entranceProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
                )
            }
        }

        val entranceModifier = Modifier.graphicsLayer {
            val p = entranceProgress.value
            alpha = p
            translationY = (1f - p) * glidePx
        }

        if (message.isBlockedBySafety && message.actionPlan == null) {
            val semantic = LocalAgentSemanticColors.current
            Column(
                modifier = entranceModifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(semantic.highRiskContainer)
                    .border(
                        1.dp,
                        semantic.highRiskContent.copy(alpha = 0.4f),
                        RoundedCornerShape(16.dp)
                    )
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Block,
                        contentDescription = null,
                        tint = semantic.highRiskContent,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = message.blockedReason ?: "Blocked by Safety Guardrail",
                        style = MaterialTheme.typography.titleSmall,
                        color = semantic.highRiskContent
                    )
                }
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = semantic.highRiskContent
                )
            }
        } else {
            Column(
                modifier = entranceModifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (message.isError) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                if (message.actionPlan != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    CompactInlineApprovalBox(
                        plan = message.actionPlan,
                        status = message.approvalStatus ?: ApprovalStatus.PENDING,
                        completedStepsCount = message.completedStepsCount,
                        activeStepIndex = message.activeStepIndex,
                        isBlockedBySafety = message.isBlockedBySafety,
                        blockedReason = message.blockedReason,
                        showStepPlan = showStepPlan,
                        livePhaseText = livePhaseText,
                        livePhaseStartMillis = livePhaseStartMillis,
                        durationLabel = message.durationLabel,
                        onApprove = { onApprove(message.actionPlan.actionId) },
                        onEditStep = { onEditStep(message.actionPlan) },
                        onCancel = { onCancel(message.actionPlan.actionId) }
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onCopy)
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                            .testTag("copy_reply_${message.id}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = stringResource(R.string.action_copy_reply),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Copy",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Text(
                        text = timeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
fun CompactInlineApprovalBox(
    plan: ActionPlan,
    status: ApprovalStatus,
    completedStepsCount: Int,
    activeStepIndex: Int,
    isBlockedBySafety: Boolean,
    blockedReason: String?,
    showStepPlan: Boolean,
    livePhaseText: String = "",
    livePhaseStartMillis: Long = 0L,
    durationLabel: String? = null,
    onApprove: () -> Unit,
    onEditStep: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val semantic = LocalAgentSemanticColors.current
    val (riskBg, riskFg) = when (plan.riskLevel) {
        RiskLevel.LOW -> semantic.lowRiskContainer to semantic.lowRiskContent
        RiskLevel.MEDIUM -> semantic.mediumRiskContainer to semantic.mediumRiskContent
        RiskLevel.HIGH -> semantic.highRiskContainer to semantic.highRiskContent
    }
    val borderColor = when {
        isBlockedBySafety -> semantic.highRiskContent
        status == ApprovalStatus.RUNNING -> MaterialTheme.colorScheme.primary
        status == ApprovalStatus.COMPLETED -> semantic.successContent.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val isRunningOrDone = status == ApprovalStatus.RUNNING || status == ApprovalStatus.COMPLETED

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .padding(14.dp)
            .testTag("approval_card_${plan.actionId}"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(riskBg)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Shield,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = riskFg
                        )
                        Text(
                            text = plan.riskLevel.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = riskFg,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                if (plan.isDemo) {
                    Text(
                        text = "DEMO",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                StatusPill(status = status)
                if (status == ApprovalStatus.PENDING && !isBlockedBySafety) {
                    IconButton(
                        onClick = onEditStep,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("edit_step_button_${plan.actionId}")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = stringResource(R.string.action_edit_step),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Text(
            text = plan.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )

        if (status == ApprovalStatus.RUNNING && livePhaseText.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(13.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                LivePhaseTimerText(
                    phase = livePhaseText,
                    startMillis = livePhaseStartMillis,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.testTag("card_live_phase_timer")
                )
            }
        } else if (!durationLabel.isNullOrBlank()) {
            Text(
                text = durationLabel,
                style = MonoCodeStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.testTag("card_duration_label")
            )
        }

        if (isBlockedBySafety && !blockedReason.isNullOrBlank()) {
            Text(
                text = "Blocked locally ($blockedReason). Approval is disabled.",
                style = MaterialTheme.typography.bodySmall,
                color = semantic.highRiskContent
            )
        }

        if ((showStepPlan || isRunningOrDone) && plan.steps.isNotEmpty()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                plan.steps.forEachIndexed { index, stepText ->
                    val isDone = status == ApprovalStatus.COMPLETED ||
                        (status == ApprovalStatus.RUNNING && index < completedStepsCount)
                    val isActive = status == ApprovalStatus.RUNNING && index == activeStepIndex

                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(18.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            when {
                                isDone -> Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = "Done",
                                    tint = semantic.successContent,
                                    modifier = Modifier.size(16.dp)
                                )
                                isActive -> CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(14.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                else -> Text(
                                    text = "${index + 1}.",
                                    style = MonoCodeStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                            }
                        }

                        Text(
                            text = stepText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        if (!plan.editedInstruction.isNullOrBlank()) {
            Text(
                text = "Edited: ${plan.editedInstruction}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (status == ApprovalStatus.PENDING && !isBlockedBySafety) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onApprove,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .testTag("approve_run_button_${plan.actionId}")
                ) {
                    Text("Approve")
                }

                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .testTag("cancel_action_button_${plan.actionId}")
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun StopAgentFloatingBar(
    statusText: String,
    startMillis: Long,
    onStop: () -> Unit
) {
    val stopInteraction = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.4f),
                shape = RoundedCornerShape(18.dp)
            )
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
            .testTag("stop_agent_banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.size(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer
        )
        LivePhaseTimerText(
            phase = statusText,
            startMillis = startMillis,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f)
        )
        Button(
            onClick = onStop,
            interactionSource = stopInteraction,
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            ),
            modifier = Modifier
                .height(38.dp)
                .pressScale(stopInteraction)
                .testTag("stop_agent_button")
        ) {
            Icon(
                imageVector = Icons.Rounded.StopCircle,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.action_stop_agent),
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun BottomPillInputBar(
    agentMode: AgentMode,
    isSending: Boolean,
    isListening: Boolean,
    voiceLanguage: VoiceLanguage,
    voicePartialText: String,
    voiceFinalEvent: Pair<Long, String>?,
    onToggleMic: () -> Unit,
    onToggleVoiceLanguage: () -> Unit,
    onConsumeVoiceFinal: () -> Unit,
    onToggleMode: (AgentMode) -> Unit,
    onSend: (String) -> Unit
) {
    // STRICTLY ISOLATED TYPING & FOCUS STATE: typing never recomposes chat list or top bar.
    var draft by rememberSaveable { mutableStateOf("") }
    var isFocused by remember { mutableStateOf(false) }
    val sendInteraction = remember { MutableInteractionSource() }
    val micInteraction = remember { MutableInteractionSource() }

    // Stream live voice transcript directly into the isolated draft field
    LaunchedEffect(isListening, voicePartialText) {
        if (isListening && voicePartialText.isNotBlank()) {
            draft = voicePartialText
        }
    }

    LaunchedEffect(voiceFinalEvent?.first) {
        val text = voiceFinalEvent?.second
        if (!text.isNullOrBlank()) {
            draft = text
            onConsumeVoiceFinal()
        }
    }

    val focusProgress = animateFloatAsState(
        targetValue = if (isFocused || draft.isNotEmpty() || isListening) 1f else 0f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "input_focus_border"
    )

    val capsuleBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    val unfocusedBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val geminiGradient = LocalAgentSemanticColors.current.geminiGradient

    // Single unified navigationBars.union(ime) inset padding — zero black gap above keyboard.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .drawBehind {
                    val corner = CornerRadius(28.dp.toPx(), 28.dp.toPx())
                    drawRoundRect(color = capsuleBg, cornerRadius = corner)

                    val p = focusProgress.value
                    val strokePx = (1.dp + 0.75.dp * p).toPx()
                    val halfStroke = strokePx / 2f
                    val insetSize = Size(size.width - strokePx, size.height - strokePx)
                    val topLeft = Offset(halfStroke, halfStroke)

                    if (p <= 0.01f) {
                        drawRoundRect(
                            color = unfocusedBorder,
                            topLeft = topLeft,
                            size = insetSize,
                            cornerRadius = corner,
                            style = Stroke(width = strokePx)
                        )
                    } else {
                        val blendedStops = geminiGradient.map { accent ->
                            lerp(unfocusedBorder, accent.copy(alpha = 0.85f), p)
                        }
                        drawRoundRect(
                            brush = Brush.horizontalGradient(colors = blendedStops),
                            topLeft = topLeft,
                            size = insetSize,
                            cornerRadius = corner,
                            style = Stroke(width = strokePx)
                        )
                    }
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 11.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 8.dp, top = 2.dp, bottom = 2.dp)
                ) {
                    if (draft.isEmpty()) {
                        Text(
                            text = when {
                                isListening -> if (voiceLanguage == VoiceLanguage.TE_IN) {
                                    "వింటున్నాను (తెలుగు)…"
                                } else {
                                    stringResource(R.string.voice_listening_hint)
                                }
                                agentMode == AgentMode.CHAT_ONLY -> "Ask anything…"
                                else -> "Tell your agent what to do…"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isListening) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            }
                        )
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 5,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { isFocused = it.isFocused }
                            .testTag("chat_input_field")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    SlidingModeTogglePill(
                        agentMode = agentMode,
                        onToggleMode = onToggleMode
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Compact EN / తెలుగు voice language pill
                        Box(
                            modifier = Modifier
                                .height(36.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                                    RoundedCornerShape(18.dp)
                                )
                                .clickable(onClick = onToggleVoiceLanguage)
                                .padding(horizontal = 10.dp)
                                .testTag("voice_language_toggle"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = voiceLanguage.shortChipLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // Microphone Voice Command button with GPU-only pulsing ring when listening
                        VoiceMicButton(
                            isListening = isListening,
                            enabled = !isSending,
                            interactionSource = micInteraction,
                            onClick = onToggleMic
                        )

                        FilledIconButton(
                            onClick = {
                                val toSend = draft.trim()
                                if (toSend.isNotBlank()) {
                                    draft = ""
                                    onSend(toSend)
                                }
                            },
                            enabled = draft.isNotBlank() && !isSending,
                            interactionSource = sendInteraction,
                            shape = CircleShape,
                            modifier = Modifier
                                .size(40.dp)
                                .pressScale(sendInteraction)
                                .testTag("send_message_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Send,
                                contentDescription = stringResource(R.string.action_send),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceMicButton(
    isListening: Boolean,
    enabled: Boolean,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit
) {
    val pulseColor = MaterialTheme.colorScheme.error
    val bgColor = if (isListening) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
    }
    val fgColor = if (isListening) {
        MaterialTheme.colorScheme.onError
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val borderColor = if (isListening) {
        Color.Transparent
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    }

    val ringModifier = if (isListening) {
        val infinite = rememberInfiniteTransition(label = "mic_pulse")
        val phase = infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "mic_ring_phase"
        )
        Modifier.drawBehind {
            val p = phase.value
            val maxRadius = (size.minDimension / 2f) + 8.dp.toPx() * p
            drawCircle(
                color = pulseColor.copy(alpha = (1f - p) * 0.45f),
                radius = maxRadius
            )
        }
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .size(40.dp)
            .then(ringModifier)
            .pressScale(interactionSource)
            .clip(CircleShape)
            .background(bgColor)
            .border(1.dp, borderColor, CircleShape)
            .clickable(
                enabled = enabled || isListening,
                interactionSource = interactionSource,
                indication = androidx.compose.material3.ripple(),
                onClick = onClick
            )
            .testTag("voice_mic_button"),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isListening) Icons.Rounded.Stop else Icons.Rounded.Mic,
            contentDescription = stringResource(
                if (isListening) R.string.action_voice_stop else R.string.action_voice_input
            ),
            tint = fgColor,
            modifier = Modifier.size(19.dp)
        )
    }
}

@Composable
private fun SlidingModeTogglePill(
    agentMode: AgentMode,
    onToggleMode: (AgentMode) -> Unit
) {
    val isAgent = agentMode == AgentMode.AGENT_CONTROL
    val slideFraction = animateFloatAsState(
        targetValue = if (isAgent) 1f else 0f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "mode_pill_slide"
    )

    val trackBg = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
    val trackBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
    val activeThumbColor = MaterialTheme.colorScheme.primaryContainer

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, trackBorder, RoundedCornerShape(18.dp))
            .drawBehind {
                val radius = CornerRadius(18.dp.toPx(), 18.dp.toPx())
                drawRoundRect(color = trackBg, cornerRadius = radius)

                val pad = 3.dp.toPx()
                val thumbWidth = (size.width - pad * 2f) / 2f
                val thumbHeight = size.height - pad * 2f
                val left = pad + slideFraction.value * thumbWidth
                drawRoundRect(
                    color = activeThumbColor,
                    topLeft = Offset(left, pad),
                    size = Size(thumbWidth, thumbHeight),
                    cornerRadius = CornerRadius(15.dp.toPx(), 15.dp.toPx())
                )
            }
            .padding(3.dp)
            .testTag("mode_toggle_pill"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ModeSegmentChip(
            label = "Chat",
            icon = Icons.Rounded.SmartToy,
            selected = !isAgent,
            onClick = { onToggleMode(AgentMode.CHAT_ONLY) },
            modifier = Modifier.testTag("mode_chat_option")
        )
        ModeSegmentChip(
            label = "Agent",
            icon = Icons.Rounded.PhoneAndroid,
            selected = isAgent,
            onClick = { onToggleMode(AgentMode.AGENT_CONTROL) },
            modifier = Modifier.testTag("mode_agent_option")
        )
    }
}

@Composable
private fun ModeSegmentChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .width(74.dp)
            .clip(RoundedCornerShape(15.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = contentColor
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1
        )
    }
}

@Composable
private fun ProviderPickerSheetContent(
    providers: List<AiProvider>,
    activeProviderId: String?,
    onSelect: (String) -> Unit,
    onManageProviders: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Switch Model / Provider",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        if (providers.isEmpty()) {
            Text(
                text = "No custom endpoints saved yet. Add your own Base URL, optional API key, and model in Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            providers.forEach { provider ->
                val isSelected = provider.id == activeProviderId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            else MaterialTheme.colorScheme.surface
                        )
                        .clickable { onSelect(provider.id) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = provider.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = provider.modelName,
                            style = MonoCodeStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "Active",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        )

        TextButton(
            onClick = onManageProviders,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Manage Custom Providers & API Keys")
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
fun StatusPill(status: ApprovalStatus) {
    val semantic = LocalAgentSemanticColors.current
    val (bg, fg) = when (status) {
        ApprovalStatus.PENDING -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        ApprovalStatus.APPROVED, ApprovalStatus.COMPLETED -> semantic.successContainer to semantic.successContent
        ApprovalStatus.RUNNING -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        ApprovalStatus.CANCELLED -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        ApprovalStatus.FAILED, ApprovalStatus.BLOCKED -> semantic.highRiskContainer to semantic.highRiskContent
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = status.label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun EditStepDialog(
    plan: ActionPlan,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var draft by remember(plan.actionId) {
        mutableStateOf(
            plan.editedInstruction
                ?: plan.steps.mapIndexed { idx, s -> "${idx + 1}. $s" }.joinToString("\n")
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Instruction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Update the instruction for \"${plan.title}\" before approving.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Instruction") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_step_text_field")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(draft) },
                modifier = Modifier.testTag("save_edited_step_button")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
