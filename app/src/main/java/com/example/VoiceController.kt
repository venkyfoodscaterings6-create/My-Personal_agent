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
 * Patient SpeechRecognizer controller tailored for slower speech, natural pauses,
 * and Indian English (`en-IN`) / Telugu (`te-IN`):
 * - Sets generous silence timeouts (`4000ms` complete, `3000ms` possibly complete, `5000ms` min)
 * - Uses a Patient Accumulation Buffer + 2.8-second countdown timer so if Android stops
 *   early on a mid-sentence pause, the recognizer restarts quietly and keeps appending
 *   words into the live text box until the user truly finishes or taps Mic/Send
 * - Never loses captured text or shows harsh errors on `ERROR_NO_MATCH` / `ERROR_SPEECH_TIMEOUT`
 * - Exposes real-time normalized microphone amplitude (`micRmsEnergy: 0f..1f`) for 3D reactivity
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
    private var currentLanguage: VoiceLanguage = VoiceLanguage.EN_IN
    private var continuousSoftRetryMode: Boolean = false
    private var isSessionActive: Boolean = false
    private var sessionToken: Long = 0L
    private var consecutiveEmptyRestarts: Int = 0

    private val speechBuffer = PatientSpeechBuffer()
    private var pendingCommitRunnable: Runnable? = null
    private var pendingRestartRunnable: Runnable? = null

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
        continuousSoftRetryMode = continuousSoftRetry
        AudioSilenceGuard.setMicListening(true)
        AgentSpeechFeedback.stopSpeaking()

        mainHandler.post {
            cancelTimers()
            speechBuffer.clear()
            consecutiveEmptyRestarts = 0
            sessionToken++
            val activeToken = sessionToken

            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                isSessionActive = false
                _isListening.value = false
                AudioSilenceGuard.setMicListening(false)
                _micRmsEnergy.value = 0f
                onRequestFallbackIntent(buildFallbackSpeechIntent(language))
                return@post
            }

            isSessionActive = true
            _isListening.value = true
            startRecognizerPass(activeToken, language)
        }
    }

    private fun startRecognizerPass(activeToken: Long, language: VoiceLanguage) {
        if (!isSessionActive || activeToken != sessionToken) return

        runCatching {
            destroySpeechRecognizerOnly()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer = recognizer

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (!isSessionActive || activeToken != sessionToken) return
                    _isListening.value = true
                    _micRmsEnergy.value = 0.14f
                }

                override fun onBeginningOfSpeech() {
                    if (!isSessionActive || activeToken != sessionToken) return
                    _isListening.value = true
                    // User resumed speaking after a pause — cancel auto-send countdown!
                    cancelCommitTimer()
                }

                override fun onRmsChanged(rmsdB: Float) {
                    if (!isSessionActive || activeToken != sessionToken) return
                    val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.05f, 1.0f)
                    _micRmsEnergy.value = normalized
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    if (!isSessionActive || activeToken != sessionToken) return
                    _micRmsEnergy.value = 0.06f
                }

                override fun onError(error: Int) {
                    if (!isSessionActive || activeToken != sessionToken) return
                    destroySpeechRecognizerOnly()

                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                            if (speechBuffer.hasText()) {
                                // We already captured words before the pause — commit cleanly!
                                if (pendingCommitRunnable != null) {
                                    // Countdown is already running; commit immediately since silence continued
                                    commitAccumulatedAndStop(activeToken)
                                } else {
                                    commitAccumulatedAndStop(activeToken)
                                }
                            } else {
                                isSessionActive = false
                                _isListening.value = false
                                _micRmsEnergy.value = 0f
                                cancelTimers()
                                if (continuousSoftRetryMode && onSilenceTimeout != null) {
                                    onSilenceTimeout.invoke()
                                } else {
                                    onVoiceError("Tap mic and speak again")
                                }
                            }
                        }

                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                            if (consecutiveEmptyRestarts < 2) {
                                consecutiveEmptyRestarts++
                                scheduleRecognizerRestart(activeToken, language, 260L)
                            } else if (speechBuffer.hasText()) {
                                commitAccumulatedAndStop(activeToken)
                            } else {
                                finishWithGentleHint()
                            }
                        }

                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                            isSessionActive = false
                            _isListening.value = false
                            AudioSilenceGuard.setMicListening(false)
                            _micRmsEnergy.value = 0f
                            cancelTimers()
                            onVoiceError("Microphone permission is required for voice commands.")
                        }

                        SpeechRecognizer.ERROR_CLIENT -> Unit

                        else -> {
                            if (speechBuffer.hasText()) {
                                commitAccumulatedAndStop(activeToken)
                            } else {
                                isSessionActive = false
                                _isListening.value = false
                                AudioSilenceGuard.setMicListening(false)
                                _micRmsEnergy.value = 0f
                                cancelTimers()
                                onRequestFallbackIntent(buildFallbackSpeechIntent(language))
                            }
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    if (!isSessionActive || activeToken != sessionToken) return
                    val best = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    destroySpeechRecognizerOnly()

                    if (best.isNotBlank()) {
                        consecutiveEmptyRestarts = 0
                        val accumulated = speechBuffer.commitSegment(best)
                        onPartialText(accumulated)
                        // Start 2.8s patient countdown AND cleanly restart recognizer so if user
                        // keeps speaking after a breath/pause, we cancel the timer and keep appending!
                        schedulePatientCommit(activeToken)
                        scheduleRecognizerRestart(activeToken, language, 140L)
                    } else if (speechBuffer.hasText()) {
                        // Already have captured words — ensure commit timer is running
                        if (pendingCommitRunnable == null) {
                            schedulePatientCommit(activeToken)
                        }
                    } else {
                        isSessionActive = false
                        _isListening.value = false
                        AudioSilenceGuard.setMicListening(false)
                        _micRmsEnergy.value = 0f
                        cancelTimers()
                        if (continuousSoftRetryMode && onSilenceTimeout != null) {
                            onSilenceTimeout.invoke()
                        } else {
                            onVoiceError("Tap mic and speak again")
                        }
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (!isSessionActive || activeToken != sessionToken) return
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (partial.isNotBlank()) {
                        // Live words arriving — cancel any pending auto-send countdown!
                        cancelCommitTimer()
                        val combined = speechBuffer.updatePartial(partial)
                        if (combined.isNotBlank()) {
                            onPartialText(combined)
                        }
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            val intent = buildRecognizerIntent(language)
            _isListening.value = true
            recognizer.startListening(intent)
        }.onFailure {
            if (speechBuffer.hasText()) {
                commitAccumulatedAndStop(activeToken)
            } else {
                isSessionActive = false
                _isListening.value = false
                AudioSilenceGuard.setMicListening(false)
                _micRmsEnergy.value = 0f
                cancelTimers()
                destroySpeechRecognizerOnly()
                onRequestFallbackIntent(buildFallbackSpeechIntent(language))
            }
        }
    }

    private fun schedulePatientCommit(activeToken: Long) {
        cancelCommitTimer()
        val runnable = Runnable {
            commitAccumulatedAndStop(activeToken)
        }
        pendingCommitRunnable = runnable
        mainHandler.postDelayed(runnable, PATIENT_COMMIT_DELAY_MS)
    }

    private fun scheduleRecognizerRestart(activeToken: Long, language: VoiceLanguage, delayMs: Long) {
        cancelRestartTimer()
        val runnable = Runnable {
            if (isSessionActive && activeToken == sessionToken) {
                startRecognizerPass(activeToken, language)
            }
        }
        pendingRestartRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun commitAccumulatedAndStop(activeToken: Long) {
        if (!isSessionActive || activeToken != sessionToken) return
        isSessionActive = false
        sessionToken++
        cancelTimers()
        _isListening.value = false
        AudioSilenceGuard.setMicListening(false)
        _micRmsEnergy.value = 0f
        destroySpeechRecognizerOnly()

        val finalTranscript = speechBuffer.finalizeAll()
        speechBuffer.clear()
        if (finalTranscript.isNotBlank()) {
            onFinalText(finalTranscript)
        } else if (continuousSoftRetryMode && onSilenceTimeout != null) {
            onSilenceTimeout.invoke()
        } else {
            onVoiceError("Tap mic and speak again")
        }
    }

    private fun finishWithGentleHint() {
        isSessionActive = false
        sessionToken++
        cancelTimers()
        _isListening.value = false
        AudioSilenceGuard.setMicListening(false)
        _micRmsEnergy.value = 0f
        destroySpeechRecognizerOnly()
        speechBuffer.clear()
        if (continuousSoftRetryMode && onSilenceTimeout != null) {
            onSilenceTimeout.invoke()
        } else {
            onVoiceError("Tap mic and speak again")
        }
    }

    /**
     * Stops listening manually when the user taps the Mic button to finish early.
     * If any text was already captured, immediately sends/commits it cleanly.
     */
    fun stopListeningAndCommit() {
        mainHandler.post {
            val activeToken = sessionToken
            if (isSessionActive && speechBuffer.hasText()) {
                commitAccumulatedAndStop(activeToken)
            } else {
                stopListeningInternal()
            }
        }
    }

    /**
     * Stops listening silently without triggering `onFinalText` (e.g., when the user
     * manually taps the Send button or exits the screen).
     */
    fun stopListening() {
        mainHandler.post {
            stopListeningInternal()
        }
    }

    private fun stopListeningInternal() {
        isSessionActive = false
        sessionToken++
        cancelTimers()
        speechBuffer.clear()
        _isListening.value = false
        AudioSilenceGuard.setMicListening(false)
        _micRmsEnergy.value = 0f
        runCatching { speechRecognizer?.stopListening() }
        destroySpeechRecognizerOnly()
    }

    private fun cancelCommitTimer() {
        pendingCommitRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingCommitRunnable = null
    }

    private fun cancelRestartTimer() {
        pendingRestartRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingRestartRunnable = null
    }

    private fun cancelTimers() {
        cancelCommitTimer()
        cancelRestartTimer()
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
            stopListeningInternal()
        }
    }

    companion object {
        const val COMPLETE_SILENCE_MS = 4000L
        const val POSSIBLY_COMPLETE_SILENCE_MS = 3000L
        const val MINIMUM_SPEECH_LENGTH_MS = 5000L
        const val PATIENT_COMMIT_DELAY_MS = 2800L

        fun buildRecognizerIntent(language: VoiceLanguage): Intent {
            return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.bcp47Tag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.bcp47Tag)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 2)
                // Generous silence thresholds tailored for slower speech and natural pauses
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, COMPLETE_SILENCE_MS)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    POSSIBLY_COMPLETE_SILENCE_MS
                )
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MINIMUM_SPEECH_LENGTH_MS)
            }
        }

        fun buildFallbackSpeechIntent(language: VoiceLanguage): Intent {
            val prompt = when (language) {
                VoiceLanguage.TE_IN -> "తెలుగు లేదా English లో మాట్లాడండి…"
                VoiceLanguage.EN_IN -> "Speak in Indian English (or switch to తెలుగు)…"
            }
            return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.bcp47Tag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.bcp47Tag)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, COMPLETE_SILENCE_MS)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    POSSIBLY_COMPLETE_SILENCE_MS
                )
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MINIMUM_SPEECH_LENGTH_MS)
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
