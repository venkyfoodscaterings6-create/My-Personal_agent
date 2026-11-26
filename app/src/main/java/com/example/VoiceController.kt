package com.example

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class VoiceController(
    private val context: Context,
    private val onPartialText: (String) -> Unit,
    private val onFinalText: (String) -> Unit,
    private val onRequestFallbackIntent: (Intent) -> Unit,
    private val onVoiceError: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var currentLanguage: VoiceLanguage = VoiceLanguage.EN_IN

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    init {
        initTextToSpeech()
    }

    private fun initTextToSpeech() {
        runCatching {
            tts = TextToSpeech(context.applicationContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _isSpeaking.value = true
                        }

                        override fun onDone(utteranceId: String?) {
                            _isSpeaking.value = false
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            _isSpeaking.value = false
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            _isSpeaking.value = false
                        }
                    })
                    applyTtsLanguage(currentLanguage)
                }
            }
        }
    }

    private fun applyTtsLanguage(language: VoiceLanguage) {
        if (!ttsReady) return
        val locale = Locale.forLanguageTag(language.bcp47Tag)
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.setLanguage(Locale.US)
        }
    }

    fun startListening(language: VoiceLanguage) {
        currentLanguage = language
        stopSpeaking()

        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                _isListening.value = false
                onRequestFallbackIntent(buildFallbackSpeechIntent(language))
                return@post
            }

            runCatching {
                destroySpeechRecognizerOnly()
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                speechRecognizer = recognizer

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        _isListening.value = true
                    }

                    override fun onBeginningOfSpeech() {
                        _isListening.value = true
                    }

                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit

                    override fun onEndOfSpeech() {
                        _isListening.value = false
                    }

                    override fun onError(error: Int) {
                        _isListening.value = false
                        destroySpeechRecognizerOnly()
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                                onVoiceError("Didn't catch that — tap the mic and try again.")
                            }
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                                onVoiceError("Microphone permission is required for voice commands.")
                            }
                            else -> {
                                // Automatic fallback to system RecognizerIntent activity on OEM/service errors
                                onRequestFallbackIntent(buildFallbackSpeechIntent(language))
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        _isListening.value = false
                        val best = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()
                        destroySpeechRecognizerOnly()
                        if (best.isNotBlank()) {
                            onFinalText(best)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()
                        if (partial.isNotBlank()) {
                            onPartialText(partial)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.bcp47Tag)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.bcp47Tag)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }

                _isListening.value = true
                recognizer.startListening(intent)
            }.onFailure {
                _isListening.value = false
                destroySpeechRecognizerOnly()
                onRequestFallbackIntent(buildFallbackSpeechIntent(language))
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            _isListening.value = false
            runCatching { speechRecognizer?.stopListening() }
            destroySpeechRecognizerOnly()
        }
    }

    fun speak(text: String, language: VoiceLanguage) {
        val cleaned = text.trim()
        if (cleaned.isBlank() || !ttsReady) return
        currentLanguage = language
        // Auto-detect Telugu Unicode block (0C00–0C7F) so Telugu replies speak in Telugu even if EN mode is selected
        val hasTeluguScript = cleaned.any { ch -> ch in '\u0C00'..'\u0C7F' }
        val targetLang = if (hasTeluguScript) VoiceLanguage.TE_IN else language
        applyTtsLanguage(targetLang)
        _isSpeaking.value = true
        runCatching {
            tts?.speak(
                cleaned.take(600),
                TextToSpeech.QUEUE_FLUSH,
                null,
                "agent_reply_${System.currentTimeMillis()}"
            )
        }.onFailure {
            _isSpeaking.value = false
        }
    }

    fun stopSpeaking() {
        runCatching { tts?.stop() }
        _isSpeaking.value = false
    }

    private fun destroySpeechRecognizerOnly() {
        runCatching {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        }
        speechRecognizer = null
    }

    fun destroy() {
        mainHandler.post {
            _isListening.value = false
            destroySpeechRecognizerOnly()
        }
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        ttsReady = false
        _isSpeaking.value = false
    }

    companion object {
        fun buildFallbackSpeechIntent(language: VoiceLanguage): Intent {
            val prompt = if (language == VoiceLanguage.TE_IN) {
                "తెలుగు లేదా English లో మాట్లాడండి…"
            } else {
                "Speak your command (English / తెలుగు)…"
            }
            return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.bcp47Tag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.bcp47Tag)
                putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            }
        }

        fun isSpeechAvailable(context: Context): Boolean {
            if (SpeechRecognizer.isRecognitionAvailable(context)) return true
            val fallback = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            return fallback.resolveActivity(context.packageManager) != null
        }
    }
}
