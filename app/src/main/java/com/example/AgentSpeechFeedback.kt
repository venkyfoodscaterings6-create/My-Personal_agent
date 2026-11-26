package com.example

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

private const val VOICE_ENGINE_TAG = "AgentSpeechFeedback"

/**
 * Unified application-wide Neural Voice Engine & Audio Manager.
 *
 * - Primary engine: Sentence-pipelined Firebase AI Logic Gemini Neural TTS (`ResponseModality.AUDIO`)
 *   with concurrent prefetching of the next sentence, played via Android `AudioTrack` with live
 *   25ms PCM amplitude metering (`playbackEnergy`) driving the 3D robot companion.
 * - Instant fallback: Tuned on-device Android `TextToSpeech` (picking high-quality local voices,
 *   auto-switching Telugu/English script, applying user speech rate) whenever offline, neural voice
 *   is toggled off, or a temporary rate limit occurs.
 * - Instant barge-in (`stopSpeaking()`): Cancels active synthesis, flushes `AudioTrack`, stops
 *   fallback TTS, releases audio focus, and resets `playbackEnergy` in <50ms.
 */
object AgentSpeechFeedback {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val firebaseAiService by lazy { FirebaseAiService() }

    private var tts: TextToSpeech? = null
    @Volatile
    private var ttsReady = false
    private var ttsInitWaiter: CompletableDeferred<Boolean>? = null

    @Volatile
    private var activeUtteranceDeferred: CompletableDeferred<Unit>? = null

    @Volatile
    private var activeAudioTrack: AudioTrack? = null
    private var activeFocusRequest: AudioFocusRequest? = null
    private var activeAudioManager: AudioManager? = null

    private var activeSpeechJob: Job? = null
    private var activeSentenceChannel: Channel<String>? = null
    private val sessionCounter = AtomicLong(0L)

    @Volatile
    var isMainAppInForeground: Boolean = false

    @Volatile
    private var lastKnownSettings: VoiceSettings = VoiceSettings()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _playbackEnergy = MutableStateFlow(0f)
    val playbackEnergy: StateFlow<Float> = _playbackEnergy.asStateFlow()

    private val _usingNeuralVoiceNow = MutableStateFlow(false)
    val usingNeuralVoiceNow: StateFlow<Boolean> = _usingNeuralVoiceNow.asStateFlow()

    fun updateCachedSettings(settings: VoiceSettings) {
        lastKnownSettings = settings
    }

