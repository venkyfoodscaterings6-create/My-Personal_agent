package com.example

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.ApprovalsScreen
import com.example.ui.ChatScreen
import com.example.ui.SettingsScreen
import com.example.ui.SetupGuideScreen
import com.example.ui.theme.PersonalAgentTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: AgentViewModel = viewModel()
            PersonalAgentApp(viewModel = viewModel)
        }
    }
}

@Composable
fun PersonalAgentApp(viewModel: AgentViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var askedNotificationPermission by remember { mutableStateOf(false) }
    var startMicAfterPermission by remember { mutableStateOf(false) }
    var enableWakeWordAfterPermission by remember { mutableStateOf(false) }
    var voicePartialText by remember { mutableStateOf("") }
    var voiceFinalEvent by remember { mutableStateOf<Pair<Long, String>?>(null) }

    val latestVoiceSettings by rememberUpdatedState(uiState.voiceSettings)

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
                voicePartialText = ""
                if (latestVoiceSettings.autoSendVoiceCommands) {
                    viewModel.sendUserMessage(spoken)
                } else {
                    voiceFinalEvent = System.currentTimeMillis() to spoken
                }
            }
        }
    }

    val voiceController = remember(context) {
        VoiceController(
            context = context,
            onPartialText = { partial ->
                voicePartialText = partial
            },
            onFinalText = { finalText ->
                voicePartialText = ""
                if (latestVoiceSettings.autoSendVoiceCommands) {
                    viewModel.sendUserMessage(finalText)
                } else {
                    voiceFinalEvent = System.currentTimeMillis() to finalText
                }
            },
            onRequestFallbackIntent = { intent ->
                runCatching { speechFallbackLauncher.launch(intent) }
                    .onFailure {
                        viewModel.showMessage("No voice recognition service available on this device.")
                    }
            },
            onVoiceError = { errMsg ->
                voicePartialText = ""
                viewModel.showMessage(errMsg)
            }
        )
    }

    val isListening by voiceController.isListening.collectAsStateWithLifecycle()
    val isSpeakingLocal by voiceController.isSpeaking.collectAsStateWithLifecycle()
    val isSpeakingGlobal by AgentSpeechFeedback.isSpeaking.collectAsStateWithLifecycle()
    val isSpeakingTts = isSpeakingLocal || isSpeakingGlobal

    val stopAllSpeaking: () -> Unit = {
        voiceController.stopSpeaking()
        AgentSpeechFeedback.stopSpeaking()
    }

    DisposableEffect(voiceController) {
        onDispose { voiceController.destroy() }
    }

    // Yield WakeWordListeningService mic whenever the main chat mic is listening
    LaunchedEffect(isListening) {
        WakeWordListeningService.setExternalMicBusy(isListening)
    }

    // Native Compose ActivityResultContracts permission launchers (no Accompanist)
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.refreshPermissionsStatus()
        if (granted && enableWakeWordAfterPermission) {
            enableWakeWordAfterPermission = false
            viewModel.setWakeWordEnabled(true)
        } else if (granted && startMicAfterPermission) {
            startMicAfterPermission = false
            voicePartialText = ""
            voiceController.startListening(latestVoiceSettings.voiceLanguage)
        } else {
            startMicAfterPermission = false
            enableWakeWordAfterPermission = false
            if (!granted) {
                viewModel.showMessage("Microphone permission denied. Allow it in Setup & Test -> Permissions Center.")
            }
        }
    }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.refreshPermissionsStatus()
        viewModel.showMessage(if (granted) "Contacts permission granted" else "Contacts permission denied")
    }

    val callPhonePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.refreshPermissionsStatus()
        viewModel.showMessage(if (granted) "Phone Calls permission granted" else "Phone Calls permission denied")
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.refreshPermissionsStatus()
        if (granted) {
            viewModel.showMessage("Notifications permission granted")
        }
    }

    // Auto-speak newly arriving plain Chat Mode AI replies via shared AgentSpeechFeedback
    // (Agent Mode confirmations, greetings, and errors are already spoken by AgentAccessibilityService / AgentViewModel)
    val sessionMountMillis = remember { System.currentTimeMillis() }
    var lastSpokenMessageId by remember { mutableStateOf<String?>(null) }
    val latestMsg = uiState.chatMessages.lastOrNull()
    LaunchedEffect(latestMsg?.id, uiState.voiceSettings.speakRepliesAloud) {
        if (
            uiState.voiceSettings.speakRepliesAloud &&
            uiState.agentMode == AgentMode.CHAT_ONLY &&
            latestMsg != null &&
            latestMsg.sender == MessageSender.AGENT &&
            latestMsg.actionPlan == null &&
            !latestMsg.isError &&
            latestMsg.timestampMillis >= sessionMountMillis &&
            latestMsg.id != lastSpokenMessageId &&
            latestMsg.text.isNotBlank()
        ) {
            lastSpokenMessageId = latestMsg.id
            AgentSpeechFeedback.speak(context, latestMsg.text, uiState.voiceSettings.voiceLanguage)
        }
    }

    // Stop speaking if user turns off TTS setting
    LaunchedEffect(uiState.voiceSettings.speakRepliesAloud) {
        if (!uiState.voiceSettings.speakRepliesAloud) {
            stopAllSpeaking()
        }
    }

    // Track MainActivity foreground status (for outside-app Toast) and refresh permissions + persistent notification on resume
    val latestRunningState by rememberUpdatedState(uiState.isAgentLoopRunning to uiState.liveAgentStatusText)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    AgentSpeechFeedback.isMainAppInForeground = true
                    viewModel.refreshPermissionsStatus()
                    val (running, statusText) = latestRunningState
                    AgentNotificationHelper.syncPersistentNotification(
                        context = context,
                        enabled = latestVoiceSettings.persistentAskNotificationEnabled,
                        isRunning = running,
                        runningText = statusText
                    )
                }
                Lifecycle.Event.ON_PAUSE -> {
                    AgentSpeechFeedback.isMainAppInForeground = false
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            AgentSpeechFeedback.isMainAppInForeground = false
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val onToggleWakeWord: (Boolean) -> Unit = { enable ->
        if (!enable) {
            viewModel.setWakeWordEnabled(false)
        } else {
            val hasMic = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasMic) {
                viewModel.setWakeWordEnabled(true)
            } else {
                enableWakeWordAfterPermission = true
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    val onTestVoiceWakeUp: () -> Unit = {
        AgentSpeechFeedback.playWakeChime()
        val overlayIntent = Intent(context, VoiceOverlayActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
            putExtra(VoiceOverlayActivity.EXTRA_WAKE_TRIGGERED, true)
        }
        runCatching { context.startActivity(overlayIntent) }
    }

    val onToggleMic: () -> Unit = {
        if (isListening) {
            voiceController.stopListening()
            voicePartialText = ""
        } else {
            val hasMic = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasMic) {
                voicePartialText = ""
                voiceController.startListening(uiState.voiceSettings.voiceLanguage)
            } else {
                startMicAfterPermission = true
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    val openOverlaySettings: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }.onFailure {
                viewModel.showMessage("Could not open Display Over Other Apps settings.")
            }
        }
    }

    val requestNotificationsPermission: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.refreshPermissionsStatus()
        }
    }

    val approveWithNotificationCheck: (String) -> Unit = { actionId ->
        val record = uiState.approvalHistory.firstOrNull { it.actionId == actionId }
        val isRealTask = record != null && !record.isDemo
        if (
            isRealTask &&
            !askedNotificationPermission &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotificationPermission = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.approveAction(actionId)
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val message = uiState.snackbarMessage
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeSnackbar()
        }
    }

    BackHandler(enabled = drawerState.isOpen || uiState.destination != AppDestination.CHAT) {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else {
            viewModel.navigateTo(AppDestination.CHAT)
        }
    }

    PersonalAgentTheme(themePreference = uiState.themePreference) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = uiState.destination == AppDestination.CHAT,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.widthIn(max = 300.dp),
                    drawerContainerColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)
                    ) {
                        Text(
                            text = "My Personal Agent",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = uiState.activeProvider?.displayName ?: "On-device AI & phone control",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    NavigationDrawerItem(
                        label = { Text("New Chat") },
                        icon = { Icon(Icons.Outlined.AddComment, contentDescription = null) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            stopAllSpeaking()
                            viewModel.startNewChat()
                        },
                        modifier = Modifier
                            .padding(NavigationDrawerItemDefaults.ItemPadding)
                            .testTag("drawer_new_chat")
                    )

                    NavigationDrawerItem(
                        label = { Text("Hands-Free \"${uiState.voiceSettings.wakePhrase}\"") },
                        icon = { Icon(Icons.Outlined.GraphicEq, contentDescription = null) },
                        badge = {
                            Text(if (uiState.voiceSettings.wakeWordEnabled) "ON" else "OFF")
                        },
                        selected = uiState.voiceSettings.wakeWordEnabled,
                        onClick = {
                            scope.launch { drawerState.close() }
                            onToggleWakeWord(!uiState.voiceSettings.wakeWordEnabled)
                        },
                        modifier = Modifier
                            .padding(NavigationDrawerItemDefaults.ItemPadding)
                            .testTag("drawer_wake_word")
                    )

                    NavigationDrawerItem(
                        label = { Text("Test Approval Demo") },
                        icon = { Icon(Icons.Outlined.PlayCircle, contentDescription = null) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            viewModel.triggerApprovalDemo()
                        },
                        modifier = Modifier
                            .padding(NavigationDrawerItemDefaults.ItemPadding)
                            .testTag("drawer_test_demo")
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                    )

                    NavigationDrawerItem(
                        label = { Text("Approvals") },
                        icon = { Icon(Icons.Outlined.Checklist, contentDescription = null) },
                        badge = if (uiState.pendingApprovalsCount > 0) {
                            { Text("${uiState.pendingApprovalsCount}") }
                        } else {
                            null
                        },
                        selected = uiState.destination == AppDestination.APPROVALS,
                        onClick = {
                            scope.launch { drawerState.close() }
                            viewModel.navigateTo(AppDestination.APPROVALS)
                        },
                        modifier = Modifier
                            .padding(NavigationDrawerItemDefaults.ItemPadding)
                            .testTag("drawer_approvals")
                    )

                    NavigationDrawerItem(
                        label = { Text("Setup & Test") },
                        icon = { Icon(Icons.Outlined.Build, contentDescription = null) },
                        badge = { Text("${uiState.permissionsStatus.grantedCount}/6") },
                        selected = uiState.destination == AppDestination.SETUP,
                        onClick = {
                            scope.launch { drawerState.close() }
                            viewModel.refreshPermissionsStatus()
                            viewModel.navigateTo(AppDestination.SETUP)
                        },
                        modifier = Modifier
                            .padding(NavigationDrawerItemDefaults.ItemPadding)
                            .testTag("drawer_setup")
                    )

                    NavigationDrawerItem(
                        label = { Text("Settings / API Keys") },
                        icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                        selected = uiState.destination == AppDestination.SETTINGS,
                        onClick = {
                            scope.launch { drawerState.close() }
                            viewModel.navigateTo(AppDestination.SETTINGS)
                        },
                        modifier = Modifier
                            .padding(NavigationDrawerItemDefaults.ItemPadding)
                            .testTag("drawer_settings")
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (uiState.destination) {
                    AppDestination.CHAT -> ChatScreen(
                        messages = uiState.chatMessages,
                        providers = uiState.providers,
                        activeProvider = uiState.activeProvider,
                        agentMode = uiState.agentMode,
                        quickActionChips = uiState.quickActionChips,
                        showStepPlan = uiState.safetyRules.showStepPlan,
                        isSending = uiState.isSending,
                        isAgentLoopRunning = uiState.isAgentLoopRunning,
                        liveAgentStatusText = uiState.liveAgentStatusText,
                        livePhaseStartMillis = uiState.livePhaseStartMillis,
                        isListening = isListening,
                        isSpeakingTts = isSpeakingTts,
                        voiceLanguage = uiState.voiceSettings.voiceLanguage,
                        voicePartialText = voicePartialText,
                        voiceFinalEvent = voiceFinalEvent,
                        onToggleMic = onToggleMic,
                        onToggleVoiceLanguage = viewModel::toggleVoiceLanguage,
                        onStopSpeaking = stopAllSpeaking,
                        onConsumeVoiceFinal = { voiceFinalEvent = null },
                        onStopAgent = viewModel::stopRunningAgent,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onNewChat = {
                            stopAllSpeaking()
                            viewModel.startNewChat()
                        },
                        onSelectProvider = viewModel::selectDefaultProvider,
                        onToggleMode = viewModel::setAgentMode,
                        onSendMessage = viewModel::sendUserMessage,
                        onApproveAction = approveWithNotificationCheck,
                        onEditActionInstruction = viewModel::editActionInstruction,
                        onCancelAction = viewModel::cancelAction,
                        onOpenSettingsProviders = {
                            viewModel.jumpToSettingsSection(SettingsFocusSection.PROVIDERS)
                        },
                        onCopyMessage = viewModel::showMessage
                    )

                    AppDestination.APPROVALS -> ApprovalsScreen(
                        approvalHistory = uiState.approvalHistory,
                        onBack = { viewModel.navigateTo(AppDestination.CHAT) },
                        onApproveAction = approveWithNotificationCheck,
                        onEditActionInstruction = viewModel::editActionInstruction,
                        onCancelAction = viewModel::cancelAction,
                        onClearHistory = viewModel::clearApprovalHistory
                    )

                    AppDestination.SETUP -> SetupGuideScreen(
                        uiState = uiState,
                        onBack = { viewModel.navigateTo(AppDestination.CHAT) },
                        onJumpToSettings = viewModel::jumpToSettingsSection,
                        onRunConnectionTest = viewModel::runConnectionTest,
                        onRunFullSelfTest = viewModel::runFullSelfTest,
                        onRequestMicrophonePermission = {
                            startMicAfterPermission = false
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onRequestContactsPermission = {
                            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                        },
                        onRequestCallPhonePermission = {
                            callPhonePermissionLauncher.launch(Manifest.permission.CALL_PHONE)
                        },
                        onRequestNotificationsPermission = requestNotificationsPermission,
                        onOpenOverlayPermissionSettings = openOverlaySettings
                    )

                    AppDestination.SETTINGS -> SettingsScreen(
                        uiState = uiState,
                        onBack = { viewModel.navigateTo(AppDestination.CHAT) },
                        onSaveProvider = viewModel::saveProvider,
                        onDeleteProvider = viewModel::deleteProvider,
                        onSelectDefaultProvider = viewModel::selectDefaultProvider,
                        onFetchModels = viewModel::fetchModelsForDraft,
                        onClearFetchedModels = viewModel::clearFetchedModelsState,
                        onSaveQuickActionChips = viewModel::saveQuickActionChips,
                        onUpdateSafetyRules = viewModel::updateSafetyRules,
                        onUpdateVoiceSettings = viewModel::updateVoiceSettings,
                        onToggleWakeWord = onToggleWakeWord,
                        onUpdateWakePhrase = viewModel::updateWakePhrase,
                        onTestVoiceWakeUp = onTestVoiceWakeUp,
                        onShowMessage = viewModel::showMessage,
                        onOpenPermissionsCenter = {
                            viewModel.refreshPermissionsStatus()
                            viewModel.navigateTo(AppDestination.SETUP)
                        },
                        onSetThemePreference = viewModel::setThemePreference,
                        onClearFocusSection = viewModel::clearSettingsFocusSection
                    )
                }

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 84.dp)
                )
            }
        }
    }
}
