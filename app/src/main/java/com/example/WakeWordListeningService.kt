package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Hands-free wake-word foreground service (like "Hey Google" -> "Hey Agent").
 *
 * Uses a 100% SILENT AudioRecord Voice Activity Detector (VAD) while waiting for speech so Android's
 * SpeechRecognizer NEVER loops and plays continuous start/stop beeps during silence.
 * Only when real speech is detected does it run a single short recognition pass (preferring the silent
 * on-device recognizer on Android 12+), and it automatically pauses whenever the main app is open,
 * TTS is speaking, or the Chat / Live Voice / Overlay microphone is active.
 */
class WakeWordListeningService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val localStorage by lazy { LocalStorage(applicationContext) }

    private var speechRecognizer: SpeechRecognizer? = null
    private var vadJob: Job? = null
    private var isRecognizingNow = false
    private var isDestroyed = false
    private var wakeTriggeredInCurrentSession = false
    private var pendingWakeOnlyRunnable: Runnable? = null
    private var lastRecognitionEndedAtMillis = 0L

    @Volatile
    private var currentWakePhrase: String = VoiceSettings.DEFAULT_WAKE_PHRASE
    @Volatile
    private var currentLanguage: VoiceLanguage = VoiceLanguage.AUTO
    @Volatile
    private var currentWakeSensitivity: WakeSensitivity = WakeSensitivity.BALANCED

    private val resumeVadRunnable = Runnable {
        if (!isDestroyed && canMonitorSilently()) {
            startSilentVadLoop()
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
                currentWakeSensitivity = settings.wakeSensitivity
                if (!settings.wakeWordEnabled) {
                    mainHandler.post { stopSelfSafely() }
                } else {
                    mainHandler.post {
                        updateForegroundNotification(currentWakePhrase)
                    }
                }
            }
        }

        // Automatically pause whenever TTS is speaking
        serviceScope.launch {
            AgentSpeechFeedback.isSpeaking.collect { speaking ->
                mainHandler.post {
                    if (speaking) {
                        pauseAllListening()
                    } else if (!isDestroyed && canMonitorSilently()) {
                        scheduleVadResume(700L)
                    }
                }
            }
        }

        // Automatically pause when VoiceOverlayActivity, Live Voice, or Chat mic is active
        serviceScope.launch {
            _externalMicBusy.collect { busy ->
                mainHandler.post {
                    if (busy) {
                        pauseAllListening()
                    } else if (!isDestroyed && canMonitorSilently()) {
                        scheduleVadResume(800L)
                    }
                }
            }
        }

        // Automatically pause while MainActivity is open in the foreground (prevents any sound/mic clash while typing)
        serviceScope.launch {
            _appInForeground.collect { inForeground ->
                mainHandler.post {
                    if (inForeground) {
                        pauseAllListening()
                    } else if (!isDestroyed && canMonitorSilently()) {
                        scheduleVadResume(900L)
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
        _statusNote.value = "Listening silently for \"$currentWakePhrase\"…"
        if (canMonitorSilently()) {
            scheduleVadResume(500L)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isDestroyed = true
        pauseAllListening()
        serviceScope.cancel()
        _isRunning.value = false
        if (serviceInstance === this) {
            serviceInstance = null
        }
        super.onDestroy()
    }

    private fun canMonitorSilently(): Boolean {
        return !isDestroyed &&
            !isRecognizingNow &&
            !_externalMicBusy.value &&
            !_appInForeground.value &&
            !AgentSpeechFeedback.isSpeaking.value
    }

    private fun stopSelfSafely() {
        isDestroyed = true
        pauseAllListening()
        _isRunning.value = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun cancelPendingCallbacks() {
        mainHandler.removeCallbacks(resumeVadRunnable)
        pendingWakeOnlyRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingWakeOnlyRunnable = null
    }

    private fun pauseAllListening() {
        cancelPendingCallbacks()
        stopSilentVadLoop()
        destroyRecognizer()
    }

    private fun scheduleVadResume(delayMs: Long) {
        if (isDestroyed) return
        mainHandler.removeCallbacks(resumeVadRunnable)
        mainHandler.postDelayed(resumeVadRunnable, delayMs)
    }

    private fun stopSilentVadLoop() {
        vadJob?.cancel()
        vadJob = null
    }

    /**
     * 100% SILENT Voice Activity Detector using AudioRecord.
     * Makes ZERO system beep sounds while waiting for the user to speak.
     */
    @SuppressLint("MissingPermission")
    private fun startSilentVadLoop() {
        if (!canMonitorSilently()) return
        if (vadJob?.isActive == true) return

        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            stopSelfSafely()
            return
        }

        vadJob = serviceScope.launch {
            val sampleRate = 16_000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return@launch

            val bufferSize = maxOf(minBuf, sampleRate / 5 * 2) // ~200ms buffer
            val recorder = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }.getOrNull() ?: return@launch

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { recorder.release() }
                return@launch
            }

            val pcm = ShortArray(1600) // 100ms chunks at 16kHz
            var consecutiveVoiceFrames = 0
            // Adaptive rolling ambient noise floor calibrated automatically to the room
            var ambientNoiseFloor = 380.0

            try {
                recorder.startRecording()
                while (isActive && canMonitorSilently()) {
                    val readCount = recorder.read(pcm, 0, pcm.size)
                    if (readCount <= 0) {
                        delay(120L)
                        continue
                    }

                    // Cooldown right after a SpeechRecognizer pass so noisy rooms never loop
                    val elapsedSinceLastPass = System.currentTimeMillis() - lastRecognitionEndedAtMillis
                    if (elapsedSinceLastPass < RECOGNITION_COOLDOWN_MS) {
                        consecutiveVoiceFrames = 0
                        continue
                    }

                    var sumSq = 0.0
                    for (i in 0 until readCount) {
                        val s = pcm[i].toDouble()
                        sumSq += s * s
                    }
                    val rms = sqrt(sumSq / readCount)

                    val sensitivity = currentWakeSensitivity
                    val dynamicThreshold = maxOf(
                        sensitivity.minRmsFloor,
                        ambientNoiseFloor * sensitivity.noiseMultiplier
                    ).coerceAtMost(4200.0)

                    if (rms >= dynamicThreshold) {
                        consecutiveVoiceFrames++
                        if (consecutiveVoiceFrames >= REQUIRED_VOICE_FRAMES) {
                            // Voice detected! Release AudioRecord before starting a single SpeechRecognizer pass
                            break
                        }
                    } else {
                        consecutiveVoiceFrames = 0
                        // Smoothly adapt room noise floor on non-speech frames
                        ambientNoiseFloor = (ambientNoiseFloor * 0.92 + rms * 0.08).coerceIn(180.0, 1800.0)
                    }
                }
            } catch (_: Exception) {
                // Ignore AudioRecord interruption cleanly
            } finally {
                runCatching {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop()
                    }
                }
                runCatching { recorder.release() }
            }

            if (isActive && consecutiveVoiceFrames >= REQUIRED_VOICE_FRAMES && canMonitorSilently()) {
                mainHandler.post {
                    runSingleWakeRecognitionPass()
                }
            }
        }
    }

    /**
     * Runs ONE short recognition pass after silent VAD detects human speech,
     * preferring silent on-device SpeechRecognizer on Android 12+.
     */
    private fun runSingleWakeRecognitionPass() {
        if (!canMonitorSilently()) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        stopSilentVadLoop()
        destroyRecognizer()
        wakeTriggeredInCurrentSession = false

        runCatching {
            val useOnDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
            val recognizer = if (useOnDevice) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else {
                SpeechRecognizer.createSpeechRecognizer(this)
            }
            speechRecognizer = recognizer

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isRecognizingNow = true
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    isRecognizingNow = false
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (wakeTriggeredInCurrentSession || _externalMicBusy.value || _appInForeground.value) return
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (partial.isBlank()) return

                    val match = WakeWordMatcher.extractWakeMatch(partial, currentWakePhrase) ?: return
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
                    isRecognizingNow = false
                    lastRecognitionEndedAtMillis = System.currentTimeMillis()
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
                        scheduleVadResume(RECOGNITION_COOLDOWN_MS)
                    }
                }

                override fun onError(error: Int) {
                    isRecognizingNow = false
                    lastRecognitionEndedAtMillis = System.currentTimeMillis()
                    pendingWakeOnlyRunnable?.let { mainHandler.removeCallbacks(it) }
                    pendingWakeOnlyRunnable = null
                    destroyRecognizer()

                    if (wakeTriggeredInCurrentSession || isDestroyed) return
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        _statusNote.value = "Microphone permission needed."
                        stopSelfSafely()
                        return
                    }

                    // Return to 100% silent VAD monitoring (never tight-loop SpeechRecognizer!)
                    scheduleVadResume(RECOGNITION_COOLDOWN_MS)
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage.bcp47Tag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, currentLanguage.bcp47Tag)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                if (useOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
            }

            isRecognizingNow = true
            recognizer.startListening(intent)
        }.onFailure {
            isRecognizingNow = false
            lastRecognitionEndedAtMillis = System.currentTimeMillis()
            destroyRecognizer()
            scheduleVadResume(RECOGNITION_COOLDOWN_MS)
        }
    }

    private fun handleWakeDetected(inlineCommand: String?) {
        if (wakeTriggeredInCurrentSession) return
        wakeTriggeredInCurrentSession = true
        pauseAllListening()

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
                AgentSpeechFeedback.speak(
                    context = applicationContext,
                    text = if (currentLanguage == VoiceLanguage.TE_IN) "చెప్పండి, నేను సిద్ధంగా ఉన్నాను." else "Yes, I'm here! Tap Ask Agent to continue.",
                    language = currentLanguage
                )
                scheduleVadResume(2200L)
            }
    }

    private fun destroyRecognizer() {
        isRecognizingNow = false
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
                setSound(null, null)
                enableVibration(false)
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
            .setContentText("Say \"$wakePhrase\" when outside the app to speak hands-free.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
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
        private const val SPEECH_RMS_THRESHOLD = 1400.0
        private const val REQUIRED_VOICE_FRAMES = 3
        private const val RECOGNITION_COOLDOWN_MS = 2200L

        const val ACTION_STOP_WAKE_WORD = "com.example.ACTION_STOP_WAKE_WORD"
        const val EXTRA_WAKE_PHRASE = "extra_wake_phrase"

        @Volatile
        private var serviceInstance: WakeWordListeningService? = null

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _statusNote = MutableStateFlow("")
        val statusNote: StateFlow<String> = _statusNote.asStateFlow()

        private val _externalMicBusy = MutableStateFlow(false)
        private val _appInForeground = MutableStateFlow(false)

        fun setExternalMicBusy(busy: Boolean) {
            _externalMicBusy.value = busy
        }

        fun setAppInForeground(inForeground: Boolean) {
            _appInForeground.value = inForeground
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
