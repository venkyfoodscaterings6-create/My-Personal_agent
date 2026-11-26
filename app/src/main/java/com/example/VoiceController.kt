package com.example

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Upgraded SpeechRecognizer controller with:
 * - Real-time normalized microphone amplitude (`micRmsEnergy: 0f..1f`) for 3D audio reactivity
 * - Generous end-of-speech silence thresholds (2.2s complete silence) to prevent mid-sentence cutoffs
 * - Soft-retry callback for continuous Live Voice mode on brief silence timeouts
 * - Unified speech output delegated to `AgentSpeechFeedback` (zero duplicate TextToSpeech engines)
 */
class VoiceController(
    private val context: Context,
    private val onPartialText: (String) -> Unit,
    private val onFinalText: (String) -> Unit,
    private val onRequestFallbackIntent: (Intent) -> Unit,
    private val onVoiceError: (String) -> Unit,
    private val onSilenceTimeout: (() -> Unit)? = null
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var currentLanguage: VoiceLanguage = VoiceLanguage.AUTO

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _micRmsEnergy = MutableStateFlow(0f)
    val micRmsEnergy: StateFlow<Float> = _micRmsEnergy.asStateFlow()

    val isSpeaking: StateFlow<Boolean> = AgentSpeechFeedback.isSpeaking

    fun startListening(
        language: VoiceLanguage,
        continuousSoftRetry: Boolean = false
    ) {
        currentLanguage = language
        AgentSpeechFeedback.stopSpeaking()

        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                _isListening.value = false
                _micRmsEnergy.value = 0f
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
                        _micRmsEnergy.value = 0.14f
                    }

                    override fun onBeginningOfSpeech() {
                        _isListening.value = true
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        // Android SpeechRecognizer rmsdB typically spans -2 dB (quiet) to +10 dB (loud speech)
                        val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.05f, 1.0f)
                        _micRmsEnergy.value = normalized
                    }

                    override fun onBufferReceived(buffer: ByteArray?) = Unit

                    override fun onEndOfSpeech() {
                        _isListening.value = false
                        _micRmsEnergy.value = 0f
                    }

                    override fun onError(error: Int) {
                        _isListening.value = false
                        _micRmsEnergy.value = 0f
                        destroySpeechRecognizerOnly()
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                                if (continuousSoftRetry && onSilenceTimeout != null) {
                                    onSilenceTimeout.invoke()
                                } else {
                                    onVoiceError("Didn't catch that — tap the mic and try again.")
                                }
                            }
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                                onVoiceError("Microphone permission is required for voice commands.")
                            }
                            SpeechRecognizer.ERROR_CLIENT -> Unit
                            else -> {
                                onRequestFallbackIntent(buildFallbackSpeechIntent(language))
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        _isListening.value = false
                        _micRmsEnergy.value = 0f
                        val best = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()
                        destroySpeechRecognizerOnly()
                        if (best.isNotBlank()) {
                            onFinalText(best)
                        } else if (continuousSoftRetry && onSilenceTimeout != null) {
                            onSilenceTimeout.invoke()
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
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 2)
                    // Generous silence windows so natural pauses mid-sentence never cut off speech
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2200L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2500L)
                }

                _isListening.value = true
                recognizer.startListening(intent)
            }.onFailure {
                _isListening.value = false
                _micRmsEnergy.value = 0f
                destroySpeechRecognizerOnly()
                onRequestFallbackIntent(buildFallbackSpeechIntent(language))
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            _isListening.value = false
            _micRmsEnergy.value = 0f
            runCatching { speechRecognizer?.stopListening() }
            destroySpeechRecognizerOnly()
        }
    }

    fun speak(text: String, language: VoiceLanguage) {
        currentLanguage = language
        AgentSpeechFeedback.speak(context, text, language)
    }

    fun stopSpeaking() {
        AgentSpeechFeedback.stopSpeaking()
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
            _micRmsEnergy.value = 0f
            destroySpeechRecognizerOnly()
        }
    }

    companion object {
        fun buildFallbackSpeechIntent(language: VoiceLanguage): Intent {
            val prompt = when (language) {
                VoiceLanguage.TE_IN -> "తెలుగు లేదా English లో మాట్లాడండి…"
                VoiceLanguage.AUTO -> "Speak in English or తెలుగు…"
                VoiceLanguage.EN_IN -> "Speak your command (English / తెలుగు)…"
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
