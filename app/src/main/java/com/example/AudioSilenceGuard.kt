package com.example

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure JVM representation of the phone's current audio/interruption state so
 * [AudioSilenceGuard.evaluatePolicy] can be unit-tested deterministically without an emulator.
 */
data class AudioEnvironmentState(
    val audioMode: Int = AudioManager.MODE_NORMAL,
    val isMusicActive: Boolean = false,
    val ringerMode: Int = AudioManager.RINGER_MODE_NORMAL,
    val isDndActive: Boolean = false,
    val isMicListening: Boolean = false,
    val isOtherAppRecording: Boolean = false,
    val isCallStarting: Boolean = false,
    val isMediaCooldownActive: Boolean = false
)

enum class SilenceReason {
    CALL_STARTING,
    ACTIVE_CALL,
    MIC_ACTIVE,
    OTHER_APP_RECORDING,
    MEDIA_PLAYING,
    MEDIA_JUST_STARTED,
    RINGER_SILENT_OR_VIBRATE,
    DND_ACTIVE
}

/**
 * Universal Smart Auto-Mute guard checked BEFORE every TextToSpeech / neural audio playback
 * and actively triggered on audio mode changes, call starts, mic start, or media playback start.
 */
object AudioSilenceGuard {
    @Volatile
    private var micListeningFlag: Boolean = false

    @Volatile
    private var callStartingUntilMs: Long = 0L

    @Volatile
    private var mediaCooldownUntilMs: Long = 0L

    private val modeListenerRegistered = AtomicBoolean(false)

    private val mediaPackageMarkers = listOf(
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.spotify.music",
        "com.instagram.android",
        "com.netflix.mediaclient",
        "in.startv.hotstar",
        "com.amazon.avod",
        "com.jio.media.jiobeats",
        "com.gaana",
        "com.bsbportal.music",
        "com.apple.android.music",
        "com.soundcloud.android",
        "com.mxtech.videoplayer",
        "org.videolan.vlc"
    )

    /**
     * Pure deterministic policy check. Returns the [SilenceReason] if TTS must be blocked,
     * or null when spoken feedback is safe and appropriate.
     */
    fun evaluatePolicy(state: AudioEnvironmentState): SilenceReason? {
        if (state.isCallStarting) return SilenceReason.CALL_STARTING

        if (state.audioMode == AudioManager.MODE_IN_CALL ||
            state.audioMode == AudioManager.MODE_IN_COMMUNICATION ||
            state.audioMode == AudioManager.MODE_RINGTONE
        ) {
            return SilenceReason.ACTIVE_CALL
        }

        if (state.isMicListening) return SilenceReason.MIC_ACTIVE
        if (state.isOtherAppRecording) return SilenceReason.OTHER_APP_RECORDING

        if (state.isMusicActive) return SilenceReason.MEDIA_PLAYING
        if (state.isMediaCooldownActive) return SilenceReason.MEDIA_JUST_STARTED

        if (state.ringerMode == AudioManager.RINGER_MODE_SILENT ||
            state.ringerMode == AudioManager.RINGER_MODE_VIBRATE
        ) {
            return SilenceReason.RINGER_SILENT_OR_VIBRATE
        }

        if (state.isDndActive) return SilenceReason.DND_ACTIVE

        return null
    }

    fun isMediaAppPackage(packageName: String?): Boolean {
        val pkg = packageName?.trim()?.lowercase().orEmpty()
        if (pkg.isBlank()) return false
        return mediaPackageMarkers.any { pkg.contains(it) }
    }

    fun setMicListening(active: Boolean) {
        micListeningFlag = active
        if (active) {
            AgentSpeechFeedback.stopSpeaking()
        }
    }

    fun markCallStarting(durationMs: Long = 15_000L) {
        callStartingUntilMs = System.currentTimeMillis() + durationMs.coerceAtLeast(3_000L)
        AgentSpeechFeedback.stopSpeaking()
    }

    fun markMediaPlaybackStarted(durationMs: Long = 10_000L) {
        mediaCooldownUntilMs = System.currentTimeMillis() + durationMs.coerceAtLeast(3_000L)
        AgentSpeechFeedback.stopSpeaking()
    }

    fun resetRuntimeFlagsForTest() {
        micListeningFlag = false
        callStartingUntilMs = 0L
        mediaCooldownUntilMs = 0L
    }

    fun registerAudioStateWatchers(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!modeListenerRegistered.compareAndSet(false, true)) return

        runCatching {
            val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return
            am.addOnModeChangedListener(context.applicationContext.mainExecutor) { newMode ->
                if (newMode == AudioManager.MODE_IN_CALL ||
                    newMode == AudioManager.MODE_IN_COMMUNICATION ||
                    newMode == AudioManager.MODE_RINGTONE
                ) {
                    AgentSpeechFeedback.stopSpeaking()
                }
            }
        }.onFailure {
            modeListenerRegistered.set(false)
        }
    }

    fun readEnvironmentState(context: Context): AudioEnvironmentState {
        val appCtx = context.applicationContext
        val am = runCatching {
            appCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }.getOrNull()

        val audioMode = runCatching { am?.mode ?: AudioManager.MODE_NORMAL }
            .getOrDefault(AudioManager.MODE_NORMAL)
        val isMusicActive = runCatching { am?.isMusicActive == true }
            .getOrDefault(false)
        val ringerMode = runCatching { am?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL }
            .getOrDefault(AudioManager.RINGER_MODE_NORMAL)

        val isOtherAppRecording = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                val activeConfigs = am?.activeRecordingConfigurations.orEmpty()
                activeConfigs.isNotEmpty()
            }.getOrDefault(false)
        } else {
            false
        }

        val isDndFromNotificationManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                val filter = nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
                filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                    filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            }.getOrDefault(false)
        } else {
            false
        }

        val isDndFromGlobalSettings = runCatching {
            Settings.Global.getInt(appCtx.contentResolver, "zen_mode", 0) > 0
        }.getOrDefault(false)

        val now = System.currentTimeMillis()
        return AudioEnvironmentState(
            audioMode = audioMode,
            isMusicActive = isMusicActive,
            ringerMode = ringerMode,
            isDndActive = isDndFromNotificationManager || isDndFromGlobalSettings,
            isMicListening = micListeningFlag,
            isOtherAppRecording = isOtherAppRecording,
            isCallStarting = now < callStartingUntilMs,
            isMediaCooldownActive = now < mediaCooldownUntilMs
        )
    }

    /**
     * Checks live device state; returns non-null [SilenceReason] whenever TTS must stay silent.
     */
    fun shouldMuteNow(context: Context): SilenceReason? {
        return evaluatePolicy(readEnvironmentState(context))
    }
}
