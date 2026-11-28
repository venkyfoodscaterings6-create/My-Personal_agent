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
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Share
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
import androidx.compose.ui.platform.LocalContext
import com.example.ActionPlan
import com.example.AiProvider
import com.example.ApiFormat
import com.example.ApprovalStatus
import com.example.BuiltInGeminiSettings
import com.example.ChatMessage
import com.example.GeminiBuiltInModel
import com.example.GeneratedFileAttachment
import com.example.MemorySuggestion
import com.example.MessageSender
import com.example.R
import com.example.RiskLevel
import com.example.SuggestionKind
import com.example.VoiceLanguage
import com.example.ui.theme.LocalAgentGlassColors
import com.example.ui.theme.LocalAgentSemanticColors
import com.example.ui.theme.MonoCodeStyle
import com.example.ui.theme.frostedPill
import com.example.ui.theme.glassPanel
import com.example.ui.theme.nestedGlassCard
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
    sessionId: String,
    messages: List<ChatMessage>,
    hasMoreOlderMessages: Boolean = false,
    isLoadingOlderMessages: Boolean = false,
    onLoadOlderMessages: () -> Unit = {},
    providers: List<AiProvider>,
    activeProvider: AiProvider?,
    activeHeaderLabel: String,
    isUsingBuiltInGemini: Boolean,
    builtInGeminiSettings: BuiltInGeminiSettings,
    streamingAssistantText: String,
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
    onStopMicSilently: () -> Unit = {},
    onOpenLiveVoice: () -> Unit,
    onToggleGoogleSearch: () -> Unit,
    onToggleVoiceLanguage: () -> Unit,
    onStopSpeaking: () -> Unit,
    onConsumeVoiceFinal: () -> Unit,
    onStopAgent: () -> Unit,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    onSelectBuiltInGemini: (GeminiBuiltInModel) -> Unit,
    onSelectProvider: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onApproveAction: (String) -> Unit,
    onEditActionInstruction: (String, String) -> Unit,
    onCancelAction: (String) -> Unit,
    onOpenSettingsProviders: () -> Unit,
    onCopyMessage: (String) -> Unit,
    onOpenGeneratedFile: (GeneratedFileAttachment) -> Unit = {},
    onSaveGeneratedFile: (GeneratedFileAttachment) -> Unit = {},
    onShareGeneratedFile: (GeneratedFileAttachment) -> Unit = {},
    memorySuggestions: Map<String, MemorySuggestion> = emptyMap(),
    onSaveToMemory: (ChatMessage) -> Unit = {},
    onAcceptSuggestion: (MemorySuggestion) -> Unit = {},
    onDismissSuggestion: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showProviderSheet by remember { mutableStateOf(false) }
    var editingPlan by remember { mutableStateOf<ActionPlan?>(null) }

    val glass = LocalAgentGlassColors.current

    // Scaffold with zero automatic insets prevents double IME padding / black keyboard gaps.
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { _ ->
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            MinimalChatTopBar(
                activeHeaderLabel = activeHeaderLabel,
                isUsingBuiltInGemini = isUsingBuiltInGemini,
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
                        sessionId = sessionId,
                        messages = messages,
                        hasMoreOlderMessages = hasMoreOlderMessages,
                        isLoadingOlderMessages = isLoadingOlderMessages,
                        onLoadOlderMessages = onLoadOlderMessages,
                        memorySuggestions = memorySuggestions,
                        showStepPlan = showStepPlan,
                        isSending = isSending,
                        streamingAssistantText = streamingAssistantText,
                        livePhaseText = liveAgentStatusText,
                        livePhaseStartMillis = livePhaseStartMillis,
                        onCopyMessage = onCopyMessage,
                        onOpenGeneratedFile = onOpenGeneratedFile,
                        onSaveGeneratedFile = onSaveGeneratedFile,
                        onShareGeneratedFile = onShareGeneratedFile,
                        onSaveToMemory = onSaveToMemory,
                        onAcceptSuggestion = onAcceptSuggestion,
                        onDismissSuggestion = onDismissSuggestion,
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
                sessionId = sessionId,
                canUseWebSearch = isUsingBuiltInGemini || activeProvider?.apiFormat == ApiFormat.GEMINI_NATIVE,
                googleSearchEnabled = builtInGeminiSettings.googleSearchEnabled,
                isSending = isSending || isAgentLoopRunning,
                isListening = isListening,
                voiceLanguage = voiceLanguage,
                voicePartialText = voicePartialText,
                voiceFinalEvent = voiceFinalEvent,
                onToggleMic = onToggleMic,
                onStopMicSilently = onStopMicSilently,
                onOpenLiveVoice = onOpenLiveVoice,
                onToggleGoogleSearch = onToggleGoogleSearch,
                onToggleVoiceLanguage = onToggleVoiceLanguage,
                onConsumeVoiceFinal = onConsumeVoiceFinal,
                onSend = onSendMessage
            )
        }
    }

    if (showProviderSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showProviderSheet = false },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            containerColor = glass.sheetFill,
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
        ) {
            ProviderPickerSheetContent(
                providers = providers,
                activeProviderId = if (isUsingBuiltInGemini) null else activeProvider?.id,
                isUsingBuiltInGemini = isUsingBuiltInGemini,
                selectedBuiltInModel = builtInGeminiSettings.selectedModel,
                onSelectBuiltIn = { model ->
                    showProviderSheet = false
                    onSelectBuiltInGemini(model)
                },
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
    activeHeaderLabel: String,
    isUsingBuiltInGemini: Boolean,
    isSpeakingTts: Boolean,
    onStopSpeaking: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenProviderPicker: () -> Unit,
    onNewChat: () -> Unit
) {
    val glass = LocalAgentGlassColors.current
    val pillInteraction = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(glass.topBarFill)
            .statusBarsPadding()
    ) {
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

            // Centered compact tappable frosted model pill with subtle 0.96f press-scale feedback
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(horizontal = 8.dp)
                    .pressScale(pillInteraction)
                    .frostedPill(cornerRadius = 20.dp, active = false)
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
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (isUsingBuiltInGemini) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = glass.accentBlue
                        )
                    }
                    Text(
                        text = activeHeaderLabel,
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
                            tint = glass.accentBlue
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
        HorizontalDivider(
            thickness = 0.5.dp,
            color = glass.hairlineDivider
        )
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
                    Box(
                        modifier = Modifier
                            .pressScale(chipInteraction)
                            .glassPanel(cornerRadius = 18.dp)
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
    sessionId: String,
    messages: List<ChatMessage>,
    hasMoreOlderMessages: Boolean,
    isLoadingOlderMessages: Boolean,
    onLoadOlderMessages: () -> Unit,
    memorySuggestions: Map<String, MemorySuggestion>,
    showStepPlan: Boolean,
    isSending: Boolean,
    streamingAssistantText: String,
    livePhaseText: String,
    livePhaseStartMillis: Long,
    onCopyMessage: (String) -> Unit,
    onOpenGeneratedFile: (GeneratedFileAttachment) -> Unit,
    onSaveGeneratedFile: (GeneratedFileAttachment) -> Unit,
    onShareGeneratedFile: (GeneratedFileAttachment) -> Unit,
    onSaveToMemory: (ChatMessage) -> Unit,
    onAcceptSuggestion: (MemorySuggestion) -> Unit,
    onDismissSuggestion: (String) -> Unit,
    onApprove: (String) -> Unit,
    onEditStep: (ActionPlan) -> Unit,
    onCancel: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val glass = LocalAgentGlassColors.current

    // Track session mount time & already-animated message IDs so history rows never animate when scrolling.
    val sessionMountMillis = remember(sessionId) { System.currentTimeMillis() }
    val animatedMessageIds = remember(sessionId) { hashSetOf<String>() }
    var initialScrollDone by remember(sessionId) { mutableStateOf(false) }
    val lastMessageId = messages.lastOrNull()?.id

    // Scroll to bottom when opening a session or when a new message / streaming token arrives at the end.
    // Prepending older paginated messages keeps lastMessageId unchanged so scroll doesn't jump to bottom.
    LaunchedEffect(sessionId, lastMessageId, isSending, streamingAssistantText.length / 40) {
        val headerOffset = if (hasMoreOlderMessages) 1 else 0
        val target = headerOffset + messages.size + if (isSending) 1 else 0
        if (target > 0) {
            listState.scrollToItem((target - 1).coerceAtLeast(0))
            initialScrollDone = true
        }
    }

    // Lazy load older messages from Room when user scrolls up to the top item
    LaunchedEffect(
        listState.firstVisibleItemIndex,
        listState.isScrollInProgress,
        hasMoreOlderMessages,
        isLoadingOlderMessages,
        initialScrollDone
    ) {
        if (initialScrollDone &&
            listState.isScrollInProgress &&
            listState.firstVisibleItemIndex == 0 &&
            hasMoreOlderMessages &&
            !isLoadingOlderMessages
        ) {
            onLoadOlderMessages()
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (hasMoreOlderMessages) {
            item(key = "load_older_messages_header", contentType = "load_more") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier
                            .frostedPill(cornerRadius = 18.dp, active = false)
                            .clickable(enabled = !isLoadingOlderMessages, onClick = onLoadOlderMessages)
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .testTag("load_older_messages_button"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isLoadingOlderMessages) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp),
                                color = glass.accentBlue
                            )
                            Text(
                                text = "Loading older messages…",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                text = "Load older messages",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

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
                suggestion = memorySuggestions[message.id],
                showStepPlan = showStepPlan,
                livePhaseText = livePhaseText,
                livePhaseStartMillis = livePhaseStartMillis,
                shouldAnimateEntrance = shouldAnimateEntrance,
                onEntranceAnimated = { animatedMessageIds.add(message.id) },
                onCopy = {
                    clipboard.setText(AnnotatedString(message.text))
                    onCopyMessage("Copied to clipboard")
                },
                onCopyCodeFeedback = onCopyMessage,
                onOpenGeneratedFile = onOpenGeneratedFile,
                onSaveGeneratedFile = onSaveGeneratedFile,
                onShareGeneratedFile = onShareGeneratedFile,
                onSaveToMemory = { onSaveToMemory(message) },
                onAcceptSuggestion = onAcceptSuggestion,
                onDismissSuggestion = onDismissSuggestion,
                onApprove = { actionId -> onApprove(actionId) },
                onEditStep = { plan -> onEditStep(plan) },
                onCancel = { actionId -> onCancel(actionId) }
            )
        }

        if (isSending) {
            item(key = "gemini_thinking_row", contentType = "thinking") {
                val glass = LocalAgentGlassColors.current
                if (streamingAssistantText.isNotBlank()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(glass.aiBubbleFill)
                            .border(1.dp, glass.aiBubbleBorder, RoundedCornerShape(18.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        GeminiMarkdownText(
                            messageId = "streaming_reply",
                            content = streamingAssistantText,
                            onCopyCode = onCopyMessage
                        )
                        Text(
                            text = "Streaming…",
                            style = MaterialTheme.typography.labelSmall,
                            color = glass.accentBlue
                        )
                    }
                } else {
                    GeminiThinkingRow(
                        livePhaseText = livePhaseText,
                        livePhaseStartMillis = livePhaseStartMillis
                    )
                }
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

    val glass = LocalAgentGlassColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(glass.aiBubbleFill)
            .border(1.dp, glass.aiBubbleBorder, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
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
    suggestion: MemorySuggestion?,
    showStepPlan: Boolean,
    livePhaseText: String,
    livePhaseStartMillis: Long,
    shouldAnimateEntrance: Boolean,
    onEntranceAnimated: () -> Unit,
    onCopy: () -> Unit,
    onCopyCodeFeedback: (String) -> Unit,
    onOpenGeneratedFile: (GeneratedFileAttachment) -> Unit,
    onSaveGeneratedFile: (GeneratedFileAttachment) -> Unit,
    onShareGeneratedFile: (GeneratedFileAttachment) -> Unit,
    onSaveToMemory: () -> Unit,
    onAcceptSuggestion: (MemorySuggestion) -> Unit,
    onDismissSuggestion: (String) -> Unit,
    onApprove: (String) -> Unit,
    onEditStep: (ActionPlan) -> Unit,
    onCancel: (String) -> Unit
) {
    val timeLabel = remember(message.timestampMillis) {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(message.timestampMillis))
    }

    val glass = LocalAgentGlassColors.current
    if (message.sender == MessageSender.USER) {
        val userBubbleShape = RoundedCornerShape(20.dp)
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 310.dp)
                    .clip(userBubbleShape)
                    .background(glass.userBubbleFill)
                    .border(1.dp, glass.userBubbleBorderBrush(), userBubbleShape)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = glass.userBubbleText
                )
            }

            if (suggestion != null) {
                Row(
                    modifier = Modifier
                        .nestedGlassCard(
                            cornerRadius = 18.dp,
                            borderColorOverride = glass.accentBlue.copy(alpha = 0.42f)
                        )
                        .clickable { onAcceptSuggestion(suggestion) }
                        .padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp)
                        .testTag("memory_suggestion_pill_${message.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.BookmarkAdd,
                        contentDescription = null,
                        tint = glass.accentBlue,
                        modifier = Modifier.size(14.dp)
                    )
                    val prefix = if (suggestion.kind == SuggestionKind.TASK) "+ Add Task:" else "+ Save Memory:"
                    Text(
                        text = "$prefix ${suggestion.title.take(30)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 220.dp)
                    )
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .clickable { onDismissSuggestion(message.id) }
                            .testTag("dismiss_suggestion_${message.id}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Dismiss suggestion",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
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
                // Left-aligned frosted glass container for AI replies
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(glass.aiBubbleFill)
                        .border(1.dp, glass.aiBubbleBorder, RoundedCornerShape(18.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
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
                        GeminiMarkdownText(
                            messageId = message.id,
                            content = message.text,
                            onCopyCode = onCopyCodeFeedback
                        )
                    }

                    if (message.generatedFile != null) {
                        GeneratedFileCard(
                            attachment = message.generatedFile,
                            onOpen = { onOpenGeneratedFile(message.generatedFile) },
                            onSave = { onSaveGeneratedFile(message.generatedFile) },
                            onShare = { onShareGeneratedFile(message.generatedFile) }
                        )
                    }

                    if (message.actionPlan != null) {
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

                    if (message.groundingSources.isNotEmpty()) {
                        val context = LocalContext.current
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            message.groundingSources.take(5).forEachIndexed { idx, src ->
                                Row(
                                    modifier = Modifier
                                        .frostedPill(cornerRadius = 14.dp, active = false)
                                        .clickable {
                                            if (GeminiMarkdownParser.isSafeWebUrl(src.uri)) {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(Intent.ACTION_VIEW, Uri.parse(src.uri))
                                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    )
                                                }
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                        .testTag("source_chip_${message.id}_$idx"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Link,
                                        contentDescription = "Open source ${src.title}",
                                        tint = glass.accentBlue,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = src.title.take(28),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
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

                    if (!message.isError && message.text.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onSaveToMemory)
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                                .testTag("save_memory_${message.id}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.BookmarkAdd,
                                    contentDescription = stringResource(R.string.action_save_to_memory),
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Save",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (!message.modelBadge.isNullOrBlank()) {
                        Text(
                            text = message.modelBadge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
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
    val glass = LocalAgentGlassColors.current
    val (riskBg, riskFg) = when (plan.riskLevel) {
        RiskLevel.LOW -> semantic.lowRiskContainer to semantic.lowRiskContent
        RiskLevel.MEDIUM -> semantic.mediumRiskContainer to semantic.mediumRiskContent
        RiskLevel.HIGH -> semantic.highRiskContainer to semantic.highRiskContent
    }
    val borderOverride = when {
        isBlockedBySafety -> semantic.highRiskContent
        status == ApprovalStatus.RUNNING -> glass.accentBlue
        status == ApprovalStatus.COMPLETED -> semantic.successContent.copy(alpha = 0.6f)
        else -> null
    }
    val isRunningOrDone = status == ApprovalStatus.RUNNING || status == ApprovalStatus.COMPLETED

    Column(
        modifier = modifier
            .fillMaxWidth()
            .nestedGlassCard(cornerRadius = 18.dp, borderColorOverride = borderOverride)
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
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = glass.accentBlue,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .testTag("approve_run_button_${plan.actionId}")
                ) {
                    Text("Approve")
                }

                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = glass.pillFill,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    border = BorderStroke(1.dp, glass.nestedCardBorder),
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
    sessionId: String,
    canUseWebSearch: Boolean,
    googleSearchEnabled: Boolean,
    isSending: Boolean,
    isListening: Boolean,
    voiceLanguage: VoiceLanguage,
    voicePartialText: String,
    voiceFinalEvent: Pair<Long, String>?,
    onToggleMic: () -> Unit,
    onStopMicSilently: () -> Unit,
    onOpenLiveVoice: () -> Unit,
    onToggleGoogleSearch: () -> Unit,
    onToggleVoiceLanguage: () -> Unit,
    onConsumeVoiceFinal: () -> Unit,
    onSend: (String) -> Unit
) {
    // STRICTLY ISOLATED TYPING & FOCUS STATE: typing never recomposes chat list or top bar.
    var draft by rememberSaveable { mutableStateOf("") }
    var draftPopulatedByVoice by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    val sendInteraction = remember { MutableInteractionSource() }
    val micInteraction = remember { MutableInteractionSource() }

    // Strict Chat Session Isolation: immediately clear text input on New Chat or chat switch
    LaunchedEffect(sessionId) {
        draft = ""
        draftPopulatedByVoice = false
    }

    // Stream patient accumulated voice transcript directly into the live input box
    LaunchedEffect(isListening, voicePartialText) {
        if (isListening && voicePartialText.isNotBlank()) {
            draft = voicePartialText
            draftPopulatedByVoice = true
        } else if (!isListening && voicePartialText.isEmpty() && draftPopulatedByVoice && voiceFinalEvent == null) {
            // Auto-send committed the accumulated voice text; clear the live draft cleanly
            draft = ""
            draftPopulatedByVoice = false
        }
    }

    LaunchedEffect(voiceFinalEvent?.first) {
        val text = voiceFinalEvent?.second
        if (!text.isNullOrBlank()) {
            draft = text
            draftPopulatedByVoice = false
            onConsumeVoiceFinal()
        }
    }

    val focusProgress = animateFloatAsState(
        targetValue = if (isFocused || draft.isNotEmpty() || isListening) 1f else 0f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "input_focus_border"
    )

    val glass = LocalAgentGlassColors.current
    val capsuleBg = glass.inputFill
    val borderStart = glass.borderStart
    val borderEnd = glass.borderEnd
    val accentBlue = glass.accentBlue

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
                .clip(RoundedCornerShape(24.dp))
                .drawBehind {
                    val corner = CornerRadius(24.dp.toPx(), 24.dp.toPx())
                    drawRoundRect(color = capsuleBg, cornerRadius = corner)

                    val p = focusProgress.value
                    val strokePx = (1.dp + 0.5.dp * p).toPx()
                    val halfStroke = strokePx / 2f
                    val insetSize = Size(size.width - strokePx, size.height - strokePx)
                    val topLeft = Offset(halfStroke, halfStroke)

                    val topLeftColor = lerp(borderStart, accentBlue.copy(alpha = 0.85f), p)
                    val bottomRightColor = lerp(borderEnd, accentBlue.copy(alpha = 0.28f), p)
                    drawRoundRect(
                        brush = Brush.linearGradient(colors = listOf(topLeftColor, bottomRightColor)),
                        topLeft = topLeft,
                        size = insetSize,
                        cornerRadius = corner,
                        style = Stroke(width = strokePx)
                    )
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 8.dp, top = 11.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 2.dp, end = 8.dp, top = 2.dp, bottom = 2.dp)
                ) {
                    if (draft.isEmpty()) {
                        Text(
                            text = if (isListening) {
                                when (voiceLanguage) {
                                    VoiceLanguage.TE_IN -> "వింటున్నాను (తెలుగు)…"
                                    VoiceLanguage.EN_IN -> "Listening (EN-IN)…"
                                }
                            } else {
                                "Ask anything or command your phone…"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isListening) {
                                accentBlue
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                            }
                        )
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = {
                            draft = it
                            draftPopulatedByVoice = false
                        },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(accentBlue),
                        maxLines = 5,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { isFocused = it.isFocused }
                            .testTag("chat_input_field")
                    )
                }

                // Unified Smart Agent controls: Web Search & EN/తెలుగు on left; Live Voice, Mic, Send on right
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Web Search (Live Facts) toggle for Built-in Gemini & Gemini Native
                        if (canUseWebSearch) {
                            Row(
                                modifier = Modifier
                                    .height(34.dp)
                                    .frostedPill(cornerRadius = 17.dp, active = googleSearchEnabled)
                                    .clickable(onClick = onToggleGoogleSearch)
                                    .padding(horizontal = 10.dp)
                                    .testTag("google_search_toggle"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Public,
                                    contentDescription = stringResource(R.string.action_toggle_google_search),
                                    tint = if (googleSearchEnabled) accentBlue
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Web",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                    color = if (googleSearchEnabled) accentBlue
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // One-tap EN (India) / తెలుగు voice language pill
                        Box(
                            modifier = Modifier
                                .height(34.dp)
                                .frostedPill(cornerRadius = 17.dp, active = false)
                                .clickable(onClick = onToggleVoiceLanguage)
                                .padding(horizontal = 10.dp)
                                .testTag("voice_language_toggle"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = voiceLanguage.shortChipLabel,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 1-tap Live Voice + 3D Agent conversation launcher
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .frostedPill(cornerRadius = 18.dp, active = true)
                                .clickable(onClick = onOpenLiveVoice)
                                .testTag("open_live_voice_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = stringResource(R.string.action_open_live_voice),
                                tint = accentBlue,
                                modifier = Modifier.size(18.dp)
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
                                    if (isListening) {
                                        onStopMicSilently()
                                    }
                                    draft = ""
                                    draftPopulatedByVoice = false
                                    onSend(toSend)
                                }
                            },
                            enabled = draft.isNotBlank() && !isSending,
                            interactionSource = sendInteraction,
                            shape = CircleShape,
                            colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                                containerColor = accentBlue,
                                contentColor = Color.White,
                                disabledContainerColor = glass.pillFill,
                                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            ),
                            modifier = Modifier
                                .size(38.dp)
                                .pressScale(sendInteraction)
                                .testTag("send_message_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Send,
                                contentDescription = stringResource(R.string.action_send),
                                modifier = Modifier.size(17.dp)
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
    val glass = LocalAgentGlassColors.current
    val pulseColor = MaterialTheme.colorScheme.error
    val bgColor = if (isListening) {
        MaterialTheme.colorScheme.error
    } else {
        glass.pillFill
    }
    val fgColor = if (isListening) {
        MaterialTheme.colorScheme.onError
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val borderColor = if (isListening) {
        Color.Transparent
    } else {
        glass.borderStart
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
private fun ProviderPickerSheetContent(
    providers: List<AiProvider>,
    activeProviderId: String?,
    isUsingBuiltInGemini: Boolean,
    selectedBuiltInModel: GeminiBuiltInModel,
    onSelectBuiltIn: (GeminiBuiltInModel) -> Unit,
    onSelect: (String) -> Unit,
    onManageProviders: () -> Unit
) {
    val glass = LocalAgentGlassColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Built-in Gemini AI (No API Key)",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        GeminiBuiltInModel.entries.forEach { model ->
            val isSelected = isUsingBuiltInGemini && selectedBuiltInModel == model
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassPanel(
                        cornerRadius = 18.dp,
                        fillColor = if (isSelected) glass.accentBlue.copy(alpha = 0.22f) else glass.panelFill,
                        borderColorOverride = if (isSelected) glass.accentBlue.copy(alpha = 0.55f) else null
                    )
                    .clickable { onSelectBuiltIn(model) }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .testTag("pick_builtin_${model.name.lowercase()}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = model.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Active",
                        tint = glass.accentBlue
                    )
                }
            }
        }

        if (providers.isNotEmpty()) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                thickness = 0.5.dp,
                color = glass.hairlineDivider
            )
            Text(
                text = "Your Custom Providers",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            providers.forEach { provider ->
                val isSelected = !isUsingBuiltInGemini && provider.id == activeProviderId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassPanel(
                            cornerRadius = 18.dp,
                            fillColor = if (isSelected) glass.accentBlue.copy(alpha = 0.22f) else glass.panelFill,
                            borderColorOverride = if (isSelected) glass.accentBlue.copy(alpha = 0.55f) else null
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
                            tint = glass.accentBlue
                        )
                    }
                }
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            thickness = 0.5.dp,
            color = glass.hairlineDivider
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
private fun GeneratedFileCard(
    attachment: GeneratedFileAttachment,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit
) {
    val glass = LocalAgentGlassColors.current
    val semantic = LocalAgentSemanticColors.current
    val format = remember(attachment.fileType) {
        com.example.files.GeneratedFileFormat.fromString(attachment.fileType)
    }
    val (badgeBg, badgeFg) = when (format) {
        com.example.files.GeneratedFileFormat.PDF -> semantic.pdfBadgeContainer to semantic.pdfBadgeContent
        com.example.files.GeneratedFileFormat.XLSX -> semantic.xlsxBadgeContainer to semantic.xlsxBadgeContent
        com.example.files.GeneratedFileFormat.DOCX -> semantic.docxBadgeContainer to semantic.docxBadgeContent
        com.example.files.GeneratedFileFormat.CSV -> semantic.csvBadgeContainer to semantic.csvBadgeContent
    }
    val sizeLabel = remember(attachment.sizeBytes) {
        com.example.files.FileBlueprintParser.formatFileSize(attachment.sizeBytes)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .nestedGlassCard(cornerRadius = 18.dp)
            .padding(14.dp)
            .testTag("generated_file_card_${format.id}"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(width = 50.dp, height = 44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(badgeBg)
                    .border(1.dp, badgeFg.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.Description,
                        contentDescription = "${format.badgeLabel} file",
                        tint = badgeFg,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = format.badgeLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = badgeFg
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = attachment.fileName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${format.spokenLabel} document · $sizeLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onOpen,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = glass.accentBlue,
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("file_open_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.OpenInNew,
                    contentDescription = "Open file",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "Open",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
            }

            OutlinedButton(
                onClick = onSave,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = glass.pillFill,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                border = BorderStroke(1.dp, glass.nestedCardBorder),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("file_save_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = "Save to Downloads",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "Save",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
            }

            OutlinedButton(
                onClick = onShare,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = glass.pillFill,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                border = BorderStroke(1.dp, glass.nestedCardBorder),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("file_share_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Share,
                    contentDescription = "Share file",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "Share",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun EditStepDialog(
    plan: ActionPlan,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    val glass = LocalAgentGlassColors.current
    var draft by remember(plan.actionId) {
        mutableStateOf(
            plan.editedInstruction
                ?: plan.steps.mapIndexed { idx, s -> "${idx + 1}. $s" }.joinToString("\n")
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = glass.dialogFill,
        shape = RoundedCornerShape(24.dp),
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
