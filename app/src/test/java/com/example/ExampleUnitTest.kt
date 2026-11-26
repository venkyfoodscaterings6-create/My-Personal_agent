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
        assertTrue(parsedCall?.alwaysRequiresApproval == true)
        assertFalse(parsedCall?.isLowRiskAutoRunnableMediaOrAlarm == true)

        val msgJson = """{"tool":"draft_message","args":{"contact":"Alex","messagingApp":"whatsapp","messageText":"On my way"}}"""
        val parsedMsg = HybridToolRouting.parseRoutedToolJson(msgJson, fallbackGoal = "whatsapp Alex")
        assertNotNull(parsedMsg)
        assertEquals(ToolName.SEND_MESSAGE, parsedMsg?.tool)
        assertEquals("Alex", parsedMsg?.contactOrNumber)
        assertEquals("whatsapp", parsedMsg?.messagingApp)
        assertEquals("On my way", parsedMsg?.messageText)
        assertTrue(parsedMsg?.alwaysRequiresApproval == true)
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
        assertTrue(waDirect?.alwaysRequiresApproval == true)

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
        assertTrue(smsDirect?.alwaysRequiresApproval == true)

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
}
