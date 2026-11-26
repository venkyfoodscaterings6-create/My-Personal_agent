package com.example

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

sealed class ChatApiResult {
    data class Success(val reply: String, val latencyMs: Long, val httpStatus: Int) : ChatApiResult()
    data class Error(val message: String, val httpStatus: Int? = null, val latencyMs: Long? = null) : ChatApiResult()
}

sealed class FetchModelsResult {
    data class Success(val models: List<String>) : FetchModelsResult()
    data class EmptyOrUnsupported(val message: String) : FetchModelsResult()
}

sealed class ActionPlanApiResult {
    data class Success(val reply: String, val plan: ActionPlan) : ActionPlanApiResult()
    data class Error(val message: String) : ActionPlanApiResult()
}

sealed class NextActionApiResult {
    data class Success(val action: OnDeviceAgentAction) : NextActionApiResult()
    data class Error(val message: String) : NextActionApiResult()
}

sealed class ToolRouteApiResult {
    data class Success(val toolCall: RoutedToolCall, val latencyMs: Long) : ToolRouteApiResult()
    data class Error(val message: String) : ToolRouteApiResult()
}

class NetworkClient {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val chatClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun sendChatMessage(
        provider: AiProvider,
        conversationHistory: List<ChatMessage>,
        systemPrompt: String? = null,
        maxTokens: Int? = null,
        temperature: Double? = null
    ): ChatApiResult = withContext(Dispatchers.IO) {
        if (!EndpointResolver.isValidHttpUrl(provider.baseUrl)) {
            return@withContext ChatApiResult.Error(
                "Invalid Base URL. Make sure it starts with https:// or http:// (e.g., https://api.example.com/v1)."
            )
        }
        if (provider.modelName.isBlank()) {
            return@withContext ChatApiResult.Error(
                "Model Name is empty. Open Settings and enter a model ID for ${provider.displayName}."
            )
        }

        val recentMessages = ConversationMemoryHelper.buildRecentChatMessages(conversationHistory, maxTurns = 15)
        if (recentMessages.isEmpty()) {
            return@withContext ChatApiResult.Error("Cannot send an empty message.")
        }

        val effectiveSystemPrompt = systemPrompt?.takeIf { it.isNotBlank() } ?: DEFAULT_CHAT_SYSTEM_PROMPT

        val endpoint = EndpointResolver.resolveChatEndpoint(
            rawBaseUrl = provider.baseUrl,
            apiFormat = provider.apiFormat,
            modelName = provider.modelName
        )

        val startNanos = System.nanoTime()
        try {
            val request = when (provider.apiFormat) {
                ApiFormat.OPENAI_COMPATIBLE -> buildOpenAiChatRequest(
                    endpoint, provider, recentMessages, effectiveSystemPrompt, maxTokens, temperature
                )
                ApiFormat.ANTHROPIC_NATIVE -> buildAnthropicChatRequest(
                    endpoint, provider, recentMessages, effectiveSystemPrompt, maxTokens, temperature
                )
                ApiFormat.GEMINI_NATIVE -> buildGeminiChatRequest(
                    endpoint, provider, recentMessages, effectiveSystemPrompt, maxTokens, temperature
                )
            }

            chatClient.newCall(request).execute().use { response ->
                val latencyMs = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(1L)
                val bodyText = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    return@withContext ChatApiResult.Error(
                        message = explainHttpError(response.code, bodyText),
                        httpStatus = response.code,
                        latencyMs = latencyMs
                    )
                }

                val parsedReply = when (provider.apiFormat) {
                    ApiFormat.OPENAI_COMPATIBLE -> parseOpenAiReply(bodyText)
                    ApiFormat.ANTHROPIC_NATIVE -> parseAnthropicReply(bodyText)
                    ApiFormat.GEMINI_NATIVE -> parseGeminiReply(bodyText)
                }

                if (parsedReply.isNullOrBlank()) {
                    ChatApiResult.Error(
                        message = "Connected (HTTP ${response.code}), but the endpoint returned an unexpected or empty response format.",
                        httpStatus = response.code,
                        latencyMs = latencyMs
                    )
                } else {
                    ChatApiResult.Success(
                        reply = parsedReply.trim(),
                        latencyMs = latencyMs,
                        httpStatus = response.code
                    )
                }
            }
        } catch (e: Exception) {
            val latencyMs = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(1L)
            ChatApiResult.Error(
                message = explainNetworkException(e, endpoint),
                latencyMs = latencyMs
            )
        }
    }

    suspend fun generateActionPlan(
        provider: AiProvider,
        userGoal: String,
        safetyRules: SafetyRules
    ): ActionPlanApiResult = withContext(Dispatchers.IO) {
        val systemPrompt = buildString {
            appendLine("You are an on-device Android automation planner.")
            appendLine("The user wants you to control their Android phone using AccessibilityService.")
            appendLine("Before taking any action, return ONLY a compact JSON object with this exact shape:")
            appendLine("""{"title":"Short 3-6 word action title","riskLevel":"LOW","steps":["Step 1","Step 2","Step 3"],"reply":"One friendly sentence asking the user to review and approve."}""")
            appendLine("Rules:")
            appendLine("- riskLevel must be LOW, MEDIUM, or HIGH.")
            appendLine("- Keep steps concise, concrete, and between 2 and 6 steps.")
            if (safetyRules.blockSensitiveFinancial) {
                appendLine("- NEVER plan banking, UPI, OTP, payment, or password entry actions.")
            }
            appendLine("- Output pure JSON only. No markdown fences or extra commentary.")
        }

        val userPrompt = "User request: $userGoal"
        val promptMessage = listOf(ChatMessage(sender = MessageSender.USER, text = userPrompt))

        when (
            val result = sendChatMessage(
                provider = provider,
                conversationHistory = promptMessage,
                systemPrompt = systemPrompt,
                maxTokens = 260,
                temperature = 0.2
            )
        ) {
            is ChatApiResult.Error -> ActionPlanApiResult.Error(result.message)
            is ChatApiResult.Success -> {
                val json = extractJsonObject(result.reply)
                if (json == null) {
                    val fallbackPlan = ActionPlan(
                        actionId = UUID.randomUUID().toString(),
                        title = userGoal.take(56),
                        riskLevel = RiskLevel.MEDIUM,
                        steps = listOf(
                            "Inspect current Android screen elements",
                            "Execute requested task: ${userGoal.take(80)}",
                            "Verify completion and report summary"
                        ),
                        isDemo = false
                    )
                    ActionPlanApiResult.Success(
                        reply = "Please review this proposed plan before I control your phone.",
                        plan = fallbackPlan
                    )
                } else {
                    val stepsArr = json.optJSONArray("steps")
                    val steps = buildList {
                        if (stepsArr != null) {
                            for (i in 0 until stepsArr.length()) {
                                val s = stepsArr.optString(i).trim()
                                if (s.isNotBlank()) add(s)
                            }
                        }
                    }.ifEmpty {
                        listOf("Execute requested phone action: ${userGoal.take(80)}")
                    }

                    val plan = ActionPlan(
                        actionId = UUID.randomUUID().toString(),
                        title = json.optString("title").trim().ifBlank { userGoal.take(56) },
                        riskLevel = RiskLevel.fromRaw(json.optString("riskLevel")),
                        steps = steps,
                        isDemo = false
                    )
                    val reply = json.optString("reply").trim()
                        .ifBlank { "Please review the steps below and tap Approve to run on your phone." }

                    ActionPlanApiResult.Success(reply = reply, plan = plan)
                }
            }
        }
    }

    suspend fun routeHybridTool(
        provider: AiProvider,
        userGoal: String,
        installedAppSample: List<String>,
        safetyRules: SafetyRules,
        conversationHistory: List<ChatMessage> = emptyList()
    ): ToolRouteApiResult = withContext(Dispatchers.IO) {
        val appsHint = installedAppSample.take(30).joinToString(", ")
        val recentHistoryText = ConversationMemoryHelper.formatRecentAgentHistory(conversationHistory, maxTurns = 8)

        val systemPrompt = buildString {
            appendLine("You are an Android Hybrid Tool Router. Output ONLY ONE valid JSON object (no markdown, no explanation).")
            appendLine("Available tools:")
            appendLine("""0) {"tool":"reply_to_user","message":"Warm natural reply","reason":"Conversational reply"}""")
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
            appendLine("""19) {"tool":"ui_automate","goal":"full multi-step goal","title":"Short title","steps":["Step 1","Step 2"],"riskLevel":"MEDIUM","reason":"Multi-step UI automation on current screen or app"}""")
            appendLine("CRITICAL RULES:")
            appendLine("- Greetings, small talk, thanks, or general conversational questions ('Hi', 'Hello', 'Good morning', 'How are you?', 'Thank you', or Telugu equivalents) MUST ALWAYS route to reply_to_user(message) — NEVER open_app or ui_automate!")
            appendLine("- Use RECENT CONVERSATION HISTORY to resolve follow-ups like 'play the first one', 'send him I am on the way', 'scroll down', or 'pause'.")
            appendLine("- NEVER pick open_app for compound requests like 'open YouTube and play...' or 'open WhatsApp and send hi to Ravi'.")
            appendLine("- For playing any video/song on YouTube (even in Telugu or English), ALWAYS pick play_youtube_video.")
            appendLine("- For sending a WhatsApp or SMS message, ALWAYS pick send_message with resolved contactOrNumber, messagingApp, and messageText.")
            appendLine("- For tapping a single visible button or text on the current screen (e.g., 'click Skip', 'tap Subscribe', 'press Like'), pick tap_on_screen(targetText).")
            appendLine("- For volume changes, pick set_volume(stream, levelPercent).")
            appendLine("- For system actions (back, home, recents, notifications, quick settings), pick the matching native tool.")
            appendLine("- For multi-step workflows inside any app, pick ui_automate.")
            if (safetyRules.blockSensitiveFinancial) {
                appendLine("- NEVER route banking, UPI, OTP, payment, or password actions.")
            }
            if (appsHint.isNotBlank()) {
                appendLine("Installed apps sample: $appsHint")
            }
        }

        val userPrompt = buildString {
            if (recentHistoryText.isNotBlank()) {
                appendLine("RECENT CONVERSATION HISTORY:")
                appendLine(recentHistoryText)
                appendLine()
            }
            append("CURRENT USER REQUEST: $userGoal")
        }

        val firstMsg = listOf(ChatMessage(sender = MessageSender.USER, text = userPrompt))
        val startNanos = System.nanoTime()

        when (
            val firstResult = sendChatMessage(
                provider = provider,
                conversationHistory = firstMsg,
                systemPrompt = systemPrompt,
                maxTokens = 200,
                temperature = 0.0
            )
        ) {
            is ChatApiResult.Error -> return@withContext ToolRouteApiResult.Error(firstResult.message)
            is ChatApiResult.Success -> {
                val parsed = HybridToolRouting.parseRoutedToolJson(firstResult.reply, userGoal)
                if (parsed != null) {
                    val elapsed = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(1L)
                    return@withContext ToolRouteApiResult.Success(parsed, elapsed)
                }

                // 1 automatic retry if malformed JSON (preserving conversation history context)
                val retryMessages = listOf(
                    ChatMessage(sender = MessageSender.USER, text = userPrompt),
                    ChatMessage(sender = MessageSender.AGENT, text = firstResult.reply.take(200)),
                    ChatMessage(
                        sender = MessageSender.USER,
                        text = "Your previous reply was not valid JSON. Return ONLY a single raw JSON object with a \"tool\" field now."
                    )
                )
                when (
                    val retryResult = sendChatMessage(
                        provider = provider,
                        conversationHistory = retryMessages,
                        systemPrompt = systemPrompt,
                        maxTokens = 200,
                        temperature = 0.0
                    )
                ) {
                    is ChatApiResult.Error -> return@withContext ToolRouteApiResult.Error(retryResult.message)
                    is ChatApiResult.Success -> {
                        val elapsed = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(1L)
                        val retried = HybridToolRouting.parseRoutedToolJson(retryResult.reply, userGoal)
                            ?: RoutedToolCall(
                                tool = ToolName.UI_AUTOMATE,
                                goal = userGoal,
                                title = userGoal.take(56),
                                steps = listOf(
                                    "Inspect current screen",
                                    "Execute: ${userGoal.take(80)}",
                                    "Verify completion"
                                ),
                                riskLevel = RiskLevel.MEDIUM,
                                reason = "Multi-step on-device task"
                            )
                        return@withContext ToolRouteApiResult.Success(retried, elapsed)
                    }
                }
            }
        }
    }

    suspend fun runSelfTestJsonProbe(provider: AiProvider): SelfTestItem = withContext(Dispatchers.IO) {
        val prompt = listOf(
            ChatMessage(
                sender = MessageSender.USER,
                text = "Return ONLY this JSON object with no extra text: {\"ok\":true,\"tool\":\"ping\"}"
            )
        )
        when (
            val result = sendChatMessage(
                provider = provider,
                conversationHistory = prompt,
                systemPrompt = "Output strict JSON only.",
                maxTokens = 40,
                temperature = 0.0
            )
        ) {
            is ChatApiResult.Error -> SelfTestItem(
                id = "ai_provider",
                title = "AI Provider Speed & JSON Accuracy",
                passed = false,
                detail = "${provider.displayName}: ${result.message}"
            )
            is ChatApiResult.Success -> {
                val json = HybridToolRouting.extractJsonObject(result.reply)
                val validJson = json != null && json.optBoolean("ok", false)
                SelfTestItem(
                    id = "ai_provider",
                    title = "AI Provider Speed & JSON Accuracy",
                    passed = validJson,
                    detail = if (validJson) {
                        "${provider.displayName} (${provider.modelName}) · ${result.latencyMs} ms · Valid JSON"
                    } else {
                        "${provider.displayName} responded in ${result.latencyMs} ms, but JSON was malformed: ${result.reply.take(60)}"
                    }
                )
            }
        }
    }

    suspend fun requestNextOnDeviceAction(
        provider: AiProvider,
        goal: String,
        plannedSteps: List<String>,
        screenSnapshot: ScreenSnapshot,
        stepHistory: List<String>,
        stepNumber: Int,
        maxSteps: Int,
        lastActionFeedback: String? = null,
        conversationHistory: List<ChatMessage> = emptyList()
    ): NextActionApiResult = withContext(Dispatchers.IO) {
        val systemPrompt = buildString {
            appendLine("You control an Android phone via AccessibilityService. You receive up to 45 visible screen elements formatted as [id] text | desc | clickable | editable, the current app, and the last 5 actions.")
            appendLine("Reply with ONE compact JSON object only (no markdown, no explanation).")
            appendLine("Actions:")
            appendLine("""{"action":"open_app","appName":"YouTube","reason":"Open YouTube"}""")
            appendLine("""{"action":"tap","index":3,"reason":"Tap first video"}""")
            appendLine("""{"action":"type_text","index":2,"text":"hello","submit":true,"reason":"Type and submit search"}""")
            appendLine("""{"action":"scroll","direction":"down","reason":"Scroll down"}""")
            appendLine("""{"action":"back","reason":"Go back"}""")
            appendLine("""{"action":"home","reason":"Go home"}""")
            appendLine("""{"action":"done","summary":"Yes, completed! Short clear summary of what was done.","reason":"Done"}""")
            appendLine("CRITICAL RULES:")
            appendLine("- If the user wants to open an app, ALWAYS return open_app immediately on Step 1 — NEVER press Home or swipe through screens to look for the app icon.")
            appendLine("- If the user's goal is a follow-up on the currently open app (like 'play the second video', 'pause', 'scroll down'), act directly on the visible screen elements.")
            appendLine("- NEVER return \"done\" right after opening an app if the goal also asks to play, search, click, or toggle something inside that app. Finish every requested action and verify the screen first.")
            appendLine("- When returning \"done\", ALWAYS write a clear \"summary\" starting with \"Yes, completed! \" describing what you finished.")
            appendLine("- Never interact with banking, UPI, OTP, or password fields.")
        }

        val recentContext = ConversationMemoryHelper.formatRecentAgentHistory(conversationHistory, maxTurns = 8)
        val userPrompt = buildString {
            if (recentContext.isNotBlank()) {
                appendLine("RECENT CONVERSATION:")
                appendLine(recentContext)
            }
            appendLine("GOAL: $goal")
            appendLine("STEP: $stepNumber/$maxSteps")
            if (stepHistory.isNotEmpty()) {
                appendLine("LAST 5 ACTIONS:")
                stepHistory.takeLast(5).forEach { appendLine("- $it") }
            }
            if (!lastActionFeedback.isNullOrBlank()) {
                appendLine("WARNING: $lastActionFeedback")
            }
            appendLine("SCREEN:")
            appendLine(screenSnapshot.compactSummary)
            append("Next JSON:")
        }

        val msg = listOf(ChatMessage(sender = MessageSender.USER, text = userPrompt))
        when (
            val result = sendChatMessage(
                provider = provider,
                conversationHistory = msg,
                systemPrompt = systemPrompt,
                maxTokens = 150,
                temperature = 0.1
            )
        ) {
            is ChatApiResult.Error -> NextActionApiResult.Error(result.message)
            is ChatApiResult.Success -> {
                val json = HybridToolRouting.extractJsonObject(result.reply)
                    ?: return@withContext NextActionApiResult.Error(
                        "AI returned an unreadable step response: ${result.reply.take(120)}"
                    )

                val actionType = json.optString("action").trim().lowercase()
                if (actionType.isBlank()) {
                    return@withContext NextActionApiResult.Error("AI response did not specify an action.")
                }

                val parsed = OnDeviceAgentAction(
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
                NextActionApiResult.Success(parsed)
            }
        }
    }

    suspend fun fetchModels(
        rawBaseUrl: String,
        apiFormat: ApiFormat,
        apiKey: String
    ): FetchModelsResult = withContext(Dispatchers.IO) {
        if (!EndpointResolver.isValidHttpUrl(rawBaseUrl)) {
            return@withContext FetchModelsResult.EmptyOrUnsupported(
                "Enter a valid Base URL starting with https:// or http:// first."
            )
        }

        val modelsUrl = EndpointResolver.resolveModelsEndpoint(rawBaseUrl, apiFormat)
        val trimmedKey = apiKey.trim()

        try {
            val finalUrl = if (apiFormat == ApiFormat.GEMINI_NATIVE && trimmedKey.isNotBlank()) {
                val sep = if (modelsUrl.contains("?")) "&" else "?"
                "$modelsUrl${sep}key=${URLEncoder.encode(trimmedKey, "UTF-8")}"
            } else {
                modelsUrl
            }

            val reqBuilder = Request.Builder()
                .url(finalUrl)
                .get()
                .header("Accept", "application/json")

            when (apiFormat) {
                ApiFormat.OPENAI_COMPATIBLE -> {
                    if (trimmedKey.isNotBlank()) {
                        reqBuilder.header("Authorization", "Bearer $trimmedKey")
                    }
                    if (EndpointResolver.isOpenRouterUrl(rawBaseUrl)) {
                        reqBuilder.header("HTTP-Referer", "https://my-personal-agent.local")
                        reqBuilder.header("X-Title", "My Personal Agent")
                    }
                }

                ApiFormat.ANTHROPIC_NATIVE -> {
                    if (trimmedKey.isNotBlank()) {
                        reqBuilder.header("x-api-key", trimmedKey)
                    }
                    reqBuilder.header("anthropic-version", "2023-06-01")
                }

                ApiFormat.GEMINI_NATIVE -> Unit
            }

            chatClient.newCall(reqBuilder.build()).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val friendly = when (response.code) {
                        401, 403 -> "HTTP ${response.code} — API key rejected. Check your key or type your model ID manually."
                        404, 405, 501 -> "Endpoint doesn't list models — type your model ID manually."
                        else -> "Endpoint doesn't list models (HTTP ${response.code}) — type your model ID manually."
                    }
                    return@withContext FetchModelsResult.EmptyOrUnsupported(friendly)
                }

                val parsed = parseModelsList(bodyText)
                if (parsed.isEmpty()) {
                    FetchModelsResult.EmptyOrUnsupported(
                        "Endpoint doesn't list models — type your model ID manually."
                    )
                } else {
                    FetchModelsResult.Success(parsed)
                }
            }
        } catch (_: Exception) {
            FetchModelsResult.EmptyOrUnsupported(
                "Endpoint doesn't list models — type your model ID manually."
            )
        }
    }

    suspend fun testProviderConnection(provider: AiProvider): EndpointTestDetail {
        val pingHistory = listOf(
            ChatMessage(
                sender = MessageSender.USER,
                text = "Reply with OK"
            )
        )
        return when (val result = sendChatMessage(provider, pingHistory)) {
            is ChatApiResult.Success -> EndpointTestDetail(
                label = "${provider.displayName} (${provider.modelName})",
                success = true,
                latencyMs = result.latencyMs,
                httpStatus = result.httpStatus,
                message = "Connected in ${result.latencyMs} ms — replied: \"${result.reply.take(40)}\""
            )

            is ChatApiResult.Error -> EndpointTestDetail(
                label = "${provider.displayName} (${provider.modelName.ifBlank { "no model" }})",
                success = false,
                latencyMs = result.latencyMs,
                httpStatus = result.httpStatus,
                message = result.message
            )
        }
    }

    fun buildOpenAiPayloadMap(
        modelName: String,
        conversationHistory: List<ChatMessage>,
        systemPrompt: String? = DEFAULT_CHAT_SYSTEM_PROMPT,
        maxTokens: Int? = null,
        temperature: Double? = null
    ): Map<String, Any> {
        val recent = ConversationMemoryHelper.buildRecentChatMessages(conversationHistory, maxTurns = 15)
        val messages = mutableListOf<Map<String, String>>()
        if (!systemPrompt.isNullOrBlank()) {
            messages.add(mapOf("role" to "system", "content" to systemPrompt))
        }
        recent.forEach { msg ->
            messages.add(
                mapOf(
                    "role" to if (msg.sender == MessageSender.USER) "user" else "assistant",
                    "content" to msg.text
                )
            )
        }
        return LinkedHashMap<String, Any>().apply {
            put("model", modelName.trim())
            put("messages", messages)
            if (maxTokens != null) put("max_tokens", maxTokens)
            if (temperature != null) put("temperature", temperature)
        }
    }

    fun buildAnthropicPayloadMap(
        modelName: String,
        conversationHistory: List<ChatMessage>,
        systemPrompt: String? = DEFAULT_CHAT_SYSTEM_PROMPT,
        maxTokens: Int? = null,
        temperature: Double? = null
    ): Map<String, Any> {
        val recent = ConversationMemoryHelper.buildRecentChatMessages(conversationHistory, maxTurns = 15)
        val normalized = mutableListOf<Pair<String, String>>()
        recent.forEach { msg ->
            val role = if (msg.sender == MessageSender.USER) "user" else "assistant"
            if (normalized.isEmpty() && role != "user") return@forEach
            if (normalized.isNotEmpty() && normalized.last().first == role) {
                val prev = normalized.removeAt(normalized.lastIndex)
                normalized.add(role to "${prev.second}\n\n${msg.text}")
            } else {
                normalized.add(role to msg.text)
            }
        }
        val messages = normalized.map { (role, content) ->
            mapOf("role" to role, "content" to content)
        }
        return LinkedHashMap<String, Any>().apply {
            put("model", modelName.trim())
            put("max_tokens", maxTokens ?: 1024)
            if (temperature != null) put("temperature", temperature)
            if (!systemPrompt.isNullOrBlank()) put("system", systemPrompt)
            put("messages", messages)
        }
    }

    fun buildGeminiPayloadMap(
        conversationHistory: List<ChatMessage>,
        systemPrompt: String? = DEFAULT_CHAT_SYSTEM_PROMPT,
        maxTokens: Int? = null,
        temperature: Double? = null
    ): Map<String, Any> {
        val recent = ConversationMemoryHelper.buildRecentChatMessages(conversationHistory, maxTurns = 15)
        // Normalize consecutive same-role turns so Gemini never rejects multi-turn payloads
        val normalized = mutableListOf<Pair<String, String>>()
        recent.forEach { msg ->
            val role = if (msg.sender == MessageSender.USER) "user" else "model"
            if (normalized.isEmpty() && role != "user") return@forEach
            if (normalized.isNotEmpty() && normalized.last().first == role) {
                val prev = normalized.removeAt(normalized.lastIndex)
                normalized.add(role to "${prev.second}\n\n${msg.text}")
            } else {
                normalized.add(role to msg.text)
            }
        }
        val contents = normalized.map { (role, text) ->
            mapOf(
                "role" to role,
                "parts" to listOf(mapOf("text" to text))
            )
        }
        return LinkedHashMap<String, Any>().apply {
            if (!systemPrompt.isNullOrBlank()) {
                put("systemInstruction", mapOf("parts" to listOf(mapOf("text" to systemPrompt))))
            }
            if (maxTokens != null || temperature != null) {
                val genConfig = LinkedHashMap<String, Any>()
                if (maxTokens != null) genConfig["maxOutputTokens"] = maxTokens
                if (temperature != null) genConfig["temperature"] = temperature
                put("generationConfig", genConfig)
            }
            put("contents", contents)
        }
    }

    private fun buildOpenAiChatRequest(
        endpoint: String,
        provider: AiProvider,
        history: List<ChatMessage>,
        systemPrompt: String?,
        maxTokens: Int?,
        temperature: Double?
    ): Request {
        val payloadJson = HybridToolRouting.mapToJson(
            buildOpenAiPayloadMap(provider.modelName, history, systemPrompt, maxTokens, temperature)
        )

        val builder = Request.Builder()
            .url(endpoint)
            .post(payloadJson.toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")

        val trimmedKey = provider.apiKey.trim()
        if (trimmedKey.isNotBlank()) {
            builder.header("Authorization", "Bearer $trimmedKey")
        }
        if (EndpointResolver.isOpenRouterUrl(provider.baseUrl)) {
            builder.header("HTTP-Referer", "https://my-personal-agent.local")
            builder.header("X-Title", "My Personal Agent")
        }

        return builder.build()
    }

    private fun buildAnthropicChatRequest(
        endpoint: String,
        provider: AiProvider,
        history: List<ChatMessage>,
        systemPrompt: String?,
        maxTokens: Int?,
        temperature: Double?
    ): Request {
        val payloadJson = HybridToolRouting.mapToJson(
            buildAnthropicPayloadMap(provider.modelName, history, systemPrompt, maxTokens, temperature)
        )

        val builder = Request.Builder()
            .url(endpoint)
            .post(payloadJson.toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("anthropic-version", "2023-06-01")

        val trimmedKey = provider.apiKey.trim()
        if (trimmedKey.isNotBlank()) {
            builder.header("x-api-key", trimmedKey)
        }

        return builder.build()
    }

    private fun buildGeminiChatRequest(
        endpoint: String,
        provider: AiProvider,
        history: List<ChatMessage>,
        systemPrompt: String?,
        maxTokens: Int?,
        temperature: Double?
    ): Request {
        val payloadJson = HybridToolRouting.mapToJson(
            buildGeminiPayloadMap(history, systemPrompt, maxTokens, temperature)
        )

        val trimmedKey = provider.apiKey.trim()
        val finalUrl = if (trimmedKey.isNotBlank() && !endpoint.contains("key=")) {
            val sep = if (endpoint.contains("?")) "&" else "?"
            "$endpoint${sep}key=${URLEncoder.encode(trimmedKey, "UTF-8")}"
        } else {
            endpoint
        }

        return Request.Builder()
            .url(finalUrl)
            .post(payloadJson.toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .build()
    }

    companion object {
        const val DEFAULT_CHAT_SYSTEM_PROMPT =
            "You are a helpful, concise personal AI assistant. Always remember and use the prior conversation history when answering follow-up questions."
    }

    private fun extractJsonObject(rawText: String): JSONObject? {
        val cleaned = rawText
            .trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        runCatching { return JSONObject(cleaned) }

        val firstBrace = cleaned.indexOf('{')
        val lastBrace = cleaned.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            val slice = cleaned.substring(firstBrace, lastBrace + 1)
            runCatching { return JSONObject(slice) }
        }
        return null
    }

    private fun parseOpenAiReply(body: String): String? {
        return runCatching {
            val root = JSONObject(body)
            val choices = root.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val first = choices.getJSONObject(0)
                val message = first.optJSONObject("message")
                if (message != null) {
                    val contentObj = message.opt("content")
                    when (contentObj) {
                        is String -> return@runCatching contentObj
                        is JSONArray -> {
                            val parts = buildList {
                                for (i in 0 until contentObj.length()) {
                                    val part = contentObj.optJSONObject(i)
                                    val text = part?.optString("text").orEmpty()
                                    if (text.isNotBlank()) add(text)
                                }
                            }
                            if (parts.isNotEmpty()) return@runCatching parts.joinToString("\n")
                        }
                    }
                }
                val fallbackText = first.optString("text")
                if (fallbackText.isNotBlank()) return@runCatching fallbackText
            }
            root.optString("output_text").takeIf { it.isNotBlank() }
                ?: root.optString("reply").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun parseAnthropicReply(body: String): String? {
        return runCatching {
            val root = JSONObject(body)
            val content = root.optJSONArray("content") ?: return@runCatching null
            val parts = buildList {
                for (i in 0 until content.length()) {
                    val block = content.optJSONObject(i)
                    if (block?.optString("type") == "text") {
                        val text = block.optString("text")
                        if (text.isNotBlank()) add(text)
                    }
                }
            }
            parts.joinToString("\n").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun parseGeminiReply(body: String): String? {
        return runCatching {
            val root = JSONObject(body)
            val candidates = root.optJSONArray("candidates") ?: return@runCatching null
            if (candidates.length() == 0) return@runCatching null
            val parts = candidates.getJSONObject(0)
                .optJSONObject("content")
                ?.optJSONArray("parts") ?: return@runCatching null
            val texts = buildList {
                for (i in 0 until parts.length()) {
                    val text = parts.optJSONObject(i)?.optString("text").orEmpty()
                    if (text.isNotBlank()) add(text)
                }
            }
            texts.joinToString("\n").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun parseModelsList(body: String): List<String> {
        val result = linkedSetOf<String>()
        runCatching {
            val trimmed = body.trim()
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                extractModelIdsFromArray(arr, result)
            } else {
                val root = JSONObject(trimmed)
                root.optJSONArray("data")?.let { extractModelIdsFromArray(it, result) }
                root.optJSONArray("models")?.let { extractModelIdsFromArray(it, result) }
            }
        }
        return result.sorted()
    }

    private fun extractModelIdsFromArray(array: JSONArray, out: MutableSet<String>) {
        for (i in 0 until array.length()) {
            val item = array.opt(i)
            when (item) {
                is String -> {
                    val cleaned = item.trim().removePrefix("models/")
                    if (cleaned.isNotBlank()) out.add(cleaned)
                }
                is JSONObject -> {
                    val raw = item.optString("id").ifBlank {
                        item.optString("model").ifBlank {
                            item.optString("name")
                        }
                    }
                    val cleaned = raw.trim().removePrefix("models/")
                    if (cleaned.isNotBlank()) out.add(cleaned)
                }
            }
        }
    }

    private fun explainHttpError(code: Int, rawBody: String): String {
        val serverDetail = extractShortErrorMessage(rawBody)
        val suffix = if (serverDetail.isNotBlank()) "\nDetails: $serverDetail" else ""
        return when (code) {
            400 -> "HTTP 400 — Bad request. Check your Model Name and API Format.$suffix"
            401 -> "HTTP 401 — Invalid API key. Double-check your key in Settings.$suffix"
            403 -> "HTTP 403 — Access forbidden. Check key permissions or account status.$suffix"
            404 -> "HTTP 404 — Check Base URL or Model Name (endpoint/model not found).$suffix"
            429 -> "HTTP 429 — Rate limit or quota exceeded. Wait a moment or check provider credits.$suffix"
            in 500..599 -> "HTTP $code — Remote server error. Try again in a few seconds.$suffix"
            else -> "HTTP $code — Request failed.$suffix"
        }
    }

    private fun extractShortErrorMessage(rawBody: String): String {
        if (rawBody.isBlank()) return ""
        val parsed = runCatching {
            val root = JSONObject(rawBody)
            val errObj = root.optJSONObject("error")
            errObj?.optString("message")?.takeIf { it.isNotBlank() }
                ?: root.optString("error").takeIf { it.isNotBlank() && !it.startsWith("{") }
                ?: root.optString("message").takeIf { it.isNotBlank() }
                ?: root.optString("detail").takeIf { it.isNotBlank() }
        }.getOrNull()
        return (parsed ?: rawBody.replace("\n", " ")).trim().take(180)
    }

    private fun explainNetworkException(e: Exception, url: String): String {
        return when (e) {
            is UnknownHostException -> "Could not resolve host for $url. Check your internet or Base URL spelling."
            is ConnectException -> "Connection refused at $url. Make sure your server is running and reachable."
            is SocketTimeoutException -> "Request timed out waiting for $url. The server took too long to respond."
            is SSLException -> "SSL/TLS handshake failed for $url. Verify HTTPS certificate or try http:// for a local server."
            is IllegalArgumentException -> "Invalid URL format: ${e.message ?: url}."
            is IOException -> "Network error: ${e.message ?: "Unable to reach endpoint."}"
            else -> "Unexpected request error: ${e.message ?: e::class.java.simpleName}"
        }
    }
}
