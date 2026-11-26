package com.example

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.GenerateContentResponse
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val TAG = "FirebaseAiService"

sealed class GeminiStreamResult {
    data class Success(
        val text: String,
        val groundingSources: List<GroundingSource>,
        val latencyMs: Long,
        val modelUsed: String,
        val usedFallback: Boolean = false
    ) : GeminiStreamResult()

    data class Error(
        val message: String,
        val isRateLimit: Boolean = false
    ) : GeminiStreamResult()
}

data class SynthesizedAudio(
    val pcmBytes: ByteArray,
    val sampleRateHz: Int,
    val mimeType: String
)

/**
 * Built-in Gemini AI engine powered by Firebase AI Logic (no client API key required).
 * Supports multi-turn streaming chat, Google Search Grounding, Live Voice turns,
 * hybrid on-device Android tool routing, and automatic multi-tier fallback when any model
 * or Search Grounding tier hits rate/quota limits.
 */
class FirebaseAiService {

    companion object {
        fun isQuotaOrRateLimitMessage(message: String?): Boolean {
            val lower = message.orEmpty().lowercase()
            return lower.contains("429") ||
                lower.contains("quota") ||
                lower.contains("rate") ||
                lower.contains("resource_exhausted") ||
                lower.contains("resource exhausted") ||
                lower.contains("too many requests") ||
                lower.contains("billing") ||
                lower.contains("limit exceeded") ||
                lower.contains("exceeded")
        }

        private fun isRecoverableFallbackError(e: Exception): Boolean {
            val msg = (e.localizedMessage ?: e.message ?: e.toString()).lowercase()
            return isQuotaOrRateLimitMessage(msg) ||
                msg.contains("not found") ||
                msg.contains("unsupported") ||
                msg.contains("overloaded") ||
                msg.contains("unavailable") ||
                msg.contains("503")
        }

        fun displayNameForModelId(modelId: String): String {
            return GeminiBuiltInModel.entries.firstOrNull { it.modelId == modelId }?.displayName
                ?: modelId
        }
    }

    private fun buildSystemInstruction(
        personalSqlContext: String,
        voiceLanguage: VoiceLanguage,
        isLiveVoice: Boolean
    ): String {
        return buildString {
            appendLine("You are My Personal Agent, a smart, warm, privacy-first personal AI assistant running on the user's Android phone.")
            if (isLiveVoice) {
                appendLine("You are in a real-time hands-free voice conversation. Keep answers natural, conversational, and concise (2 to 4 short spoken sentences unless the user asks for deep detail). Avoid markdown asterisks, code blocks, or long bullet lists.")
            } else {
                appendLine("Always remember and use the prior conversation history when answering follow-up questions. Be clear, direct, and helpful.")
            }
            when (voiceLanguage) {
                VoiceLanguage.TE_IN -> {
                    appendLine("The user's voice language is set to Telugu (తెలుగు). Respond naturally in clear spoken Telugu unless the user explicitly asks for English.")
                }
                VoiceLanguage.AUTO -> {
                    appendLine("The user speaks English and Telugu. Automatically match the user's language: if they speak or write in Telugu (or Telugu transliterated in English script), reply naturally in Telugu; otherwise reply in natural English.")
                }
                VoiceLanguage.EN_IN -> {
                    appendLine("Respond naturally in clear English unless the user speaks in Telugu.")
                }
            }
            appendLine("Safety rule: Never assist with banking transfers, UPI PINs, OTPs, or entering passwords.")
            if (personalSqlContext.isNotBlank()) {
                appendLine()
                appendLine(personalSqlContext)
                appendLine("Use these saved memories and open tasks naturally whenever relevant to personalize your answer.")
            }
        }
    }

    private fun buildHistoryContent(history: List<ChatMessage>): List<Content> {
        val recent = ConversationMemoryHelper.buildRecentChatMessages(history, maxTurns = 12)
        // Drop the very last user turn because it is passed to sendMessage / sendMessageStream
        val prior = if (recent.isNotEmpty() && recent.last().sender == MessageSender.USER) {
            recent.dropLast(1)
        } else {
            recent
        }

        val normalized = mutableListOf<Pair<String, String>>()
        prior.forEach { msg ->
            val role = if (msg.sender == MessageSender.USER) "user" else "model"
            if (normalized.isEmpty() && role != "user") return@forEach
            if (normalized.isNotEmpty() && normalized.last().first == role) {
                val prev = normalized.removeAt(normalized.lastIndex)
                normalized.add(role to "${prev.second}\n\n${msg.text}")
            } else {
                normalized.add(role to msg.text)
            }
        }
        return normalized.map { (role, text) ->
            content(role = role) { text(text) }
        }
    }

