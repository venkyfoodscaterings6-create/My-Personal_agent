package com.example

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

@Immutable
data class AgentUiState(
    val isLoaded: Boolean = false,
    val destination: AppDestination = AppDestination.CHAT,
    val settingsFocusSection: SettingsFocusSection? = null,
    val sessionId: String = "",
    val providers: List<AiProvider> = emptyList(),
    val providerResolvedUrls: Map<String, String> = emptyMap(),
    val chatMessages: List<ChatMessage> = emptyList(),
    val approvalHistory: List<ApprovalRecord> = emptyList(),
    val safetyRules: SafetyRules = SafetyRules(),
    val voiceSettings: VoiceSettings = VoiceSettings(),
    val permissionsStatus: PermissionsStatus = PermissionsStatus(),
    val installedAppsCount: Int = 0,
    val quickActionChips: List<String> = DefaultQuickChips.items,
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val agentMode: AgentMode = AgentMode.SMART_AUTO,
    val connectionTestPassed: Boolean = false,
    val isSending: Boolean = false,
    val isAgentLoopRunning: Boolean = false,
    val liveAgentStatusText: String = "",
    val livePhaseStartMillis: Long = 0L,
    val isTestingConnection: Boolean = false,
    val connectionTestSummary: ConnectionTestSummary? = null,
    val isRunningSelfTest: Boolean = false,
    val selfTestReport: SelfTestReport? = null,
    val isFetchingModels: Boolean = false,
    val fetchedModels: List<String> = emptyList(),
    val fetchModelsNote: String? = null,
    val isWakeWordServiceRunning: Boolean = false,
    val builtInGeminiSettings: BuiltInGeminiSettings = BuiltInGeminiSettings(),
    val streamingAssistantText: String = "",
    val memories: List<MemoryEntity> = emptyList(),
    val tasks: List<TaskEntity> = emptyList(),
    val sqlLogs: List<AgentSqlLogEntity> = emptyList(),
    val sqlSearchQuery: String = "",
    val chatThreads: List<ChatThreadEntity> = emptyList(),
    val threadSearchQuery: String = "",
    val memorySuggestions: Map<String, MemorySuggestion> = emptyMap(),
    val liveVoicePhase: LiveVoicePhase = LiveVoicePhase.IDLE,
    val liveUserTranscript: String = "",
    val liveAgentCaption: String = "",
    val liveGroundingSources: List<GroundingSource> = emptyList(),
    val liveContinuousMode: Boolean = true,
    val livePendingApprovalPlan: ActionPlan? = null,
    val snackbarMessage: String? = null
) {
    val activeProvider: AiProvider?
        get() = providers.firstOrNull { it.isDefault } ?: providers.firstOrNull()

    val isUsingBuiltInGemini: Boolean
        get() = builtInGeminiSettings.useBuiltInGemini || providers.isEmpty()

    val activeHeaderLabel: String
        get() = if (isUsingBuiltInGemini) {
            builtInGeminiSettings.selectedModel.displayName
        } else {
            activeProvider?.displayName ?: "No AI Provider"
        }

    val activeProviderResolvedUrl: String
        get() = activeProvider?.let { providerResolvedUrls[it.id] }.orEmpty()

    val pendingApprovalsCount: Int
        get() = approvalHistory.count { it.status == ApprovalStatus.PENDING }

    val openTasksCount: Int
        get() = tasks.count { !it.isCompleted }

    val isAccessibilityEnabled: Boolean
        get() = permissionsStatus.accessibilityEnabled

    // 3-step Setup Progress
    val step1Complete: Boolean
        get() = isUsingBuiltInGemini || providers.isNotEmpty()

    val step2Complete: Boolean
        get() = permissionsStatus.accessibilityEnabled

    val step3Complete: Boolean
        get() = connectionTestPassed

    val completedSetupSteps: Int
        get() = listOf(step1Complete, step2Complete, step3Complete).count { it }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AgentViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val storage = LocalStorage(appContext)
    private val networkClient = NetworkClient()
    private val firebaseAiService = FirebaseAiService()
    private val sqlRepository = AgentSqlRepository(PersonalAgentDatabase.getInstance(appContext))
    private val sqlSearchFlow = MutableStateFlow("")
    private val threadSearchFlow = MutableStateFlow("")
    private var seededInitialThread = false

    private val _uiState = MutableStateFlow(AgentUiState())
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    private var demoCounter = 0

    init {
        refreshPermissionsStatus()

        // Cache installed launcher apps off the main thread for instant fuzzy matching
        viewModelScope.launch {
            val apps = InstalledAppCache.refresh(appContext)
            _uiState.update { it.copy(installedAppsCount = apps.size) }
        }

        // Observe live Hands-Free Wake Word service state
        viewModelScope.launch {
            WakeWordListeningService.isRunning.collect { running ->
                _uiState.update { it.copy(isWakeWordServiceRunning = running) }
            }
        }

        // Observe live AccessibilityService connection changes
        viewModelScope.launch {
            AgentAccessibilityService.isServiceConnected.collect { connected ->
                val status = NativeToolExecutor.readPermissionsStatus(appContext).let { current ->
                    if (connected) current.copy(accessibilityEnabled = true) else current
                }
                _uiState.update { it.copy(permissionsStatus = status) }
            }
        }

        // Observe background-safe execution progress from AgentAccessibilityService
        // (final completion records & messages are persisted directly by the service into LocalStorage)
        viewModelScope.launch {
            AgentAccessibilityService.executionState.collect { exec ->
                if (exec.isRunning) {
                    _uiState.update { current ->
                        val updatedMessages = if (exec.actionId != null) {
                            current.chatMessages.map { msg ->
                                if (msg.actionPlan?.actionId == exec.actionId) {
                                    msg.copy(
                                        approvalStatus = ApprovalStatus.RUNNING,
                                        completedStepsCount = exec.completedSteps,
                                        activeStepIndex = exec.activeStep
                                    )
                                } else {
                                    msg
                                }
                            }
                        } else {
                            current.chatMessages
                        }
                        current.copy(
                            isAgentLoopRunning = true,
                            liveAgentStatusText = exec.phaseLabel,
                            livePhaseStartMillis = exec.phaseStartMillis,
                            chatMessages = updatedMessages
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isAgentLoopRunning = false,
                            liveAgentStatusText = if (it.isSending) it.liveAgentStatusText else "",
                            livePhaseStartMillis = if (it.isSending) it.livePhaseStartMillis else 0L
                        )
                    }
                }

                exec.completionEvent?.let { event ->
                    AgentAccessibilityService.consumeCompletionEvent(event.eventId)
                }
            }
        }

        viewModelScope.launch {
            storage.ensureSessionId()
            storage.stateFlow.collect { persisted ->
                _uiState.update { current ->
                    current.copy(
                        isLoaded = true,
                        sessionId = persisted.sessionId,
                        providers = persisted.providers,
                        providerResolvedUrls = persisted.providerResolvedUrls,
                        chatMessages = persisted.chatMessages,
                        approvalHistory = persisted.approvalHistory,
                        safetyRules = persisted.safetyRules,
                        voiceSettings = persisted.voiceSettings,
                        quickActionChips = persisted.quickActionChips,
                        themePreference = persisted.themePreference,
                        agentMode = persisted.agentMode,
                        connectionTestPassed = persisted.connectionTestPassed,
                        builtInGeminiSettings = persisted.builtInGeminiSettings
                    )
                }
                AgentSpeechFeedback.updateCachedSettings(persisted.voiceSettings)
                if (!seededInitialThread && persisted.chatMessages.isNotEmpty() && persisted.sessionId.isNotBlank()) {
                    seededInitialThread = true
                    if (sqlRepository.countThreads() == 0) {
                        sqlRepository.upsertThread(
                            id = persisted.sessionId,
                            messages = persisted.chatMessages,
                            messagesJson = storage.encodeChatMessages(persisted.chatMessages)
                        )
                    }
                }
                AgentNotificationHelper.syncPersistentNotification(
                    context = appContext,
                    enabled = persisted.voiceSettings.persistentAskNotificationEnabled,
                    isRunning = _uiState.value.isAgentLoopRunning,
                    runningText = _uiState.value.liveAgentStatusText
                )
                if (persisted.voiceSettings.wakeWordEnabled &&
                    NativeToolExecutor.readPermissionsStatus(appContext).microphoneGranted &&
                    !WakeWordListeningService.isRunning.value
                ) {
                    WakeWordListeningService.start(appContext, persisted.voiceSettings.wakePhrase)
                }
            }
        }

        // Reactive Room SQLite database observers (supports live SQL LIKE search)
        viewModelScope.launch {
            sqlSearchFlow.flatMapLatest { q -> sqlRepository.searchMemories(q) }
                .collect { list -> _uiState.update { it.copy(memories = list) } }
        }
        viewModelScope.launch {
            sqlSearchFlow.flatMapLatest { q -> sqlRepository.searchTasks(q) }
                .collect { list -> _uiState.update { it.copy(tasks = list) } }
        }
        viewModelScope.launch {
            sqlSearchFlow.flatMapLatest { q -> sqlRepository.searchLogs(q) }
                .collect { list -> _uiState.update { it.copy(sqlLogs = list) } }
        }
        viewModelScope.launch {
            threadSearchFlow.flatMapLatest { q -> sqlRepository.searchThreads(q) }
                .collect { list -> _uiState.update { it.copy(chatThreads = list) } }
        }
    }

    fun refreshPermissionsStatus() {
        val status = NativeToolExecutor.readPermissionsStatus(appContext)
        _uiState.update { it.copy(permissionsStatus = status) }
    }

    fun navigateTo(destination: AppDestination) {
        _uiState.update { it.copy(destination = destination) }
    }

    fun jumpToSettingsSection(section: SettingsFocusSection) {
        _uiState.update {
            it.copy(
                destination = AppDestination.SETTINGS,
                settingsFocusSection = section
            )
        }
    }

    fun clearSettingsFocusSection() {
        _uiState.update { it.copy(settingsFocusSection = null) }
    }

    fun consumeSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun showMessage(message: String) {
        _uiState.update { it.copy(snackbarMessage = message) }
    }

    fun setAgentMode(mode: AgentMode) {
        if (_uiState.value.agentMode == mode) return
        _uiState.update { it.copy(agentMode = mode) }
        viewModelScope.launch {
            storage.saveAgentMode(mode)
        }
    }

    fun updateVoiceSettings(transform: (VoiceSettings) -> VoiceSettings) {
        val updated = transform(_uiState.value.voiceSettings)
        _uiState.update { it.copy(voiceSettings = updated) }
        AgentSpeechFeedback.updateCachedSettings(updated)
        if (!updated.speakRepliesAloud) {
            AgentSpeechFeedback.stopSpeaking()
        }
        AgentNotificationHelper.syncPersistentNotification(
            context = appContext,
            enabled = updated.persistentAskNotificationEnabled,
            isRunning = _uiState.value.isAgentLoopRunning,
            runningText = _uiState.value.liveAgentStatusText
        )
        viewModelScope.launch {
            storage.saveVoiceSettings(updated)
        }
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        val currentSettings = _uiState.value.voiceSettings
        val phrase = currentSettings.wakePhrase.ifBlank { VoiceSettings.DEFAULT_WAKE_PHRASE }
        if (enabled && !NativeToolExecutor.readPermissionsStatus(appContext).microphoneGranted) {
            showMessage("Allow Microphone permission first to enable \"$phrase\".")
            return
        }
        val updated = currentSettings.copy(wakeWordEnabled = enabled)
        _uiState.update { it.copy(voiceSettings = updated) }
        viewModelScope.launch {
            storage.saveVoiceSettings(updated)
        }
        if (enabled) {
            WakeWordListeningService.start(appContext, phrase)
            showMessage("Hands-free \"$phrase\" is ON — say it anytime!")
        } else {
            WakeWordListeningService.stop(appContext)
            showMessage("Hands-free wake word turned OFF")
        }
    }

    fun updateWakePhrase(newPhrase: String) {
        val cleaned = newPhrase.trim().take(30)
        if (cleaned.length < 2) return
        val updated = _uiState.value.voiceSettings.copy(wakePhrase = cleaned)
        _uiState.update { it.copy(voiceSettings = updated) }
        viewModelScope.launch {
            storage.saveVoiceSettings(updated)
        }
        if (updated.wakeWordEnabled && NativeToolExecutor.readPermissionsStatus(appContext).microphoneGranted) {
            WakeWordListeningService.start(appContext, cleaned)
        }
        showMessage("Wake phrase set to \"$cleaned\"")
    }

    fun toggleVoiceLanguage() {
        val next = _uiState.value.voiceSettings.voiceLanguage.next()
        updateVoiceSettings { it.copy(voiceLanguage = next) }
        showMessage("Voice language: ${next.displayName}")
    }

    fun testCurrentVoice() {
        val vs = _uiState.value.voiceSettings
        val sample = when (vs.voiceLanguage) {
            VoiceLanguage.TE_IN -> "నమస్కారం! నేను మీ పర్సనల్ ఏజెంట్. నేను మీకు ఎలా సహాయం చేయగలను?"
            VoiceLanguage.AUTO -> "Hi! I am ${vs.voicePersona.displayName}, your personal agent. Ready in English and Telugu."
            VoiceLanguage.EN_IN -> "Hi! I am ${vs.voicePersona.displayName}, your personal agent. How can I help you today?"
        }
        AgentSpeechFeedback.speak(
            context = appContext,
            text = sample,
            voiceSettings = vs
        )
    }

    fun selectBuiltInGeminiModel(model: GeminiBuiltInModel) {
        val updated = _uiState.value.builtInGeminiSettings.copy(
            useBuiltInGemini = true,
            selectedModel = model
        )
        _uiState.update {
            it.copy(
                builtInGeminiSettings = updated,
                snackbarMessage = "Switched to Built-in ${model.displayName}"
            )
        }
        viewModelScope.launch {
            storage.saveBuiltInGeminiSettings(updated)
        }
    }

    fun updateBuiltInGeminiSettings(transform: (BuiltInGeminiSettings) -> BuiltInGeminiSettings) {
        val updated = transform(_uiState.value.builtInGeminiSettings)
        _uiState.update { it.copy(builtInGeminiSettings = updated) }
        viewModelScope.launch {
            storage.saveBuiltInGeminiSettings(updated)
        }
    }

    fun toggleGoogleSearchGrounding() {
        val next = !_uiState.value.builtInGeminiSettings.googleSearchEnabled
        updateBuiltInGeminiSettings { it.copy(googleSearchEnabled = next) }
        showMessage(if (next) "Google Search Grounding ON" else "Google Search Grounding OFF")
    }

    fun selectDefaultProvider(providerId: String) {
        val current = _uiState.value.providers
        val updated = current.map { it.copy(isDefault = it.id == providerId) }
        val chosen = updated.firstOrNull { it.id == providerId }
        val updatedGemini = _uiState.value.builtInGeminiSettings.copy(useBuiltInGemini = false)
        _uiState.update {
            it.copy(
                providers = updated,
                builtInGeminiSettings = updatedGemini,
                snackbarMessage = chosen?.let { p -> "Switched to ${p.displayName}" }
            )
        }
        viewModelScope.launch {
            storage.saveProviders(updated)
            storage.saveBuiltInGeminiSettings(updatedGemini)
        }
    }

    // Room SQL Database (Memories, Tasks, Logs) actions
    fun updateSqlSearchQuery(query: String) {
        _uiState.update { it.copy(sqlSearchQuery = query) }
        sqlSearchFlow.value = query
    }

    fun addMemory(category: String, title: String, content: String, isPinned: Boolean) {
        if (content.isBlank()) return
        if (_uiState.value.safetyRules.blockSensitiveFinancial) {
            val violation = SafetyGuardrail.detectSensitiveViolation(title, content)
            if (violation != null) {
                showMessage("Blocked by Safety Guardrail ($violation): secrets cannot be saved in SQL Memory.")
                return
            }
        }
        viewModelScope.launch {
            sqlRepository.addMemory(category, title, content, isPinned)
            showMessage("Memory saved to SQL database")
        }
    }

    fun toggleMemoryPin(id: Long, pinned: Boolean) {
        viewModelScope.launch { sqlRepository.toggleMemoryPin(id, pinned) }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch {
            sqlRepository.deleteMemory(id)
            showMessage("Memory removed")
        }
    }

    fun addTask(title: String, notes: String, priority: String, dueLabel: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            sqlRepository.addTask(title, notes, priority, dueLabel)
            showMessage("Task added to SQL database")
        }
    }

    fun toggleTaskCompleted(id: Long, completed: Boolean) {
        viewModelScope.launch { sqlRepository.setTaskCompleted(id, completed) }
    }

    fun deleteTask(id: Long) {
        viewModelScope.launch {
            sqlRepository.deleteTask(id)
            showMessage("Task deleted")
        }
    }

    fun clearCompletedTasks() {
        viewModelScope.launch {
            sqlRepository.clearCompletedTasks()
            showMessage("Completed tasks cleared")
        }
    }

    fun clearSqlLogs() {
        viewModelScope.launch {
            sqlRepository.clearAllLogs()
            showMessage("SQL logs cleared")
        }
    }

    // Multi-Thread Saved Chat Drawer actions
    fun updateThreadSearchQuery(query: String) {
        _uiState.update { it.copy(threadSearchQuery = query) }
        threadSearchFlow.value = query
    }

    fun selectChatThread(threadId: String) {
        if (_uiState.value.isAgentLoopRunning) {
            stopRunningAgent()
        }
        viewModelScope.launch {
            val entity = sqlRepository.getThreadById(threadId) ?: return@launch
            val decoded = storage.decodeChatMessages(entity.messagesJson)
            storage.switchActiveChatSession(entity.id, decoded)
            _uiState.update {
                it.copy(
                    destination = AppDestination.CHAT,
                    sessionId = entity.id,
                    chatMessages = decoded,
                    memorySuggestions = emptyMap(),
                    livePendingApprovalPlan = null,
                    isSending = false
                )
            }
        }
    }

    fun deleteChatThread(threadId: String) {
        viewModelScope.launch {
            val deletingActive = _uiState.value.sessionId == threadId
            sqlRepository.deleteThread(threadId)
            if (deletingActive) {
                val remaining = _uiState.value.chatThreads.firstOrNull { it.id != threadId }
                if (remaining != null) {
                    selectChatThread(remaining.id)
                } else {
                    startNewChat()
                }
            }
            showMessage("Chat removed")
        }
    }

    // One-Tap Save to Memory & Auto-Extracted Suggestions
    fun saveMessageToMemory(message: ChatMessage) {
        val cleaned = SpeechTextCleaner.cleanForSpeech(message.text).trim()
        if (cleaned.isBlank()) return
        if (_uiState.value.safetyRules.blockSensitiveFinancial) {
            val violation = SafetyGuardrail.detectSensitiveViolation(cleaned)
            if (violation != null) {
                showMessage("Blocked by Safety Guardrail ($violation): secrets cannot be saved in Memory.")
                return
            }
        }
        val title = cleaned.take(48).let { if (cleaned.length > 48) "$it…" else it }
        viewModelScope.launch {
            sqlRepository.addMemory(
                category = "Saved Note",
                title = title,
                content = cleaned.take(400),
                isPinned = false
            )
            showMessage("Saved to Memory DB")
        }
    }

    fun acceptMemorySuggestion(suggestion: MemorySuggestion) {
        _uiState.update { it.copy(memorySuggestions = it.memorySuggestions - suggestion.anchorMessageId) }
        viewModelScope.launch {
            when (suggestion.kind) {
                SuggestionKind.MEMORY -> {
                    sqlRepository.addMemory(
                        category = suggestion.category,
                        title = suggestion.title,
                        content = suggestion.content,
                        isPinned = true
                    )
                    showMessage("Saved to Memory: ${suggestion.title.take(32)}")
                }
                SuggestionKind.TASK -> {
                    sqlRepository.addTask(
                        title = suggestion.title,
                        notes = suggestion.content,
                        priority = "NORMAL"
                    )
                    showMessage("Task added: ${suggestion.title.take(32)}")
                }
            }
        }
    }

    fun dismissMemorySuggestion(anchorMessageId: String) {
        _uiState.update { it.copy(memorySuggestions = it.memorySuggestions - anchorMessageId) }
    }

    private fun maybeAttachMemorySuggestion(userText: String, anchorMessageId: String) {
        val suggestion = SmartMemorySuggestionDetector.detect(userText, anchorMessageId) ?: return
        _uiState.update { current ->
            current.copy(memorySuggestions = current.memorySuggestions + (anchorMessageId to suggestion))
        }
    }

    private suspend fun persistMessagesAndThread(
        messages: List<ChatMessage>,
        approvals: List<ApprovalRecord>? = null
    ) {
        if (approvals != null) {
            storage.saveChatAndApprovals(messages, approvals)
        } else {
            storage.saveChatMessages(messages)
        }
        val activeSession = _uiState.value.sessionId
        if (activeSession.isNotBlank() && messages.isNotEmpty()) {
            sqlRepository.upsertThread(
                id = activeSession,
                messages = messages,
                messagesJson = storage.encodeChatMessages(messages)
            )
        }
    }

    // Live Voice + 3D Conversation Engine
    fun setLiveVoicePhase(phase: LiveVoicePhase) {
        _uiState.update { it.copy(liveVoicePhase = phase) }
    }

    fun setLiveUserTranscript(text: String) {
        _uiState.update { it.copy(liveUserTranscript = text) }
    }

    fun toggleLiveContinuousMode() {
        val next = !_uiState.value.liveContinuousMode
        _uiState.update { it.copy(liveContinuousMode = next) }
        showMessage(if (next) "Continuous hands-free conversation ON" else "Single-turn push-to-talk ON")
    }

    fun resetLiveVoiceSession() {
        _uiState.update {
            it.copy(
                liveVoicePhase = LiveVoicePhase.IDLE,
                liveUserTranscript = "",
                liveAgentCaption = "",
                liveGroundingSources = emptyList(),
                livePendingApprovalPlan = null
            )
        }
    }

    fun handleLiveVoiceUtterance(
        rawUtterance: String,
        onFinishedSpeaking: () -> Unit
    ) {
        val text = rawUtterance.trim()
        if (text.isBlank()) {
            _uiState.update { it.copy(liveVoicePhase = LiveVoicePhase.IDLE) }
            return
        }

        val state = _uiState.value
        val userMsg = ChatMessage(sender = MessageSender.USER, text = text)
        val withUser = state.chatMessages + userMsg
        _uiState.update {
            it.copy(
                chatMessages = withUser,
                liveUserTranscript = text,
                liveAgentCaption = "",
                liveGroundingSources = emptyList(),
                livePendingApprovalPlan = null,
                liveVoicePhase = LiveVoicePhase.THINKING
            )
        }
        maybeAttachMemorySuggestion(text, userMsg.id)

        viewModelScope.launch {
            persistMessagesAndThread(withUser)

            // 1. Safety Guardrail check first
            if (state.safetyRules.blockSensitiveFinancial) {
                val violation = withContext(Dispatchers.Default) {
                    SafetyGuardrail.detectSensitiveViolation(text)
                }
                if (violation != null) {
                    val blockedReply = "I cannot help with $violation while financial safety protection is enabled."
                    recordHardBlockedRequest(text, violation, withUser)
                    speakLiveReply(blockedReply, emptyList(), onFinishedSpeaking)
                    return@launch
                }
            }

            // 2. Direct SQL Memory / Task voice commands
            val sqlReply = tryHandleDirectSqlIntent(text, modeTag = "LIVE_VOICE")
            if (sqlReply != null) {
                speakLiveReply(sqlReply, emptyList(), onFinishedSpeaking)
                return@launch
            }

            // 3. Direct deterministic phone actions during Live Voice (calls, messages, alarms, apps, YouTube, scroll)
            val localTool = withContext(Dispatchers.Default) {
                HybridToolRouting.matchDirectLocalTool(text)
            }
            if (localTool != null && localTool.tool != ToolName.REPLY_TO_USER) {
                val createdPlan = dispatchRoutedToolCall(localTool, text, "Live · Instant")
                if (localTool.shouldAutoRun(state.safetyRules)) {
                    val confirm = "On it! ${localTool.title}."
                    speakLiveReply(confirm, emptyList(), onFinishedSpeaking)
                } else {
                    _uiState.update { it.copy(livePendingApprovalPlan = createdPlan) }
                    val askApprove = "I prepared a plan to ${localTool.title}. Tap Approve on screen to run it."
                    speakLiveReply(askApprove, emptyList(), onFinishedSpeaking)
                }
                return@launch
            }

            val sqlContext = if (state.builtInGeminiSettings.injectSqlMemory) {
                sqlRepository.buildPersonalContextBlock()
            } else {
                ""
            }

            // 3b. Full AI Hybrid Tool Router inside Live Voice when the utterance looks like a phone action
            val looksLikeAction = withContext(Dispatchers.Default) {
                SmartIntentClassifier.looksLikePhoneAction(text)
            }
            if (looksLikeAction) {
                val installedNames = InstalledAppCache.getCached().map { it.displayName }
                val routeResult = routeHybridWithFallback(
                    state = state,
                    userGoal = text,
                    installedNames = installedNames,
                    messagesWithUser = withUser,
                    sqlContext = sqlContext
                )
                if (routeResult is ToolRouteApiResult.Success && routeResult.toolCall.tool != ToolName.REPLY_TO_USER) {
                    val routed = routeResult.toolCall
                    val createdPlan = dispatchRoutedToolCall(routed, text, "Live · Smart Action")
                    if (routed.shouldAutoRun(state.safetyRules)) {
                        val confirm = "On it! ${routed.title}."
                        speakLiveReply(confirm, emptyList(), onFinishedSpeaking)
                    } else {
                        _uiState.update { it.copy(livePendingApprovalPlan = createdPlan) }
                        val askApprove = "This action needs your approval: ${routed.title}. Tap Approve to run it."
                        speakLiveReply(askApprove, emptyList(), onFinishedSpeaking)
                    }
                    return@launch
                }
            }

            // 4. Streaming Gemini Live conversational turn with Sentence-Pipelined Neural Voice + Google Search + SQL Memory
            if (state.isUsingBuiltInGemini || state.activeProvider == null) {
                var streamOpened = false
                var spokenCursor = 0
                var pendingSpeechBuf = ""
                val activeVoiceSettings = _uiState.value.voiceSettings
                val onLiveTurnFinished: () -> Unit = {
                    if (_uiState.value.destination == AppDestination.LIVE_VOICE) {
                        if (_uiState.value.liveContinuousMode) {
                            onFinishedSpeaking()
                        } else {
                            _uiState.update { it.copy(liveVoicePhase = LiveVoicePhase.IDLE) }
                        }
                    }
                }
                val ensureSpeechStreamOpen = {
                    if (!streamOpened) {
                        streamOpened = true
                        _uiState.update { it.copy(liveVoicePhase = LiveVoicePhase.SPEAKING) }
                        AgentSpeechFeedback.beginSpeechStream(
                            context = appContext,
                            voiceSettings = activeVoiceSettings,
                            onAllDone = onLiveTurnFinished
                        )
                    }
                }

                val result = firebaseAiService.streamChat(
                    modelChoice = state.builtInGeminiSettings.selectedModel,
                    conversationHistory = withUser,
                    latestUserPrompt = text,
                    enableGoogleSearch = state.builtInGeminiSettings.googleSearchEnabled,
                    personalSqlContext = sqlContext,
                    voiceLanguage = state.voiceSettings.voiceLanguage,
                    isLiveVoice = true,
                    onPartialText = { partial ->
                        _uiState.update { it.copy(liveAgentCaption = partial) }
                        if (partial.length > spokenCursor) {
                            pendingSpeechBuf += partial.substring(spokenCursor)
                            spokenCursor = partial.length
                            val (readySentences, remainder) = SpeechTextCleaner.extractReadySentences(pendingSpeechBuf)
                            pendingSpeechBuf = remainder
                            readySentences.forEach { sentence ->
                                ensureSpeechStreamOpen()
                                AgentSpeechFeedback.enqueueSentence(sentence)
                            }
                        }
                    }
                )
                when (result) {
                    is GeminiStreamResult.Success -> {
                        val badge = buildString {
                            append("Live · ${FirebaseAiService.displayNameForModelId(result.modelUsed)}")
                            if (result.groundingSources.isNotEmpty()) append(" · Google Search")
                        }
                        val replyMsg = ChatMessage(
                            sender = MessageSender.AGENT,
                            text = result.text,
                            groundingSources = result.groundingSources,
                            modelBadge = badge
                        )
                        val updated = _uiState.value.chatMessages + replyMsg
                        _uiState.update {
                            it.copy(
                                chatMessages = updated,
                                liveAgentCaption = result.text,
                                liveGroundingSources = result.groundingSources
                            )
                        }
                        persistMessagesAndThread(updated)
                        sqlRepository.recordInteractionLog(
                            sessionId = state.sessionId,
                            mode = "LIVE_VOICE",
                            userPrompt = text,
                            agentResponse = result.text,
                            toolUsed = result.modelUsed,
                            usedGoogleSearch = result.groundingSources.isNotEmpty()
                        )

                        // Flush remaining tail or full text into the streaming speech pipeline
                        val tailText = if (streamOpened) {
                            if (result.text.length > spokenCursor) {
                                pendingSpeechBuf + result.text.substring(spokenCursor)
                            } else {
                                pendingSpeechBuf
                            }
                        } else {
                            result.text
                        }
                        val cleanedTail = SpeechTextCleaner.cleanForSpeech(tailText)
                        if (cleanedTail.isNotBlank()) {
                            ensureSpeechStreamOpen()
                            AgentSpeechFeedback.enqueueSentence(cleanedTail)
                        }
                        if (streamOpened) {
                            AgentSpeechFeedback.finishSpeechStream()
                        } else {
                            onLiveTurnFinished()
                        }
                    }
                    is GeminiStreamResult.Error -> {
                        if (streamOpened) AgentSpeechFeedback.stopSpeaking()
                        val fallbackCustom = state.activeProvider ?: state.providers.firstOrNull()
                        if (result.isRateLimit && fallbackCustom != null) {
                            val liveSysPrompt = buildString {
                                appendLine("You are My Personal Agent in a real-time spoken voice conversation. Reply warmly in 2 to 3 concise sentences without markdown formatting.")
                                if (sqlContext.isNotBlank()) appendLine(sqlContext)
                            }
                            when (val customRes = networkClient.sendChatMessage(fallbackCustom, withUser, systemPrompt = liveSysPrompt)) {
                                is ChatApiResult.Success -> {
                                    val replyMsg = ChatMessage(
                                        sender = MessageSender.AGENT,
                                        text = customRes.reply,
                                        modelBadge = "Live · ${fallbackCustom.displayName} (Fallback)"
                                    )
                                    val updated = _uiState.value.chatMessages + replyMsg
                                    _uiState.update { it.copy(chatMessages = updated) }
                                    persistMessagesAndThread(updated)
                                    speakLiveReply(customRes.reply, emptyList(), onFinishedSpeaking)
                                    return@launch
                                }
                                is ChatApiResult.Error -> Unit
                            }
                        }
                        val errMsg = ChatMessage(
                            sender = MessageSender.AGENT,
                            text = result.message,
                            isError = true,
                            excludeFromContext = true
                        )
                        val updated = _uiState.value.chatMessages + errMsg
                        _uiState.update {
                            it.copy(
                                chatMessages = updated,
                                liveAgentCaption = result.message,
                                liveVoicePhase = LiveVoicePhase.IDLE
                            )
                        }
                        persistMessagesAndThread(updated)
                    }
                }
            } else {
                val customProvider = state.activeProvider!!
                val liveSysPrompt = buildString {
                    appendLine("You are My Personal Agent in a real-time spoken voice conversation. Reply warmly in 2 to 3 concise sentences without markdown formatting.")
                    if (sqlContext.isNotBlank()) appendLine(sqlContext)
                }
                when (val res = networkClient.sendChatMessage(customProvider, withUser, systemPrompt = liveSysPrompt)) {
                    is ChatApiResult.Success -> {
                        val replyMsg = ChatMessage(
                            sender = MessageSender.AGENT,
                            text = res.reply,
                            modelBadge = "Live · ${customProvider.displayName}"
                        )
                        val updated = _uiState.value.chatMessages + replyMsg
                        _uiState.update { it.copy(chatMessages = updated) }
                        persistMessagesAndThread(updated)
                        sqlRepository.recordInteractionLog(
                            sessionId = state.sessionId,
                            mode = "LIVE_VOICE",
                            userPrompt = text,
                            agentResponse = res.reply,
                            toolUsed = customProvider.modelName
                        )
                        speakLiveReply(res.reply, emptyList(), onFinishedSpeaking)
                    }
                    is ChatApiResult.Error -> {
                        if (FirebaseAiService.isQuotaOrRateLimitMessage(res.message)) {
                            val geminiFallback = firebaseAiService.streamChat(
                                modelChoice = GeminiBuiltInModel.FLASH_LITE,
                                conversationHistory = withUser,
                                latestUserPrompt = text,
                                enableGoogleSearch = false,
                                personalSqlContext = sqlContext,
                                voiceLanguage = state.voiceSettings.voiceLanguage,
                                isLiveVoice = true,
                                onPartialText = { partial ->
                                    _uiState.update { it.copy(liveAgentCaption = partial) }
                                }
                            )
                            if (geminiFallback is GeminiStreamResult.Success) {
                                val replyMsg = ChatMessage(
                                    sender = MessageSender.AGENT,
                                    text = geminiFallback.text,
                                    modelBadge = "Live · ${FirebaseAiService.displayNameForModelId(geminiFallback.modelUsed)}"
                                )
                                val updated = _uiState.value.chatMessages + replyMsg
                                _uiState.update { it.copy(chatMessages = updated) }
                                persistMessagesAndThread(updated)
                                speakLiveReply(geminiFallback.text, emptyList(), onFinishedSpeaking)
                                return@launch
                            }
                        }
                        _uiState.update {
                            it.copy(
                                liveAgentCaption = res.message,
                                liveVoicePhase = LiveVoicePhase.IDLE
                            )
                        }
                    }
                }
            }
        }
    }

    private fun speakLiveReply(
        spokenText: String,
        sources: List<GroundingSource>,
        onFinishedSpeaking: () -> Unit
    ) {
        _uiState.update {
            it.copy(
                liveAgentCaption = spokenText,
                liveGroundingSources = sources,
                liveVoicePhase = LiveVoicePhase.SPEAKING
            )
        }
        AgentSpeechFeedback.speak(
            context = appContext,
            text = spokenText,
            voiceSettings = _uiState.value.voiceSettings,
            onDone = {
                if (_uiState.value.destination == AppDestination.LIVE_VOICE) {
                    if (_uiState.value.liveContinuousMode) {
                        onFinishedSpeaking()
                    } else {
                        _uiState.update { it.copy(liveVoicePhase = LiveVoicePhase.IDLE) }
                    }
                }
            }
        )
    }

    private suspend fun tryHandleDirectSqlIntent(rawText: String, modeTag: String): String? {
        val intent = withContext(Dispatchers.Default) { SqlMemoryCommandParser.parse(rawText) }
            ?: return null

        val reply = when (intent) {
            is SqlDirectIntent.SaveMemory -> {
                sqlRepository.addMemory(intent.category, intent.title, intent.content, isPinned = true)
                "Saved to your SQL Memory (${intent.category}): \"${intent.content}\""
            }
            is SqlDirectIntent.AddTask -> {
                sqlRepository.addTask(intent.title, priority = intent.priority)
                "Added task to your SQL database: \"${intent.title}\""
            }
            SqlDirectIntent.ListTasks -> {
                val pending = PersonalAgentDatabase.getInstance(appContext).taskDao().getPendingTasks(10)
                if (pending.isEmpty()) {
                    "You have no open tasks in your SQL database right now."
                } else {
                    "Your open tasks:\n" + pending.mapIndexed { i, t -> "${i + 1}. ${t.title}" }.joinToString("\n")
                }
            }
            SqlDirectIntent.ListMemories -> {
                val mems = PersonalAgentDatabase.getInstance(appContext).memoryDao().getTopMemories(10)
                if (mems.isEmpty()) {
                    "No personal memories are saved yet. Tell me 'Remember that...' anytime!"
                } else {
                    "Saved SQL memories:\n" + mems.mapIndexed { i, m -> "${i + 1}. [${m.category}] ${m.content}" }.joinToString("\n")
                }
            }
        }

        val replyMsg = ChatMessage(
            sender = MessageSender.AGENT,
            text = reply,
            modelBadge = "SQL Memory"
        )
        val updated = _uiState.value.chatMessages + replyMsg
        _uiState.update { it.copy(chatMessages = updated) }
        persistMessagesAndThread(updated)
        sqlRepository.recordInteractionLog(
            sessionId = _uiState.value.sessionId,
            mode = modeTag,
            userPrompt = rawText,
            agentResponse = reply,
            toolUsed = "sql_memory"
        )
        return reply
    }

    fun saveProvider(
        existingId: String?,
        displayName: String,
        baseUrl: String,
        apiKey: String,
        modelName: String,
        apiFormat: ApiFormat,
        makeDefault: Boolean
    ) {
        viewModelScope.launch {
            val updated = withContext(Dispatchers.Default) {
                val currentList = _uiState.value.providers
                val id = existingId ?: UUID.randomUUID().toString()
                val shouldBeDefault = makeDefault || currentList.isEmpty() ||
                    currentList.none { it.isDefault && it.id != id }

                val newProvider = AiProvider(
                    id = id,
                    displayName = displayName.trim().ifBlank { "Custom API" },
                    baseUrl = baseUrl.trim().trimEnd('/'),
                    apiKey = apiKey.trim(),
                    modelName = modelName.trim(),
                    apiFormat = apiFormat,
                    isDefault = shouldBeDefault
                )

                if (existingId == null) {
                    val baseList = if (shouldBeDefault) {
                        currentList.map { it.copy(isDefault = false) }
                    } else {
                        currentList
                    }
                    baseList + newProvider
                } else {
                    currentList.map { existing ->
                        when {
                            existing.id == existingId -> newProvider
                            shouldBeDefault -> existing.copy(isDefault = false)
                            else -> existing
                        }
                    }
                }
            }

            storage.saveProviders(updated)
            showMessage("Saved ${displayName.trim().ifBlank { "Custom API" }}")
        }
    }

    fun deleteProvider(providerId: String) {
        viewModelScope.launch {
            val normalized = withContext(Dispatchers.Default) {
                val remaining = _uiState.value.providers.filterNot { it.id == providerId }
                if (remaining.isNotEmpty() && remaining.none { it.isDefault }) {
                    remaining.mapIndexed { index, item -> item.copy(isDefault = index == 0) }
                } else {
                    remaining
                }
            }
            storage.saveProviders(normalized)
            showMessage("Provider removed")
        }
    }

    fun updateSafetyRules(transform: (SafetyRules) -> SafetyRules) {
        val updated = transform(_uiState.value.safetyRules)
        _uiState.update { it.copy(safetyRules = updated) }
        viewModelScope.launch {
            storage.saveSafetyRules(updated)
        }
    }

    fun saveQuickActionChips(chips: List<String>) {
        viewModelScope.launch {
            val cleaned = withContext(Dispatchers.Default) {
                chips.map { it.trim() }.filter { it.isNotBlank() }
            }
            storage.saveQuickActionChips(cleaned)
            showMessage("Starter chips updated")
        }
    }

    fun setThemePreference(theme: ThemePreference) {
        _uiState.update { it.copy(themePreference = theme) }
        viewModelScope.launch {
            storage.saveThemePreference(theme)
        }
    }

    fun clearFetchedModelsState() {
        _uiState.update {
            it.copy(fetchedModels = emptyList(), fetchModelsNote = null, isFetchingModels = false)
        }
    }

    fun fetchModelsForDraft(
        rawBaseUrl: String,
        apiFormat: ApiFormat,
        apiKey: String
    ) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isFetchingModels = true, fetchModelsNote = null, fetchedModels = emptyList())
            }
            when (val result = networkClient.fetchModels(rawBaseUrl, apiFormat, apiKey)) {
                is FetchModelsResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isFetchingModels = false,
                            fetchedModels = result.models,
                            fetchModelsNote = null
                        )
                    }
                }
                is FetchModelsResult.EmptyOrUnsupported -> {
                    _uiState.update {
                        it.copy(
                            isFetchingModels = false,
                            fetchedModels = emptyList(),
                            fetchModelsNote = result.message
                        )
                    }
                }
            }
        }
    }

    fun runConnectionTest() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isTestingConnection = true) }
            val providerResult = if (state.isUsingBuiltInGemini || state.activeProvider == null) {
                firebaseAiService.testBuiltInGeminiConnection(state.builtInGeminiSettings.selectedModel)
            } else {
                networkClient.testProviderConnection(state.activeProvider!!)
            }
            val summary = ConnectionTestSummary(
                testedAtMillis = System.currentTimeMillis(),
                providerResult = providerResult
            )
            _uiState.update {
                it.copy(
                    isTestingConnection = false,
                    connectionTestSummary = summary
                )
            }
            if (summary.allPassed) {
                storage.saveConnectionTestPassed(true)
            }
        }
    }

    fun runFullSelfTest() {
        if (_uiState.value.isRunningSelfTest) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRunningSelfTest = true) }

            // 1. Active AI Provider (Built-in Gemini or Custom) speed & accuracy
            val state = _uiState.value
            val aiItem = if (state.isUsingBuiltInGemini || state.activeProvider == null) {
                val detail = firebaseAiService.testBuiltInGeminiConnection(state.builtInGeminiSettings.selectedModel)
                SelfTestItem(
                    id = "ai_provider",
                    title = "Built-in Gemini AI (Firebase AI Logic)",
                    passed = detail.success,
                    detail = detail.message
                )
            } else {
                networkClient.runSelfTestJsonProbe(state.activeProvider!!)
            }
            if (aiItem.passed) {
                storage.saveConnectionTestPassed(true)
            }

            // 2. Installed app discovery (YouTube, Settings, Chrome)
            val apps = InstalledAppCache.refresh(appContext)
            _uiState.update { it.copy(installedAppsCount = apps.size) }
            val ytFound = AppNameFuzzyMatcher.findBestMatch("YouTube", apps) != null
            val settingsFound = AppNameFuzzyMatcher.findBestMatch("Settings", apps) != null
            val chromeFound = AppNameFuzzyMatcher.findBestMatch("Chrome", apps) != null
            val foundList = buildList {
                if (ytFound) add("YouTube")
                if (settingsFound) add("Settings")
                if (chromeFound) add("Chrome")
            }
            val appsItem = SelfTestItem(
                id = "app_discovery",
                title = "Installed App Discovery",
                passed = apps.isNotEmpty(),
                detail = if (apps.isNotEmpty()) {
                    "Discovered ${apps.size} launcher apps" +
                        if (foundList.isNotEmpty()) " (found: ${foundList.joinToString(", ")})" else ""
                } else {
                    "No launcher apps returned. Check Android package visibility."
                }
            )

            // 3. Speech Recognition availability
            val speechOk = withContext(Dispatchers.Main) {
                VoiceController.isSpeechAvailable(appContext)
            }
            val speechItem = SelfTestItem(
                id = "speech_recognition",
                title = "Voice Speech Recognition (EN + తెలుగు)",
                passed = speechOk,
                detail = if (speechOk) {
                    "Device SpeechRecognizer / Google Voice input is available."
                } else {
                    "No speech recognition service found on this device. Install Google app / Speech Services."
                }
            )

            // 4. All 6 permission statuses
            val perms = NativeToolExecutor.readPermissionsStatus(appContext)
            _uiState.update { it.copy(permissionsStatus = perms) }
            val missing = buildList {
                if (!perms.microphoneGranted) add("Microphone")
                if (!perms.accessibilityEnabled) add("Accessibility")
                if (!perms.overlayGranted) add("Display Over Apps")
                if (!perms.contactsGranted) add("Contacts")
                if (!perms.callPhoneGranted) add("Phone Calls")
                if (!perms.notificationsGranted) add("Notifications")
            }
            val permItem = SelfTestItem(
                id = "permissions",
                title = "Permissions Center (${perms.grantedCount}/6 Granted)",
                passed = perms.grantedCount == 6,
                detail = if (missing.isEmpty()) {
                    "All 6 permissions are granted and ready."
                } else {
                    "Still needed: ${missing.joinToString(", ")}"
                }
            )

            _uiState.update {
                it.copy(
                    isRunningSelfTest = false,
                    selfTestReport = SelfTestReport(
                        ranAtMillis = System.currentTimeMillis(),
                        items = listOf(aiItem, appsItem, speechItem, permItem)
                    )
                )
            }
        }
    }

    fun sendUserMessage(rawText: String) {
        val text = rawText.trim()
        if (text.isBlank() || _uiState.value.isSending || _uiState.value.isAgentLoopRunning) return

        val state = _uiState.value
        val userMsg = ChatMessage(
            sender = MessageSender.USER,
            text = text
        )
        val withUser = state.chatMessages + userMsg
        _uiState.update { it.copy(chatMessages = withUser) }
        maybeAttachMemorySuggestion(text, userMsg.id)

        viewModelScope.launch {
            persistMessagesAndThread(withUser)

            when (state.agentMode) {
                AgentMode.SMART_AUTO -> handleSmartAutoDispatch(text, withUser)
                AgentMode.CHAT_ONLY -> handleChatOnlyDispatch(withUser)
                AgentMode.AGENT_CONTROL -> handleOnDeviceAgentPlan(text, withUser)
            }
        }
    }

    private suspend fun handleSmartAutoDispatch(
        userGoal: String,
        messagesWithUser: List<ChatMessage>
    ) {
        val state = _uiState.value

        // 1. Safety Guardrail check first
        if (state.safetyRules.blockSensitiveFinancial) {
            val violation = withContext(Dispatchers.Default) {
                SafetyGuardrail.detectSensitiveViolation(userGoal)
            }
            if (violation != null) {
                recordHardBlockedRequest(userGoal, violation, messagesWithUser)
                return
            }
        }

        // 2. Direct SQL memory / task commands
        val sqlReply = tryHandleDirectSqlIntent(userGoal, modeTag = "SMART")
        if (sqlReply != null) {
            AgentSpeechFeedback.announceOutcome(
                context = appContext,
                message = sqlReply,
                isSuccess = true,
                voiceSettings = state.voiceSettings,
                showToastWhenOutsideApp = false
            )
            return
        }

        // 3. Deterministic local phone commands (instant <20ms)
        val localTool = withContext(Dispatchers.Default) {
            HybridToolRouting.matchDirectLocalTool(userGoal)
        }
        if (localTool != null && localTool.tool != ToolName.REPLY_TO_USER) {
            dispatchRoutedToolCall(
                toolCall = localTool,
                userGoal = userGoal,
                planDurationLabel = "Instant · 0.0s"
            )
            return
        }

        // 4. Smart Intent Classifier: conversational questions go straight to streaming Gemini + Google Search!
        val looksLikeAction = withContext(Dispatchers.Default) {
            SmartIntentClassifier.looksLikePhoneAction(userGoal)
        }
        if (!looksLikeAction) {
            handleChatOnlyDispatch(messagesWithUser, skipPreflight = true)
            return
        }

        // 5. Route action-like prompts via AI Hybrid Tool Router; if it decides REPLY_TO_USER, hand off to streaming chat
        val routeStartMillis = System.currentTimeMillis()
        _uiState.update {
            it.copy(
                isSending = true,
                liveAgentStatusText = "Smart routing…",
                livePhaseStartMillis = routeStartMillis
            )
        }
        val installedNames = InstalledAppCache.getCached().map { it.displayName }
        val sqlContext = if (state.builtInGeminiSettings.injectSqlMemory) {
            sqlRepository.buildPersonalContextBlock()
        } else {
            ""
        }
        val routeResult = routeHybridWithFallback(
            state = state,
            userGoal = userGoal,
            installedNames = installedNames,
            messagesWithUser = messagesWithUser,
            sqlContext = sqlContext
        )
        val routeElapsed = formatElapsed(System.currentTimeMillis() - routeStartMillis)
        _uiState.update {
            it.copy(
                isSending = false,
                liveAgentStatusText = "",
                livePhaseStartMillis = 0L
            )
        }

        when (routeResult) {
            is ToolRouteApiResult.Error -> {
                handleChatOnlyDispatch(messagesWithUser, skipPreflight = true)
            }
            is ToolRouteApiResult.Success -> {
                if (routeResult.toolCall.tool == ToolName.REPLY_TO_USER) {
                    handleChatOnlyDispatch(messagesWithUser, skipPreflight = true)
                } else {
                    dispatchRoutedToolCall(
                        toolCall = routeResult.toolCall,
                        userGoal = userGoal,
                        planDurationLabel = "Smart · $routeElapsed"
                    )
                }
            }
        }
    }

    private suspend fun routeHybridWithFallback(
        state: AgentUiState,
        userGoal: String,
        installedNames: List<String>,
        messagesWithUser: List<ChatMessage>,
        sqlContext: String
    ): ToolRouteApiResult {
        val primaryRouteResult = if (state.isUsingBuiltInGemini || state.activeProvider == null) {
            firebaseAiService.routeHybridToolWithGemini(
                userGoal = userGoal,
                installedAppSample = installedNames,
                safetyRules = state.safetyRules,
                conversationHistory = messagesWithUser,
                personalSqlContext = sqlContext
            )
        } else {
            networkClient.routeHybridTool(
                provider = state.activeProvider!!,
                userGoal = userGoal,
                installedAppSample = installedNames,
                safetyRules = state.safetyRules,
                conversationHistory = messagesWithUser
            )
        }
        return if (primaryRouteResult is ToolRouteApiResult.Error &&
            FirebaseAiService.isQuotaOrRateLimitMessage(primaryRouteResult.message)
        ) {
            if (state.isUsingBuiltInGemini || state.activeProvider == null) {
                val fallbackCustom = state.activeProvider ?: state.providers.firstOrNull()
                if (fallbackCustom != null) {
                    networkClient.routeHybridTool(
                        provider = fallbackCustom,
                        userGoal = userGoal,
                        installedAppSample = installedNames,
                        safetyRules = state.safetyRules,
                        conversationHistory = messagesWithUser
                    )
                } else {
                    primaryRouteResult
                }
            } else {
                firebaseAiService.routeHybridToolWithGemini(
                    userGoal = userGoal,
                    installedAppSample = installedNames,
                    safetyRules = state.safetyRules,
                    conversationHistory = messagesWithUser,
                    personalSqlContext = sqlContext
                )
            }
        } else {
            primaryRouteResult
        }
    }

    private suspend fun handleChatOnlyDispatch(
        messagesWithUser: List<ChatMessage>,
        skipPreflight: Boolean = false
    ) {
        val state = _uiState.value
        val latestUserText = messagesWithUser.lastOrNull()?.text.orEmpty()

        if (!skipPreflight) {
            // 1. Keep Safety Guardrail active even for SQL memory saves
            if (state.safetyRules.blockSensitiveFinancial) {
                val violation = withContext(Dispatchers.Default) {
                    SafetyGuardrail.detectSensitiveViolation(latestUserText)
                }
                if (violation != null) {
                    recordHardBlockedRequest(latestUserText, violation, messagesWithUser)
                    return
                }
            }

            // 2. Direct on-device SQL memory / task commands ("Remember that...", "Add task...")
            if (tryHandleDirectSqlIntent(latestUserText, modeTag = "CHAT") != null) {
                return
            }

            // 2b. Unmistakable direct phone commands ("open zomato", "open youtube and play...", "set volume...")
            // execute immediately even if the user didn't toggle the Agent Mode chip first.
            val directPhoneTool = withContext(Dispatchers.Default) {
                HybridToolRouting.matchDirectLocalTool(latestUserText)
            }
            if (directPhoneTool != null && directPhoneTool.tool != ToolName.REPLY_TO_USER) {
                dispatchRoutedToolCall(
                    toolCall = directPhoneTool,
                    userGoal = latestUserText,
                    planDurationLabel = "Instant · 0.0s"
                )
                return
            }
        }

        val sqlContext = if (state.builtInGeminiSettings.injectSqlMemory) {
            sqlRepository.buildPersonalContextBlock()
        } else {
            ""
        }

        if (state.isUsingBuiltInGemini || state.activeProvider == null) {
            val startMillis = System.currentTimeMillis()
            val searchEnabled = state.builtInGeminiSettings.googleSearchEnabled
            _uiState.update {
                it.copy(
                    isSending = true,
                    streamingAssistantText = "",
                    liveAgentStatusText = if (searchEnabled) "Searching Google & thinking…" else "Thinking with Gemini…",
                    livePhaseStartMillis = startMillis
                )
            }

            val result = firebaseAiService.streamChat(
                modelChoice = state.builtInGeminiSettings.selectedModel,
                conversationHistory = messagesWithUser,
                latestUserPrompt = latestUserText,
                enableGoogleSearch = searchEnabled,
                personalSqlContext = sqlContext,
                voiceLanguage = state.voiceSettings.voiceLanguage,
                isLiveVoice = false,
                onPartialText = { partial ->
                    _uiState.update { it.copy(streamingAssistantText = partial) }
                }
            )

            val replyMsg = when (result) {
                is GeminiStreamResult.Success -> {
                    val modelLabel = FirebaseAiService.displayNameForModelId(result.modelUsed)
                    val badge = buildString {
                        append(modelLabel)
                        if (result.groundingSources.isNotEmpty()) append(" · Google Search")
                    }
                    sqlRepository.recordInteractionLog(
                        sessionId = state.sessionId,
                        mode = "CHAT",
                        userPrompt = latestUserText,
                        agentResponse = result.text,
                        toolUsed = result.modelUsed,
                        usedGoogleSearch = result.groundingSources.isNotEmpty()
                    )
                    ChatMessage(
                        sender = MessageSender.AGENT,
                        text = result.text,
                        groundingSources = result.groundingSources,
                        modelBadge = badge
                    )
                }
                is GeminiStreamResult.Error -> {
                    val fallbackCustom = state.activeProvider ?: state.providers.firstOrNull()
                    if (result.isRateLimit && fallbackCustom != null) {
                        val customSysPrompt = if (sqlContext.isNotBlank()) {
                            "${NetworkClient.DEFAULT_CHAT_SYSTEM_PROMPT}\n\n$sqlContext"
                        } else {
                            null
                        }
                        when (val fallbackRes = networkClient.sendChatMessage(fallbackCustom, messagesWithUser, systemPrompt = customSysPrompt)) {
                            is ChatApiResult.Success -> {
                                sqlRepository.recordInteractionLog(
                                    sessionId = state.sessionId,
                                    mode = "CHAT",
                                    userPrompt = latestUserText,
                                    agentResponse = fallbackRes.reply,
                                    toolUsed = fallbackCustom.modelName
                                )
                                ChatMessage(
                                    sender = MessageSender.AGENT,
                                    text = fallbackRes.reply,
                                    modelBadge = "${fallbackCustom.displayName} (Auto-Fallback)"
                                )
                            }
                            is ChatApiResult.Error -> ChatMessage(
                                sender = MessageSender.AGENT,
                                text = result.message,
                                isError = true,
                                excludeFromContext = true
                            )
                        }
                    } else {
                        ChatMessage(
                            sender = MessageSender.AGENT,
                            text = result.message,
                            isError = true,
                            excludeFromContext = true
                        )
                    }
                }
            }
            val updated = _uiState.value.chatMessages + replyMsg
            _uiState.update {
                it.copy(
                    isSending = false,
                    streamingAssistantText = "",
                    liveAgentStatusText = "",
                    livePhaseStartMillis = 0L,
                    chatMessages = updated
                )
            }
            persistMessagesAndThread(updated)
            return
        }

        // Custom Base URL + API Key provider path (preserved 100%, with automatic Built-in Gemini fallback on 429 rate limits)
        val provider = state.activeProvider!!
        _uiState.update { it.copy(isSending = true, streamingAssistantText = "") }
        val customSysPrompt = if (sqlContext.isNotBlank()) {
            "${NetworkClient.DEFAULT_CHAT_SYSTEM_PROMPT}\n\n$sqlContext"
        } else {
            null
        }
        val result = networkClient.sendChatMessage(provider, messagesWithUser, systemPrompt = customSysPrompt)
        val replyMsg = when (result) {
            is ChatApiResult.Success -> {
                sqlRepository.recordInteractionLog(
                    sessionId = state.sessionId,
                    mode = "CHAT",
                    userPrompt = latestUserText,
                    agentResponse = result.reply,
                    toolUsed = provider.modelName
                )
                ChatMessage(
                    sender = MessageSender.AGENT,
                    text = result.reply,
                    modelBadge = provider.displayName
                )
            }
            is ChatApiResult.Error -> {
                if (FirebaseAiService.isQuotaOrRateLimitMessage(result.message)) {
                    val geminiFallback = firebaseAiService.streamChat(
                        modelChoice = GeminiBuiltInModel.FLASH_LITE,
                        conversationHistory = messagesWithUser,
                        latestUserPrompt = latestUserText,
                        enableGoogleSearch = false,
                        personalSqlContext = sqlContext,
                        voiceLanguage = state.voiceSettings.voiceLanguage,
                        isLiveVoice = false,
                        onPartialText = { partial ->
                            _uiState.update { it.copy(streamingAssistantText = partial) }
                        }
                    )
                    when (geminiFallback) {
                        is GeminiStreamResult.Success -> {
                            sqlRepository.recordInteractionLog(
                                sessionId = state.sessionId,
                                mode = "CHAT",
                                userPrompt = latestUserText,
                                agentResponse = geminiFallback.text,
                                toolUsed = geminiFallback.modelUsed
                            )
                            ChatMessage(
                                sender = MessageSender.AGENT,
                                text = geminiFallback.text,
                                modelBadge = "${FirebaseAiService.displayNameForModelId(geminiFallback.modelUsed)} (Auto-Fallback)"
                            )
                        }
                        is GeminiStreamResult.Error -> ChatMessage(
                            sender = MessageSender.AGENT,
                            text = result.message,
                            isError = true,
                            excludeFromContext = true
                        )
                    }
                } else {
                    ChatMessage(
                        sender = MessageSender.AGENT,
                        text = result.message,
                        isError = true,
                        excludeFromContext = true
                    )
                }
            }
        }
        val updated = _uiState.value.chatMessages + replyMsg
        _uiState.update {
            it.copy(
                isSending = false,
                streamingAssistantText = "",
                chatMessages = updated
            )
        }
        persistMessagesAndThread(updated)
    }

    private suspend fun handleOnDeviceAgentPlan(
        userGoal: String,
        messagesWithUser: List<ChatMessage>
    ) {
        val state = _uiState.value

        // 1. On-device hard-block guardrail check BEFORE any routing or planning
        if (state.safetyRules.blockSensitiveFinancial) {
            val violation = withContext(Dispatchers.Default) {
                SafetyGuardrail.detectSensitiveViolation(userGoal)
            }
            if (violation != null) {
                recordHardBlockedRequest(userGoal, violation, messagesWithUser)
                return
            }
        }

        // 1b. Direct on-device SQL memory / task commands work in Agent Mode too!
        val sqlReply = tryHandleDirectSqlIntent(userGoal, modeTag = "AGENT")
        if (sqlReply != null) {
            AgentSpeechFeedback.announceOutcome(
                context = appContext,
                message = sqlReply,
                isSuccess = true,
                voiceSettings = state.voiceSettings,
                showToastWhenOutsideApp = false
            )
            return
        }

        // 2. Ultra-fast local deterministic tool router (never swallows compound commands into open_app)
        val localTool = withContext(Dispatchers.Default) {
            HybridToolRouting.matchDirectLocalTool(userGoal)
        }
        if (localTool != null) {
            dispatchRoutedToolCall(
                toolCall = localTool,
                userGoal = userGoal,
                planDurationLabel = "Instant · 0.0s"
            )
            return
        }

        // 3. Hybrid AI Tool Router (Built-in Gemini or Custom AI Provider)
        val routeStartMillis = System.currentTimeMillis()
        _uiState.update {
            it.copy(
                isSending = true,
                liveAgentStatusText = "Routing…",
                livePhaseStartMillis = routeStartMillis
            )
        }
        val installedNames = InstalledAppCache.getCached().map { it.displayName }
        val sqlContext = if (state.builtInGeminiSettings.injectSqlMemory) {
            sqlRepository.buildPersonalContextBlock()
        } else {
            ""
        }
        val routeResult = routeHybridWithFallback(
            state = state,
            userGoal = userGoal,
            installedNames = installedNames,
            messagesWithUser = messagesWithUser,
            sqlContext = sqlContext
        )
        val routeElapsed = formatElapsed(System.currentTimeMillis() - routeStartMillis)
        _uiState.update {
            it.copy(
                isSending = false,
                liveAgentStatusText = "",
                livePhaseStartMillis = 0L
            )
        }

        when (routeResult) {
            is ToolRouteApiResult.Error -> {
                val errMsg = ChatMessage(
                    sender = MessageSender.AGENT,
                    text = routeResult.message,
                    isError = true,
                    excludeFromContext = true
                )
                val updated = _uiState.value.chatMessages + errMsg
                _uiState.update { it.copy(chatMessages = updated) }
                persistMessagesAndThread(updated)
            }

            is ToolRouteApiResult.Success -> {
                dispatchRoutedToolCall(
                    toolCall = routeResult.toolCall,
                    userGoal = userGoal,
                    planDurationLabel = "Routed in $routeElapsed"
                )
            }
        }
    }

    private suspend fun recordHardBlockedRequest(
        userGoal: String,
        violation: String,
        messagesWithUser: List<ChatMessage>,
        plan: ActionPlan? = null
    ) {
        val actionId = plan?.actionId ?: UUID.randomUUID().toString()
        val blockedMsg = ChatMessage(
            sender = MessageSender.AGENT,
            text = "Blocked on-device by your Safety Guardrail ($violation). Banking, UPI, OTP, and password actions are hard-blocked while this protection is ON in Settings.",
            actionPlan = plan?.copy(riskLevel = RiskLevel.HIGH),
            isBlockedBySafety = true,
            blockedReason = violation,
            approvalStatus = ApprovalStatus.BLOCKED,
            excludeFromContext = true
        )
        val blockedRecord = ApprovalRecord(
            actionId = actionId,
            title = plan?.title ?: "Blocked Request: ${userGoal.take(60)}",
            riskLevel = RiskLevel.HIGH,
            steps = plan?.steps ?: listOf("Blocked locally ($violation)"),
            status = ApprovalStatus.BLOCKED,
            notes = "Blocked by on-device financial/credential guardrail."
        )
        val nextMessages = messagesWithUser + blockedMsg
        val nextApprovals = listOf(blockedRecord) + _uiState.value.approvalHistory
        _uiState.update {
            it.copy(chatMessages = nextMessages, approvalHistory = nextApprovals)
        }
        persistMessagesAndThread(nextMessages, nextApprovals)
    }

    private suspend fun dispatchRoutedToolCall(
        toolCall: RoutedToolCall,
        userGoal: String,
        planDurationLabel: String
    ): ActionPlan? {
        val state = _uiState.value

        // 0. Natural greetings & conversational replies — reply warmly, NEVER create an ActionPlan or open an app!
        if (toolCall.tool == ToolName.REPLY_TO_USER) {
            val warmReply = toolCall.replyMessage
                ?.takeIf { it.isNotBlank() }
                ?: "Hello! How can I help you today?"
            val replyMsg = ChatMessage(
                sender = MessageSender.AGENT,
                text = warmReply
            )
            val nextMessages = _uiState.value.chatMessages + replyMsg
            _uiState.update { it.copy(chatMessages = nextMessages) }
            persistMessagesAndThread(nextMessages)
            AgentSpeechFeedback.announceOutcome(
                context = appContext,
                message = warmReply,
                isSuccess = true,
                voiceSettings = state.voiceSettings,
                showToastWhenOutsideApp = false
            )
            return null
        }

        // Re-verify financial/UPI/OTP/password hard-block on routed tool arguments
        if (state.safetyRules.blockSensitiveFinancial) {
            val violation = withContext(Dispatchers.Default) {
                SafetyGuardrail.detectSensitiveViolation(
                    toolCall.title,
                    toolCall.appName,
                    toolCall.query,
                    toolCall.messageText,
                    toolCall.targetText,
                    toolCall.goal,
                    *toolCall.steps.toTypedArray()
                )
            }
            if (violation != null) {
                val blockedPlan = ActionPlan(
                    actionId = UUID.randomUUID().toString(),
                    title = toolCall.title.ifBlank { userGoal.take(56) },
                    riskLevel = RiskLevel.HIGH,
                    steps = toolCall.steps,
                    routedToolJson = toolCall.toJsonString()
                )
                recordHardBlockedRequest(userGoal, violation, _uiState.value.chatMessages, blockedPlan)
                return null
            }
        }

        val plan = ActionPlan(
            actionId = UUID.randomUUID().toString(),
            title = toolCall.title.ifBlank { userGoal.take(56) },
            riskLevel = toolCall.riskLevel,
            steps = toolCall.steps.ifEmpty { listOf(toolCall.reason.ifBlank { userGoal.take(80) }) },
            isDemo = false,
            routedToolJson = toolCall.toJsonString()
        )

        // Approval Policy:
        // - LOW and MEDIUM risk actions auto-run without approval (when autoRunLowAndMediumRisk is enabled).
        // - HIGH risk actions ALWAYS require an Approval Card.
        // - Financial/UPI/OTP/password actions remain hard-blocked above.
        val shouldAutoRun = toolCall.shouldAutoRun(state.safetyRules)

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
            activeStepIndex = if (shouldAutoRun) 0 else -1,
            durationLabel = if (shouldAutoRun) null else planDurationLabel
        )
        val record = ApprovalRecord(
            actionId = plan.actionId,
            title = plan.title,
            riskLevel = plan.riskLevel,
            steps = plan.steps,
            status = initialStatus
        )
        val nextMessages = _uiState.value.chatMessages + cardMsg
        val nextApprovals = listOf(record) + _uiState.value.approvalHistory
        _uiState.update {
            it.copy(chatMessages = nextMessages, approvalHistory = nextApprovals)
        }
        persistMessagesAndThread(nextMessages, nextApprovals)

        if (shouldAutoRun) {
            executeRoutedToolPlan(plan, toolCall)
        }
        return plan
    }

    fun triggerApprovalDemo() {
        viewModelScope.launch {
            demoCounter++
            val plan = when (demoCounter % 3) {
                1 -> ActionPlan(
                    actionId = "demo-${UUID.randomUUID().toString().take(8)}",
                    title = "Mute notifications & open focus playlist",
                    riskLevel = RiskLevel.LOW,
                    steps = listOf(
                        "Turn on Do Not Disturb mode for 60 minutes",
                        "Lower media volume to 40%",
                        "Open your music app and play Focus Mix"
                    ),
                    isDemo = true
                )
                2 -> ActionPlan(
                    actionId = "demo-${UUID.randomUUID().toString().take(8)}",
                    title = "Draft 'Running 10 mins late' in Messages",
                    riskLevel = RiskLevel.MEDIUM,
                    steps = listOf(
                        "Open Messages app and select Alex's recent thread",
                        "Type: \"Hey! Running about 10 minutes late, see you soon.\"",
                        "Wait for final send confirmation"
                    ),
                    isDemo = true
                )
                else -> ActionPlan(
                    actionId = "demo-${UUID.randomUUID().toString().take(8)}",
                    title = "Clean old APK files in Downloads",
                    riskLevel = RiskLevel.HIGH,
                    steps = listOf(
                        "Open Files and inspect Downloads older than 30 days",
                        "Move selected old APK installers to Trash",
                        "Return to home screen and report freed storage"
                    ),
                    isDemo = true
                )
            }

            val demoMessage = ChatMessage(
                sender = MessageSender.AGENT,
                text = "Approval preview (Demo). Review the steps, optionally edit them, then tap Approve or Cancel.",
                actionPlan = plan,
                approvalStatus = ApprovalStatus.PENDING,
                excludeFromContext = true
            )
            val record = ApprovalRecord(
                actionId = plan.actionId,
                title = plan.title,
                riskLevel = plan.riskLevel,
                steps = plan.steps,
                status = ApprovalStatus.PENDING,
                isDemo = true
            )

            val nextMessages = _uiState.value.chatMessages + demoMessage
            val nextApprovals = listOf(record) + _uiState.value.approvalHistory
            _uiState.update {
                it.copy(
                    destination = AppDestination.CHAT,
                    chatMessages = nextMessages,
                    approvalHistory = nextApprovals
                )
            }
            storage.saveChatAndApprovals(nextMessages, nextApprovals)
        }
    }

    fun editActionInstruction(actionId: String, newInstruction: String) {
        val cleaned = newInstruction.trim()
        viewModelScope.launch {
            val state = _uiState.value
            val updatedMessages = state.chatMessages.map { msg ->
                if (msg.actionPlan?.actionId == actionId) {
                    msg.copy(
                        actionPlan = msg.actionPlan.copy(
                            editedInstruction = cleaned.ifBlank { null }
                        )
                    )
                } else {
                    msg
                }
            }
            val updatedApprovals = state.approvalHistory.map { rec ->
                if (rec.actionId == actionId) {
                    rec.copy(editedInstruction = cleaned.ifBlank { null })
                } else {
                    rec
                }
            }
            _uiState.update {
                it.copy(
                    chatMessages = updatedMessages,
                    approvalHistory = updatedApprovals,
                    snackbarMessage = "Instruction updated"
                )
            }
            storage.saveChatAndApprovals(updatedMessages, updatedApprovals)
        }
    }

    fun approveAction(actionId: String) {
        if (_uiState.value.isAgentLoopRunning) {
            showMessage("An agent task is already running. Tap Stop Agent first.")
            return
        }
        if (_uiState.value.livePendingApprovalPlan?.actionId == actionId) {
            _uiState.update { it.copy(livePendingApprovalPlan = null) }
        }

        viewModelScope.launch {
            val state = _uiState.value
            val matchingMsg = state.chatMessages.lastOrNull { it.actionPlan?.actionId == actionId }
            val matchingRecord = state.approvalHistory.firstOrNull { it.actionId == actionId }
            val plan = matchingMsg?.actionPlan ?: matchingRecord?.let {
                ActionPlan(
                    actionId = it.actionId,
                    title = it.title,
                    riskLevel = it.riskLevel,
                    steps = it.steps,
                    editedInstruction = it.editedInstruction,
                    isDemo = it.isDemo
                )
            } ?: return@launch

            // Re-verify on-device safety guardrail (including any edited instruction!)
            if (state.safetyRules.blockSensitiveFinancial) {
                val violation = withContext(Dispatchers.Default) {
                    SafetyGuardrail.detectSensitiveViolation(
                        plan.title,
                        plan.editedInstruction,
                        *plan.steps.toTypedArray()
                    )
                }
                if (violation != null) {
                    updateActionStatus(
                        actionId = actionId,
                        status = ApprovalStatus.BLOCKED,
                        completedSteps = 0,
                        activeStep = -1,
                        blockedReason = violation,
                        notes = "Blocked locally before execution: $violation",
                        persistNow = true
                    )
                    showMessage("Blocked locally: $violation")
                    return@launch
                }
            }

            if (plan.isDemo) {
                runDemoStepExecution(plan)
                return@launch
            }

            val edited = plan.editedInstruction?.takeIf { it.isNotBlank() }
            val resolvedToolCall = if (edited != null) {
                withContext(Dispatchers.Default) {
                    HybridToolRouting.matchDirectLocalTool(edited)
                } ?: RoutedToolCall(
                    tool = ToolName.UI_AUTOMATE,
                    goal = edited,
                    title = plan.title,
                    steps = plan.steps,
                    riskLevel = plan.riskLevel
                )
            } else {
                RoutedToolCall.fromJsonString(plan.routedToolJson)
                    ?: legacyFastPathToRoutedTool(plan)
                    ?: RoutedToolCall(
                        tool = ToolName.UI_AUTOMATE,
                        goal = plan.title,
                        title = plan.title,
                        steps = plan.steps,
                        riskLevel = plan.riskLevel
                    )
            }

            executeRoutedToolPlan(plan, resolvedToolCall)
        }
    }

    private fun legacyFastPathToRoutedTool(plan: ActionPlan): RoutedToolCall? {
        val type = plan.fastPathType ?: return null
        val arg = plan.fastPathArg.orEmpty()
        return when (type) {
            FastPathType.OPEN_APP -> RoutedToolCall(tool = ToolName.OPEN_APP, appName = arg, title = plan.title)
            FastPathType.YOUTUBE_SEARCH -> RoutedToolCall(tool = ToolName.SEARCH_YOUTUBE, query = arg, title = plan.title)
            FastPathType.GO_HOME -> RoutedToolCall(tool = ToolName.GO_HOME, title = plan.title)
            FastPathType.GO_BACK -> RoutedToolCall(tool = ToolName.GO_BACK, title = plan.title)
            else -> null
        }
    }

    private suspend fun executeRoutedToolPlan(plan: ActionPlan, toolCall: RoutedToolCall) {
        val blockSensitive = _uiState.value.safetyRules.blockSensitiveFinancial

        when (toolCall.tool) {
            ToolName.PLAY_YOUTUBE_VIDEO -> {
                val query = toolCall.query?.takeIf { it.isNotBlank() } ?: plan.title
                val service = AgentAccessibilityService.getInstance()
                if (service != null) {
                    updateActionStatus(
                        actionId = plan.actionId,
                        status = ApprovalStatus.RUNNING,
                        completedSteps = 0,
                        activeStep = 0,
                        persistNow = false
                    )
                    service.startPlayYouTubeVideo(
                        plan = plan,
                        query = query,
                        provider = _uiState.value.activeProvider,
                        blockSensitive = blockSensitive,
                        conversationHistory = _uiState.value.chatMessages
                    )
                } else {
                    // Graceful fallback when Accessibility isn't enabled yet: still open YouTube search directly!
                    val startMillis = System.currentTimeMillis()
                    val result = withContext(Dispatchers.Main) {
                        NativeToolExecutor.searchYouTube(appContext, query)
                    }
                    val elapsed = formatElapsed(System.currentTimeMillis() - startMillis)
                    val note = if (result.success) {
                        ConfirmationFormatter.formatSuccess(
                            "${result.summary}. Enable Accessibility in Setup & Test -> Permissions Center to auto-tap the first video",
                            elapsed
                        )
                    } else {
                        ConfirmationFormatter.formatFailure(result.summary, elapsed)
                    }
                    val msg = ChatMessage(
                        sender = MessageSender.AGENT,
                        text = note,
                        isError = !result.success,
                        excludeFromContext = !result.success
                    )
                    updateActionStatus(
                        actionId = plan.actionId,
                        status = if (result.success) ApprovalStatus.COMPLETED else ApprovalStatus.FAILED,
                        completedSteps = if (result.success) plan.steps.size.coerceAtLeast(1) else 0,
                        activeStep = -1,
                        notes = note,
                        durationLabel = if (result.success) "Opened search · $elapsed" else "Failed · $elapsed",
                        appendChatMessage = msg,
                        persistNow = true
                    )
                    AgentSpeechFeedback.announceOutcome(
                        context = appContext,
                        message = note,
                        isSuccess = result.success,
                        voiceSettings = _uiState.value.voiceSettings
                    )
                }
            }

            ToolName.SEND_MESSAGE -> {
                val contact = toolCall.contactOrNumber.orEmpty()
                val app = toolCall.messagingApp ?: "whatsapp"
                val text = toolCall.messageText.orEmpty()
                val service = AgentAccessibilityService.getInstance()
                if (service != null) {
                    updateActionStatus(
                        actionId = plan.actionId,
                        status = ApprovalStatus.RUNNING,
                        completedSteps = 0,
                        activeStep = 0,
                        persistNow = true
                    )
                    service.startSendMessage(
                        plan = plan,
                        contactOrNumber = contact,
                        messagingApp = app,
                        messageText = text,
                        blockSensitive = blockSensitive
                    )
                } else {
                    // Fallback when Accessibility is disabled: open composer pre-filled and guide user
                    val startMillis = System.currentTimeMillis()
                    val launch = withContext(Dispatchers.Main) {
                        NativeToolExecutor.openMessageComposer(appContext, contact, app, text)
                    }
                    val elapsed = formatElapsed(System.currentTimeMillis() - startMillis)
                    val note = if (launch.success) {
                        ConfirmationFormatter.formatSuccess(
                            "${launch.summary}. Enable Accessibility in Setup & Test -> Permissions Center to tap Send automatically",
                            elapsed
                        )
                    } else {
                        ConfirmationFormatter.formatFailure(launch.summary, elapsed)
                    }
                    val msg = ChatMessage(
                        sender = MessageSender.AGENT,
                        text = note,
                        isError = !launch.success,
                        excludeFromContext = !launch.success
                    )
                    updateActionStatus(
                        actionId = plan.actionId,
                        status = if (launch.success) ApprovalStatus.COMPLETED else ApprovalStatus.FAILED,
                        completedSteps = if (launch.success) 1 else 0,
                        activeStep = -1,
                        notes = note,
                        durationLabel = if (launch.success) "Opened chat · $elapsed" else "Failed · $elapsed",
                        appendChatMessage = msg,
                        persistNow = true
                    )
                    AgentSpeechFeedback.announceOutcome(
                        context = appContext,
                        message = note,
                        isSuccess = launch.success,
                        voiceSettings = _uiState.value.voiceSettings
                    )
                }
            }

            ToolName.TAP_ON_SCREEN -> {
                val target = toolCall.targetText?.takeIf { it.isNotBlank() } ?: plan.title
                val service = AgentAccessibilityService.getInstance()
                if (service != null) {
                    updateActionStatus(
                        actionId = plan.actionId,
                        status = ApprovalStatus.RUNNING,
                        completedSteps = 0,
                        activeStep = 0,
                        persistNow = true
                    )
                    service.startTapOnScreen(
                        plan = plan,
                        targetText = target,
                        blockSensitive = blockSensitive,
                        initialDelayMs = 150L
                    )
                } else {
                    val errText = ConfirmationFormatter.formatFailure(
                        "Enable Accessibility Service in Setup & Test -> Permissions Center to tap items on screen."
                    )
                    val helpMsg = ChatMessage(
                        sender = MessageSender.AGENT,
                        text = errText,
                        isError = true,
                        excludeFromContext = true
                    )
                    updateActionStatus(
                        actionId = plan.actionId,
                        status = ApprovalStatus.FAILED,
                        completedSteps = 0,
                        activeStep = -1,
                        notes = "Accessibility Service required for tap_on_screen",
                        appendChatMessage = helpMsg,
                        persistNow = true
                    )
                    AgentSpeechFeedback.announceOutcome(
                        context = appContext,
                        message = errText,
                        isSuccess = false,
                        voiceSettings = _uiState.value.voiceSettings
                    )
                }
            }

            ToolName.SCROLL_SCREEN -> {
                val dir = toolCall.direction ?: "down"
                val service = AgentAccessibilityService.getInstance()
                if (service != null) {
                    updateActionStatus(
                        actionId = plan.actionId,
                        status = ApprovalStatus.RUNNING,
                        completedSteps = 0,
                        activeStep = 0,
                        persistNow = true
                    )
                    service.startDirectScroll(plan, dir)
                } else {
                    val errText = ConfirmationFormatter.formatFailure(
                        "Enable Accessibility Service in Setup & Test -> Permissions Center to scroll screens."
                    )
                    val helpMsg = ChatMessage(
                        sender = MessageSender.AGENT,
                        text = errText,
                        isError = true,
                        excludeFromContext = true
                    )
                    updateActionStatus(
                        actionId = plan.actionId,
                        status = ApprovalStatus.FAILED,
                        completedSteps = 0,
                        activeStep = -1,
                        notes = "Accessibility Service required for scroll",
                        appendChatMessage = helpMsg,
                        persistNow = true
                    )
                    AgentSpeechFeedback.announceOutcome(
                        context = appContext,
                        message = errText,
                        isSuccess = false,
                        voiceSettings = _uiState.value.voiceSettings
                    )
                }
            }

            ToolName.UI_AUTOMATE -> {
                val effectiveProvider = if (_uiState.value.isUsingBuiltInGemini) null else _uiState.value.activeProvider
                val service = AgentAccessibilityService.getInstance()
                if (service == null) {
                    refreshPermissionsStatus()
                    val errText = ConfirmationFormatter.formatFailure(
                        "Accessibility Service is not enabled yet. Open Setup & Test -> Permissions Center to turn on \"My Personal Agent\", then tap Approve again."
                    )
                    val helpMsg = ChatMessage(
                        sender = MessageSender.AGENT,
                        text = errText,
                        isError = true,
                        excludeFromContext = true
                    )
                    val updated = _uiState.value.chatMessages + helpMsg
                    _uiState.update { it.copy(chatMessages = updated) }
                    storage.saveChatMessages(updated)
                    AgentSpeechFeedback.announceOutcome(
                        context = appContext,
                        message = errText,
                        isSuccess = false,
                        voiceSettings = _uiState.value.voiceSettings
                    )
                    return
                }

                val goal = plan.editedInstruction?.takeIf { it.isNotBlank() }
                    ?: toolCall.goal?.takeIf { it.isNotBlank() }
                    ?: plan.title
                updateActionStatus(
                    actionId = plan.actionId,
                    status = ApprovalStatus.RUNNING,
                    completedSteps = 0,
                    activeStep = 0,
                    persistNow = true
                )
                service.startAiUiAutomation(
                    plan = plan,
                    goal = goal,
                    provider = effectiveProvider,
                    blockSensitive = blockSensitive,
                    maxSteps = 15,
                    conversationHistory = _uiState.value.chatMessages
                )
            }

            else -> {
                // Direct native Android tool execution (<300ms)
                val startMillis = System.currentTimeMillis()
                val phaseLabel = toolCall.reason.ifBlank { plan.title }
                _uiState.update {
                    it.copy(
                        isAgentLoopRunning = true,
                        liveAgentStatusText = phaseLabel,
                        livePhaseStartMillis = startMillis
                    )
                }
                updateActionStatus(
                    actionId = plan.actionId,
                    status = ApprovalStatus.RUNNING,
                    completedSteps = 0,
                    activeStep = 0,
                    persistNow = false
                )

                val execResult = withContext(Dispatchers.Main) {
                    NativeToolExecutor.executeDirectTool(appContext, toolCall)
                }
                val elapsed = formatElapsed(System.currentTimeMillis() - startMillis)

                _uiState.update {
                    it.copy(
                        isAgentLoopRunning = false,
                        liveAgentStatusText = "",
                        livePhaseStartMillis = 0L
                    )
                }

                if (execResult.success) {
                    val confirmText = ConfirmationFormatter.formatSuccess(execResult.summary, elapsed)
                    val doneMsg = ChatMessage(
                        sender = MessageSender.AGENT,
                        text = confirmText
                    )
                    updateActionStatus(
                        actionId = plan.actionId,
                        status = ApprovalStatus.COMPLETED,
                        completedSteps = plan.steps.size.coerceAtLeast(1),
                        activeStep = -1,
                        notes = confirmText,
                        durationLabel = "${execResult.summary} · $elapsed",
                        appendChatMessage = doneMsg,
                        persistNow = true
                    )
                    AgentSpeechFeedback.announceOutcome(
                        context = appContext,
                        message = confirmText,
                        isSuccess = true,
                        voiceSettings = _uiState.value.voiceSettings
                    )
                } else {
                    val failText = ConfirmationFormatter.formatFailure(execResult.summary, elapsed)
                    val errMsg = ChatMessage(
                        sender = MessageSender.AGENT,
                        text = failText,
                        isError = true,
                        excludeFromContext = true
                    )
                    updateActionStatus(
                        actionId = plan.actionId,
                        status = ApprovalStatus.FAILED,
                        completedSteps = 0,
                        activeStep = -1,
                        notes = execResult.summary,
                        durationLabel = "Failed · $elapsed",
                        appendChatMessage = errMsg,
                        persistNow = true
                    )
                    AgentSpeechFeedback.announceOutcome(
                        context = appContext,
                        message = failText,
                        isSuccess = false,
                        voiceSettings = _uiState.value.voiceSettings
                    )
                }
            }
        }
    }

    private suspend fun runDemoStepExecution(plan: ActionPlan) {
        val total = plan.steps.size.coerceAtLeast(1)
        for (stepIndex in 0 until total) {
            updateActionStatus(
                actionId = plan.actionId,
                status = ApprovalStatus.RUNNING,
                completedSteps = stepIndex,
                activeStep = stepIndex,
                persistNow = false
            )
            delay(650L)
        }

        val summaryText = buildString {
            append("Demo action \"${plan.title}\" finished all $total steps.")
            if (!plan.editedInstruction.isNullOrBlank()) {
                append("\nApplied your edit: \"${plan.editedInstruction}\"")
            }
        }
        val completionMsg = ChatMessage(
            sender = MessageSender.AGENT,
            text = summaryText,
            excludeFromContext = true
        )

        updateActionStatus(
            actionId = plan.actionId,
            status = ApprovalStatus.COMPLETED,
            completedSteps = total,
            activeStep = -1,
            notes = "Demo completed cleanly on-device.",
            appendChatMessage = completionMsg,
            persistNow = true
        )
    }

    fun stopRunningAgent() {
        AgentAccessibilityService.stopActiveExecution(appContext)
        _uiState.update {
            it.copy(
                isAgentLoopRunning = false,
                liveAgentStatusText = "",
                livePhaseStartMillis = 0L,
                snackbarMessage = "Agent stopped"
            )
        }
    }

    private fun formatElapsed(elapsedMillis: Long): String {
        val seconds = (elapsedMillis.coerceAtLeast(100L)) / 1000.0
        return String.format(Locale.US, "%.1fs", seconds)
    }

    fun cancelAction(actionId: String) {
        if (_uiState.value.livePendingApprovalPlan?.actionId == actionId) {
            _uiState.update { it.copy(livePendingApprovalPlan = null) }
        }
        viewModelScope.launch {
            updateActionStatus(
                actionId = actionId,
                status = ApprovalStatus.CANCELLED,
                completedSteps = 0,
                activeStep = -1,
                notes = "Cancelled by user.",
                persistNow = true
            )
            showMessage("Action cancelled")
        }
    }

    fun startNewChat() {
        if (_uiState.value.isAgentLoopRunning) {
            stopRunningAgent()
        }
        _uiState.update {
            it.copy(
                destination = AppDestination.CHAT,
                chatMessages = emptyList(),
                memorySuggestions = emptyMap(),
                livePendingApprovalPlan = null,
                isSending = false
            )
        }
        viewModelScope.launch {
            val newSession = storage.startNewChatSession()
            _uiState.update { it.copy(sessionId = newSession) }
        }
    }

    fun clearApprovalHistory() {
        _uiState.update {
            it.copy(approvalHistory = emptyList(), snackbarMessage = "Approval history cleared")
        }
        viewModelScope.launch {
            storage.saveApprovalHistory(emptyList())
        }
    }

    private suspend fun updateActionStatus(
        actionId: String,
        status: ApprovalStatus,
        completedSteps: Int,
        activeStep: Int,
        blockedReason: String? = null,
        notes: String? = null,
        durationLabel: String? = null,
        appendChatMessage: ChatMessage? = null,
        persistNow: Boolean = true
    ) {
        val currentState = _uiState.value
        val baseMessages = currentState.chatMessages.map { msg ->
            if (msg.actionPlan?.actionId == actionId) {
                msg.copy(
                    approvalStatus = status,
                    completedStepsCount = completedSteps,
                    activeStepIndex = activeStep,
                    isBlockedBySafety = status == ApprovalStatus.BLOCKED || msg.isBlockedBySafety,
                    blockedReason = blockedReason ?: msg.blockedReason,
                    durationLabel = durationLabel ?: msg.durationLabel
                )
            } else {
                msg
            }
        }
        val updatedMessages = if (appendChatMessage != null) baseMessages + appendChatMessage else baseMessages
        val updatedHistory = currentState.approvalHistory.map { rec ->
            if (rec.actionId == actionId) {
                rec.copy(
                    status = status,
                    notes = notes ?: rec.notes
                )
            } else {
                rec
            }
        }
        _uiState.update {
            it.copy(chatMessages = updatedMessages, approvalHistory = updatedHistory)
        }
        if (persistNow) {
            persistMessagesAndThread(updatedMessages, updatedHistory)
        }
    }
}
