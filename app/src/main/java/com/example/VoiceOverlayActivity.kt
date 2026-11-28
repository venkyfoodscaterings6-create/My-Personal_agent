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
import com.example.ui.theme.LocalAgentGlassColors
import com.example.ui.theme.LocalAgentSemanticColors
import com.example.ui.theme.PersonalAgentTheme
import com.example.ui.theme.frostedPill
import com.example.ui.theme.nestedGlassCard
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
    private var launchForegroundApp by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this, intent)
        captureForegroundAppBeforeOverlay()
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
                    agentMode = AgentMode.SMART_AUTO,
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
                    foregroundAppAtLaunch = launchForegroundApp,
                    onCloseOverlay = { closeQuietly() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureForegroundAppBeforeOverlay()
        readLaunchExtras(intent)
    }

    private fun captureForegroundAppBeforeOverlay() {
        val detected = AgentAccessibilityService.getCurrentForegroundApp(packageName)
        if (!detected.isNullOrBlank()) {
            launchForegroundApp = detected
        }
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
    foregroundAppAtLaunch: String?,
    onCloseOverlay: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val networkClient = remember { NetworkClient() }
    val sqlRepo = remember(context) {
        AgentSqlRepository(PersonalAgentDatabase.getInstance(context.applicationContext))
    }

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
    val latestForegroundApp by rememberUpdatedState(foregroundAppAtLaunch)
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
        if (service == null) {
            val errMsg = AgentReliabilityConstants.ACCESSIBILITY_OFF_MESSAGE
            errorNote = errMsg
            scope.launch {
                val chatErr = ChatMessage(
                    sender = MessageSender.AGENT,
                    text = errMsg,
                    isError = true,
                    excludeFromContext = true
                )
                ConversationRepository.appendMessages(
                    storage = storage,
                    newMessages = listOf(chatErr),
                    targetSessionId = plan.sessionId
                )
                if (plan.sessionId.isNotBlank()) {
                    sqlRepo.updateActionInSession(
                        sessionId = plan.sessionId,
                        actionId = plan.actionId,
                        status = ApprovalStatus.FAILED,
                        completedSteps = 0,
                        activeStep = -1,
                        appendChatMessage = chatErr
                    )
                }
                storage.updateActionStatusInStorage(
                    actionId = plan.actionId,
                    status = ApprovalStatus.FAILED,
                    completedSteps = 0,
                    activeStep = -1,
                    notes = errMsg,
                    targetSessionId = plan.sessionId
                )
            }
            AgentSpeechFeedback.announceOutcome(
                context = context.applicationContext,
                message = errMsg,
                isSuccess = false,
                voiceSettings = voiceSettings,
                showToastWhenOutsideApp = false
            )
            return
        }

        // Speak immediate short acknowledgment for actions that don't speak their own (never before calls or media)
        if (voiceSettings.speakRepliesAloud &&
            toolCall.tool !in setOf(
                ToolName.BOOK_RIDE,
                ToolName.PLAY_YOUTUBE_VIDEO,
                ToolName.SEND_MESSAGE,
                ToolName.OPEN_APP_AND_DO,
                ToolName.OPEN_APP_AND_PROMPT,
                ToolName.CALL_CONTACT
            )
        ) {
            val ack = HybridToolRouting.buildImmediateAckPhrase(toolCall)
            if (ack.isNotBlank()) {
                AgentSpeechFeedback.speak(
                    context = context.applicationContext,
                    text = ack,
                    voiceSettings = voiceSettings
                )
            }
        }

        onCloseOverlay()

        when (toolCall.tool) {
            ToolName.OPEN_APP_AND_DO, ToolName.OPEN_APP_AND_PROMPT -> {
                val app = toolCall.appName?.takeIf { it.isNotBlank() } ?: "App"
                val goal = toolCall.goal?.takeIf { it.isNotBlank() }
                    ?: toolCall.promptText?.takeIf { it.isNotBlank() }
                    ?: toolCall.query?.takeIf { it.isNotBlank() }
                    ?: plan.title
                service.startOpenAppAndDo(
                    plan = plan,
                    appName = app,
                    goal = goal,
                    provider = activeProvider,
                    blockSensitive = blockSensitive,
                    conversationHistory = history,
                    initialDelayMs = 220L
                )
            }

            ToolName.BOOK_RIDE -> {
                service.startBookRide(
                    plan = plan,
                    rideApp = toolCall.appName,
                    destination = toolCall.destination,
                    provider = activeProvider,
                    blockSensitive = blockSensitive,
                    conversationHistory = history,
                    initialDelayMs = 240L
                )
            }

            ToolName.PLAY_YOUTUBE_VIDEO -> {
                val query = toolCall.query?.takeIf { it.isNotBlank() } ?: plan.title
                service.startPlayYouTubeVideo(
                    plan = plan,
                    query = query,
                    provider = activeProvider,
                    blockSensitive = blockSensitive,
                    conversationHistory = history
                )
            }

            ToolName.SEND_MESSAGE -> {
                val contact = toolCall.contactOrNumber.orEmpty()
                val app = toolCall.messagingApp ?: "whatsapp"
                val text = toolCall.messageText.orEmpty()
                service.startSendMessage(
                    plan = plan,
                    contactOrNumber = contact,
                    messagingApp = app,
                    messageText = text,
                    blockSensitive = blockSensitive
                )
            }

            ToolName.TAP_ON_SCREEN -> {
                val target = toolCall.targetText?.takeIf { it.isNotBlank() } ?: plan.title
                service.startTapOnScreen(
                    plan = plan,
                    targetText = target,
                    blockSensitive = blockSensitive,
                    initialDelayMs = 240L
                )
            }

            ToolName.SCROLL_SCREEN -> {
                service.startDirectScroll(plan, toolCall.direction ?: "down")
            }

            ToolName.UI_AUTOMATE -> {
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

            else -> {
                service.startDirectAction(plan, toolCall)
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
            var activeVoiceSettings = latestVoiceSettings
            var activeSessionId = ""
            try {
                val latestState = storage.readLatestState()
                activeVoiceSettings = latestState.voiceSettings
                activeSessionId = latestState.sessionId
                val useBuiltIn = latestState.builtInGeminiSettings.useBuiltInGemini || latestState.providers.isEmpty()
                val activeProvider = if (useBuiltIn) {
                    null
                } else {
                    latestState.providers.firstOrNull { it.isDefault }
                        ?: latestState.providers.firstOrNull()
                }

                // Read live foreground app BEFORE routing so follow-up commands act directly on the open app
                val currentForegroundApp = latestForegroundApp
                    ?.takeIf { it.isNotBlank() && it != context.packageName }
                    ?: AgentAccessibilityService.getCurrentForegroundApp(context.packageName)

                // Always merge and append to the SINGLETON ConversationRepository under the active sessionId
                ConversationRepository.mergeWithPersisted(latestState.chatMessages, activeSessionId)
                val userMsg = ChatMessage(sender = MessageSender.USER, text = command)
                val historyWithUser = ConversationRepository.appendMessages(
                    storage = storage,
                    newMessages = listOf(userMsg),
                    targetSessionId = activeSessionId
                )
                sqlRepo.saveMessagesToSession(activeSessionId, listOf(userMsg))

                // 1. On-device financial/UPI/OTP/password hard-block check
                if (latestState.safetyRules.blockSensitiveFinancial) {
                    val violation = withContext(Dispatchers.Default) {
                        SafetyGuardrail.detectSensitiveViolation(command)
                    }
                    if (violation != null) {
                        val blockedActionId = UUID.randomUUID().toString()
                        val blockedText = "Blocked on-device by your Safety Guardrail ($violation). Banking, UPI, OTP, and password actions are hard-blocked."
                        val blockedMsg = ChatMessage(
                            sender = MessageSender.AGENT,
                            text = blockedText,
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
                        ConversationRepository.appendMessages(storage, listOf(blockedMsg), blockedRec, activeSessionId)
                        sqlRepo.saveMessagesToSession(activeSessionId, listOf(blockedMsg))
                        isRouting = false
                        statusNote = null
                        blockedReason = violation
                        AgentSpeechFeedback.announceOutcome(
                            context = context.applicationContext,
                            message = blockedText,
                            isSuccess = false,
                            voiceSettings = latestState.voiceSettings,
                            showToastWhenOutsideApp = false
                        )
                        return@launch
                    }
                }

                // 1b. Direct on-device SQL memory / task commands ("Remember that...", "Add task...")
                val sqlIntent = withContext(Dispatchers.Default) { SqlMemoryCommandParser.parse(command) }
                if (sqlIntent != null) {
                    val reply = when (sqlIntent) {
                        is SqlDirectIntent.SaveMemory -> {
                            sqlRepo.addMemory(sqlIntent.category, sqlIntent.title, sqlIntent.content, isPinned = true)
                            "Saved to your personal SQL memory: ${sqlIntent.content}"
                        }
                        is SqlDirectIntent.AddTask -> {
                            sqlRepo.addTask(sqlIntent.title, priority = sqlIntent.priority)
                            "Added to your tasks: ${sqlIntent.title}"
                        }
                        SqlDirectIntent.ListTasks -> {
                            val pending = sqlRepo.getPendingTasks(6)
                            if (pending.isEmpty()) "You have no open tasks right now."
                            else "Your open tasks are: " + pending.joinToString("; ") { it.title }
                        }
                        SqlDirectIntent.ListMemories -> {
                            val mems = sqlRepo.getTopMemories(5)
                            if (mems.isEmpty()) "No memories saved yet. Say 'Remember that...' to save one."
                            else "Here is what I remember: " + mems.joinToString("; ") { it.content }
                        }
                    }
                    val replyMsg = ChatMessage(sender = MessageSender.AGENT, text = reply, modelBadge = "SQL Memory")
                    ConversationRepository.appendMessages(storage, listOf(replyMsg), targetSessionId = activeSessionId)
                    sqlRepo.saveMessagesToSession(activeSessionId, listOf(replyMsg))
                    sqlRepo.recordInteractionLog(activeSessionId, "VOICE_OVERLAY", command, reply, toolUsed = "sql_memory")
                    isRouting = false
                    statusNote = null
                    replyText = reply
                    AgentSpeechFeedback.announceOutcome(
                        context = context.applicationContext,
                        message = reply,
                        isSuccess = true,
                        voiceSettings = latestState.voiceSettings,
                        showToastWhenOutsideApp = false
                    )
                    return@launch
                }

                // 2. Local deterministic fast router first (foreground-aware!)
                val localTool = withContext(Dispatchers.Default) {
                    HybridToolRouting.matchDirectLocalTool(command, currentForegroundApp)
                }

                val resolvedTool: RoutedToolCall? = if (localTool != null) {
                    localTool
                } else {
                    val installed = InstalledAppCache.getCached().ifEmpty {
                        InstalledAppCache.refresh(context.applicationContext)
                    }.map { it.displayName }

                    val routed = if (activeProvider != null) {
                        networkClient.routeHybridTool(
                            provider = activeProvider,
                            userGoal = command,
                            installedAppSample = installed,
                            safetyRules = latestState.safetyRules,
                            conversationHistory = historyWithUser,
                            currentForegroundApp = currentForegroundApp
                        )
                    } else {
                        val sqlCtx = if (latestState.builtInGeminiSettings.injectSqlMemory) {
                            sqlRepo.buildPersonalContextBlock()
                        } else {
                            ""
                        }
                        FirebaseAiService().routeHybridToolWithGemini(
                            userGoal = command,
                            installedAppSample = installed,
                            safetyRules = latestState.safetyRules,
                            conversationHistory = historyWithUser,
                            personalSqlContext = sqlCtx,
                            currentForegroundApp = currentForegroundApp
                        )
                    }

                    when (routed) {
                        is ToolRouteApiResult.Error -> {
                            val failText = ConfirmationFormatter.formatStrictFailure(routed.message)
                            val errMsg = ChatMessage(
                                sender = MessageSender.AGENT,
                                text = failText,
                                isError = true,
                                excludeFromContext = true
                            )
                            ConversationRepository.appendMessages(storage, listOf(errMsg), targetSessionId = activeSessionId)
                            sqlRepo.saveMessagesToSession(activeSessionId, listOf(errMsg))
                            isRouting = false
                            statusNote = null
                            errorNote = failText
                            AgentSpeechFeedback.announceOutcome(
                                context = context.applicationContext,
                                message = failText,
                                isSuccess = false,
                                voiceSettings = latestState.voiceSettings,
                                showToastWhenOutsideApp = false
                            )
                            return@launch
                        }
                        is ToolRouteApiResult.Success -> routed.toolCall
                    }
                }

                if (resolvedTool == null) {
                    val failText = ConfirmationFormatter.formatStrictFailure("Could not understand that command")
                    val errMsg = ChatMessage(
                        sender = MessageSender.AGENT,
                        text = failText,
                        isError = true,
                        excludeFromContext = true
                    )
                    ConversationRepository.appendMessages(storage, listOf(errMsg), targetSessionId = activeSessionId)
                    sqlRepo.saveMessagesToSession(activeSessionId, listOf(errMsg))
                    isRouting = false
                    statusNote = null
                    errorNote = failText
                    AgentSpeechFeedback.announceOutcome(
                        context = context.applicationContext,
                        message = failText,
                        isSuccess = false,
                        voiceSettings = latestState.voiceSettings,
                        showToastWhenOutsideApp = false
                    )
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
                    ConversationRepository.appendMessages(storage, listOf(replyMsg), targetSessionId = activeSessionId)
                    sqlRepo.saveMessagesToSession(activeSessionId, listOf(replyMsg))
                    isRouting = false
                    statusNote = null
                    replyText = warmReply
                    if (latestState.voiceSettings.speakRepliesAloud) {
                        AgentSpeechFeedback.speak(
                            context = context.applicationContext,
                            text = warmReply,
                            voiceSettings = latestState.voiceSettings
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
                            resolvedTool.promptText,
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
                        ConversationRepository.appendMessages(storage, listOf(blockedMsg), blockedRec, activeSessionId)
                        sqlRepo.saveMessagesToSession(activeSessionId, listOf(blockedMsg))
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
                    routedToolJson = resolvedTool.toJsonString(),
                    sessionId = activeSessionId
                )

                // Mandatory Accessibility Service check for all phone-control tools
                if (ToolName.requiresAccessibility(resolvedTool.tool) && AgentAccessibilityService.getInstance() == null) {
                    val err = AgentReliabilityConstants.ACCESSIBILITY_OFF_MESSAGE
                    val errMsg = ChatMessage(
                        sender = MessageSender.AGENT,
                        text = err,
                        isError = true,
                        excludeFromContext = true
                    )
                    ConversationRepository.appendMessages(storage, listOf(errMsg), targetSessionId = activeSessionId)
                    sqlRepo.saveMessagesToSession(activeSessionId, listOf(errMsg))
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

                // LOW and MEDIUM risk actions auto-run immediately without approval;
                // HIGH risk actions ALWAYS show the compact Approval Card inside this overlay.
                val shouldAutoRun = resolvedTool.shouldAutoRun(latestState.safetyRules)

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
                ConversationRepository.appendMessages(storage, listOf(cardMsg), record, activeSessionId)
                sqlRepo.saveMessagesToSession(activeSessionId, listOf(cardMsg))

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
                    if (latestState.voiceSettings.speakRepliesAloud) {
                        AgentSpeechFeedback.speak(
                            context = context.applicationContext,
                            text = "Please review and tap Approve to continue.",
                            voiceSettings = latestState.voiceSettings
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val failText = ConfirmationFormatter.formatStrictFailure(
                    e.localizedMessage ?: "Something went wrong while handling your command"
                )
                val errMsg = ChatMessage(
                    sender = MessageSender.AGENT,
                    text = failText,
                    isError = true,
                    excludeFromContext = true
                )
                runCatching {
                    ConversationRepository.appendMessages(storage, listOf(errMsg), targetSessionId = activeSessionId.ifBlank { null })
                    if (activeSessionId.isNotBlank()) {
                        sqlRepo.saveMessagesToSession(activeSessionId, listOf(errMsg))
                    }
                }
                isRouting = false
                statusNote = null
                errorNote = failText
                AgentSpeechFeedback.announceOutcome(
                    context = context.applicationContext,
                    message = failText,
                    isSuccess = false,
                    voiceSettings = activeVoiceSettings,
                    showToastWhenOutsideApp = false
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
            val greeting = when (latest.voiceLanguage) {
                VoiceLanguage.TE_IN -> "చెప్పండి, వింటున్నాను"
                else -> "Yes, I'm listening"
            }
            replyText = greeting
            AgentSpeechFeedback.speak(
                context = context.applicationContext,
                text = greeting,
                voiceSettings = latest,
                onDone = { startMicNow() }
            )
        } else {
            startMicNow()
        }
    }

    val glass = LocalAgentGlassColors.current
    val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

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
                .border(1.dp, glass.luminousBorderBrush(), sheetShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* consume clicks inside the sheet */ }
                ),
            shape = sheetShape,
            color = glass.sheetFill,
            tonalElevation = 0.dp,
            shadowElevation = 16.dp
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
                        .background(glass.borderStart)
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
                                .frostedPill(cornerRadius = 18.dp, active = false)
                                .clickable {
                                    val next = voiceLanguage.next()
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
                                color = MaterialTheme.colorScheme.onSurface
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
                            .clip(RoundedCornerShape(18.dp))
                            .background(glass.aiBubbleFill)
                            .border(1.dp, glass.aiBubbleBorder, RoundedCornerShape(18.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }

                // Inline Safety Hard-Block Banner
                blockedReason?.let { reason ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
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
                            .clip(RoundedCornerShape(18.dp))
                            .background(glass.aiBubbleFill)
                            .border(1.dp, glass.aiBubbleBorder, RoundedCornerShape(18.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .testTag("overlay_reply_card"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = reply,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
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
                                    activeStep = 0,
                                    targetSessionId = approval.plan.sessionId
                                )
                                if (approval.plan.sessionId.isNotBlank()) {
                                    sqlRepo.updateActionInSession(
                                        sessionId = approval.plan.sessionId,
                                        actionId = approval.plan.actionId,
                                        status = ApprovalStatus.RUNNING,
                                        completedSteps = 0,
                                        activeStep = 0
                                    )
                                }
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
                                    notes = "Cancelled from quick overlay.",
                                    targetSessionId = approval.plan.sessionId
                                )
                                if (approval.plan.sessionId.isNotBlank()) {
                                    sqlRepo.updateActionInSession(
                                        sessionId = approval.plan.sessionId,
                                        actionId = approval.plan.actionId,
                                        status = ApprovalStatus.CANCELLED,
                                        completedSteps = 0,
                                        activeStep = -1
                                    )
                                }
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
                            .background(glass.inputFill)
                            .border(
                                width = 1.dp,
                                brush = if (isListening) Brush.linearGradient(
                                    listOf(glass.accentBlue, glass.accentBlue.copy(alpha = 0.45f))
                                ) else glass.luminousBorderBrush(),
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
                                        voiceController.stopListeningAndCommit()
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
            .nestedGlassCard(cornerRadius = 18.dp)
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