    private fun extractGroundingSources(response: GenerateContentResponse): List<GroundingSource> {
        val chunks = response.candidates.firstOrNull()
            ?.groundingMetadata
            ?.groundingChunks
            .orEmpty()

        val seen = linkedSetOf<String>()
        return buildList {
            for (chunk in chunks) {
                val web = chunk.web ?: continue
                val uri = web.uri?.trim().orEmpty()
                if (uri.isBlank() || !seen.add(uri)) continue
                val title = web.title?.trim().orEmpty().ifBlank {
                    uri.substringAfter("://").substringBefore("/").removePrefix("www.")
                }
                add(GroundingSource(title = title, uri = uri))
            }
        }
    }

    private data class ChatAttempt(
        val modelId: String,
        val withGoogleSearch: Boolean
    )

    private fun buildFallbackAttempts(
        modelChoice: GeminiBuiltInModel,
        enableGoogleSearch: Boolean
    ): List<ChatAttempt> {
        val primaryModelId = if (enableGoogleSearch && modelChoice == GeminiBuiltInModel.FLASH_LITE) {
            GeminiBuiltInModel.FLASH.modelId
        } else {
            modelChoice.modelId
        }
        val attempts = linkedSetOf<ChatAttempt>()
        attempts.add(ChatAttempt(primaryModelId, enableGoogleSearch))
        if (enableGoogleSearch) {
            // If Google Search grounding quota is hit, retry same model without Search tool
            attempts.add(ChatAttempt(modelChoice.modelId, false))
        }
        // Always include free-tier high-quota fallback tiers (no billing required)
        attempts.add(ChatAttempt(GeminiBuiltInModel.FLASH.modelId, false))
        attempts.add(ChatAttempt(GeminiBuiltInModel.FLASH_LITE.modelId, false))
        return attempts.toList()
    }

