package com.example

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.LocalAgentSemanticColors
import com.example.ui.theme.PersonalAgentTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private data class PendingOverlayApproval(
    val plan: ActionPlan,
    val toolCall: RoutedToolCall,
    val conversationHistory: List<ChatMessage>
)

class VoiceOverlayActivity : ComponentActivity() {

    private var launchNonce by mutableStateOf(0L)
    private var launchWakeTriggered by mutableStateOf(false)
    private var launchPrefilledCommand by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readLaunchExtras(intent)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        )

        setContent {
            val storage = remember { LocalStorage(applicationContext) }
            val initialPersisted = remember {
                PersistedAppState(
                    sessionId = "",
                    providers = emptyList(),
                    providerResolvedUrls = emptyMap(),
                    chatMessages = emptyList(),
                    approvalHistory = emptyList(),
                    safetyRules = SafetyRules(),
                    voiceSettings = VoiceSettings(),
                    quickActionChips = DefaultQuickChips.items,
                    themePreference = ThemePreference.SYSTEM,
                    agentMode = AgentMode.AGENT_CONTROL,
                    connectionTestPassed = false
                )
            }
            val persisted by storage.stateFlow.collectAsStateWithLifecycle(initialValue = initialPersisted)

            PersonalAgentTheme(themePreference = persisted.themePreference) {
                VoiceOverlaySheetContent(
                    storage = storage,
                    persistedState = persisted,
                    launchNonce = launchNonce,
                    wakeTriggered = launchWakeTriggered,
                    prefilledCommand = launchPrefilledCommand,
                    onCloseOverlay = { closeQuietly() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readLaunchExtras(intent)
    }

    override fun onStart() {
        super.onStart()
        WakeWordListeningService.setExternalMicBusy(true)
    }

    override fun onStop() {
        WakeWordListeningService.setExternalMicBusy(false)
        super.onStop()
    }

    override fun onDestroy() {
        WakeWordListeningService.setExternalMicBusy(false)
        super.onDestroy()
    }

    private fun readLaunchExtras(srcIntent: Intent?) {
        val isAssist = srcIntent?.action == Intent.ACTION_ASSIST
        launchWakeTriggered = isAssist || (srcIntent?.getBooleanExtra(EXTRA_WAKE_TRIGGERED, false) == true)
        launchPrefilledCommand = srcIntent?.getStringExtra(EXTRA_PREFILLED_COMMAND)?.trim()?.takeIf { it.isNotBlank() }
        launchNonce = System.currentTimeMillis()
    }

    private fun closeQuietly() {
        WakeWordListeningService.setExternalMicBusy(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        } else {
            finish()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        const val EXTRA_WAKE_TRIGGERED = "extra_wake_triggered"
        const val EXTRA_PREFILLED_COMMAND = "extra_prefilled_command"
    }
}

@Composable
private fun VoiceOverlaySheetContent(
    storage: LocalStorage,
    persistedState: PersistedAppState,
    launchNonce: Long,
    wakeTriggered: Boolean,
    prefilledCommand: String?,
    onCloseOverlay: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val networkClient = remember { NetworkClient() }

    var inputText by remember { mutableStateOf("") }
    var partialText by remember { mutableStateOf("") }
    var statusNote by remember { mutableStateOf<String?>(null) }
    var replyText by remember { mutableStateOf<String?>(null) }
    var errorNote by remember { mutableStateOf<String?>(null) }
    var blockedReason by remember { mutableStateOf<String?>(null) }
    var isRouting by remember { mutableStateOf(false) }
    var pendingApproval by remember { mutableStateOf<PendingOverlayApproval?>(null) }
    var lastHandledLaunchNonce by remember { mutableStateOf(0L) }

    val latestVoiceSettings by rememberUpdatedState(persistedState.voiceSettings)
    val voiceLanguage = persistedState.voiceSettings.voiceLanguage

    fun dispatchToAccessibilityAndClose(
        plan: ActionPlan,
        toolCall: RoutedToolCall,
        history: List<ChatMessage>,
        blockSensitive: Boolean,
        activeProvider: AiProvider?,
        voiceSettings: VoiceSettings
    ) {
        val service = AgentAccessibilityService.getInstance()
        onCloseOverlay()

        when (toolCall.tool) {
            ToolName.PLAY_YOUTUBE_VIDEO -> {
                val query = toolCall.query?.takeIf { it.isNotBlank() } ?: plan.title
                if (service != null) {
                    service.startPlayYouTubeVideo(
                        plan = plan,
                        query = query,
                        provider = activeProvider,
                        blockSensitive = blockSensitive,
                        conversationHistory = history
                    )
                } else {
                    val res = NativeToolExecutor.searchYouTube(context.applicationContext, query)
                    val msg = if (res.success) {
                        ConfirmationFormatter.formatSuccess(res.summary)
                    } else {
                        ConfirmationFormatter.formatFailure(res.summary)
                    }
                    AgentSpeechFeedback.announceOutcome(context.applicationContext, msg, res.success, voiceSettings)
                }
            }

            ToolName.SEND_MESSAGE -> {
                val contact = toolCall.contactOrNumber.orEmpty()
                val app = toolCall.messagingApp ?: "whatsapp"
                val text = toolCall.messageText.orEmpty()
                if (service != null) {
                    service.startSendMessage(
                        plan = plan,
                        contactOrNumber = contact,
                        messagingApp = app,
                        messageText = text,
                        blockSensitive = blockSensitive
                    )
                } else {
                    val res = NativeToolExecutor.openMessageComposer(context.applicationContext, contact, app, text)
                    val msg = if (res.success) {
                        ConfirmationFormatter.formatSuccess(res.summary)
                    } else {
                        ConfirmationFormatter.formatFailure(res.summary)
                    }
                    AgentSpeechFeedback.announceOutcome(context.applicationContext, msg, res.success, voiceSettings)
                }
            }

            ToolName.TAP_ON_SCREEN -> {
                val target = toolCall.targetText?.takeIf { it.isNotBlank() } ?: plan.title
                service?.startTapOnScreen(
                    plan = plan,
                    targetText = target,
                    blockSensitive = blockSensitive,
                    initialDelayMs = 240L
                )
            }

            ToolName.SCROLL_SCREEN -> {
                service?.startDirectScroll(plan, toolCall.direction ?: "down")
            }

            ToolName.UI_AUTOMATE -> {
                if (service != null && activeProvider != null) {
                    val goal = toolCall.goal?.takeIf { it.isNotBlank() } ?: plan.title
                    service.startAiUiAutomation(
                        plan = plan,
                        goal = goal,
                        provider = activeProvider,
                        blockSensitive = blockSensitive,
                        maxSteps = 15,
                        conversationHistory = history,
                        initialDelayMs = 280L
                    )
                }
            }

            else -> {
                if (service != null) {
                    service.startDirectAction(plan, toolCall)
                } else {
                    val res = NativeToolExecutor.executeDirectTool(context.applicationContext, toolCall)
                    val msg = if (res.success) {
                        ConfirmationFormatter.formatSuccess(res.summary)
                    } else {
                        ConfirmationFormatter.formatFailure(res.summary)
                    }
                    AgentSpeechFeedback.announceOutcome(context.applicationContext, msg, res.success, voiceSettings)
                }
            }
        }
    }

    fun submitCommand(rawCommand: String) {
        val rawTrimmed = rawCommand.trim()
        if (rawTrimmed.isBlank() || isRouting) return

        // Strip wake phrase cleanly if user spoke "Hey Agent, open YouTube" inside the overlay too
        val wakeCheck = WakeWordMatcher.extractWakeMatch(rawTrimmed, latestVoiceSettings.wakePhrase)
        val command = if (wakeCheck != null) {
            wakeCheck.inlineCommand?.takeIf { it.isNotBlank() } ?: "Hi"
        } else {
            rawTrimmed
        }

        errorNote = null
        blockedReason = null
        replyText = null
        partialText = ""
        inputText = ""
        isRouting = true
        statusNote = "Understanding command…"

        scope.launch {
            val latestState = storage.readLatestState()
            val activeProvider = latestState.providers.firstOrNull { it.isDefault }
                ?: latestState.providers.firstOrNull()
            val userMsg = ChatMessage(sender = MessageSender.USER, text = command)
            val historyWithUser = latestState.chatMessages + userMsg
            storage.appendChatAndApproval(listOf(userMsg))

            // 1. On-device financial/UPI/OTP/password hard-block check
            if (latestState.safetyRules.blockSensitiveFinancial) {
                val violation = withContext(Dispatchers.Default) {
                    SafetyGuardrail.detectSensitiveViolation(command)
                }
                if (violation != null) {
                    val blockedActionId = UUID.randomUUID().toString()
                    val blockedMsg = ChatMessage(
                        sender = MessageSender.AGENT,
                        text = "Blocked on-device by your Safety Guardrail ($violation). Banking, UPI, OTP, and password actions are hard-blocked.",
                        isBlockedBySafety = true,
                        blockedReason = violation,
                        approvalStatus = ApprovalStatus.BLOCKED,
                        excludeFromContext = true
                    )
                    val blockedRec = ApprovalRecord(
                        actionId = blockedActionId,
                        title = "Blocked Request: ${command.take(56)}",
                        riskLevel = RiskLevel.HIGH,
                        steps = listOf("Blocked locally ($violation)"),
                        status = ApprovalStatus.BLOCKED,
                        notes = "Blocked by on-device financial/credential guardrail."
                    )
                    storage.appendChatAndApproval(listOf(blockedMsg), blockedRec)
                    isRouting = false
                    statusNote = null
                    blockedReason = violation
                    return@launch
                }
            }

            // 2. Local deterministic fast router first
            val localTool = withContext(Dispatchers.Default) {
                HybridToolRouting.matchDirectLocalTool(command)
            }

            val resolvedTool: RoutedToolCall? = if (localTool != null) {
                localTool
            } else {
                if (activeProvider == null) {
                    isRouting = false
                    statusNote = null
                    errorNote = "Add an AI provider in My Personal Agent -> Settings to handle complex voice commands."
                    return@launch
                }
                val installed = InstalledAppCache.getCached().ifEmpty {
                    InstalledAppCache.refresh(context.applicationContext)
                }.map { it.displayName }

                when (
                    val routed = networkClient.routeHybridTool(
                        provider = activeProvider,
                        userGoal = command,
                        installedAppSample = installed,
                        safetyRules = latestState.safetyRules,
                        conversationHistory = historyWithUser
                    )
                ) {
                    is ToolRouteApiResult.Error -> {
                        val errMsg = ChatMessage(
                            sender = MessageSender.AGENT,
                            text = routed.message,
                            isError = true,
                            excludeFromContext = true
                        )
                        storage.appendChatAndApproval(listOf(errMsg))
                        isRouting = false
                        statusNote = null
                        errorNote = routed.message
                        return@launch
                    }
                    is ToolRouteApiResult.Success -> routed.toolCall
                }
            }

            if (resolvedTool == null) {
                isRouting = false
                statusNote = null
                errorNote = "Could not understand that command."
                return@launch
            }

            // 2b. Natural greetings & conversational replies — reply warmly inline, NEVER open an app!
            if (resolvedTool.tool == ToolName.REPLY_TO_USER) {
                val warmReply = resolvedTool.replyMessage
                    ?.takeIf { it.isNotBlank() }
                    ?: "Hello! How can I help you today?"
                val replyMsg = ChatMessage(
                    sender = MessageSender.AGENT,
                    text = warmReply
                )
                storage.appendChatAndApproval(listOf(replyMsg))
                isRouting = false
                statusNote = null
                replyText = warmReply
                if (latestState.voiceSettings.speakRepliesAloud) {
                    AgentSpeechFeedback.speak(
                        context = context.applicationContext,
                        text = warmReply,
                        language = latestState.voiceSettings.voiceLanguage
                    )
                }
                return@launch
            }

            // 3. Re-verify safety guardrail on routed tool arguments
            if (latestState.safetyRules.blockSensitiveFinancial) {
                val violation = withContext(Dispatchers.Default) {
                    SafetyGuardrail.detectSensitiveViolation(
                        resolvedTool.title,
                        resolvedTool.appName,
                        resolvedTool.query,
                        resolvedTool.messageText,
                        resolvedTool.targetText,
                        resolvedTool.goal,
                        *resolvedTool.steps.toTypedArray()
                    )
                }
                if (violation != null) {
                    val blockedActionId = UUID.randomUUID().toString()
                    val blockedMsg = ChatMessage(
                        sender = MessageSender.AGENT,
                        text = "Blocked on-device by your Safety Guardrail ($violation).",
                        isBlockedBySafety = true,
                        blockedReason = violation,
                        approvalStatus = ApprovalStatus.BLOCKED,
                        excludeFromContext = true
                    )
                    val blockedRec = ApprovalRecord(
                        actionId = blockedActionId,
                        title = resolvedTool.title.ifBlank { command.take(56) },
                        riskLevel = RiskLevel.HIGH,
                        steps = resolvedTool.steps,
                        status = ApprovalStatus.BLOCKED,
                        notes = "Blocked by on-device financial/credential guardrail."
                    )
                    storage.appendChatAndApproval(listOf(blockedMsg), blockedRec)
                    isRouting = false
                    statusNote = null
                    blockedReason = violation
                    AgentSpeechFeedback.announceOutcome(
                        context = context.applicationContext,
                        message = "Blocked by your safety guardrail: $violation",
                        isSuccess = false,
                        voiceSettings = latestState.voiceSettings,
                        showToastWhenOutsideApp = false
                    )
                    return@launch
                }
            }

            val plan = ActionPlan(
                actionId = UUID.randomUUID().toString(),
                title = resolvedTool.title.ifBlank { command.take(56) },
                riskLevel = resolvedTool.riskLevel,
                steps = resolvedTool.steps.ifEmpty { listOf(resolvedTool.reason.ifBlank { command.take(80) }) },
                isDemo = false,
                routedToolJson = resolvedTool.toJsonString()
            )

            // Check whether AccessibilityService is needed for this tool
            val needsAccessibility = resolvedTool.tool in setOf(
                ToolName.TAP_ON_SCREEN,
                ToolName.SCROLL_SCREEN,
                ToolName.PRESS_BACK,
                ToolName.PRESS_HOME,
                ToolName.OPEN_RECENTS,
                ToolName.OPEN_NOTIFICATIONS,
                ToolName.OPEN_QUICK_SETTINGS,
                ToolName.UI_AUTOMATE,
                ToolName.SEND_MESSAGE
            )
            if (needsAccessibility && AgentAccessibilityService.getInstance() == null) {
                val err = ConfirmationFormatter.formatFailure(
                    "Enable Accessibility Service in My Personal Agent -> Setup & Test first."
                )
                isRouting = false
                statusNote = null
                errorNote = err
                AgentSpeechFeedback.announceOutcome(
                    context = context.applicationContext,
                    message = err,
                    isSuccess = false,
                    voiceSettings = latestState.voiceSettings,
                    showToastWhenOutsideApp = false
                )
                return@launch
            }

            // Calls, Messages, and Medium/High UI automation ALWAYS show the compact Approval Card inside this overlay.
            // Safe quick overlay commands (tap_on_screen, scroll_screen, volume, system nav, play_youtube_video, set_alarm, open_maps)
            // auto-run immediately!
            val shouldAutoRun = when {
                resolvedTool.tool == ToolName.CALL_CONTACT || resolvedTool.tool == ToolName.SEND_MESSAGE -> false
                resolvedTool.isLowRiskAutoRunnableMediaOrAlarm -> true
                resolvedTool.tool == ToolName.UI_AUTOMATE -> resolvedTool.riskLevel == RiskLevel.LOW
                resolvedTool.tool == ToolName.OPEN_APP ->
                    !latestState.safetyRules.alwaysAskApproval || latestState.safetyRules.instantRunLowRiskAppLaunch
                else -> !latestState.safetyRules.alwaysAskApproval
            }

            val initialStatus = if (shouldAutoRun) ApprovalStatus.RUNNING else ApprovalStatus.PENDING
            val cardMsg = ChatMessage(
                sender = MessageSender.AGENT,
                text = if (shouldAutoRun) {
                    "Running on-device…"
                } else {
                    "Review the steps below and tap Approve to run on your phone."
                },
                actionPlan = plan,
                approvalStatus = initialStatus,
                activeStepIndex = if (shouldAutoRun) 0 else -1
            )
            val record = ApprovalRecord(
                actionId = plan.actionId,
                title = plan.title,
                riskLevel = plan.riskLevel,
                steps = plan.steps,
                status = initialStatus
            )
            storage.appendChatAndApproval(listOf(cardMsg), record)

            isRouting = false
            statusNote = null

            if (shouldAutoRun) {
                dispatchToAccessibilityAndClose(
                    plan = plan,
                    toolCall = resolvedTool,
                    history = historyWithUser,
                    blockSensitive = latestState.safetyRules.blockSensitiveFinancial,
                    activeProvider = activeProvider,
                    voiceSettings = latestState.voiceSettings
                )
            } else {
                pendingApproval = PendingOverlayApproval(
                    plan = plan,
                    toolCall = resolvedTool,
                    conversationHistory = historyWithUser
                )
            }
        }
    }

    val speechFallbackLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (spoken.isNotBlank()) {
                submitCommand(spoken)
            }
        }
    }

    val voiceController = remember(context) {
        VoiceController(
            context = context,
            onPartialText = { partial ->
                partialText = partial
                errorNote = null
            },
            onFinalText = { finalText ->
                partialText = ""
                submitCommand(finalText)
            },
            onRequestFallbackIntent = { intent ->
                runCatching { speechFallbackLauncher.launch(intent) }
                    .onFailure {
                        errorNote = "Voice recognition is unavailable on this device. Type your command below."
                    }
            },
            onVoiceError = { err ->
                partialText = ""
                errorNote = err
            }
        )
    }

    val isListening by voiceController.isListening.collectAsStateWithLifecycle()

    DisposableEffect(voiceController) {
        onDispose { voiceController.destroy() }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            errorNote = null
            voiceController.startListening(latestVoiceSettings.voiceLanguage)
        } else {
            errorNote = "Microphone permission denied. Type your command below or allow Mic in Setup & Test."
        }
    }

    // Handle initial launch & hands-free wake-word response ("Hey Agent" -> chimes, replies aloud, then listens!)
    LaunchedEffect(launchNonce) {
        if (launchNonce == 0L || launchNonce == lastHandledLaunchNonce) return@LaunchedEffect
        lastHandledLaunchNonce = launchNonce

        val latest = storage.readLatestState().voiceSettings
        if (!prefilledCommand.isNullOrBlank()) {
            // One-shot command like "Hey Agent, open YouTube" — execute immediately!
            submitCommand(prefilledCommand)
            return@LaunchedEffect
        }

        val startMicNow: () -> Unit = {
            val hasMic = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasMic) {
                voiceController.startListening(latest.voiceLanguage)
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        if (wakeTriggered && latest.speakRepliesAloud) {
            val greeting = if (latest.voiceLanguage == VoiceLanguage.TE_IN) {
                "చెప్పండి, వింటున్నాను"
            } else {
                "Yes, I'm listening"
            }
            replyText = greeting
            AgentSpeechFeedback.speak(
                context = context.applicationContext,
                text = greeting,
                language = latest.voiceLanguage,
                onDone = { startMicNow() }
            )
        } else {
            startMicNow()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    voiceController.stopListening()
                    onCloseOverlay()
                }
            )
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* consume clicks inside the sheet */ }
                ),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Drag handle pill
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )

                // Top Row: Sparkle + Status + EN/తెలుగు Switcher + Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        OverlaySparkleIcon(active = isListening || isRouting)
                        Text(
                            text = when {
                                pendingApproval != null -> "Review & Approve"
                                isRouting -> statusNote ?: "Thinking…"
                                isListening -> if (voiceLanguage == VoiceLanguage.TE_IN) "వింటున్నాను…" else "Listening…"
                                else -> stringResource(R.string.voice_overlay_title)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .clickable {
                                    val next = if (voiceLanguage == VoiceLanguage.EN_IN) {
                                        VoiceLanguage.TE_IN
                                    } else {
                                        VoiceLanguage.EN_IN
                                    }
                                    scope.launch {
                                        storage.saveVoiceSettings(latestVoiceSettings.copy(voiceLanguage = next))
                                    }
                                    if (isListening) {
                                        voiceController.startListening(next)
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .testTag("overlay_voice_lang_toggle"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = voiceLanguage.shortChipLabel,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }

                        IconButton(
                            onClick = {
                                voiceController.stopListening()
                                onCloseOverlay()
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("overlay_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Close voice overlay",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Live streaming speech transcript
                if (partialText.isNotBlank()) {
                    Text(
                        text = partialText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }

                // Inline Safety Hard-Block Banner
                blockedReason?.let { reason ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Block,
                            contentDescription = "Blocked by safety rule",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Blocked on-device ($reason). Financial, UPI, OTP, and password actions are protected.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                // Inline conversational reply (greetings / questions)
                replyText?.let { reply ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .testTag("overlay_reply_card"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = reply,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                // Error feedback banner
                errorNote?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Compact Approval Card (shown right over the foreground app when Approval is needed!)
                pendingApproval?.let { approval ->
                    CompactOverlayApprovalCard(
                        plan = approval.plan,
                        onApprove = {
                            scope.launch {
                                val latest = storage.readLatestState()
                                val provider = latest.providers.firstOrNull { it.isDefault }
                                    ?: latest.providers.firstOrNull()
                                storage.updateActionStatusInStorage(
                                    actionId = approval.plan.actionId,
                                    status = ApprovalStatus.RUNNING,
                                    completedSteps = 0,
                                    activeStep = 0
                                )
                                pendingApproval = null
                                dispatchToAccessibilityAndClose(
                                    plan = approval.plan,
                                    toolCall = approval.toolCall,
                                    history = approval.conversationHistory,
                                    blockSensitive = latest.safetyRules.blockSensitiveFinancial,
                                    activeProvider = provider,
                                    voiceSettings = latest.voiceSettings
                                )
                            }
                        },
                        onCancel = {
                            scope.launch {
                                storage.updateActionStatusInStorage(
                                    actionId = approval.plan.actionId,
                                    status = ApprovalStatus.CANCELLED,
                                    completedSteps = 0,
                                    activeStep = -1,
                                    notes = "Cancelled from quick overlay."
                                )
                                pendingApproval = null
                                onCloseOverlay()
                            }
                        }
                    )
                }

                // Quick Text-Type + Mic Row (hidden while reviewing an approval card)
                if (pendingApproval == null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                            .border(
                                width = 1.dp,
                                color = if (isListening) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(24.dp)
                            )
                            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (inputText.isBlank()) {
                                Text(
                                    text = stringResource(R.string.voice_overlay_type_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            BasicTextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(
                                    onSend = {
                                        if (inputText.isNotBlank()) {
                                            voiceController.stopListening()
                                            submitCommand(inputText)
                                        }
                                    }
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("overlay_command_input")
                            )
                        }

                        if (inputText.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    voiceController.stopListening()
                                    submitCommand(inputText)
                                },
                                enabled = !isRouting,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .testTag("overlay_send_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.Send,
                                    contentDescription = "Send command",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        } else {
                            OverlayMicButton(
                                isListening = isListening,
                                enabled = !isRouting,
                                onClick = {
                                    if (isListening) {
                                        voiceController.stopListening()
                                    } else {
                                        val hasMic = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (hasMic) {
                                            errorNote = null
                                            voiceController.startListening(voiceLanguage)
                                        } else {
                                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactOverlayApprovalCard(
    plan: ActionPlan,
    onApprove: () -> Unit,
    onCancel: () -> Unit
) {
    val semantic = LocalAgentSemanticColors.current
    val (badgeBg, badgeFg) = when (plan.riskLevel) {
        RiskLevel.LOW -> semantic.lowRiskContainer to semantic.lowRiskContent
        RiskLevel.MEDIUM -> semantic.mediumRiskContainer to semantic.mediumRiskContent
        RiskLevel.HIGH -> semantic.highRiskContainer to semantic.highRiskContent
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(14.dp)
            .testTag("overlay_approval_card"),
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
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(badgeBg)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = plan.riskLevel.name,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = badgeFg
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            plan.steps.take(4).forEachIndexed { idx, step ->
                Text(
                    text = "${idx + 1}. $step",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .testTag("overlay_cancel_button")
            ) {
                Text("Cancel")
            }
            Button(
                onClick = onApprove,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .testTag("overlay_approve_button")
            ) {
                Text("Approve")
            }
        }
    }
}

@Composable
private fun OverlayMicButton(
    isListening: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val pulseTransition = rememberInfiniteTransition(label = "overlayMicPulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.12f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "overlayMicScale"
    )

    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(44.dp)
            .graphicsLayer {
                scaleX = if (isListening) pulseScale else 1f
                scaleY = if (isListening) pulseScale else 1f
            }
            .clip(CircleShape)
            .background(
                if (isListening) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
            .testTag("overlay_mic_button")
    ) {
        Icon(
            imageVector = if (isListening) Icons.Rounded.Stop else Icons.Rounded.Mic,
            contentDescription = if (isListening) "Stop listening" else "Start voice command",
            tint = if (isListening) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun OverlaySparkleIcon(active: Boolean) {
    val gradientColors = LocalAgentSemanticColors.current.geminiGradient
    val transition = rememberInfiniteTransition(label = "overlaySparkle")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "overlaySparkleRot"
    )
    val scale by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "overlaySparkleScale"
    )

    Canvas(
        modifier = Modifier
            .size(22.dp)
            .graphicsLayer {
                rotationZ = if (active) rotation else 0f
                scaleX = if (active) scale else 1f
                scaleY = if (active) scale else 1f
            }
    ) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val path = Path().apply {
            moveTo(cx, 0f)
            quadraticTo(cx, cy, w, cy)
            quadraticTo(cx, cy, cx, h)
            quadraticTo(cx, cy, 0f, cy)
            quadraticTo(cx, cy, cx, 0f)
            close()
        }
        drawPath(
            path = path,
            brush = Brush.linearGradient(colors = gradientColors)
        )
    }
}
