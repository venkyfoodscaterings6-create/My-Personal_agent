package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun `openai compatible bare domain normalizes to v1 chat completions`() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            EndpointResolver.resolveChatEndpoint("https://api.example.com", ApiFormat.OPENAI_COMPATIBLE)
        )
        assertEquals(
            "http://100.80.12.5:11434/v1/chat/completions",
            EndpointResolver.resolveChatEndpoint("  http://100.80.12.5:11434/  ", ApiFormat.OPENAI_COMPATIBLE)
        )
    }

    @Test
    fun `openai compatible versioned path appends chat completions cleanly`() {
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            EndpointResolver.resolveChatEndpoint("https://openrouter.ai/api/v1/", ApiFormat.OPENAI_COMPATIBLE)
        )
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            EndpointResolver.resolveChatEndpoint(
                "https://api.example.com/v1/chat/completions/",
                ApiFormat.OPENAI_COMPATIBLE
            )
        )
    }

    @Test
    fun `models endpoint strips chat completions or adds v1 models`() {
        assertEquals(
            "https://api.example.com/v1/models",
            EndpointResolver.resolveModelsEndpoint("https://api.example.com", ApiFormat.OPENAI_COMPATIBLE)
        )
        assertEquals(
            "https://api.example.com/v1/models",
            EndpointResolver.resolveModelsEndpoint(
                "https://api.example.com/v1/chat/completions",
                ApiFormat.OPENAI_COMPATIBLE
            )
        )
        assertEquals(
            "https://openrouter.ai/api/v1/models",
            EndpointResolver.resolveModelsEndpoint("https://openrouter.ai/api/v1/", ApiFormat.OPENAI_COMPATIBLE)
        )
    }

    @Test
    fun `anthropic and gemini native endpoints normalize cleanly`() {
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            EndpointResolver.resolveChatEndpoint("https://api.anthropic.com/v1/", ApiFormat.ANTHROPIC_NATIVE)
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
            EndpointResolver.resolveChatEndpoint(
                "https://generativelanguage.googleapis.com/v1beta",
                ApiFormat.GEMINI_NATIVE,
                "gemini-2.5-flash"
            )
        )
    }

    @Test
    fun `http and https validation rejects malformed urls`() {
        assertTrue(EndpointResolver.isValidHttpUrl("https://api.deepseek.com/v1"))
        assertTrue(EndpointResolver.isValidHttpUrl("http://192.168.1.10:8080/v1"))
        assertFalse(EndpointResolver.isValidHttpUrl("ftp://example.com"))
        assertFalse(EndpointResolver.isValidHttpUrl("api.example.com"))
        assertFalse(EndpointResolver.isValidHttpUrl(""))
    }

    @Test
    fun `safety guardrail detects financial upi otp and password actions while allowing safe tasks`() {
        assertNotNull(SafetyGuardrail.detectSensitiveViolation("Send 500 rupees via UPI on PhonePe"))
        assertNotNull(SafetyGuardrail.detectSensitiveViolation("Read my bank OTP from SMS"))
        assertNotNull(SafetyGuardrail.detectSensitiveViolation("Enter my login password in Chrome"))
        assertNull(SafetyGuardrail.detectSensitiveViolation("Turn on Do Not Disturb and play focus music"))
        assertNull(SafetyGuardrail.detectSensitiveViolation("Draft a friendly reply to Alex about dinner"))
    }

    @Test
    fun `fast path matcher handles simple commands and leaves multi-step commands for AI`() {
        val openYt = FastPathMatcher.match("open you tube")
        assertNotNull(openYt)
        assertEquals(FastPathType.OPEN_APP, openYt?.type)
        assertEquals("Open YouTube", openYt?.title)
        assertTrue(openYt?.isLowRiskAppLaunch == true)

        val ytSearch = FastPathMatcher.match("search lofi beats on youtube")
        assertNotNull(ytSearch)
        assertEquals(FastPathType.YOUTUBE_SEARCH, ytSearch?.type)
        assertEquals("lofi beats", ytSearch?.arg)

        assertEquals(FastPathType.GO_HOME, FastPathMatcher.match("go home")?.type)
        assertEquals(FastPathType.GO_BACK, FastPathMatcher.match("go back")?.type)

        // Multi-step commands must NOT be swallowed by simple open_app fast-path
        assertNull(FastPathMatcher.match("open whatsapp and send hi to mom"))
        assertNull(FastPathMatcher.match("open settings then turn on wifi"))
        assertNull(FastPathMatcher.match("open youtube and play lofi beats"))
    }

    @Test
    fun `hybrid tool routing maps compound youtube play commands to play_youtube_video`() {
        val compound = HybridToolRouting.matchDirectLocalTool("open youtube and play lofi beats")
        assertNotNull(compound)
        assertEquals(ToolName.PLAY_YOUTUBE_VIDEO, compound?.tool)
        assertEquals("lofi beats", compound?.query)
        assertTrue(compound?.isLowRiskAutoRunnableMediaOrAlarm == true)
        assertFalse(compound?.alwaysRequiresApproval == true)

        val directPlay = HybridToolRouting.matchDirectLocalTool("play Telugu melody songs on YouTube")
        assertNotNull(directPlay)
        assertEquals(ToolName.PLAY_YOUTUBE_VIDEO, directPlay?.tool)
        assertEquals("Telugu melody songs", directPlay?.query)

        val wifiSetting = HybridToolRouting.matchDirectLocalTool("open wifi settings")
        assertNotNull(wifiSetting)
        assertEquals(ToolName.OPEN_SETTING, wifiSetting?.tool)
        assertTrue(wifiSetting?.isLowRiskAutoRunnableMediaOrAlarm == true)

        // Compound non-YouTube commands must NOT collapse into open_app
        assertNull(HybridToolRouting.matchDirectLocalTool("open settings and turn on wifi"))
        assertNull(HybridToolRouting.matchDirectLocalTool("open whatsapp and message Mom"))
    }

    @Test
    fun `resilient json parser handles markdown fences prose wrappers and nested args`() {
        val fenced = """
            Sure, here is the tool call:
            ```json
            {"tool":"play_youtube_video","args":{"query":"AR Rahman hits"},"reason":"Play music on YouTube"}
            ```
        """.trimIndent()
        val parsed = HybridToolRouting.parseRoutedToolJson(fenced, fallbackGoal = "play AR Rahman hits")
        assertNotNull(parsed)
        assertEquals(ToolName.PLAY_YOUTUBE_VIDEO, parsed?.tool)
        assertEquals("AR Rahman hits", parsed?.query)
        assertTrue(parsed?.isLowRiskAutoRunnableMediaOrAlarm == true)

        val callJson = """{"tool":"call_contact","contactOrNumber":"Mom","reason":"Call Mom"}"""
        val parsedCall = HybridToolRouting.parseRoutedToolJson(callJson, fallbackGoal = "call Mom")
        assertNotNull(parsedCall)
        assertEquals(ToolName.CALL_CONTACT, parsedCall?.tool)
        assertEquals("Mom", parsedCall?.contactOrNumber)
        assertEquals(RiskLevel.MEDIUM, parsedCall?.riskLevel)
        assertTrue(parsedCall?.shouldAutoRun(SafetyRules()) == true)
        assertFalse(parsedCall?.alwaysRequiresApproval == true)

        val msgJson = """{"tool":"draft_message","args":{"contact":"Alex","messagingApp":"whatsapp","messageText":"On my way"}}"""
        val parsedMsg = HybridToolRouting.parseRoutedToolJson(msgJson, fallbackGoal = "whatsapp Alex")
        assertNotNull(parsedMsg)
        assertEquals(ToolName.SEND_MESSAGE, parsedMsg?.tool)
        assertEquals("Alex", parsedMsg?.contactOrNumber)
        assertEquals("whatsapp", parsedMsg?.messagingApp)
        assertEquals("On my way", parsedMsg?.messageText)
        assertEquals(RiskLevel.MEDIUM, parsedMsg?.riskLevel)
        assertTrue(parsedMsg?.shouldAutoRun(SafetyRules()) == true)

        val highRiskJson = """{"tool":"ui_automate","goal":"Delete old files","riskLevel":"HIGH"}"""
        val parsedHigh = HybridToolRouting.parseRoutedToolJson(highRiskJson, fallbackGoal = "Delete old files")
        assertNotNull(parsedHigh)
        assertEquals(RiskLevel.HIGH, parsedHigh?.riskLevel)
        assertTrue(parsedHigh?.alwaysRequiresApproval == true)
        assertFalse(parsedHigh?.shouldAutoRun(SafetyRules()) == true)
    }

    @Test
    fun `fuzzy app matcher resolves spaced alias and typo app names`() {
        val sampleApps = listOf(
            InstalledAppInfo("YouTube", "com.google.android.youtube", "youtube"),
            InstalledAppInfo("WhatsApp", "com.whatsapp", "whatsapp"),
            InstalledAppInfo("Chrome", "com.android.chrome", "chrome"),
            InstalledAppInfo("Settings", "com.android.settings", "settings")
        )

        assertEquals("com.google.android.youtube", AppNameFuzzyMatcher.findBestMatch("you tube", sampleApps)?.packageName)
        assertEquals("com.google.android.youtube", AppNameFuzzyMatcher.findBestMatch("yt", sampleApps)?.packageName)
        assertEquals("com.google.android.youtube", AppNameFuzzyMatcher.findBestMatch("youtbe", sampleApps)?.packageName)
        assertEquals("com.whatsapp", AppNameFuzzyMatcher.findBestMatch("whats app", sampleApps)?.packageName)
        assertEquals("com.android.chrome", AppNameFuzzyMatcher.findBestMatch("Chrome", sampleApps)?.packageName)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `multi-turn network payloads include prior user and assistant messages in chronological order`() {
        val client = NetworkClient()
        val history = listOf(
            ChatMessage(sender = MessageSender.USER, text = "My name is Kiran and my favorite song is Naatu Naatu."),
            ChatMessage(sender = MessageSender.AGENT, text = "Nice to meet you, Kiran! Naatu Naatu is a great song."),
            ChatMessage(sender = MessageSender.AGENT, text = "Running on-device…"),
            ChatMessage(sender = MessageSender.USER, text = "What is my name and favorite song?")
        )

        // 1. OpenAI payload: system + 3 real turns (transient placeholder filtered out)
        val openAiMap = client.buildOpenAiPayloadMap(modelName = "gpt-4o-mini", conversationHistory = history)
        val openAiMessages = openAiMap["messages"] as List<Map<String, String>>
        assertEquals(4, openAiMessages.size)
        assertEquals("system", openAiMessages[0]["role"])
        assertEquals("user", openAiMessages[1]["role"])
        assertTrue(openAiMessages[1]["content"]!!.contains("Kiran"))
        assertEquals("assistant", openAiMessages[2]["role"])
        assertTrue(openAiMessages[2]["content"]!!.contains("Naatu Naatu"))
        assertEquals("user", openAiMessages[3]["role"])
        assertEquals("What is my name and favorite song?", openAiMessages[3]["content"])

        // 2. Anthropic payload: system + alternating user/assistant/user
        val anthropicMap = client.buildAnthropicPayloadMap(modelName = "claude-3-5-haiku", conversationHistory = history)
        assertNotNull(anthropicMap["system"])
        val anthropicMessages = anthropicMap["messages"] as List<Map<String, String>>
        assertEquals(3, anthropicMessages.size)
        assertEquals("user", anthropicMessages[0]["role"])
        assertEquals("assistant", anthropicMessages[1]["role"])
        assertEquals("user", anthropicMessages[2]["role"])

        // 3. Gemini payload: systemInstruction + user/model/user contents
        val geminiMap = client.buildGeminiPayloadMap(conversationHistory = history)
        assertNotNull(geminiMap["systemInstruction"])
        val geminiContents = geminiMap["contents"] as List<Map<String, Any>>
        assertEquals(3, geminiContents.size)
        assertEquals("user", geminiContents[0]["role"])
        assertEquals("model", geminiContents[1]["role"])
        assertEquals("user", geminiContents[2]["role"])

        // 4. Agent recent history helper formats up to last 8 turns cleanly
        val formattedAgentContext = ConversationMemoryHelper.formatRecentAgentHistory(history, maxTurns = 8)
        assertTrue(formattedAgentContext.contains("User: My name is Kiran"))
        assertTrue(formattedAgentContext.contains("Agent: Nice to meet you, Kiran"))
        assertFalse(formattedAgentContext.contains("Running on-device"))
    }

    @Test
    fun `send_message routes WhatsApp and SMS commands with mandatory approval and SendButtonMatcher locates Send button`() {
        val waDirect = HybridToolRouting.matchDirectLocalTool("open WhatsApp and send hi to Ravi")
        assertNotNull(waDirect)
        assertEquals(ToolName.SEND_MESSAGE, waDirect?.tool)
        assertEquals("Ravi", waDirect?.contactOrNumber)
        assertEquals("whatsapp", waDirect?.messagingApp)
        assertEquals("hi", waDirect?.messageText)
        assertEquals(RiskLevel.MEDIUM, waDirect?.riskLevel)
        assertTrue(waDirect?.shouldAutoRun(SafetyRules()) == true)

        val waSuffix = HybridToolRouting.matchDirectLocalTool("send hi to Ravi on whatsapp")
        assertNotNull(waSuffix)
        assertEquals(ToolName.SEND_MESSAGE, waSuffix?.tool)
        assertEquals("Ravi", waSuffix?.contactOrNumber)
        assertEquals("hi", waSuffix?.messageText)

        val smsDirect = HybridToolRouting.matchDirectLocalTool("send sms On my way to Mom")
        assertNotNull(smsDirect)
        assertEquals(ToolName.SEND_MESSAGE, smsDirect?.tool)
        assertEquals("Mom", smsDirect?.contactOrNumber)
        assertEquals("sms", smsDirect?.messagingApp)
        assertEquals("On my way", smsDirect?.messageText)
        assertEquals(RiskLevel.MEDIUM, smsDirect?.riskLevel)
        assertTrue(smsDirect?.shouldAutoRun(SafetyRules()) == true)

        // Pronoun follow-ups ("send him ...") must pass through to AI router with conversation history
        assertNull(HybridToolRouting.matchDirectLocalTool("send 'I am on the way' to him on whatsapp"))

        // Scroll shortcut works directly
        val scroll = HybridToolRouting.matchDirectLocalTool("scroll down")
        assertNotNull(scroll)
        assertEquals(ToolName.SCROLL, scroll?.tool)
        assertEquals("down", scroll?.direction)
        assertTrue(scroll?.isLowRiskAutoRunnableMediaOrAlarm == true)

        // SendButtonMatcher finds WhatsApp com.whatsapp:id/send and ignores "Send money"
        val whatsappNodes = listOf(
            ScreenNode(0, "Hi Ravi", "", "Hi Ravi", "EditText", true, true, 400, 1900, 1850, 100, "com.whatsapp:id/entry"),
            ScreenNode(1, "Send money", "Send money", "Send money", "Button", true, false, 200, 1750, 1700, 80, "com.whatsapp:id/pay"),
            ScreenNode(2, "", "Send", "Send", "ImageButton", true, false, 980, 1900, 1850, 100, "com.whatsapp:id/send")
        )
        val matchedWa = SendButtonMatcher.findBestSendButton(whatsappNodes)
        assertNotNull(matchedWa)
        assertEquals(2, matchedWa?.index)

        // SendButtonMatcher finds SMS "Send SMS" button
        val smsNodes = listOf(
            ScreenNode(0, "On my way", "", "On my way", "EditText", true, true, 400, 1900, 1850, 100, "com.google.android.apps.messaging:id/compose_message_text"),
            ScreenNode(1, "", "Send SMS", "Send SMS", "ImageView", true, false, 980, 1900, 1850, 100, "com.google.android.apps.messaging:id/send_message_button_icon")
        )
        val matchedSms = SendButtonMatcher.findBestSendButton(smsNodes)
        assertNotNull(matchedSms)
        assertEquals(1, matchedSms?.index)
    }

    @Test
    fun `greetings always route to reply_to_user and never open_app`() {
        val greetings = listOf("Hi", "Hello!", "Good morning", "How are you?", "Thank you")
        greetings.forEach { prompt ->
            val routed = HybridToolRouting.matchDirectLocalTool(prompt)
            assertNotNull("Expected greeting '$prompt' to match locally", routed)
            assertEquals("Greeting '$prompt' must route to reply_to_user", ToolName.REPLY_TO_USER, routed?.tool)
            assertFalse("Greeting '$prompt' must never route to open_app", routed?.tool == ToolName.OPEN_APP)
            assertTrue("Greeting '$prompt' must include a non-empty warm reply", !routed?.replyMessage.isNullOrBlank())
        }

        // AI router JSON for reply_to_user also parses cleanly
        val aiReplyJson = """{"tool":"reply_to_user","message":"Good morning! Ready whenever you are."}"""
        val parsed = HybridToolRouting.parseRoutedToolJson(aiReplyJson, fallbackGoal = "Good morning")
        assertNotNull(parsed)
        assertEquals(ToolName.REPLY_TO_USER, parsed?.tool)
        assertEquals("Good morning! Ready whenever you are.", parsed?.replyMessage)
    }

    @Test
    fun `completed tool confirmations always start with Yes completed and new native controls route cleanly`() {
        val c1 = ConfirmationFormatter.formatSuccess("Opened YouTube", "0.3s")
        assertTrue("Expected confirmation to start with 'Yes, completed', got: $c1", c1.startsWith("Yes, completed"))
        assertTrue(c1.contains("Opened YouTube"))

        val c2 = ConfirmationFormatter.formatSuccess("Yes, completed! Turned on Dark Mode.", "1.4s")
        assertTrue("Expected no double prefix, got: $c2", c2.startsWith("Yes, completed! Turned on Dark Mode"))
        assertFalse(c2.contains("Yes, completed! Yes, completed"))

        val c3 = ConfirmationFormatter.formatSuccess("")
        assertTrue(c3.startsWith("Yes, completed"))

        val tapSkip = HybridToolRouting.matchDirectLocalTool("click skip")
        assertNotNull(tapSkip)
        assertEquals(ToolName.TAP_ON_SCREEN, tapSkip?.tool)
        assertEquals("skip", tapSkip?.targetText)

        val setVol = HybridToolRouting.matchDirectLocalTool("set media volume to 60%")
        assertNotNull(setVol)
        assertEquals(ToolName.SET_VOLUME, setVol?.tool)
        assertEquals("media", setVol?.stream)
        assertEquals(60, setVol?.levelPercent)

        assertEquals(ToolName.OPEN_RECENTS, HybridToolRouting.matchDirectLocalTool("open recents")?.tool)
        assertEquals(ToolName.OPEN_NOTIFICATIONS, HybridToolRouting.matchDirectLocalTool("open notifications")?.tool)
        assertEquals(ToolName.OPEN_QUICK_SETTINGS, HybridToolRouting.matchDirectLocalTool("open quick settings")?.tool)
    }

    @Test
    fun `wake word matcher detects Hey Agent wake-only, one-shot commands, custom phrases, and rejects unrelated speech`() {
        val wakeOnly = WakeWordMatcher.extractWakeMatch("Hey Agent", "Hey Agent")
        assertNotNull(wakeOnly)
        assertNull(wakeOnly?.inlineCommand)

        val oneShot = WakeWordMatcher.extractWakeMatch("Hey Agent, open YouTube", "Hey Agent")
        assertNotNull(oneShot)
        assertEquals("open YouTube", oneShot?.inlineCommand)

        val okVariant = WakeWordMatcher.extractWakeMatch("OK Agent set media volume to 50%", "Hey Agent")
        assertNotNull(okVariant)
        assertEquals("set media volume to 50%", okVariant?.inlineCommand)

        val customPhrase = WakeWordMatcher.extractWakeMatch("Hey Jarvis play Naatu Naatu on YouTube", "Hey Jarvis")
        assertNotNull(customPhrase)
        assertEquals("play Naatu Naatu on YouTube", customPhrase?.inlineCommand)

        val teluguWake = WakeWordMatcher.extractWakeMatch("హే ఏజెంట్", "Hey Agent")
        assertNotNull(teluguWake)
        assertNull(teluguWake?.inlineCommand)

        assertNull(WakeWordMatcher.extractWakeMatch("Hello everyone let us watch a movie", "Hey Agent"))
    }

    @Test
    fun `sql memory command parser detects save memory, add task, list queries, and ignores normal chat`() {
        val saveMem = SqlMemoryCommandParser.parse("Remember that my favorite coffee is South Indian filter coffee")
        assertTrue(saveMem is SqlDirectIntent.SaveMemory)
        val mem = saveMem as SqlDirectIntent.SaveMemory
        assertTrue(mem.content.contains("filter coffee", ignoreCase = true))

        val addTask = SqlMemoryCommandParser.parse("Add task buy groceries tomorrow")
        assertTrue(addTask is SqlDirectIntent.AddTask)
        val task = addTask as SqlDirectIntent.AddTask
        assertEquals("Buy groceries tomorrow", task.title)

        val listTasks = SqlMemoryCommandParser.parse("What are my tasks?")
        assertTrue(listTasks is SqlDirectIntent.ListTasks)

        val listMemories = SqlMemoryCommandParser.parse("Show my memories")
        assertTrue(listMemories is SqlDirectIntent.ListMemories)

        assertNull(SqlMemoryCommandParser.parse("Explain quantum computing in simple words"))
    }

    @Test
    fun `built-in gemini models include gemini 4 hydrogen`() {
        assertEquals("gemini-4-hydrogen", GeminiBuiltInModel.HYDROGEN.modelId)
        assertTrue(GeminiBuiltInModel.entries.any { it.modelId == "gemini-4-hydrogen" })
    }

    @Test
    fun `live screen guardrail allows zomato banners and home screen while blocking banking apps and password fields`() {
        val ownPkg = "com.aistudio.personalagent.k7m4px"

        // Own app screen displaying guardrail notice must NEVER block
        val ownNodes = listOf(
            ScreenNode(0, "Banking, UPI, OTP, and password actions are hard-blocked", "", "", "TextView", false, false, 100, 100, 100, 40)
        )
        assertNull(SafetyGuardrail.detectSensitiveScreen(ownPkg, ownPkg, ownNodes))

        // Android Home screen with GPay / PhonePe app icons must NEVER block
        val homeNodes = listOf(
            ScreenNode(0, "PhonePe", "PhonePe", "PhonePe", "TextView", true, false, 100, 200, 200, 40),
            ScreenNode(1, "Zomato", "Zomato", "Zomato", "TextView", true, false, 200, 200, 200, 40)
        )
        assertNull(SafetyGuardrail.detectSensitiveScreen("com.google.android.apps.nexuslauncher", ownPkg, homeNodes))

        // Zomato home screen with promo banners mentioning UPI / Bank offer must NEVER block
        val zomatoNodes = listOf(
            ScreenNode(0, "Flat 100 off with HDFC Bank Credit Card", "", "", "TextView", false, false, 200, 300, 300, 40),
            ScreenNode(1, "Try Zomato UPI", "", "", "TextView", true, false, 200, 400, 400, 40)
        )
        assertNull(SafetyGuardrail.detectSensitiveScreen("com.application.zomato", ownPkg, zomatoNodes))

        // Actual PhonePe / Banking app package MUST still be blocked
        assertNotNull(SafetyGuardrail.detectSensitiveScreen("com.phonepe.app", ownPkg, emptyList()))
        assertNotNull(SafetyGuardrail.detectSensitiveScreen("com.google.android.apps.nbu.paisa.user", ownPkg, emptyList()))

        // Active password / UPI PIN editable input field MUST still be blocked
        val pinNodes = listOf(
            ScreenNode(0, "Enter UPI PIN", "Enter UPI PIN", "Enter UPI PIN", "EditText", true, true, 200, 500, 500, 60)
        )
        assertNotNull(SafetyGuardrail.detectSensitiveScreen("com.application.zomato", ownPkg, pinNodes))

        // Fuzzy match opens Zomato even when spelled "zomoto"
        val apps = listOf(InstalledAppInfo("Zomato", "com.application.zomato", "zomato"))
        assertEquals("com.application.zomato", AppNameFuzzyMatcher.findBestMatch("zomoto", apps)?.packageName)
    }

    @Test
    fun `speech text cleaner strips markdown urls and extracts streaming sentences`() {
        val raw = "**Hello!** Check [Google](https://google.com) and `val x = 1` #heading"
        val cleaned = SpeechTextCleaner.cleanForSpeech(raw)
        assertFalse(cleaned.contains("**"))
        assertFalse(cleaned.contains("https://"))
        assertFalse(cleaned.contains("`"))
        assertTrue(cleaned.contains("Hello!"))
        assertTrue(cleaned.contains("Google"))

        assertTrue(SpeechTextCleaner.containsTeluguScript("నమస్కారం, ఎలా ఉన్నారు?"))
        assertFalse(SpeechTextCleaner.containsTeluguScript("Hello, how are you?"))

        val (ready, remainder) = SpeechTextCleaner.extractReadySentences(
            "Good morning, welcome back! Today is sunny and warm. Tomorrow might"
        )
        assertEquals(2, ready.size)
        assertEquals("Good morning, welcome back!", ready[0])
        assertEquals("Today is sunny and warm.", ready[1])
        assertEquals("Tomorrow might", remainder.trim())
    }

    @Test
    fun `smart intent classifier distinguishes conversational questions from phone actions`() {
        assertFalse(SmartIntentClassifier.looksLikePhoneAction("What is the capital of Japan?"))
        assertFalse(SmartIntentClassifier.looksLikePhoneAction("Explain quantum computing in simple words"))
        assertFalse(SmartIntentClassifier.looksLikePhoneAction("How to make South Indian filter coffee?"))

        assertTrue(SmartIntentClassifier.looksLikePhoneAction("Open Zomato and search biryani"))
        assertTrue(SmartIntentClassifier.looksLikePhoneAction("Call Mom"))
        assertTrue(SmartIntentClassifier.looksLikePhoneAction("Set an alarm for 6:30 AM"))
        assertTrue(SmartIntentClassifier.looksLikePhoneAction("Send a WhatsApp message to Ravi"))
        assertTrue(SmartIntentClassifier.looksLikePhoneAction("Turn on WiFi"))
    }

    @Test
    fun `smart memory suggestion detector extracts preferences and tasks while ignoring secrets`() {
        val pref = SmartMemorySuggestionDetector.detect("I prefer dark roast coffee without sugar", "m1")
        assertNotNull(pref)
        assertEquals(SuggestionKind.MEMORY, pref?.kind)

        val task = SmartMemorySuggestionDetector.detect("I need to submit the project report tomorrow", "m2")
        assertNotNull(task)
        assertEquals(SuggestionKind.TASK, task?.kind)

        // Secrets must never trigger a memory suggestion
        assertNull(SmartMemorySuggestionDetector.detect("My bank password is secret123", "m3"))
        // Explicit SQL commands already auto-save so no duplicate pill needed
        assertNull(SmartMemorySuggestionDetector.detect("Remember that my birthday is June 5", "m4"))
    }

    @Test
    fun `voice language defaults to Indian English and toggles cleanly with Telugu`() {
        assertEquals(VoiceLanguage.EN_IN, VoiceSettings().voiceLanguage)
        assertEquals("en-IN", VoiceLanguage.EN_IN.bcp47Tag)
        assertEquals(VoiceLanguage.TE_IN, VoiceLanguage.EN_IN.next())
        assertEquals(VoiceLanguage.EN_IN, VoiceLanguage.TE_IN.next())
        assertTrue(BuiltInGeminiSettings().googleSearchEnabled)
        assertEquals(AgentMode.CHAT_ONLY, AgentUiState().agentMode)
    }

    @Test
    fun `unified smart router sends normal questions to direct chat and phone commands to tools without mode switch`() {
        // Conversational greetings, study help, and explanations -> DIRECT_CHAT (never tap screen)
        val chatPrompts = listOf(
            "Hi, how are you?",
            "What is the capital of Japan?",
            "Explain photosynthesis for my exam",
            "Write a short email requesting leave tomorrow",
            "Why is the sky blue?",
            "Compare Python and Kotlin in a table"
        )
        chatPrompts.forEach { prompt ->
            assertEquals(
                "Expected DIRECT_CHAT for: $prompt",
                UnifiedRouteDecision.DIRECT_CHAT,
                SmartIntentClassifier.decideUnifiedRoute(prompt)
            )
        }

        // Phone control requests -> LOCAL_TOOL or AI_TOOL_ROUTER without needing any mode pill
        val phoneCommands = listOf(
            "Open YouTube and play lo-fi beats",
            "Open Zomato and search biryani",
            "Set an alarm for 6:30 AM",
            "Set a timer for 10 minutes",
            "Send a WhatsApp message to Ravi saying hi",
            "Scroll down and tap the first result",
            "Turn on WiFi",
            "Call Mom"
        )
        phoneCommands.forEach { cmd ->
            val route = SmartIntentClassifier.decideUnifiedRoute(cmd)
            assertTrue(
                "Expected tool route (not DIRECT_CHAT) for: $cmd, got $route",
                route == UnifiedRouteDecision.LOCAL_TOOL || route == UnifiedRouteDecision.AI_TOOL_ROUTER
            )
        }
    }

    @Test
    fun `gemini markdown parser renders bold headings lists code tables and links without raw markers`() {
        val sample = """
            ### Quick Summary
            Here is **Bold Text** and *Italic Text* plus `inline_code`.

            - First **important** item
            - Second *helpful* item

            1. Step one
            2. Step two

            ```kotlin
            val greeting = "Hello"
            ```

            | Metric | Value |
            | --- | --- |
            | Speed | Fast |

            Read more at [Android Docs](https://developer.android.com).
        """.trimIndent()

        val blocks = com.example.ui.GeminiMarkdownParser.parse(sample)
        assertTrue(
            blocks.any {
                it is com.example.ui.MarkdownBlock.Heading &&
                    it.level == 3 &&
                    com.example.ui.GeminiMarkdownParser.flattenInlines(it.inlines) == "Quick Summary"
            }
        )
        assertEquals(2, blocks.count { it is com.example.ui.MarkdownBlock.BulletItem })
        assertEquals(2, blocks.count { it is com.example.ui.MarkdownBlock.NumberedItem })
        assertTrue(
            blocks.any {
                it is com.example.ui.MarkdownBlock.CodeBlock &&
                    it.language == "kotlin" &&
                    it.code.contains("val greeting")
            }
        )
        assertTrue(
            blocks.any {
                it is com.example.ui.MarkdownBlock.Table &&
                    it.headers.map { cell -> com.example.ui.GeminiMarkdownParser.flattenInlines(cell) } == listOf("Metric", "Value") &&
                    it.rows.size == 1
            }
        )

        val renderedPlain = com.example.ui.GeminiMarkdownParser.extractPlainRenderedText(blocks)
        assertFalse("Raw bold stars leaked: $renderedPlain", renderedPlain.contains("**"))
        assertFalse("Raw single stars leaked: $renderedPlain", renderedPlain.contains("*"))
        assertFalse("Raw heading hashes leaked: $renderedPlain", renderedPlain.contains("###"))
        assertTrue(renderedPlain.contains("Quick Summary"))
        assertTrue(renderedPlain.contains("Bold Text"))
        assertTrue(renderedPlain.contains("Italic Text"))
        assertTrue(renderedPlain.contains("First important item"))
        assertTrue(renderedPlain.contains("Android Docs"))

        // Streaming partial markdown with unclosed ** must also never leak raw stars
        val streamingBlocks = com.example.ui.GeminiMarkdownParser.parse("Working on **important update")
        val streamingRendered = com.example.ui.GeminiMarkdownParser.extractPlainRenderedText(streamingBlocks)
        assertFalse(streamingRendered.contains("**"))
        assertTrue(streamingRendered.contains("important update"))

        // Safe web URL validation
        assertTrue(com.example.ui.GeminiMarkdownParser.isSafeWebUrl("https://developer.android.com"))
        assertFalse(com.example.ui.GeminiMarkdownParser.isSafeWebUrl("javascript:alert(1)"))
    }

    @Test
    fun `patient speech buffer accumulates paused phrases cleanly without overlap`() {
        val buffer = PatientSpeechBuffer()
        assertEquals("open youtube", buffer.updatePartial("open youtube"))
        assertEquals("open youtube", buffer.commitSegment("open youtube"))
        assertEquals("open youtube and play", buffer.updatePartial("and play"))
        assertEquals("open youtube and play lo fi music", buffer.commitSegment("and play lo fi music"))
        assertEquals("open youtube and play lo fi music", buffer.finalizeAll())
    }

    @Test
    fun `system prompt injects live date time factual rule and voice grace rule and gemini native payload enables google search`() {
        val prompt = SystemPromptBuilder.buildRichAssistantSystemPrompt(
            personalSqlContext = "Favorite coffee: filter coffee",
            voiceLanguage = VoiceLanguage.EN_IN,
            hasLiveWebSearch = true
        )
        assertTrue(prompt.contains("Current date:"))
        assertTrue(prompt.contains("Time:"))
        assertTrue(prompt.contains("Indian English"))
        assertTrue(prompt.contains("Never fabricate or guess facts"))
        assertTrue(prompt.contains("Favorite coffee: filter coffee"))

        val client = NetworkClient()
        val payloadWithSearch = client.buildGeminiPayloadMap(
            conversationHistory = listOf(ChatMessage(id = "1", sender = MessageSender.USER, text = "Latest news")),
            systemPrompt = prompt,
            enableGoogleSearch = true
        )
        assertTrue(payloadWithSearch.containsKey("tools"))

        val fakeGeminiResponse = """
            {
              "candidates": [{
                "content": {"parts": [{"text": "Here is the update."}]},
                "groundingMetadata": {
                  "groundingChunks": [
                    {"web": {"title": "BBC News", "uri": "https://www.bbc.com/news"}}
                  ]
                }
              }]
            }
        """.trimIndent()
        val sources = client.parseGeminiGroundingSources(fakeGeminiResponse)
        assertEquals(1, sources.size)
        assertEquals("BBC News", sources[0].title)
        assertEquals("https://www.bbc.com/news", sources[0].uri)
    }

    @Test
    fun `styled xlsx generator creates valid multi-sheet workbook zip with formulas and accounting styles`() {
        val blueprint = com.example.files.FileBlueprint(
            fileType = com.example.files.GeneratedFileFormat.XLSX,
            fileName = "monthly_expense_tracker.xlsx",
            title = "Monthly Expense Tracker (INR)",
            subtitle = "Personal Budget & Actual Spend Dashboard",
            categoryTag = "PERSONAL FINANCE",
            accentColorHex = "#0F766E",
            executiveSummary = "Tracks monthly household spend across key categories with dynamic Excel formulas.",
            sheets = listOf(
                com.example.files.BlueprintSheet(
                    name = "Summary",
                    title = "Monthly Budget Summary",
                    subtitle = "Linked dynamically to Details tab",
                    columns = listOf(
                        com.example.files.BlueprintColumn("Category", "text"),
                        com.example.files.BlueprintColumn("Budget", "currency"),
                        com.example.files.BlueprintColumn("Actual", "currency"),
                        com.example.files.BlueprintColumn("Share", "percent")
                    ),
                    rows = listOf(
                        listOf("Housing & Utilities", "22000", "=SUM(Details!C5:C6)", "=C5/\$C\$7"),
                        listOf("Food & Groceries", "12000", "=SUM(Details!C7:C8)", "=C6/\$C\$7")
                    ),
                    totalRow = listOf("Grand Total", "=SUM(B5:B6)", "=SUM(C5:C6)", "=SUM(D5:D6)")
                ),
                com.example.files.BlueprintSheet(
                    name = "Details",
                    title = "Expense Line Items",
                    subtitle = "Itemized monthly transactions",
                    columns = listOf(
                        com.example.files.BlueprintColumn("Date", "date"),
                        com.example.files.BlueprintColumn("Description", "text"),
                        com.example.files.BlueprintColumn("Amount", "currency")
                    ),
                    rows = listOf(
                        listOf("01-Nov-2026", "Apartment Rent", "18500"),
                        listOf("03-Nov-2026", "Electricity & Broadband", "3200"),
                        listOf("05-Nov-2026", "Supermarket Groceries", "6400"),
                        listOf("10-Nov-2026", "Fresh Vegetables & Dairy", "4100")
                    ),
                    totalRow = listOf("Total", "", "=SUM(C5:C8)")
                )
            )
        )

        val zipBytes = com.example.files.StyledXlsxGenerator.generateBytes(blueprint)
        assertTrue("Generated XLSX must be non-empty", zipBytes.size > 1000)

        val entries = mutableMapOf<String, String>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entries[entry.name] = zis.readBytes().toString(Charsets.UTF_8)
                entry = zis.nextEntry
            }
        }

        assertTrue("Missing [Content_Types].xml", entries.containsKey("[Content_Types].xml"))
        assertTrue("Missing xl/workbook.xml", entries.containsKey("xl/workbook.xml"))
        assertTrue("Missing xl/styles.xml", entries.containsKey("xl/styles.xml"))
        assertTrue("Missing xl/worksheets/sheet1.xml", entries.containsKey("xl/worksheets/sheet1.xml"))
        assertTrue("Missing xl/worksheets/sheet2.xml", entries.containsKey("xl/worksheets/sheet2.xml"))

        val workbookXml = entries.getValue("xl/workbook.xml")
        assertTrue(workbookXml.contains("""name="Summary""""))
        assertTrue(workbookXml.contains("""name="Details""""))

        val stylesXml = entries.getValue("xl/styles.xml")
        assertTrue("Expected ₹ currency format", stylesXml.contains("₹"))
        assertTrue("Expected accounting double bottom border", stylesXml.contains("""<bottom style="double">"""))
        assertTrue("Expected dark slate header fill", stylesXml.contains("FF1E293B"))

        val sheet1Xml = entries.getValue("xl/worksheets/sheet1.xml")
        assertTrue("Expected frozen header pane", sheet1Xml.contains("""ySplit="4""""))
        assertTrue("Expected cross-sheet <f> formula", sheet1Xml.contains("<f>SUM(Details!C5:C6)</f>"))
        assertTrue("Expected total <f> formula", sheet1Xml.contains("<f>SUM(B5:B6)</f>"))
    }

    @Test
    fun `styled docx generator creates valid word zip with headings callouts and bordered tables`() {
        val blueprint = com.example.files.FileBlueprint(
            fileType = com.example.files.GeneratedFileFormat.DOCX,
            fileName = "quarterly_project_report.docx",
            title = "Q4 Engineering Delivery Report",
            subtitle = "Mobile Platform Modernization & Performance",
            categoryTag = "PROJECT REPORT",
            accentColorHex = "#1E3A8A",
            executiveSummary = "All core reliability targets were achieved ahead of schedule with 38% faster startup.",
            kpiCards = listOf(
                com.example.files.BlueprintKpiCard("Crash-Free Rate", "99.94%", "+0.4% QoQ"),
                com.example.files.BlueprintKpiCard("Cold Start", "420 ms", "38% faster")
            ),
            sections = listOf(
                com.example.files.BlueprintSection(
                    heading = "Key Milestones Delivered",
                    paragraphs = listOf("The engineering team completed the migration with zero customer downtime."),
                    bullets = listOf("**Architecture Upgrade:** Migrated core rendering pipeline to lazy Compose layouts."),
                    callout = com.example.files.BlueprintCallout(
                        title = "Executive Takeaway",
                        text = "Prioritize battery telemetry validation before Q1 expansion.",
                        tone = "tip"
                    ),
                    table = com.example.files.BlueprintTable(
                        headers = listOf("Milestone", "Owner", "Status"),
                        columnWeights = listOf(2.4f, 1.2f, 1.0f),
                        alignments = listOf("left", "left", "center"),
                        rows = listOf(
                            listOf("Voice Latency Optimization", "Audio Team", "Completed"),
                            listOf("Offline Document Export", "Core Team", "Completed")
                        ),
                        totalRow = listOf("Total Completed", "2 / 2", "100%")
                    )
                )
            )
        )

        val zipBytes = com.example.files.StyledDocxGenerator.generateBytes(blueprint)
        assertTrue("Generated DOCX must be non-empty", zipBytes.size > 1000)

        val entries = mutableMapOf<String, String>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entries[entry.name] = zis.readBytes().toString(Charsets.UTF_8)
                entry = zis.nextEntry
            }
        }

        assertTrue(entries.containsKey("[Content_Types].xml"))
        assertTrue(entries.containsKey("word/document.xml"))
        assertTrue(entries.containsKey("word/styles.xml"))

        val docXml = entries.getValue("word/document.xml")
        assertTrue("Expected Heading1 style", docXml.contains("""w:val="Heading1""""))
        assertTrue("Expected styled table header", docXml.contains("<w:tblHeader/>"))
        assertTrue("Expected dark header shading", docXml.contains("""w:fill="1E293B""""))
        assertTrue("Expected document content", docXml.contains("Q4 Engineering Delivery Report"))
        assertTrue("Expected callout box content", docXml.contains("Executive Takeaway"))
    }

    @Test
    fun `file creation requests route deterministically to generate_file with accurate format`() {
        val excelRoute = HybridToolRouting.matchDirectLocalTool("create an Excel monthly expense tracker")
        assertNotNull(excelRoute)
        assertEquals(ToolName.GENERATE_FILE, excelRoute?.tool)
        assertEquals("xlsx", excelRoute?.label)

        val pdfRoute = HybridToolRouting.matchDirectLocalTool("make a PDF study plan for GATE CSE")
        assertNotNull(pdfRoute)
        assertEquals(ToolName.GENERATE_FILE, pdfRoute?.tool)
        assertEquals("pdf", pdfRoute?.label)

        val docxRoute = HybridToolRouting.matchDirectLocalTool("generate a Word report on renewable energy in India")
        assertNotNull(docxRoute)
        assertEquals(ToolName.GENERATE_FILE, docxRoute?.tool)
        assertEquals("docx", docxRoute?.label)

        val csvRoute = HybridToolRouting.matchDirectLocalTool("export a CSV of top 10 Indian tech stocks")
        assertNotNull(csvRoute)
        assertEquals(ToolName.GENERATE_FILE, csvRoute?.tool)
        assertEquals("csv", csvRoute?.label)

        // Informational questions about file types must NOT trigger file generation
        assertNull(com.example.files.FileRequestDetector.detect("What is a PDF file?"))
        assertNull(com.example.files.FileRequestDetector.detect("How does Excel work?"))

        // CSV RFC-4180 escaping preserves commas and quotes
        assertEquals("\"Rent, Hyderabad\"", com.example.files.CsvGenerator.escapeRfc4180("Rent, Hyderabad"))
        assertEquals("\"He said \"\"OK\"\"\"", com.example.files.CsvGenerator.escapeRfc4180("He said \"OK\""))
    }

    @Test
    fun `follow-up play the first video when YouTube is foreground keeps active session and never calls open_app YouTube`() {
        ConversationRepository.clearForTest("session-yt-1")
        ConversationRepository.appendMessagesInMemory(
            listOf(
                ChatMessage(id = "u1", sender = MessageSender.USER, text = "Open YouTube"),
                ChatMessage(id = "a1", sender = MessageSender.AGENT, text = "Yes, completed! Opened YouTube.")
            )
        )
        ConversationRepository.recordActionLog("Opened YouTube", "Completed")

        val followUp = "play the first video"
        val fgApp = "com.google.android.youtube"

        // Append follow-up into the exact same singleton session (simulating VoiceOverlayActivity / Quick Settings tile)
        ConversationRepository.appendMessagesInMemory(
            listOf(ChatMessage(id = "u2", sender = MessageSender.USER, text = followUp))
        )
        assertEquals("session-yt-1", ConversationRepository.activeSessionId.value)
        assertEquals(3, ConversationRepository.messages.value.size)

        // Direct local routing acts on current screen and NEVER calls open_app("YouTube")
        val localRoute = HybridToolRouting.matchDirectLocalTool(followUp, fgApp)
        assertNotNull(localRoute)
        assertEquals(ToolName.UI_AUTOMATE, localRoute?.tool)
        assertFalse(localRoute?.tool == ToolName.OPEN_APP)
        assertTrue(localRoute?.goal?.contains("ALREADY open", ignoreCase = true) == true)

        // Even if an AI router mistakenly returned open_app("YouTube"), the foreground guard overrides it
        val mistakenOpenApp = RoutedToolCall(tool = ToolName.OPEN_APP, appName = "YouTube")
        val guarded = HybridToolRouting.applyForegroundContextGuard(mistakenOpenApp, followUp, fgApp)
        assertEquals(ToolName.UI_AUTOMATE, guarded.tool)
        assertFalse(guarded.tool == ToolName.OPEN_APP)

        // Router prompts always include live currentForegroundApp, recent conversation, action logs, and anti-relaunch rule
        val systemPrompt = HybridToolRouting.buildRouterSystemPrompt(
            installedAppSample = listOf("YouTube", "Uber"),
            safetyRules = SafetyRules()
        )
        assertTrue(systemPrompt.contains("If `currentForegroundApp` is ALREADY the app the user is referring to"))
        assertTrue(systemPrompt.contains("DO NOT relaunch the app from scratch"))

        val userPrompt = HybridToolRouting.buildRouterUserPrompt(
            userGoal = followUp,
            currentForegroundApp = fgApp,
            conversationHistory = ConversationRepository.messages.value
        )
        assertTrue(userPrompt.contains("currentForegroundApp: com.google.android.youtube"))
        assertTrue(userPrompt.contains("Open YouTube"))
        assertTrue(userPrompt.contains("Opened YouTube"))
        assertTrue(userPrompt.contains("CURRENT USER REQUEST: play the first video"))
    }

    @Test
    fun `book a ride in Uber to airport is allowed by safety guardrail and routes to book_ride with safe final check`() {
        // Ride booking prompts must NOT be blocked by financial guardrail
        assertNull(SafetyGuardrail.detectSensitiveViolation("Book a ride in Uber to airport"))
        assertNull(SafetyGuardrail.detectSensitiveViolation("Open Uber and book a cab to railway station"))
        assertNull(SafetyGuardrail.detectSensitiveViolation("Book an Ola auto to Hitech City"))
        assertNull(SafetyGuardrail.detectSensitiveViolation("Book a Rapido bike to Kondapur"))

        // Real financial/UPI/OTP/password requests MUST remain hard-blocked
        assertNotNull(SafetyGuardrail.detectSensitiveViolation("Pay 500 via UPI PIN 1234"))
        assertNotNull(SafetyGuardrail.detectSensitiveViolation("Enter my OTP 482910"))
        assertNotNull(SafetyGuardrail.detectSensitiveViolation("Transfer money from HDFC bank transfer"))
        assertNotNull(SafetyGuardrail.detectSensitiveViolation("Type my account password"))

        // Routing checks for Uber, default Uber, Ola, and Rapido
        val uberRoute = HybridToolRouting.matchDirectLocalTool("Book a ride in Uber to airport")
        assertNotNull(uberRoute)
        assertEquals(ToolName.BOOK_RIDE, uberRoute?.tool)
        assertEquals("Uber", uberRoute?.appName)
        assertEquals("airport", uberRoute?.destination)
        assertEquals("com.ubercab", HybridToolRouting.ridePackageForApp(uberRoute?.appName))

        val defaultUberRoute = HybridToolRouting.matchDirectLocalTool("book a cab to airport")
        assertNotNull(defaultUberRoute)
        assertEquals(ToolName.BOOK_RIDE, defaultUberRoute?.tool)
        assertEquals("Uber", defaultUberRoute?.appName)
        assertEquals("airport", defaultUberRoute?.destination)

        val olaRoute = HybridToolRouting.matchDirectLocalTool("Book an Ola to Gachibowli")
        assertNotNull(olaRoute)
        assertEquals(ToolName.BOOK_RIDE, olaRoute?.tool)
        assertEquals("Ola", olaRoute?.appName)
        assertEquals("Gachibowli", olaRoute?.destination)
        assertEquals("com.olacabs.customer", HybridToolRouting.ridePackageForApp(olaRoute?.appName))

        val rapidoRoute = HybridToolRouting.matchDirectLocalTool("open Rapido and book a bike to Madhapur")
        assertNotNull(rapidoRoute)
        assertEquals(ToolName.BOOK_RIDE, rapidoRoute?.tool)
        assertEquals("Rapido", rapidoRoute?.appName)
        assertEquals("Madhapur", rapidoRoute?.destination)
        assertEquals("com.rapido.passenger", HybridToolRouting.ridePackageForApp(rapidoRoute?.appName))

        // Unified route decision must send ride booking directly to LOCAL_TOOL (not DIRECT_CHAT)
        assertEquals(
            UnifiedRouteDecision.LOCAL_TOOL,
            SmartIntentClassifier.decideUnifiedRoute("Book a ride in Uber to airport")
        )

        // Safe final check: Confirm / Book / Pay buttons are detected and blocked from auto-tapping
        assertTrue(HybridToolRouting.isRideConfirmButton("Confirm Uber Go"))
        assertTrue(HybridToolRouting.isRideConfirmButton("Book Now"))
        assertTrue(HybridToolRouting.isRideConfirmButton("Confirm"))
        assertTrue(HybridToolRouting.isRideConfirmButton("Pay Now"))
        assertFalse(HybridToolRouting.isRideConfirmButton("Where to?"))
        assertEquals(
            "I've set your destination in Uber. Please review the fare and tap Confirm to book.",
            HybridToolRouting.buildRideReadyMessage("Uber")
        )
    }

    @Test
    fun `strict 3-state verification prevents false Yes completed on PartialSuccess or Failed and validates ui_automate done`() {
        // Failed never contains "Yes, completed"
        val failedMsg = ToolExecutionResult.Failed("App did not open on screen").toUserMessage("2.5s")
        assertTrue(failedMsg.startsWith("I couldn't complete that"))
        assertFalse(failedMsg.contains("Yes, completed", ignoreCase = true))

        // Even if raw error text accidentally includes "Yes, completed", formatter strips it
        val sanitizedFail = ToolExecutionResult.Failed("Yes, completed! actually failed").toUserMessage()
        assertFalse(sanitizedFail.contains("Yes, completed", ignoreCase = true))
        assertTrue(sanitizedFail.startsWith("I couldn't complete that"))

        // PartialSuccess never contains "Yes, completed"
        val partialYt = ToolExecutionResult.PartialSuccess(
            whatWorked = "opened YouTube search",
            whatFailed = "tap the video to play it"
        ).toUserMessage("3.1s")
        assertEquals("I opened YouTube search, but I couldn't tap the video to play it. (3.1s)", partialYt)
        assertFalse(partialYt.contains("Yes, completed", ignoreCase = true))

        val partialMsg = ToolExecutionResult.PartialSuccess(
            whatWorked = "typed the message",
            whatFailed = "tap the Send button"
        ).toUserMessage()
        assertFalse(partialMsg.contains("Yes, completed", ignoreCase = true))

        // Accessibility disabled message is preserved verbatim without "Yes, completed"
        val accOffMsg = ToolExecutionResult.Failed(AgentReliabilityConstants.ACCESSIBILITY_OFF_MESSAGE).toUserMessage()
        assertEquals(AgentReliabilityConstants.ACCESSIBILITY_OFF_MESSAGE, accOffMsg)
        assertFalse(accOffMsg.contains("Yes, completed", ignoreCase = true))

        // VerifiedSuccess formats with "Yes, completed!"
        val verifiedMsg = ToolExecutionResult.VerifiedSuccess("Opened YouTube").toUserMessage("0.8s")
        assertEquals("Yes, completed! Opened YouTube. (0.8s)", verifiedMsg)

        // ui_automate done validation: reject Step 1 with no real action
        val step1Reject = UiAutomationVerification.validateDoneAction(
            stepNumber = 1,
            maxSteps = 10,
            anyRealActionPerformed = false,
            anyScreenChanged = false,
            lastActionChangedScreen = false,
            evidence = "Home",
            visibleScreenText = "Home"
        )
        assertTrue(step1Reject is DoneValidationResult.Rejected)

        // Reject done when zero screen changes occurred across the whole run
        val zeroChangeReject = UiAutomationVerification.validateDoneAction(
            stepNumber = 3,
            maxSteps = 10,
            anyRealActionPerformed = true,
            anyScreenChanged = false,
            lastActionChangedScreen = false,
            evidence = "Play",
            visibleScreenText = "Play"
        )
        assertTrue(zeroChangeReject is DoneValidationResult.Rejected)
        assertFalse((zeroChangeReject as DoneValidationResult.Rejected).shouldRetry)
        assertEquals("No action took effect on the screen", zeroChangeReject.reason)

        // Reject done when evidence is missing or not on screen
        val badEvidenceReject = UiAutomationVerification.validateDoneAction(
            stepNumber = 3,
            maxSteps = 10,
            anyRealActionPerformed = true,
            anyScreenChanged = true,
            lastActionChangedScreen = true,
            evidence = "Confirm Uber Go",
            visibleScreenText = "YouTube Home Subscriptions Shorts"
        )
        assertTrue(badEvidenceReject is DoneValidationResult.Rejected)

        // Accept done when real action occurred, screen changed, and exact visible evidence matches
        val accepted = UiAutomationVerification.validateDoneAction(
            stepNumber = 2,
            maxSteps = 10,
            anyRealActionPerformed = true,
            anyScreenChanged = true,
            lastActionChangedScreen = true,
            evidence = "Pause video",
            visibleScreenText = "Now playing Lo-Fi Beats Pause video Subscribe Like Share"
        )
        assertTrue(accepted is DoneValidationResult.Accepted)
        assertEquals("Pause video", (accepted as DoneValidationResult.Accepted).evidence)
    }
}