    suspend fun streamChat(
        modelChoice: GeminiBuiltInModel,
        conversationHistory: List<ChatMessage>,
        latestUserPrompt: String,
        enableGoogleSearch: Boolean,
        personalSqlContext: String,
        voiceLanguage: VoiceLanguage,
        isLiveVoice: Boolean = false,
        onPartialText: (String) -> Unit
    ): GeminiStreamResult = withContext(Dispatchers.IO) {
        val startNanos = System.nanoTime()
        val sysPrompt = buildSystemInstruction(
            personalSqlContext = personalSqlContext,
            voiceLanguage = voiceLanguage,
            isLiveVoice = isLiveVoice
        )
        val historyContents = buildHistoryContent(conversationHistory)
        val attempts = buildFallbackAttempts(modelChoice, enableGoogleSearch)

        var lastErrorMsg = "Gemini request failed."
        var lastWasRateLimit = false

        for ((attemptIndex, attempt) in attempts.withIndex()) {
            if (attemptIndex > 0) {
                // Brief polite backoff before trying the next fallback tier
                delay(650L)
            }

            val toolsList = if (attempt.withGoogleSearch) listOf(Tool.googleSearch()) else null
            val generativeModel by lazy {
                Firebase.ai.generativeModel(
                    modelName = attempt.modelId,
                    systemInstruction = content { text(sysPrompt) },
                    tools = toolsList
                )
            }

            val accumulated = StringBuilder()
            val collectedSources = linkedMapOf<String, GroundingSource>()

            try {
                withTimeout(60_000L) {
                    val chat = generativeModel.startChat(history = historyContents)
                    chat.sendMessageStream(latestUserPrompt).collect { chunk ->
                        val delta = chunk.text.orEmpty()
                        if (delta.isNotEmpty()) {
                            accumulated.append(delta)
                            onPartialText(accumulated.toString())
                        }
                        extractGroundingSources(chunk).forEach { src ->
                            collectedSources.putIfAbsent(src.uri, src)
                        }
                    }
                }

                val finalText = accumulated.toString().trim()
                val latencyMs = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(1L)
                if (finalText.isNotBlank()) {
                    return@withContext GeminiStreamResult.Success(
                        text = finalText,
                        groundingSources = collectedSources.values.toList(),
                        latencyMs = latencyMs,
                        modelUsed = attempt.modelId,
                        usedFallback = attemptIndex > 0
                    )
                }
                lastErrorMsg = "Gemini returned an empty response."
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Gemini streaming timed out on ${attempt.modelId}", e)
                lastErrorMsg = "Gemini timed out after 60 seconds. Check your internet connection and try again."
                if (attemptIndex == attempts.lastIndex) {
                    return@withContext GeminiStreamResult.Error(lastErrorMsg, isRateLimit = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val rawErr = e.localizedMessage ?: e.message ?: "Gemini request failed."
                Log.e(TAG, "Firebase AI chat failed on ${attempt.modelId} (search=${attempt.withGoogleSearch}): $rawErr", e)
                lastErrorMsg = rawErr
                lastWasRateLimit = isQuotaOrRateLimitMessage(rawErr)

                // If partial output already streamed, return what we have instead of failing
                if (accumulated.isNotBlank()) {
                    val latencyMs = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(1L)
                    return@withContext GeminiStreamResult.Success(
                        text = accumulated.toString().trim(),
                        groundingSources = collectedSources.values.toList(),
                        latencyMs = latencyMs,
                        modelUsed = attempt.modelId,
                        usedFallback = attemptIndex > 0
                    )
                }

                // Only continue trying fallback tiers on recoverable rate/quota/availability errors
                if (!isRecoverableFallbackError(e)) {
                    break
                }
            }
        }

        val friendlyMessage = if (lastWasRateLimit) {
            "Built-in Gemini hit a temporary rate limit right now. Wait a few seconds and try again, or switch to Gemini 3.5 Flash-Lite or one of your Custom Providers in the top bar."
        } else {
            lastErrorMsg
        }
        GeminiStreamResult.Error(friendlyMessage, isRateLimit = lastWasRateLimit)
    }

    suspend fun routeHybridToolWithGemini(
        userGoal: String,
        installedAppSample: List<String>,
        safetyRules: SafetyRules,
        conversationHistory: List<ChatMessage>,
        personalSqlContext: String
    ): ToolRouteApiResult = withContext(Dispatchers.IO) {
        val appsHint = installedAppSample.take(30).joinToString(", ")
        val recentHistoryText = ConversationMemoryHelper.formatRecentAgentHistory(conversationHistory, maxTurns = 8)

        val systemPrompt = buildString {
            appendLine("You are an Android Hybrid Tool Router. Output ONLY ONE valid JSON object (no markdown, no explanation).")
            appendLine("Available tools:")
            appendLine("""0) {"tool":"reply_to_user","message":"Warm natural reply","reason":"Conversational reply or knowledge question"}""")
            appendLine("""1) {"tool":"play_youtube_video","query":"song or video name","reason":"Play video on YouTube"}""")
            appendLine("""2) {"tool":"open_app","appName":"App Name","reason":"Open app ONLY when user asks ONLY to open an app"}""")
            appendLine("""3) {"tool":"search_youtube","query":"search text","reason":"Search YouTube without playing"}""")
            appendLine("""4) {"tool":"search_web","query":"search text","reason":"Search the web"}""")
            appendLine("""5) {"tool":"open_maps","destination":"place","reason":"Navigate in Maps"}""")
            appendLine("""6) {"tool":"set_alarm","hour":7,"minute":30,"label":"Wake up","reason":"Set alarm"}""")
            appendLine("""7) {"tool":"set_timer","seconds":300,"label":"Tea","reason":"Set timer"}""")
            appendLine("""8) {"tool":"open_setting","settingName":"wifi|bluetooth|sound|display|battery|location","reason":"Open setting"}""")
            appendLine("""9) {"tool":"call_contact","contactOrNumber":"Mom","reason":"Call contact"}""")
            appendLine("""10) {"tool":"send_message","contactOrNumber":"Ravi","messagingApp":"whatsapp|sms","messageText":"Hi","reason":"Send WhatsApp or SMS message"}""")
            appendLine("""11) {"tool":"tap_on_screen","targetText":"Skip","reason":"Tap visible button/text on current screen"}""")
            appendLine("""12) {"tool":"scroll_screen","direction":"down|up|left|right","reason":"Scroll current screen"}""")
            appendLine("""13) {"tool":"press_back","reason":"Navigate back"}""")
            appendLine("""14) {"tool":"press_home","reason":"Go to home screen"}""")
            appendLine("""15) {"tool":"open_recents","reason":"Open recent apps"}""")
            appendLine("""16) {"tool":"open_notifications","reason":"Open notification shade"}""")
            appendLine("""17) {"tool":"open_quick_settings","reason":"Open Quick Settings panel"}""")
            appendLine("""18) {"tool":"set_volume","stream":"media|ring|alarm|notification","levelPercent":60,"reason":"Set volume level 0-100"}""")
            appendLine("""19) {"tool":"ui_automate","goal":"full multi-step goal","title":"Short title","steps":["Step 1","Step 2"],"riskLevel":"MEDIUM","reason":"Multi-step UI automation"}""")
            appendLine("RULES:")
            appendLine("- Greetings, small talk, or general questions MUST route to reply_to_user(message).")
            appendLine("- NEVER pick open_app for compound requests like 'open YouTube and play...'.")
            if (safetyRules.blockSensitiveFinancial) {
                appendLine("- NEVER route banking, UPI, OTP, payment, or password actions.")
            }
            if (appsHint.isNotBlank()) appendLine("Installed apps: $appsHint")
            if (personalSqlContext.isNotBlank()) appendLine(personalSqlContext)
        }

        val userPrompt = buildString {
            if (recentHistoryText.isNotBlank()) {
                appendLine("RECENT HISTORY:")
                appendLine(recentHistoryText)
                appendLine()
            }
            append("CURRENT REQUEST: $userGoal")
        }

        val startNanos = System.nanoTime()
        val routerModels = listOf(
            GeminiBuiltInModel.FLASH_LITE.modelId,
            GeminiBuiltInModel.FLASH.modelId
        )
        var lastError = "Built-in Gemini routing failed."

        for ((index, modelId) in routerModels.withIndex()) {
            if (index > 0) delay(500L)
            val routerModel by lazy {
                Firebase.ai.generativeModel(
                    modelName = modelId,
                    systemInstruction = content { text(systemPrompt) },
                    generationConfig = generationConfig {
                        temperature = 0.0f
                        maxOutputTokens = 220
                        responseMimeType = "application/json"
                    }
                )
            }

            try {
                val response = withTimeout(30_000L) {
                    routerModel.generateContent(userPrompt)
                }
                val rawText = response.text.orEmpty()
                val latencyMs = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(1L)
                val parsed = HybridToolRouting.parseRoutedToolJson(rawText, userGoal)
                    ?: RoutedToolCall(
                        tool = ToolName.UI_AUTOMATE,
                        goal = userGoal,
                        title = userGoal.take(56),
                        steps = listOf("Inspect current screen", "Execute: ${userGoal.take(80)}", "Verify completion"),
                        riskLevel = RiskLevel.MEDIUM,
                        reason = "Multi-step on-device task"
                    )
                return@withContext ToolRouteApiResult.Success(parsed, latencyMs)
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Gemini tool router timed out on $modelId", e)
                lastError = "Built-in Gemini router timed out. Please try again."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Gemini tool router failed on $modelId", e)
                lastError = e.localizedMessage ?: "Built-in Gemini routing failed."
                if (!isRecoverableFallbackError(e)) break
            }
        }

        ToolRouteApiResult.Error(lastError)
    }

    suspend fun requestNextOnDeviceActionWithGemini(
        goal: String,
        screenSnapshot: ScreenSnapshot,
        stepHistory: List<String>,
        stepNumber: Int,
        maxSteps: Int,
        lastActionFeedback: String? = null,
        conversationHistory: List<ChatMessage> = emptyList()
    ): NextActionApiResult = withContext(Dispatchers.IO) {
        val systemPrompt = buildString {
            appendLine("You control an Android phone via AccessibilityService. Reply with ONE compact JSON object only.")
            appendLine("""Actions: {"action":"open_app","appName":"..."} | {"action":"tap","index":3} | {"action":"type_text","index":2,"text":"...","submit":true} | {"action":"scroll","direction":"down"} | {"action":"back"} | {"action":"home"} | {"action":"done","summary":"Yes, completed! ..."}""")
            appendLine("- Never interact with banking, UPI, OTP, or password fields.")
        }

        val recentContext = ConversationMemoryHelper.formatRecentAgentHistory(conversationHistory, maxTurns = 6)
        val userPrompt = buildString {
            if (recentContext.isNotBlank()) {
                appendLine("RECENT CONVERSATION:")
                appendLine(recentContext)
            }
            appendLine("GOAL: $goal")
            appendLine("STEP: $stepNumber/$maxSteps")
            if (stepHistory.isNotEmpty()) {
                appendLine("LAST ACTIONS:")
                stepHistory.takeLast(5).forEach { appendLine("- $it") }
            }
            if (!lastActionFeedback.isNullOrBlank()) {
                appendLine("WARNING: $lastActionFeedback")
            }
            appendLine("SCREEN:")
            appendLine(screenSnapshot.compactSummary)
        }

        val stepModels = listOf(
            GeminiBuiltInModel.FLASH_LITE.modelId,
            GeminiBuiltInModel.FLASH.modelId
        )
        var lastError = "Gemini step error."

        for ((index, modelId) in stepModels.withIndex()) {
            if (index > 0) delay(500L)
            val stepModel by lazy {
                Firebase.ai.generativeModel(
                    modelName = modelId,
                    systemInstruction = content { text(systemPrompt) },
                    generationConfig = generationConfig {
                        temperature = 0.1f
                        maxOutputTokens = 160
                        responseMimeType = "application/json"
                    }
                )
            }

            try {
                val response = withTimeout(30_000L) {
                    stepModel.generateContent(userPrompt)
                }
                val raw = response.text.orEmpty()
                val json = HybridToolRouting.extractJsonObject(raw)
                    ?: return@withContext NextActionApiResult.Error("Gemini returned unreadable step JSON: ${raw.take(100)}")

                val actionType = json.optString("action").trim().lowercase()
                if (actionType.isBlank()) {
                    return@withContext NextActionApiResult.Error("Gemini step response missing action field.")
                }

                return@withContext NextActionApiResult.Success(
                    OnDeviceAgentAction(
                        action = actionType,
                        appName = json.optString("appName").takeIf { it.isNotBlank() },
                        index = if (json.has("index") && !json.isNull("index")) json.optInt("index") else null,
                        x = if (json.has("x") && !json.isNull("x")) json.optInt("x") else null,
                        y = if (json.has("y") && !json.isNull("y")) json.optInt("y") else null,
                        text = json.optString("text").takeIf { it.isNotBlank() },
                        submit = json.optBoolean("submit", false),
                        direction = json.optString("direction").takeIf { it.isNotBlank() },
                        summary = json.optString("summary").takeIf { it.isNotBlank() },
                        reason = json.optString("reason").trim().ifBlank { actionType }
                    )
                )
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Gemini step loop timed out on $modelId", e)
                lastError = "Step timed out waiting for Gemini."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Gemini step loop failed on $modelId", e)
                lastError = e.localizedMessage ?: "Gemini step error."
                if (!isRecoverableFallbackError(e)) break
            }
        }

        NextActionApiResult.Error(lastError)
    }

    suspend fun testBuiltInGeminiConnection(modelChoice: GeminiBuiltInModel): EndpointTestDetail =
        withContext(Dispatchers.IO) {
            val startNanos = System.nanoTime()
            val testModel by lazy {
                Firebase.ai.generativeModel(
                    modelName = modelChoice.modelId,
                    generationConfig = generationConfig { maxOutputTokens = 24 }
                )
            }
            try {
                val resp = withTimeout(30_000L) { testModel.generateContent("Reply with OK") }
                val latency = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(1L)
                val text = resp.text.orEmpty().trim()
                EndpointTestDetail(
                    label = "Built-in ${modelChoice.displayName}",
                    success = text.isNotBlank(),
                    latencyMs = latency,
                    httpStatus = 200,
                    message = if (text.isNotBlank()) {
                        "Connected via Firebase AI Logic in $latency ms — replied: \"${text.take(40)}\""
                    } else {
                        "Connected but model returned empty response."
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Built-in Gemini test failed", e)
                EndpointTestDetail(
                    label = "Built-in ${modelChoice.displayName}",
                    success = false,
                    message = e.localizedMessage ?: "Built-in Gemini test failed."
                )
            }
        }

    /**
     * Synthesizes natural neural speech audio via Firebase AI Logic Gemini TTS
     * (`gemini-3.1-flash-tts-preview` with automatic fallback to `gemini-3.8-flash-tts`).
     * Returns null on timeout/quota/offline so the unified voice engine falls back immediately
     * to tuned on-device Android TTS with zero audible interruption.
     */
    @OptIn(PublicPreviewAPI::class)
    suspend fun synthesizeSpeechWithGemini(
        text: String,
        persona: GeminiVoicePersona,
        language: VoiceLanguage
    ): SynthesizedAudio? = withContext(Dispatchers.IO) {
        val cleaned = SpeechTextCleaner.cleanForSpeech(text)
        if (cleaned.isBlank()) return@withContext null

        val effectiveLang = SpeechTextCleaner.resolveEffectiveLanguage(cleaned, language)
        val prompt = if (effectiveLang == VoiceLanguage.TE_IN) {
            "Read the following text aloud naturally in a warm, clear voice without adding any extra words:\n$cleaned"
        } else {
            "Speak the following text naturally in a warm, conversational assistant voice without adding any extra words:\n$cleaned"
        }

        val ttsModels = listOf(
            "gemini-3.1-flash-tts-preview",
            "gemini-3.8-flash-tts"
        )

        for (modelId in ttsModels) {
            val ttsModel by lazy {
                Firebase.ai.generativeModel(
                    modelName = modelId,
                    generationConfig = generationConfig {
                        responseModalities = listOf(ResponseModality.AUDIO)
                        speechConfig = SpeechConfig(voice = Voice(persona.apiVoiceName))
                    }
                )
            }
            try {
                val response = withTimeout(12_000L) {
                    ttsModel.generateContent(prompt)
                }
                val parts = response.candidates.firstOrNull()?.content?.parts.orEmpty()
                val audioPart = parts.filterIsInstance<InlineDataPart>()
                    .firstOrNull { it.inlineData.isNotEmpty() }
                if (audioPart != null) {
                    val raw = audioPart.inlineData
                    val mime = audioPart.mimeType
                    val (pcmBytes, sampleRate) = extractPcmAndSampleRate(raw, mime)
                    if (pcmBytes.size >= 64) {
                        return@withContext SynthesizedAudio(
                            pcmBytes = pcmBytes,
                            sampleRateHz = sampleRate,
                            mimeType = mime
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Gemini neural TTS ($modelId) fell back to next tier: ${e.localizedMessage}")
            }
        }
        null
    }

    private fun extractPcmAndSampleRate(raw: ByteArray, mimeType: String): Pair<ByteArray, Int> {
        // Handle RIFF WAV header if returned as audio/wav
        if (raw.size > 44 &&
            raw[0] == 'R'.code.toByte() &&
            raw[1] == 'I'.code.toByte() &&
            raw[2] == 'F'.code.toByte() &&
            raw[3] == 'F'.code.toByte()
        ) {
            val sampleRate = (raw[24].toInt() and 0xFF) or
                ((raw[25].toInt() and 0xFF) shl 8) or
                ((raw[26].toInt() and 0xFF) shl 16) or
                ((raw[27].toInt() and 0xFF) shl 24)
            // Find "data" chunk offset cleanly
            var dataOffset = 44
            var pos = 12
            while (pos + 8 <= raw.size) {
                val id = String(raw, pos, 4, Charsets.US_ASCII)
                val chunkSize = (raw[pos + 4].toInt() and 0xFF) or
                    ((raw[pos + 5].toInt() and 0xFF) shl 8) or
                    ((raw[pos + 6].toInt() and 0xFF) shl 16) or
                    ((raw[pos + 7].toInt() and 0xFF) shl 24)
                if (id == "data") {
                    dataOffset = (pos + 8).coerceAtMost(raw.size)
                    break
                }
                if (chunkSize <= 0) break
                pos += 8 + chunkSize
            }
            val pcm = raw.copyOfRange(dataOffset, raw.size)
            return pcm to sampleRate.coerceIn(8_000, 48_000)
        }

        // Raw PCM L16 (default Gemini TTS output is 24000 Hz mono 16-bit signed LE)
        val rateMatch = Regex("""rate=(\d+)""", RegexOption.IGNORE_CASE).find(mimeType)
        val parsedRate = rateMatch?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(8_000, 48_000) ?: 24_000
        return raw to parsedRate
    }
}
