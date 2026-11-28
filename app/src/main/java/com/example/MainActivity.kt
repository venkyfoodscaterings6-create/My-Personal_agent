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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.ApprovalsScreen
import com.example.ui.ChatScreen
import com.example.ui.LiveVoiceScreen
import com.example.ui.MemoryDatabaseScreen
import com.example.ui.SettingsScreen
import com.example.ui.SetupGuideScreen
import com.example.ui.theme.LocalAgentGlassColors
import com.example.ui.theme.PersonalAgentTheme
import com.example.ui.theme.spatialBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // STEP 1: Configure Firebase App Check with launch intent BEFORE UI, ViewModels, or Firebase calls.
        configureAppCheck(this, intent)
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
    var startLiveVoiceAfterPermission by remember { mutableStateOf(false) }
    var enableWakeWordAfterPermission by remember { mutableStateOf(false) }
    var voicePartialText by remember { mutableStateOf("") }
    var voiceFinalEvent by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var voiceBoundSessionId by remember { mutableStateOf("") }

    val latestVoiceSettings by rememberUpdatedState(uiState.voiceSettings)
    val latestDestination by rememberUpdatedState(uiState.destination)
    val latestLiveContinuous by rememberUpdatedState(uiState.liveContinuousMode)
    val latestSessionId by rememberUpdatedState(uiState.sessionId)

    val startLiveListeningRef = remember { arrayOf<(() -> Unit)?>(null) }

    val speechFallbackLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val boundSession = voiceBoundSessionId
        voiceBoundSessionId = ""
        if (result.resultCode == Activity.RESULT_OK) {
            // Strict session isolation: ignore late voice fallback results if user switched/started a New Chat
            if (latestDestination != AppDestination.LIVE_VOICE &&
                boundSession.isNotBlank() &&
                boundSession != latestSessionId
            ) {
                voicePartialText = ""
                return@rememberLauncherForActivityResult
            }
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (spoken.isNotBlank()) {
                voicePartialText = ""
                if (latestDestination == AppDestination.LIVE_VOICE) {
                    viewModel.handleLiveVoiceUtterance(spoken) { startLiveListeningRef[0]?.invoke() }
                } else if (latestVoiceSettings.autoSendVoiceCommands) {
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
                if (latestDestination == AppDestination.LIVE_VOICE) {
                    viewModel.setLiveUserTranscript(partial)
                } else if (voiceBoundSessionId.isBlank() || voiceBoundSessionId == latestSessionId) {
                    voicePartialText = partial
                }
            },
            onFinalText = { finalText ->
                val boundSession = voiceBoundSessionId
                voiceBoundSessionId = ""
                voicePartialText = ""
                if (latestDestination == AppDestination.LIVE_VOICE) {
                    viewModel.handleLiveVoiceUtterance(finalText) { startLiveListeningRef[0]?.invoke() }
                } else if (boundSession.isNotBlank() && boundSession != latestSessionId) {
                    // Discard stale speech from a previous chat session
                    return@VoiceController
                } else if (latestVoiceSettings.autoSendVoiceCommands) {
                    viewModel.sendUserMessage(finalText)
                } else {
                    voiceFinalEvent = System.currentTimeMillis() to finalText
                }
            },
            onRequestFallbackIntent = { intent ->
                runCatching { speechFallbackLauncher.launch(intent) }
                    .onFailure {
                        voiceBoundSessionId = ""
                        viewModel.showMessage("No voice recognition service available on this device.")
                    }
            },
            onVoiceError = { errMsg ->
                voiceBoundSessionId = ""
                voicePartialText = ""
                if (latestDestination == AppDestination.LIVE_VOICE) {
                    viewModel.setLiveVoicePhase(LiveVoicePhase.IDLE)
                }
                viewModel.showMessage(errMsg)
            },
            onSilenceTimeout = {
                voiceBoundSessionId = ""
                voicePartialText = ""
                if (latestDestination == AppDestination.LIVE_VOICE && latestLiveContinuous) {
                    scope.launch {
                        delay(350L)
                        if (latestDestination == AppDestination.LIVE_VOICE && latestLiveContinuous) {
                            startLiveListeningRef[0]?.invoke()
                        }
                    }
                } else if (latestDestination == AppDestination.LIVE_VOICE) {
                    viewModel.setLiveVoicePhase(LiveVoicePhase.IDLE)
                }
            }
        )
    }

    val startLiveListeningNow: () -> Unit = {
        AgentSpeechFeedback.stopSpeaking()
        voiceController.stopSpeaking()
        viewModel.setLiveUserTranscript("")
        viewModel.setLiveVoicePhase(LiveVoicePhase.LISTENING)
        voiceController.startListening(
            language = latestVoiceSettings.voiceLanguage,
            continuousSoftRetry = latestLiveContinuous
        )
    }
    startLiveListeningRef[0] = startLiveListeningNow

    val isListening by voiceController.isListening.collectAsStateWithLifecycle()
    val micRmsEnergy by voiceController.micRmsEnergy.collectAsStateWithLifecycle()
    val isSpeakingGlobal by AgentSpeechFeedback.isSpeaking.collectAsStateWithLifecycle()
    val playbackEnergy by AgentSpeechFeedback.playbackEnergy.collectAsStateWithLifecycle()
    val usingNeuralVoiceNow by AgentSpeechFeedback.usingNeuralVoiceNow.collectAsStateWithLifecycle()
    val isSpeakingTts = isSpeakingGlobal
    val liveAudioEnergy = when {
        isListening -> micRmsEnergy
        uiState.liveVoicePhase == LiveVoicePhase.SPEAKING || isSpeakingGlobal -> playbackEnergy
        else -> 0.08f
    }

    val stopAllSpeaking: () -> Unit = {
        voiceController.stopSpeaking()
        AgentSpeechFeedback.stopSpeaking()
    }

    val cancelVoiceAndClearDrafts: () -> Unit = {
        voiceController.stopListening()
        stopAllSpeaking()
        voiceBoundSessionId = ""
        voicePartialText = ""
        voiceFinalEvent = null
    }

    DisposableEffect(voiceController) {
        onDispose { voiceController.destroy() }
    }

    // Yield WakeWordListeningService mic whenever the main chat mic or Live Voice screen is active
    LaunchedEffect(isListening, uiState.destination) {
        WakeWordListeningService.setExternalMicBusy(
            isListening || uiState.destination == AppDestination.LIVE_VOICE
        )
    }

    // Native Compose ActivityResultContracts permission launchers (no Accompanist)
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.refreshPermissionsStatus()
        if (granted && enableWakeWordAfterPermission) {
            enableWakeWordAfterPermission = false
            viewModel.setWakeWordEnabled(true)
        } else if (granted && startLiveVoiceAfterPermission) {
            startLiveVoiceAfterPermission = false
            viewModel.resetLiveVoiceSession()
            viewModel.navigateTo(AppDestination.LIVE_VOICE)
            startLiveListeningNow()
        } else if (granted && startMicAfterPermission) {
            startMicAfterPermission = false
            voiceBoundSessionId = latestSessionId
            voicePartialText = ""
            voiceController.startListening(latestVoiceSettings.voiceLanguage)
        } else {
            startMicAfterPermission = false
            startLiveVoiceAfterPermission = false
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

    // Auto-speak newly arriving conversational AI replies via shared AgentSpeechFeedback
    // (Tool execution confirmations like "Yes, completed!" and SQL memory saves are already spoken at source)
    val sessionMountMillis = remember { System.currentTimeMillis() }
    var lastSpokenMessageId by remember { mutableStateOf<String?>(null) }
    val latestMsg = uiState.chatMessages.lastOrNull()
    LaunchedEffect(latestMsg?.id, uiState.voiceSettings.speakRepliesAloud, uiState.destination) {
        if (
            uiState.voiceSettings.speakRepliesAloud &&
            uiState.destination != AppDestination.LIVE_VOICE &&
            latestMsg != null &&
            latestMsg.sender == MessageSender.AGENT &&
            latestMsg.actionPlan == null &&
            latestMsg.generatedFile == null &&
            !latestMsg.isError &&
            !latestMsg.isBlockedBySafety &&
            latestMsg.modelBadge != "SQL Memory" &&
            !latestMsg.text.startsWith("Yes, completed!") &&
            latestMsg.timestampMillis >= sessionMountMillis &&
            latestMsg.id != lastSpokenMessageId &&
            latestMsg.text.isNotBlank()
        ) {
            lastSpokenMessageId = latestMsg.id
            AgentSpeechFeedback.speak(context, latestMsg.text, uiState.voiceSettings)
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
                    WakeWordListeningService.setAppInForeground(true)
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
                    WakeWordListeningService.setAppInForeground(false)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            AgentSpeechFeedback.isMainAppInForeground = false
            WakeWordListeningService.setAppInForeground(false)
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

    val openLiveVoiceMode: () -> Unit = {
        stopAllSpeaking()
        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasMic) {
            viewModel.resetLiveVoiceSession()
            viewModel.navigateTo(AppDestination.LIVE_VOICE)
            startLiveListeningNow()
        } else {
            startLiveVoiceAfterPermission = true
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val exitLiveVoiceMode: () -> Unit = {
        voiceController.stopListening()
        stopAllSpeaking()
        viewModel.resetLiveVoiceSession()
        viewModel.navigateTo(AppDestination.CHAT)
    }

    val onToggleMic: () -> Unit = {
        if (isListening) {
            // Patient buffer commit: tapping Mic while active sends any words already captured
            voiceController.stopListeningAndCommit()
        } else {
            val hasMic = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasMic) {
                voiceBoundSessionId = latestSessionId
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
        } else if (uiState.destination == AppDestination.LIVE_VOICE) {
            exitLiveVoiceMode()
        } else {
            viewModel.navigateTo(AppDestination.CHAT)
        }
    }

    PersonalAgentTheme(themePreference = uiState.themePreference) {
        val glass = LocalAgentGlassColors.current
        val drawerItemColors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = glass.accentBlue.copy(alpha = 0.24f),
            selectedIconColor = glass.accentBlue,
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
            selectedBadgeColor = glass.accentBlue,
            unselectedContainerColor = Color.Transparent,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurface,
            unselectedBadgeColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = uiState.destination == AppDestination.CHAT,
            drawerContent = {
                val drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)
                ModalDrawerSheet(
                    modifier = Modifier
                        .widthIn(max = 312.dp)
                        .border(1.dp, glass.luminousBorderBrush(), drawerShape),
                    drawerShape = drawerShape,
                    drawerContainerColor = glass.sheetFill
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
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
                                text = uiState.activeHeaderLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        NavigationDrawerItem(
                            label = { Text("New Chat") },
                            icon = { Icon(Icons.Outlined.AddComment, contentDescription = null) },
                            selected = false,
                            colors = drawerItemColors,
                            onClick = {
                                scope.launch { drawerState.close() }
                                cancelVoiceAndClearDrafts()
                                viewModel.startNewChat()
                            },
                            modifier = Modifier
                                .padding(NavigationDrawerItemDefaults.ItemPadding)
                                .testTag("drawer_new_chat")
                        )

                        // Saved Multi-Thread Chats Section
                        if (uiState.chatThreads.isNotEmpty() || uiState.threadSearchQuery.isNotBlank()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                thickness = 0.5.dp,
                                color = glass.hairlineDivider
                            )
                            Text(
                                text = stringResource(R.string.drawer_saved_chats),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                            )
                            if (uiState.chatThreads.size >= 2 || uiState.threadSearchQuery.isNotBlank()) {
                                OutlinedTextField(
                                    value = uiState.threadSearchQuery,
                                    onValueChange = viewModel::updateThreadSearchQuery,
                                    placeholder = {
                                        Text(
                                            text = stringResource(R.string.drawer_search_chats_hint),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.Search,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    trailingIcon = if (uiState.threadSearchQuery.isNotBlank()) {
                                        {
                                            IconButton(onClick = { viewModel.updateThreadSearchQuery("") }) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Close,
                                                    contentDescription = "Clear search",
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                    singleLine = true,
                                    shape = RoundedCornerShape(18.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 4.dp)
                                        .testTag("drawer_thread_search")
                                )
                            }

                            uiState.chatThreads.take(12).forEach { thread ->
                                val isSelected = thread.id == uiState.sessionId && uiState.destination == AppDestination.CHAT
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 2.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(
                                            if (isSelected) glass.accentBlue.copy(alpha = 0.22f)
                                            else Color.Transparent
                                        )
                                        .then(
                                            if (isSelected) {
                                                Modifier.border(
                                                    1.dp,
                                                    glass.accentBlue.copy(alpha = 0.45f),
                                                    RoundedCornerShape(16.dp)
                                                )
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .clickable {
                                            scope.launch { drawerState.close() }
                                            cancelVoiceAndClearDrafts()
                                            viewModel.selectChatThread(thread.id)
                                        }
                                        .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                                        .testTag("drawer_thread_${thread.id}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.ChatBubbleOutline,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = if (isSelected) glass.accentBlue
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = thread.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${thread.messageCount} msgs",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                    IconButton(
                                        onClick = { viewModel.deleteChatThread(thread.id) },
                                        modifier = Modifier
                                            .size(34.dp)
                                            .testTag("delete_thread_${thread.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.DeleteOutline,
                                            contentDescription = "Delete chat",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            thickness = 0.5.dp,
                            color = glass.hairlineDivider
                        )

                        NavigationDrawerItem(
                            label = { Text(stringResource(R.string.drawer_live_voice_label)) },
                            icon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                            badge = { Text("3D") },
                            selected = uiState.destination == AppDestination.LIVE_VOICE,
                            colors = drawerItemColors,
                            onClick = {
                                scope.launch { drawerState.close() }
                                openLiveVoiceMode()
                            },
                            modifier = Modifier
                                .padding(NavigationDrawerItemDefaults.ItemPadding)
                                .testTag("drawer_live_voice")
                        )

                        NavigationDrawerItem(
                            label = { Text(stringResource(R.string.drawer_memory_db_label)) },
                            icon = { Icon(Icons.Outlined.Storage, contentDescription = null) },
                            badge = {
                                val total = uiState.memories.size + uiState.openTasksCount
                                if (total > 0) Text("$total")
                            },
                            selected = uiState.destination == AppDestination.MEMORY_DB,
                            colors = drawerItemColors,
                            onClick = {
                                scope.launch { drawerState.close() }
                                viewModel.navigateTo(AppDestination.MEMORY_DB)
                            },
                            modifier = Modifier
                                .padding(NavigationDrawerItemDefaults.ItemPadding)
                                .testTag("drawer_memory_db")
                        )

                        NavigationDrawerItem(
                            label = { Text("Hands-Free \"${uiState.voiceSettings.wakePhrase}\"") },
                            icon = { Icon(Icons.Outlined.GraphicEq, contentDescription = null) },
                            badge = {
                                Text(if (uiState.voiceSettings.wakeWordEnabled) "ON" else "OFF")
                            },
                            selected = uiState.voiceSettings.wakeWordEnabled,
                            colors = drawerItemColors,
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
                            colors = drawerItemColors,
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
                            thickness = 0.5.dp,
                            color = glass.hairlineDivider
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
                            colors = drawerItemColors,
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
                            colors = drawerItemColors,
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
                            colors = drawerItemColors,
                            onClick = {
                                scope.launch { drawerState.close() }
                                viewModel.navigateTo(AppDestination.SETTINGS)
                            },
                            modifier = Modifier
                                .padding(NavigationDrawerItemDefaults.ItemPadding)
                                .testTag("drawer_settings")
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .spatialBackdrop()
            ) {
                when (uiState.destination) {
                    AppDestination.CHAT -> ChatScreen(
                        sessionId = uiState.sessionId,
                        messages = uiState.chatMessages,
                        hasMoreOlderMessages = uiState.hasMoreOlderMessages,
                        isLoadingOlderMessages = uiState.isLoadingOlderMessages,
                        onLoadOlderMessages = viewModel::loadOlderMessages,
                        providers = uiState.providers,
                        activeProvider = uiState.activeProvider,
                        activeHeaderLabel = uiState.activeHeaderLabel,
                        isUsingBuiltInGemini = uiState.isUsingBuiltInGemini,
                        builtInGeminiSettings = uiState.builtInGeminiSettings,
                        streamingAssistantText = uiState.streamingAssistantText,
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
                        onStopMicSilently = {
                            voiceController.stopListening()
                            voiceBoundSessionId = ""
                            voicePartialText = ""
                        },
                        onOpenLiveVoice = openLiveVoiceMode,
                        onToggleGoogleSearch = viewModel::toggleGoogleSearchGrounding,
                        onToggleVoiceLanguage = viewModel::toggleVoiceLanguage,
                        onStopSpeaking = stopAllSpeaking,
                        onConsumeVoiceFinal = { voiceFinalEvent = null },
                        onStopAgent = viewModel::stopRunningAgent,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onNewChat = {
                            cancelVoiceAndClearDrafts()
                            viewModel.startNewChat()
                        },
                        onSelectBuiltInGemini = viewModel::selectBuiltInGeminiModel,
                        onSelectProvider = viewModel::selectDefaultProvider,
                        onSendMessage = viewModel::sendUserMessage,
                        onApproveAction = approveWithNotificationCheck,
                        onEditActionInstruction = viewModel::editActionInstruction,
                        onCancelAction = viewModel::cancelAction,
                        onOpenSettingsProviders = {
                            viewModel.jumpToSettingsSection(SettingsFocusSection.PROVIDERS)
                        },
                        onCopyMessage = viewModel::showMessage,
                        onOpenGeneratedFile = viewModel::openGeneratedFile,
                        onSaveGeneratedFile = viewModel::saveGeneratedFileToDownloads,
                        onShareGeneratedFile = viewModel::shareGeneratedFile,
                        memorySuggestions = uiState.memorySuggestions,
                        onSaveToMemory = viewModel::saveMessageToMemory,
                        onAcceptSuggestion = viewModel::acceptMemorySuggestion,
                        onDismissSuggestion = viewModel::dismissMemorySuggestion
                    )

                    AppDestination.LIVE_VOICE -> LiveVoiceScreen(
                        phase = if (isListening) LiveVoicePhase.LISTENING else uiState.liveVoicePhase,
                        liveAudioEnergy = liveAudioEnergy,
                        liveUserTranscript = uiState.liveUserTranscript,
                        liveAgentCaption = uiState.liveAgentCaption,
                        groundingSources = uiState.liveGroundingSources,
                        voiceLanguage = uiState.voiceSettings.voiceLanguage,
                        voicePersonaLabel = uiState.voiceSettings.voicePersona.displayName,
                        usingNeuralVoice = uiState.voiceSettings.useGeminiNeuralVoice && usingNeuralVoiceNow,
                        googleSearchEnabled = uiState.builtInGeminiSettings.googleSearchEnabled,
                        continuousHandsFree = uiState.liveContinuousMode,
                        memoryCount = uiState.memories.size,
                        pendingApprovalPlan = uiState.livePendingApprovalPlan,
                        onToggleMicOrBargeIn = {
                            if (isListening) {
                                voiceController.stopListening()
                                viewModel.setLiveVoicePhase(LiveVoicePhase.IDLE)
                            } else {
                                startLiveListeningNow()
                            }
                        },
                        onToggleGoogleSearch = viewModel::toggleGoogleSearchGrounding,
                        onToggleLanguage = viewModel::toggleVoiceLanguage,
                        onToggleContinuous = viewModel::toggleLiveContinuousMode,
                        onApprovePendingPlan = approveWithNotificationCheck,
                        onCancelPendingPlan = viewModel::cancelAction,
                        onEndSession = exitLiveVoiceMode
                    )

                    AppDestination.MEMORY_DB -> MemoryDatabaseScreen(
                        memories = uiState.memories,
                        tasks = uiState.tasks,
                        logs = uiState.sqlLogs,
                        searchQuery = uiState.sqlSearchQuery,
                        onSearchQueryChange = viewModel::updateSqlSearchQuery,
                        onAddMemory = viewModel::addMemory,
                        onToggleMemoryPin = viewModel::toggleMemoryPin,
                        onDeleteMemory = viewModel::deleteMemory,
                        onAddTask = viewModel::addTask,
                        onToggleTaskCompleted = viewModel::toggleTaskCompleted,
                        onDeleteTask = viewModel::deleteTask,
                        onClearCompletedTasks = viewModel::clearCompletedTasks,
                        onClearLogs = viewModel::clearSqlLogs,
                        onBack = { viewModel.navigateTo(AppDestination.CHAT) }
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
                        onSelectBuiltInGemini = viewModel::selectBuiltInGeminiModel,
                        onUpdateBuiltInGeminiSettings = viewModel::updateBuiltInGeminiSettings,
                        onOpenMemoryDatabase = { viewModel.navigateTo(AppDestination.MEMORY_DB) },
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
                        onTestVoice = viewModel::testCurrentVoice,
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