    fun playWakeChime() {
        runCatching {
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 160)
            mainHandler.postDelayed({ runCatching { toneGen.release() } }, 240L)
        }
    }

    @Synchronized
    private fun ensureTtsInitialized(context: Context): CompletableDeferred<Boolean> {
        if (ttsReady && tts != null) {
            return CompletableDeferred(true)
        }
        ttsInitWaiter?.let { if (!it.isCompleted) return it }

        val deferred = CompletableDeferred<Boolean>()
        ttsInitWaiter = deferred
        val appCtx = context.applicationContext
        runCatching {
            tts = TextToSpeech(appCtx) { status ->
                val ok = status == TextToSpeech.SUCCESS
                ttsReady = ok
                if (ok) {
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _isSpeaking.value = true
                        }

                        override fun onDone(utteranceId: String?) {
                            activeUtteranceDeferred?.complete(Unit)
                            activeUtteranceDeferred = null
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            activeUtteranceDeferred?.complete(Unit)
                            activeUtteranceDeferred = null
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            activeUtteranceDeferred?.complete(Unit)
                            activeUtteranceDeferred = null
                        }
                    })
                }
                deferred.complete(ok)
            }
        }.onFailure {
            ttsReady = false
            deferred.complete(false)
        }
        return deferred
    }

    /**
     * Backwards-compatible single-shot speak overload using cached neural/speed preferences.
     */
    fun speak(
        context: Context,
        text: String,
        language: VoiceLanguage,
        onDone: (() -> Unit)? = null
    ) {
        speak(
            context = context,
            text = text,
            voiceSettings = lastKnownSettings.copy(voiceLanguage = language),
            onDone = onDone
        )
    }

    /**
     * Speaks a complete reply cleanly: strips markdown/URLs, splits into natural chunks for fast
     * first-sentence playback, synthesizes with Gemini neural voice (with prefetching) or falls back
     * instantly to tuned on-device Android TTS.
     */
    fun speak(
        context: Context,
        text: String,
        voiceSettings: VoiceSettings,
        onDone: (() -> Unit)? = null
    ) {
        lastKnownSettings = voiceSettings
        val chunks = SpeechTextCleaner.splitIntoSpeakableChunks(text)
        if (chunks.isEmpty()) {
            if (onDone != null) mainHandler.post { onDone() }
            return
        }
        beginSpeechStream(context, voiceSettings, onDone)
        chunks.forEach { enqueueSentence(it) }
        finishSpeechStream()
    }

    /**
     * Opens a sentence-pipelined streaming voice session so Live Voice can start playing Sentence 1
     * while Gemini is still streaming Sentence 2+.
     */
    fun beginSpeechStream(
        context: Context,
        voiceSettings: VoiceSettings,
        onAllDone: (() -> Unit)? = null
    ) {
        lastKnownSettings = voiceSettings
        stopSpeakingInternal(abandonFocus = false)

        val mySessionId = sessionCounter.incrementAndGet()
        val appCtx = context.applicationContext
        val channel = Channel<String>(Channel.UNLIMITED)
        activeSentenceChannel = channel
        requestAudioFocus(appCtx)

        activeSpeechJob = engineScope.launch {
            // Warm up on-device TTS lazily in parallel so fallback is instant if ever needed
            val ttsWarmup = async { ensureTtsInitialized(appCtx).await() }
            var completedNormally = false
            try {
                // Prefetch queue: pull sentences and immediately kick off neural TTS synthesis
                val pendingAudio = ArrayDeque<Pair<String, Deferred<SynthesizedAudio?>>>()

                suspend fun drainNextIfAvailable(blockUntilFirst: Boolean): Boolean {
                    if (blockUntilFirst) {
                        val first = channel.receiveCatching().getOrNull() ?: return false
                        val cleaned = SpeechTextCleaner.cleanForSpeech(first)
                        if (cleaned.isNotBlank()) {
                            pendingAudio.addLast(
                                cleaned to startSynthesisAsync(cleaned, voiceSettings)
                            )
                        }
                    }
                    // Greedily pull up to 2 lookahead sentences already queued so next sentence prefetches
                    while (pendingAudio.size < 3) {
                        val next = channel.tryReceive().getOrNull() ?: break
                        val cleaned = SpeechTextCleaner.cleanForSpeech(next)
                        if (cleaned.isNotBlank()) {
                            pendingAudio.addLast(
                                cleaned to startSynthesisAsync(cleaned, voiceSettings)
                            )
                        }
                    }
                    return pendingAudio.isNotEmpty()
                }

                while (isActive && sessionCounter.get() == mySessionId) {
                    if (pendingAudio.isEmpty()) {
                        val gotWork = drainNextIfAvailable(blockUntilFirst = true)
                        if (!gotWork) break
                    } else {
                        drainNextIfAvailable(blockUntilFirst = false)
                    }

                    val (sentence, audioDeferred) = pendingAudio.removeFirst()
                    if (!isActive || sessionCounter.get() != mySessionId) break

                    // Kick off prefetch of whatever else arrived in the channel while we wait/play
                    drainNextIfAvailable(blockUntilFirst = false)

                    val synthesized = runCatching { audioDeferred.await() }.getOrNull()
                    if (!isActive || sessionCounter.get() != mySessionId) break

                    if (synthesized != null) {
                        _usingNeuralVoiceNow.value = true
                        _isSpeaking.value = true
                        playPcmAudioWithEnergy(
                            audio = synthesized,
                            speechRate = voiceSettings.speechRate,
                            sessionId = mySessionId
                        )
                    } else {
                        _usingNeuralVoiceNow.value = false
                        val ready = runCatching { ttsWarmup.await() }.getOrDefault(false)
                        if (ready && isActive && sessionCounter.get() == mySessionId) {
                            speakWithOnDeviceTts(
                                text = sentence,
                                voiceSettings = voiceSettings,
                                sessionId = mySessionId
                            )
                        }
                    }
                }
                completedNormally = isActive && sessionCounter.get() == mySessionId
            } catch (_: CancellationException) {
                // Expected on instant barge-in or stopSpeaking()
            } catch (e: Exception) {
                Log.w(VOICE_ENGINE_TAG, "Speech stream ended with exception: ${e.localizedMessage}")
            } finally {
                if (sessionCounter.get() == mySessionId) {
                    _isSpeaking.value = false
                    _playbackEnergy.value = 0f
                    _usingNeuralVoiceNow.value = false
                    abandonAudioFocus()
                    if (completedNormally && onAllDone != null) {
                        mainHandler.post { onAllDone() }
                    }
                }
            }
        }
    }

    fun enqueueSentence(rawSentence: String) {
        val cleaned = SpeechTextCleaner.cleanForSpeech(rawSentence)
        if (cleaned.isBlank()) return
        activeSentenceChannel?.trySend(cleaned)
    }

    fun finishSpeechStream() {
        activeSentenceChannel?.close()
    }

    private fun CoroutineScope.startSynthesisAsync(
        sentence: String,
        voiceSettings: VoiceSettings
    ): Deferred<SynthesizedAudio?> {
        return async(Dispatchers.IO) {
            if (!voiceSettings.useGeminiNeuralVoice) return@async null
            firebaseAiService.synthesizeSpeechWithGemini(
                text = sentence,
                persona = voiceSettings.voicePersona,
                language = voiceSettings.voiceLanguage
            )
        }
    }

    private suspend fun playPcmAudioWithEnergy(
        audio: SynthesizedAudio,
        speechRate: Float,
        sessionId: Long
    ) = withContext(Dispatchers.IO) {
        val effectiveSampleRate = (audio.sampleRateHz * speechRate.coerceIn(0.85f, 1.3f))
            .toInt()
            .coerceIn(8_000, 48_000)

        val minBuf = AudioTrack.getMinBufferSize(
            effectiveSampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(effectiveSampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return@withContext

        activeAudioTrack = track
        try {
            track.play()
            // Write in ~30ms chunks so playbackEnergy updates at ~33Hz smoothly for the 3D robot!
            val bytesPerChunk = ((effectiveSampleRate * 2) / 33).coerceIn(640, 4096) and 0xFFFFFFFE.toInt()
            var offset = 0
            val total = audio.pcmBytes.size
            while (offset < total && isActive && sessionCounter.get() == sessionId) {
                val len = minOf(bytesPerChunk, total - offset)
                val energy = computeNormalizedPcmEnergy(audio.pcmBytes, offset, len)
                _playbackEnergy.value = energy
                val written = track.write(audio.pcmBytes, offset, len)
                if (written <= 0) break
                offset += written
            }
            // Brief flush tail so the final phoneme finishes cleanly
            if (isActive && sessionCounter.get() == sessionId) {
                _playbackEnergy.value = 0.08f
                delay(90L)
            }
        } catch (_: Exception) {
            // Ignore AudioTrack stop/interruption on barge-in
        } finally {
            _playbackEnergy.value = 0f
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                    track.stop()
                }
                track.release()
            }
            if (activeAudioTrack === track) {
                activeAudioTrack = null
            }
        }
    }

    private fun computeNormalizedPcmEnergy(bytes: ByteArray, offset: Int, length: Int): Float {
        if (length < 2) return 0f
        var sumSq = 0.0
        var samples = 0
        var i = offset
        val end = (offset + length - 1).coerceAtMost(bytes.size - 1)
        while (i < end) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt()
            val sample = (hi shl 8) or lo
            sumSq += sample.toDouble() * sample.toDouble()
            samples++
            i += 2
        }
        if (samples == 0) return 0f
        val rms = sqrt(sumSq / samples)
        // Speech PCM RMS typically peaks around 5500–9500
        return (rms / 6500.0).toFloat().coerceIn(0.05f, 1.0f)
    }

    private suspend fun speakWithOnDeviceTts(
        text: String,
        voiceSettings: VoiceSettings,
        sessionId: Long
    ) {
        val engine = tts ?: return
        val targetLang = SpeechTextCleaner.resolveEffectiveLanguage(text, voiceSettings.voiceLanguage)
        val locale = Locale.forLanguageTag(targetLang.bcp47Tag)

        val doneSignal = CompletableDeferred<Unit>()
        activeUtteranceDeferred = doneSignal

        withContext(Dispatchers.Main) {
            val langResult = engine.setLanguage(locale)
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.US)
            } else {
                // Pick the highest-quality available Android voice for this locale
                runCatching {
                    val bestVoice = engine.voices
                        ?.filter { v ->
                            v.locale.language.equals(locale.language, ignoreCase = true) && !v.isNetworkConnectionRequired
                        }
                        ?.maxByOrNull { it.quality }
                    if (bestVoice != null) {
                        engine.voice = bestVoice
                    }
                }
            }
            engine.setSpeechRate(voiceSettings.speechRate.coerceIn(0.8f, 1.35f))
            engine.setPitch(1.02f)

            _isSpeaking.value = true
            val status = engine.speak(
                text.take(900),
                TextToSpeech.QUEUE_FLUSH,
                null,
                "agent_tts_${System.currentTimeMillis()}"
            )
            if (status != TextToSpeech.SUCCESS) {
                doneSignal.complete(Unit)
            }
        }

        // Drive gentle speaking energy while Android TTS is active
        val meterJob = engineScope.launch {
            var t = 0f
            while (isActive && !doneSignal.isCompleted && sessionCounter.get() == sessionId) {
                _playbackEnergy.value = (0.38f + 0.44f * abs(sin(t * 7.8f))).coerceIn(0.15f, 0.92f)
                t += 0.045f
                delay(45L)
            }
            _playbackEnergy.value = 0f
        }

        try {
            doneSignal.await()
        } finally {
            meterJob.cancel()
            _playbackEnergy.value = 0f
        }
    }

    fun stopSpeaking() {
        stopSpeakingInternal(abandonFocus = true)
    }

    private fun stopSpeakingInternal(abandonFocus: Boolean) {
        sessionCounter.incrementAndGet()
        activeSentenceChannel?.close()
        activeSentenceChannel = null
        activeSpeechJob?.cancel()
        activeSpeechJob = null
        activeUtteranceDeferred?.complete(Unit)
        activeUtteranceDeferred = null

        val track = activeAudioTrack
        activeAudioTrack = null
        if (track != null) {
            runCatching {
                track.pause()
                track.flush()
                track.stop()
                track.release()
            }
        }
        runCatching { tts?.stop() }
        _isSpeaking.value = false
        _playbackEnergy.value = 0f
        _usingNeuralVoiceNow.value = false
        if (abandonFocus) {
            abandonAudioFocus()
        }
    }

    private fun requestAudioFocus(context: Context) {
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            activeAudioManager = am
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                activeFocusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        }
    }

    private fun abandonAudioFocus() {
        runCatching {
            val am = activeAudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activeFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                activeFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        }
    }

    fun announceOutcome(
        context: Context,
        message: String,
        isSuccess: Boolean,
        voiceSettings: VoiceSettings,
        showToastWhenOutsideApp: Boolean = true
    ) {
        lastKnownSettings = voiceSettings
        val cleaned = message.trim()
        if (cleaned.isBlank()) return

        if (voiceSettings.speakRepliesAloud) {
            speak(context, cleaned, voiceSettings)
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
