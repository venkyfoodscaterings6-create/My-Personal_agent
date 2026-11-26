package com.example

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Shared application-wide TextToSpeech + Assistant chime + non-blocking Toast announcer so
 * wake-word greetings, completed actions, and error explanations speak aloud cleanly even while
 * the user is inside YouTube, WhatsApp, or any other foreground app.
 */
object AgentSpeechFeedback {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    @Volatile
    private var ttsReady = false
    @Volatile
    private var pendingSpeakText: String? = null
    @Volatile
    private var pendingLanguage: VoiceLanguage = VoiceLanguage.EN_IN
    @Volatile
    private var pendingOnDone: (() -> Unit)? = null
    @Volatile
    private var activeOnDone: (() -> Unit)? = null

    @Volatile
    var isMainAppInForeground: Boolean = false

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    fun playWakeChime() {
        runCatching {
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 160)
            mainHandler.postDelayed({ runCatching { toneGen.release() } }, 240L)
        }
    }

    @Synchronized
    private fun ensureTts(context: Context) {
        if (tts != null) return
        val appCtx = context.applicationContext
        runCatching {
            tts = TextToSpeech(appCtx) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _isSpeaking.value = true
                        }

                        override fun onDone(utteranceId: String?) {
                            _isSpeaking.value = false
                            val cb = activeOnDone
                            activeOnDone = null
                            if (cb != null) mainHandler.post { cb() }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            _isSpeaking.value = false
                            val cb = activeOnDone
                            activeOnDone = null
                            if (cb != null) mainHandler.post { cb() }
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            _isSpeaking.value = false
                            val cb = activeOnDone
                            activeOnDone = null
                            if (cb != null) mainHandler.post { cb() }
                        }
                    })
                    val queued = pendingSpeakText
                    val lang = pendingLanguage
                    val cb = pendingOnDone
                    pendingSpeakText = null
                    pendingOnDone = null
                    if (!queued.isNullOrBlank()) {
                        speakInternal(queued, lang, cb)
                    } else if (cb != null) {
                        mainHandler.post { cb() }
                    }
                } else {
                    val cb = pendingOnDone
                    pendingOnDone = null
                    if (cb != null) mainHandler.post { cb() }
                }
            }
        }.onFailure {
            val cb = pendingOnDone
            pendingOnDone = null
            if (cb != null) mainHandler.post { cb() }
        }
    }

    fun speak(
        context: Context,
        text: String,
        language: VoiceLanguage,
        onDone: (() -> Unit)? = null
    ) {
        val cleaned = text.trim()
        if (cleaned.isBlank()) {
            if (onDone != null) mainHandler.post { onDone() }
            return
        }
        ensureTts(context)
        if (!ttsReady) {
            pendingSpeakText = cleaned
            pendingLanguage = language
            pendingOnDone = onDone
            return
        }
        speakInternal(cleaned, language, onDone)
    }

    private fun speakInternal(cleaned: String, language: VoiceLanguage, onDone: (() -> Unit)?) {
        val hasTeluguScript = cleaned.any { ch -> ch in '\u0C00'..'\u0C7F' }
        val targetLang = if (hasTeluguScript) VoiceLanguage.TE_IN else language
        val locale = Locale.forLanguageTag(targetLang.bcp47Tag)
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.setLanguage(Locale.US)
        }
        activeOnDone = onDone
        _isSpeaking.value = true
        runCatching {
            val queued = tts?.speak(
                cleaned.take(600),
                TextToSpeech.QUEUE_FLUSH,
                null,
                "agent_feedback_${System.currentTimeMillis()}"
            )
            if (queued != TextToSpeech.SUCCESS) {
                _isSpeaking.value = false
                val cb = activeOnDone
                activeOnDone = null
                if (cb != null) mainHandler.post { cb() }
            }
        }.onFailure {
            _isSpeaking.value = false
            val cb = activeOnDone
            activeOnDone = null
            if (cb != null) mainHandler.post { cb() }
        }
    }

    fun stopSpeaking() {
        pendingSpeakText = null
        pendingOnDone = null
        activeOnDone = null
        runCatching { tts?.stop() }
        _isSpeaking.value = false
    }

    fun announceOutcome(
        context: Context,
        message: String,
        isSuccess: Boolean,
        voiceSettings: VoiceSettings,
        showToastWhenOutsideApp: Boolean = true
    ) {
        val cleaned = message.trim()
        if (cleaned.isBlank()) return

        if (voiceSettings.speakRepliesAloud) {
            // Strip timing suffix like "(0.4s)" so spoken voice sounds crisp and natural
            val spokenText = cleaned.replace(Regex("""\s*\(\d+(?:\.\d+)?s\)\s*$"""), "").trim()
            speak(context, spokenText, voiceSettings.voiceLanguage)
        }

        if (showToastWhenOutsideApp && !isMainAppInForeground) {
            val toastText = if (isSuccess) {
                val withoutPrefix = cleaned
                    .removePrefix("Yes, completed!")
                    .removePrefix("Yes, completed:")
                    .trim()
                if (withoutPrefix.isNotBlank()) "✓ Yes, completed: $withoutPrefix" else "✓ Yes, completed!"
            } else {
                cleaned
            }
            mainHandler.post {
                runCatching {
                    Toast.makeText(context.applicationContext, toastText.take(140), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
