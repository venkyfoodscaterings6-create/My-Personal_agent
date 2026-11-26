package com.example

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Hands-free wake-word foreground service (like "Hey Google" -> "Hey Agent").
 * Uses native Android SpeechRecognizer (preferring on-device recognizer on Android 12+ when available)
 * with microphone foregroundServiceType, automatically yielding the microphone whenever
 * VoiceOverlayActivity, Chat mic, or Text-to-Speech is active.
 */
class WakeWordListeningService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val localStorage by lazy { LocalStorage(applicationContext) }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningNow = false
    private var isDestroyed = false
    private var wakeTriggeredInCurrentSession = false
    private var consecutiveErrors = 0
    private var pendingWakeOnlyRunnable: Runnable? = null

    @Volatile
    private var currentWakePhrase: String = VoiceSettings.DEFAULT_WAKE_PHRASE
    @Volatile
    private var currentLanguage: VoiceLanguage = VoiceLanguage.EN_IN

    private val restartRunnable = Runnable {
        if (!isDestroyed) {
            maybeStartListening()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this

        serviceScope.launch {
            localStorage.stateFlow.collect { persisted ->
                val settings = persisted.voiceSettings
                currentWakePhrase = settings.wakePhrase.ifBlank { VoiceSettings.DEFAULT_WAKE_PHRASE }
                currentLanguage = settings.voiceLanguage
                if (!settings.wakeWordEnabled) {
                    mainHandler.post { stopSelfSafely() }
                } else {
                    mainHandler.post {
                        updateForegroundNotification(currentWakePhrase)
                    }
                }
            }
        }

        // Automatically pause recognition while TTS is speaking so the mic never hears itself
        serviceScope.launch {
            AgentSpeechFeedback.isSpeaking.collect { speaking ->
                mainHandler.post {
                    if (speaking) {
                        pauseRecognitionForExternalUse()
                    } else if (!isDestroyed && !_externalMicBusy.value) {
                        scheduleRestart(450L)
                    }
                }
            }
        }

        // Automatically pause recognition when VoiceOverlayActivity or MainActivity mic is active
        serviceScope.launch {
            _externalMicBusy.collect { busy ->
                mainHandler.post {
                    if (busy) {
                        pauseRecognitionForExternalUse()
                    } else if (!isDestroyed && !AgentSpeechFeedback.isSpeaking.value) {
                        scheduleRestart(450L)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_WAKE_WORD) {
            serviceScope.launch {
                val latest = localStorage.readLatestState().voiceSettings
                localStorage.saveVoiceSettings(latest.copy(wakeWordEnabled = false))
            }
            stopSelfSafely()
            return START_NOT_STICKY
        }

        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            _statusNote.value = "Microphone permission required for hands-free listening."
            stopSelfSafely()
            return START_NOT_STICKY
        }

        intent?.getStringExtra(EXTRA_WAKE_PHRASE)?.takeIf { it.isNotBlank() }?.let {
            currentWakePhrase = it
        }

        val notification = buildNotification(currentWakePhrase)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            _statusNote.value = "Could not start hands-free microphone service: ${e.message ?: "system restriction"}"
            stopSelfSafely()
            return START_NOT_STICKY
        }

        _isRunning.value = true
        _statusNote.value = "Listening for \"$currentWakePhrase\"…"
        scheduleRestart(250L)
        return START_STICKY
    }

    override fun onDestroy() {
        isDestroyed = true
        cancelPendingCallbacks()
        destroyRecognizer()
        serviceScope.cancel()
        _isRunning.value = false
        if (serviceInstance === this) {
            serviceInstance = null
        }
        super.onDestroy()
    }

    private fun stopSelfSafely() {
        isDestroyed = true
        cancelPendingCallbacks()
        destroyRecognizer()
        _isRunning.value = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun cancelPendingCallbacks() {
        mainHandler.removeCallbacks(restartRunnable)
        pendingWakeOnlyRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingWakeOnlyRunnable = null
    }

    private fun pauseRecognitionForExternalUse() {
        cancelPendingCallbacks()
        destroyRecognizer()
    }

    private fun scheduleRestart(delayMs: Long) {
        if (isDestroyed) return
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.postDelayed(restartRunnable, delayMs)
    }

    private fun maybeStartListening() {
        if (isDestroyed || isListeningNow) return
        if (_externalMicBusy.value || AgentSpeechFeedback.isSpeaking.value) return

        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            stopSelfSafely()
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            _statusNote.value = "Speech recognition service unavailable on this device."
            stopSelfSafely()
            return
        }

        destroyRecognizer()
        wakeTriggeredInCurrentSession = false

        runCatching {
            val recognizer = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
            ) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else {
                SpeechRecognizer.createSpeechRecognizer(this)
            }
            speechRecognizer = recognizer

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListeningNow = true
                    consecutiveErrors = 0
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    isListeningNow = false
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (wakeTriggeredInCurrentSession || _externalMicBusy.value) return
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (partial.isBlank()) return

                    val match = WakeWordMatcher.extractWakeMatch(partial, currentWakePhrase) ?: return
                    // If user already spoke a trailing command right after "Hey Agent", wait briefly for final result;
                    // if they only said "Hey Agent", schedule a quick 650ms trigger so it responds super fast!
                    pendingWakeOnlyRunnable?.let { mainHandler.removeCallbacks(it) }
                    if (match.inlineCommand.isNullOrBlank()) {
                        val trigger = Runnable {
                            if (!wakeTriggeredInCurrentSession && !isDestroyed && !_externalMicBusy.value) {
                                handleWakeDetected(inlineCommand = null)
                            }
                        }
                        pendingWakeOnlyRunnable = trigger
                        mainHandler.postDelayed(trigger, 650L)
                    } else {
                        val trigger = Runnable {
                            if (!wakeTriggeredInCurrentSession && !isDestroyed && !_externalMicBusy.value) {
                                handleWakeDetected(inlineCommand = match.inlineCommand)
                            }
                        }
                        pendingWakeOnlyRunnable = trigger
                        mainHandler.postDelayed(trigger, 1100L)
                    }
                }

                override fun onResults(results: Bundle?) {
                    isListeningNow = false
                    pendingWakeOnlyRunnable?.let { mainHandler.removeCallbacks(it) }
                    pendingWakeOnlyRunnable = null

                    if (wakeTriggeredInCurrentSession) {
                        destroyRecognizer()
                        return
                    }

                    val candidates = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        .orEmpty()

                    val match = candidates.firstNotNullOfOrNull { candidate ->
                        WakeWordMatcher.extractWakeMatch(candidate, currentWakePhrase)
                    }

                    destroyRecognizer()
                    if (match != null) {
                        handleWakeDetected(match.inlineCommand)
                    } else {
                        scheduleRestart(300L)
                    }
                }

                override fun onError(error: Int) {
                    isListeningNow = false
                    pendingWakeOnlyRunnable?.let { mainHandler.removeCallbacks(it) }
                    pendingWakeOnlyRunnable = null
                    destroyRecognizer()

                    if (wakeTriggeredInCurrentSession || isDestroyed) return
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        _statusNote.value = "Microphone permission needed."
                        stopSelfSafely()
                        return
                    }

                    val retryDelay = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 350L
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                        SpeechRecognizer.ERROR_AUDIO -> {
                            consecutiveErrors++
                            (1200L * consecutiveErrors.coerceAtMost(4))
                        }
                        else -> {
                            consecutiveErrors++
                            (1000L * consecutiveErrors.coerceAtMost(5))
                        }
                    }
                    scheduleRestart(retryDelay)
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage.bcp47Tag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, currentLanguage.bcp47Tag)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
            }

            isListeningNow = true
            recognizer.startListening(intent)
        }.onFailure {
            isListeningNow = false
            destroyRecognizer()
            scheduleRestart(2000L)
        }
    }

    private fun handleWakeDetected(inlineCommand: String?) {
        if (wakeTriggeredInCurrentSession) return
        wakeTriggeredInCurrentSession = true
        cancelPendingCallbacks()
        destroyRecognizer()

        // Play signature Assistant rising chime immediately
        AgentSpeechFeedback.playWakeChime()

        // Launch transparent VoiceOverlayActivity over whichever app is currently open
        val overlayIntent = Intent(this, VoiceOverlayActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
            putExtra(VoiceOverlayActivity.EXTRA_WAKE_TRIGGERED, true)
            if (!inlineCommand.isNullOrBlank()) {
                putExtra(VoiceOverlayActivity.EXTRA_PREFILLED_COMMAND, inlineCommand)
            }
        }
        runCatching { startActivity(overlayIntent) }
            .onFailure {
                // If background activity launch is blocked by OEM policy, still respond aloud and restart!
                AgentSpeechFeedback.speak(
                    context = applicationContext,
                    text = if (currentLanguage == VoiceLanguage.TE_IN) "చెప్పండి, నేను సిద్ధంగా ఉన్నాను." else "Yes, I'm here! Tap Ask Agent to continue.",
                    language = currentLanguage
                )
                scheduleRestart(1800L)
            }
    }

    private fun destroyRecognizer() {
        isListeningNow = false
        runCatching {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        }
        speechRecognizer = null
    }

    private fun ensureChannel(): NotificationManager? {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hands-Free Wake Word",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Allows My Personal Agent to respond hands-free when you say your wake phrase."
            }
            manager.createNotificationChannel(channel)
        }
        return manager
    }

    private fun buildNotification(wakePhrase: String): Notification {
        ensureChannel()

        val askIntent = Intent(this, VoiceOverlayActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
            putExtra(VoiceOverlayActivity.EXTRA_WAKE_TRIGGERED, true)
        }
        val askPendingIntent = PendingIntent.getActivity(
            this,
            201,
            askIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, WakeWordListeningService::class.java).apply {
            action = ACTION_STOP_WAKE_WORD
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            202,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_robot_tile)
            .setContentTitle("Listening for \"$wakePhrase\"")
            .setContentText("Say \"$wakePhrase\" anytime to speak hands-free.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(askPendingIntent)
            .addAction(R.drawable.ic_robot_tile, "Ask Now", askPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Turn Off", stopPendingIntent)
            .build()
    }

    private fun updateForegroundNotification(wakePhrase: String) {
        if (!_isRunning.value || isDestroyed) return
        val manager = ensureChannel() ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(wakePhrase)) }
    }

    companion object {
        private const val CHANNEL_ID = "agent_wake_word_channel"
        private const val NOTIFICATION_ID = 4109
        const val ACTION_STOP_WAKE_WORD = "com.example.ACTION_STOP_WAKE_WORD"
        const val EXTRA_WAKE_PHRASE = "extra_wake_phrase"

        @Volatile
        private var serviceInstance: WakeWordListeningService? = null

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _statusNote = MutableStateFlow("")
        val statusNote: StateFlow<String> = _statusNote.asStateFlow()

        private val _externalMicBusy = MutableStateFlow(false)

        fun setExternalMicBusy(busy: Boolean) {
            _externalMicBusy.value = busy
        }

        fun start(context: Context, wakePhrase: String = VoiceSettings.DEFAULT_WAKE_PHRASE) {
            val intent = Intent(context, WakeWordListeningService::class.java).apply {
                putExtra(EXTRA_WAKE_PHRASE, wakePhrase)
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, WakeWordListeningService::class.java))
            }
            _isRunning.value = false
        }
    }
}
