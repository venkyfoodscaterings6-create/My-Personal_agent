package com.example

import androidx.compose.runtime.Immutable
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.json.JSONObject
import java.util.UUID
import kotlin.math.min

enum class AppDestination(val route: String, val label: String) {
    CHAT("chat", "Chat"),
    LIVE_VOICE("live_voice", "Live Voice & 3D"),
    MEMORY_DB("memory_db", "Memory & SQL"),
    APPROVALS("approvals", "Approvals"),
    SETUP("setup", "Setup & Test"),
    SETTINGS("settings", "Settings / API Keys")
}

enum class LiveVoicePhase {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING
}

enum class GeminiBuiltInModel(
    val modelId: String,
    val displayName: String,
    val subtitle: String
) {
    FLASH(
        modelId = "gemini-3.8-flash",
        displayName = "Gemini 3.8 Flash",
        subtitle = "General + Google Search Grounding (Default)"
    ),
    HYDROGEN(
        modelId = "gemini-4-hydrogen",
        displayName = "Gemini 4 Hydrogen",
        subtitle = "Fast Gemini 4 workhorse & agentic workflows"
    ),
    FLASH_LITE(
        modelId = "gemini-3.5-flash-lite",
        displayName = "Gemini 3.5 Flash-Lite",
        subtitle = "Ultra-fast responses & instant routing"
    ),
    ARGON(
        modelId = "gemini-4-argon",
        displayName = "Gemini 4 Argon",
        subtitle = "Deep reasoning & complex planning"
    )
}

@Immutable
data class BuiltInGeminiSettings(
    val useBuiltInGemini: Boolean = true,
    val selectedModel: GeminiBuiltInModel = GeminiBuiltInModel.FLASH,
    val googleSearchEnabled: Boolean = true,
    val injectSqlMemory: Boolean = true
)

@Immutable
@JsonClass(generateAdapter = true)
data class GroundingSource(
    val title: String,
    val uri: String
)

enum class SettingsFocusSection {
    PROVIDERS
}

enum class AgentMode {
    SMART_AUTO,
    CHAT_ONLY,
    AGENT_CONTROL
}

enum class ThemePreference(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark")
}

enum class VoiceLanguage(
    val bcp47Tag: String,
    val shortChipLabel: String,
    val displayName: String
) {
    EN_IN("en-IN", "EN", "English (India)"),
    TE_IN("te-IN", "తెలుగు", "Telugu (తెలుగు)");

    fun next(): VoiceLanguage = when (this) {
        EN_IN -> TE_IN
        TE_IN -> EN_IN
    }
}

enum class GeminiVoicePersona(
    val apiVoiceName: String,
    val displayName: String,
    val styleHint: String
) {
    KORE("Kore", "Kore", "Warm & clear"),
    PUCK("Puck", "Puck", "Upbeat & lively"),
    AOEDE("Aoede", "Aoede", "Calm & smooth"),
    FENRIR("Fenrir", "Fenrir", "Deep & steady")
}

enum class WakeSensitivity(
    val displayName: String,
    val noiseMultiplier: Double,
    val minRmsFloor: Double
) {
    QUIET("Quiet", 2.1, 650.0),
    BALANCED("Balanced", 2.8, 1050.0),
    NOISY("Noisy", 3.6, 1650.0)
}

enum class ApiFormat(
    val displayName: String,
    val wireName: String,
    val subtitle: String
) {
    OPENAI_COMPATIBLE(
        displayName = "OpenAI-Compatible (Standard Base URL)",
        wireName = "openai_compatible",
        subtitle = "POST /chat/completions with Authorization: Bearer"
    ),
    ANTHROPIC_NATIVE(
        displayName = "Anthropic Native",
        wireName = "anthropic_native",
        subtitle = "POST /messages with x-api-key & anthropic-version"
    ),
    GEMINI_NATIVE(
        displayName = "Gemini Native",
        wireName = "gemini_native",
        subtitle = "POST /models/{model}:generateContent"
    )
}

@Immutable
data class ProviderPreset(
    val label: String,
    val baseUrl: String,
    val apiFormat: ApiFormat,
    val modelPlaceholder: String
)

object ProviderPresets {
    val all: List<ProviderPreset> = listOf(
        ProviderPreset(
            label = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            apiFormat = ApiFormat.OPENAI_COMPATIBLE,
            modelPlaceholder = "openai/gpt-4o-mini"
        ),
        ProviderPreset(
            label = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            apiFormat = ApiFormat.OPENAI_COMPATIBLE,
            modelPlaceholder = "deepseek-chat"
        ),
        ProviderPreset(
            label = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            apiFormat = ApiFormat.OPENAI_COMPATIBLE,
            modelPlaceholder = "gpt-4o-mini"
        ),
        ProviderPreset(
            label = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            apiFormat = ApiFormat.OPENAI_COMPATIBLE,
            modelPlaceholder = "llama-3.3-70b-versatile"
        ),
        ProviderPreset(
            label = "Claude",
            baseUrl = "https://api.anthropic.com/v1",
            apiFormat = ApiFormat.ANTHROPIC_NATIVE,
            modelPlaceholder = "claude-sonnet-4-20250514"
        ),
        ProviderPreset(
            label = "Gemini",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            apiFormat = ApiFormat.GEMINI_NATIVE,
            modelPlaceholder = "gemini-2.5-flash"
        )
    )
}

@Immutable
@JsonClass(generateAdapter = true)
data class AiProvider(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val baseUrl: String,
    val apiKey: String = "",
    val modelName: String,
    val apiFormat: ApiFormat = ApiFormat.OPENAI_COMPATIBLE,
    val isDefault: Boolean = false
)

@Immutable
@JsonClass(generateAdapter = true)
data class SafetyRules(
    val alwaysAskApproval: Boolean = false,
    val blockSensitiveFinancial: Boolean = true,
    val showStepPlan: Boolean = true,
    val instantRunLowRiskAppLaunch: Boolean = true,
    val autoRunLowAndMediumRisk: Boolean = true
)

@Immutable
data class VoiceSettings(
    val autoSendVoiceCommands: Boolean = true,
    val speakRepliesAloud: Boolean = true,
    val voiceLanguage: VoiceLanguage = VoiceLanguage.EN_IN,
    val persistentAskNotificationEnabled: Boolean = true,
    val wakeWordEnabled: Boolean = false,
    val wakePhrase: String = DEFAULT_WAKE_PHRASE,
    val useGeminiNeuralVoice: Boolean = true,
    val voicePersona: GeminiVoicePersona = GeminiVoicePersona.KORE,
    val speechRate: Float = 1.0f,
    val wakeSensitivity: WakeSensitivity = WakeSensitivity.BALANCED
) {
    companion object {
        const val DEFAULT_WAKE_PHRASE = "Hey Agent"
        val PRESET_WAKE_PHRASES = listOf("Hey Agent", "OK Agent", "Hey Buddy")
    }
}

enum class SuggestionKind {
    MEMORY,
    TASK
}

@Immutable
data class MemorySuggestion(
    val id: String = UUID.randomUUID().toString(),
    val anchorMessageId: String,
    val kind: SuggestionKind,
    val category: String,
    val title: String,
    val content: String
)

@Immutable
data class PermissionsStatus(
    val microphoneGranted: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val overlayGranted: Boolean = false,
    val contactsGranted: Boolean = false,
    val callPhoneGranted: Boolean = false,
    val notificationsGranted: Boolean = false
) {
    val grantedCount: Int
        get() = listOf(
            microphoneGranted,
            accessibilityEnabled,
            overlayGranted,
            contactsGranted,
            callPhoneGranted,
            notificationsGranted
        ).count { it }
}

@Immutable
data class InstalledAppInfo(
    val displayName: String,
    val packageName: String,
    val normalizedName: String
)

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH;

    companion object {
        fun fromRaw(raw: String?): RiskLevel {
            return when (raw?.trim()?.uppercase()) {
                "HIGH" -> HIGH
                "MEDIUM", "MODERATE" -> MEDIUM
                else -> LOW
            }
        }
    }
}

enum class ApprovalStatus(val label: String) {
    PENDING("Pending"),
    APPROVED("Approved"),
    RUNNING("Running"),
    COMPLETED("Completed"),
    CANCELLED("Cancelled"),
    FAILED("Failed"),
    BLOCKED("Blocked")
}

@Immutable
@JsonClass(generateAdapter = true)
data class ActionPlan(
    val actionId: String = UUID.randomUUID().toString(),
    val title: String,
    val riskLevel: RiskLevel = RiskLevel.LOW,
    val steps: List<String> = emptyList(),
    val editedInstruction: String? = null,
    val isDemo: Boolean = false,
    val fastPathType: String? = null,
    val fastPathArg: String? = null,
    val routedToolJson: String? = null
)

@Immutable
data class OnDeviceAgentAction(
    val action: String,
    val appName: String? = null,
    val index: Int? = null,
    val x: Int? = null,
    val y: Int? = null,
    val text: String? = null,
    val submit: Boolean = false,
    val direction: String? = null,
    val summary: String? = null,
    val evidence: String? = null,
    val reason: String = ""
)

sealed class ToolExecutionResult {
    data class VerifiedSuccess(
        val summary: String,
        val customUserMessage: String? = null
    ) : ToolExecutionResult()

    data class PartialSuccess(
        val whatWorked: String,
        val whatFailed: String
    ) : ToolExecutionResult()

    data class Failed(
        val reason: String
    ) : ToolExecutionResult()

    val isVerifiedSuccess: Boolean
        get() = this is VerifiedSuccess

    fun toUserMessage(elapsed: String? = null): String {
        return ConfirmationFormatter.formatToolResult(this, elapsed)
    }
}

object AgentReliabilityConstants {
    const val ACCESSIBILITY_OFF_MESSAGE =
        "My Accessibility Service is turned off. Please enable it in Settings so I can control your phone."
}

object ToolName {
    const val REPLY_TO_USER = "reply_to_user"
    const val PLAY_YOUTUBE_VIDEO = "play_youtube_video"
    const val OPEN_APP = "open_app"
    const val SEARCH_YOUTUBE = "search_youtube"
    const val SEARCH_WEB = "search_web"
    const val OPEN_MAPS = "open_maps"
    const val SET_ALARM = "set_alarm"
    const val SET_TIMER = "set_timer"
    const val OPEN_SETTING = "open_setting"
    const val CALL_CONTACT = "call_contact"
    const val SEND_MESSAGE = "send_message"
    const val BOOK_RIDE = "book_ride"
    const val TAP_ON_SCREEN = "tap_on_screen"
    const val SCROLL_SCREEN = "scroll_screen"
    const val SCROLL = SCROLL_SCREEN
    const val PRESS_HOME = "press_home"
    const val GO_HOME = PRESS_HOME
    const val PRESS_BACK = "press_back"
    const val GO_BACK = PRESS_BACK
    const val OPEN_RECENTS = "open_recents"
    const val OPEN_NOTIFICATIONS = "open_notifications"
    const val OPEN_QUICK_SETTINGS = "open_quick_settings"
    const val SET_VOLUME = "set_volume"
    const val UI_AUTOMATE = "ui_automate"
    const val GENERATE_FILE = "generate_file"

    fun requiresAccessibility(tool: String): Boolean {
        return tool !in setOf(
            REPLY_TO_USER,
            GENERATE_FILE
        )
    }
}

@Immutable
@JsonClass(generateAdapter = true)
data class RoutedToolCall(
    val tool: String,
    val appName: String? = null,
    val query: String? = null,
    val destination: String? = null,
    val hour: Int? = null,
    val minute: Int? = null,
    val seconds: Int? = null,
    val label: String? = null,
    val settingName: String? = null,
    val contactOrNumber: String? = null,
    val messagingApp: String? = null,
    val messageText: String? = null,
    val direction: String? = null,
    val targetText: String? = null,
    val stream: String? = null,
    val levelPercent: Int? = null,
    val replyMessage: String? = null,
    val goal: String? = null,
    val title: String = "",
    val steps: List<String> = emptyList(),
    val riskLevel: RiskLevel = RiskLevel.LOW,
    val reason: String = ""
) {
    val isLowRiskAutoRunnableMediaOrAlarm: Boolean
        get() = tool in setOf(
            ToolName.PLAY_YOUTUBE_VIDEO,
            ToolName.SEARCH_YOUTUBE,
            ToolName.SEARCH_WEB,
            ToolName.OPEN_MAPS,
            ToolName.SET_ALARM,
            ToolName.SET_TIMER,
            ToolName.OPEN_SETTING,
            ToolName.TAP_ON_SCREEN,
            ToolName.SCROLL_SCREEN,
            ToolName.PRESS_BACK,
            ToolName.PRESS_HOME,
            ToolName.OPEN_RECENTS,
            ToolName.OPEN_NOTIFICATIONS,
            ToolName.OPEN_QUICK_SETTINGS,
            ToolName.SET_VOLUME
        )

    val alwaysRequiresApproval: Boolean
        get() = riskLevel == RiskLevel.HIGH

    fun shouldAutoRun(safetyRules: SafetyRules): Boolean {
        return when (riskLevel) {
            RiskLevel.HIGH -> false
            RiskLevel.LOW, RiskLevel.MEDIUM -> safetyRules.autoRunLowAndMediumRisk
        }
    }

    fun toJsonString(): String {
        val map = LinkedHashMap<String, Any>()
        map["tool"] = tool
        appName?.let { map["appName"] = it }
        query?.let { map["query"] = it }
        destination?.let { map["destination"] = it }
        hour?.let { map["hour"] = it }
        minute?.let { map["minute"] = it }
        seconds?.let { map["seconds"] = it }
        label?.let { map["label"] = it }
        settingName?.let { map["settingName"] = it }
        contactOrNumber?.let { map["contactOrNumber"] = it }
        messagingApp?.let { map["messagingApp"] = it }
        messageText?.let { map["messageText"] = it }
        direction?.let { map["direction"] = it }
        targetText?.let { map["targetText"] = it }
        stream?.let { map["stream"] = it }
        levelPercent?.let { map["levelPercent"] = it }
        replyMessage?.let { map["message"] = it }
        goal?.let { map["goal"] = it }
        if (title.isNotBlank()) map["title"] = title
        if (steps.isNotEmpty()) map["steps"] = steps
        if (reason.isNotBlank()) map["reason"] = reason
        map["riskLevel"] = riskLevel.name
        return HybridToolRouting.mapToJson(map)
    }

    companion object {
        fun fromJsonString(raw: String?): RoutedToolCall? {
            if (raw.isNullOrBlank()) return null
            return HybridToolRouting.parseRoutedToolJson(raw, fallbackGoal = "")
        }
    }
}

enum class MessageSender {
    USER,
    AGENT,
    SYSTEM
}

@Immutable
@JsonClass(generateAdapter = true)
data class GeneratedFileAttachment(
    val fileName: String,
    val fileType: String,
    val filePath: String,
    val sizeBytes: Long
)

@Immutable
@JsonClass(generateAdapter = true)
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val timestampMillis: Long = System.currentTimeMillis(),
    val actionPlan: ActionPlan? = null,
    val approvalStatus: ApprovalStatus? = null,
    val completedStepsCount: Int = 0,
    val activeStepIndex: Int = -1,
    val isError: Boolean = false,
    val isBlockedBySafety: Boolean = false,
    val blockedReason: String? = null,
    val excludeFromContext: Boolean = false,
    val durationLabel: String? = null,
    val groundingSources: List<GroundingSource> = emptyList(),
    val modelBadge: String? = null,
    val generatedFile: GeneratedFileAttachment? = null
)

@Immutable
@JsonClass(generateAdapter = true)
data class ApprovalRecord(
    val actionId: String,
    val title: String,
    val riskLevel: RiskLevel,
    val steps: List<String>,
    val status: ApprovalStatus,
    val timestampMillis: Long = System.currentTimeMillis(),
    val editedInstruction: String? = null,
    val notes: String? = null,
    val isDemo: Boolean = false
)

@Immutable
data class EndpointTestDetail(
    val label: String,
    val success: Boolean,
    val latencyMs: Long? = null,
    val httpStatus: Int? = null,
    val message: String
)

@Immutable
data class ConnectionTestSummary(
    val testedAtMillis: Long = System.currentTimeMillis(),
    val providerResult: EndpointTestDetail? = null
) {
    val allPassed: Boolean
        get() = providerResult?.success == true
}

@Immutable
data class SelfTestItem(
    val id: String,
    val title: String,
    val passed: Boolean,
    val detail: String
)

@Immutable
data class SelfTestReport(
    val ranAtMillis: Long = System.currentTimeMillis(),
    val items: List<SelfTestItem>
) {
    val passedCount: Int get() = items.count { it.passed }
    val totalCount: Int get() = items.size
}

object EndpointResolver {
    fun isValidHttpUrl(raw: String): Boolean {
        val cleaned = raw.trim()
        if (cleaned.isBlank()) return false
        val lower = cleaned.lowercase()
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) return false
        val hostPart = cleaned.substringAfter("://").substringBefore("/").trim()
        return hostPart.isNotBlank() && !hostPart.contains(" ")
    }

    private fun cleanBase(rawBaseUrl: String): String {
        return rawBaseUrl.trim().trimEnd('/')
    }

    private fun hasPathSegment(cleanedUrl: String): Boolean {
        val afterScheme = cleanedUrl.substringAfter("://", "")
        return afterScheme.contains("/")
    }

    fun resolveChatEndpoint(
        rawBaseUrl: String,
        apiFormat: ApiFormat,
        modelName: String = ""
    ): String {
        val cleaned = cleanBase(rawBaseUrl)
        if (cleaned.isBlank()) return ""

        return when (apiFormat) {
            ApiFormat.OPENAI_COMPATIBLE -> {
                when {
                    cleaned.endsWith("/chat/completions", ignoreCase = true) -> cleaned
                    hasPathSegment(cleaned) -> "$cleaned/chat/completions"
                    else -> "$cleaned/v1/chat/completions"
                }
            }

            ApiFormat.ANTHROPIC_NATIVE -> {
                when {
                    cleaned.endsWith("/messages", ignoreCase = true) -> cleaned
                    hasPathSegment(cleaned) -> "$cleaned/messages"
                    else -> "$cleaned/v1/messages"
                }
            }

            ApiFormat.GEMINI_NATIVE -> {
                val modelSegment = modelName.trim().removePrefix("models/").ifBlank { "<model>" }
                when {
                    cleaned.contains(":generateContent", ignoreCase = true) -> cleaned
                    hasPathSegment(cleaned) -> "$cleaned/models/$modelSegment:generateContent"
                    else -> "$cleaned/v1beta/models/$modelSegment:generateContent"
                }
            }
        }
    }

    fun resolveModelsEndpoint(
        rawBaseUrl: String,
        apiFormat: ApiFormat
    ): String {
        val cleaned = cleanBase(rawBaseUrl)
        if (cleaned.isBlank()) return ""

        return when (apiFormat) {
            ApiFormat.OPENAI_COMPATIBLE -> {
                when {
                    cleaned.endsWith("/chat/completions", ignoreCase = true) -> {
                        val prefix = cleaned.dropLast("/chat/completions".length)
                        "$prefix/models"
                    }
                    cleaned.endsWith("/models", ignoreCase = true) -> cleaned
                    hasPathSegment(cleaned) -> "$cleaned/models"
                    else -> "$cleaned/v1/models"
                }
            }

            ApiFormat.ANTHROPIC_NATIVE -> {
                when {
                    cleaned.endsWith("/messages", ignoreCase = true) -> {
                        val prefix = cleaned.dropLast("/messages".length)
                        "$prefix/models"
                    }
                    cleaned.endsWith("/models", ignoreCase = true) -> cleaned
                    hasPathSegment(cleaned) -> "$cleaned/models"
                    else -> "$cleaned/v1/models"
                }
            }

            ApiFormat.GEMINI_NATIVE -> {
                when {
                    cleaned.endsWith("/models", ignoreCase = true) -> cleaned
                    hasPathSegment(cleaned) -> "$cleaned/models"
                    else -> "$cleaned/v1beta/models"
                }
            }
        }
    }

    fun isOpenRouterUrl(rawBaseUrl: String): Boolean {
        return rawBaseUrl.lowercase().contains("openrouter.ai")
    }
}

object SafetyGuardrail {
    private data class RulePattern(
        val category: String,
        val regex: Regex
    )

    private val sensitivePatterns: List<RulePattern> = listOf(
        RulePattern(
            category = "UPI / Payment App Action",
            regex = Regex(
                """\b(upi|gpay|google\s*pay|phonepe|paytm|bhim|paypal|venmo|zelle|cash\s*app)\b""",
                RegexOption.IGNORE_CASE
            )
        ),
        RulePattern(
            category = "Banking / Money Transfer",
            regex = Regex(
                """\b(net\s*banking|bank\s*(?:transfer|account|balance|app|statement|login|otp|pin|password)|mobile\s+banking|internet\s+banking|neft|imps|rtgs|ifsc|wire\s+transfer|transfer\s+money|send\s+money|pay\s+bill|debit\s+card|credit\s+card|cvv|card\s+number)\b""",
                RegexOption.IGNORE_CASE
            )
        ),
        RulePattern(
            category = "OTP / Verification Code",
            regex = Regex(
                """\b(otp|one[\s-]*time\s+password|verification\s+code|2fa\s+code|mfa\s+code|auth\s+code)\b""",
                RegexOption.IGNORE_CASE
            )
        ),
        RulePattern(
            category = "Password / PIN / Secret Credential",
            regex = Regex(
                """\b(password(s)?|passcode(s)?|passphrase|upi\s+pin|atm\s+pin|bank\s+pin|card\s+pin|mpin|tpin|seed\s+phrase|recovery\s+phrase|private\s+key)\b""",
                RegexOption.IGNORE_CASE
            )
        )
    )

    private val sensitivePackageMarkers: List<String> = listOf(
        "phonepe",
        "nbu.paisa",
        "net.one97.paytm",
        "paytm",
        "npci.upi",
        "bhim",
        "paypal",
        "venmo",
        "squareup.cash",
        "mobikwik",
        "freecharge",
        "cred.club",
        "bharatpe",
        "yono",
        "imobile",
        "netbanking",
        "sbi.lotusintouch",
        "snapwork.hdfc",
        "hdfcbank",
        "icicibank",
        "axis.mobile",
        "kotak",
        "canarabank",
        "pnbone",
        "indusind",
        "yesbank",
        "idfcfirstbank",
        "rblbank",
        "federalbank",
        "chase.sig",
        "bankofamerica",
        "wf.wellsfargomobile",
        "citi.citimobile",
        "hsbc"
    )

    private val launcherOrSystemPackageMarkers: List<String> = listOf(
        "launcher",
        "trebuchet",
        "miui.home",
        "systemui",
        "android.settings"
    )

    private val sensitiveEditableFieldRegex = Regex(
        """\b(password|passcode|passphrase|upi\s*pin|enter\s*pin|mpin|tpin|atm\s*pin|cvv|card\s*number|enter\s*otp|verification\s*code)\b""",
        RegexOption.IGNORE_CASE
    )

    private val sensitiveCheckoutButtonRegex = Regex(
        """\b(enter\s*upi\s*pin|confirm\s*payment|authorize\s*payment|transfer\s*money|send\s*money|pay\s*now\s*via\s*upi)\b""",
        RegexOption.IGNORE_CASE
    )

    fun detectSensitiveViolation(vararg texts: String?): String? {
        val combined = texts.filterNotNull().joinToString("\n")
        if (combined.isBlank()) return null
        return sensitivePatterns.firstOrNull { it.regex.containsMatchIn(combined) }?.category
    }

    /**
     * Live-screen guardrail specifically designed to avoid false positives on Zomato, Swiggy,
     * Amazon, YouTube, WhatsApp, or the Android Home Screen (which often display "Bank offers",
     * "Zomato UPI" promo banners, or GPay/PhonePe home-screen icons).
     *
     * Blocks ONLY:
     * 1) Dedicated Banking / UPI / Payment apps (by packageName)
     * 2) Active password / PIN / OTP / card-number editable input fields
     * 3) Explicit payment-authorization buttons (e.g., "Enter UPI PIN", "Confirm Payment")
     */
    fun detectSensitiveScreen(
        packageName: String,
        ownPackageName: String,
        nodes: List<ScreenNode>
    ): String? {
        val pkg = packageName.trim().lowercase()
        if (pkg.isBlank() || pkg == ownPackageName.lowercase()) return null
        if (launcherOrSystemPackageMarkers.any { pkg.contains(it) }) return null

        if (sensitivePackageMarkers.any { pkg.contains(it) }) {
            return "UPI / Banking App ($packageName)"
        }

        for (node in nodes) {
            val label = buildString {
                if (node.text.isNotBlank()) append(node.text).append(' ')
                if (node.contentDescription.isNotBlank()) append(node.contentDescription).append(' ')
                if (node.viewIdResourceName.isNotBlank()) append(node.viewIdResourceName.substringAfterLast('/')).append(' ')
            }
            if (label.isBlank()) continue

            if (node.isEditable && sensitiveEditableFieldRegex.containsMatchIn(label)) {
                return "Password / PIN / OTP Input Field"
            }
            if (node.isClickable && sensitiveCheckoutButtonRegex.containsMatchIn(label)) {
                return "Payment Confirmation Action"
            }
        }
        return null
    }
}

object AppNameFuzzyMatcher {

    fun normalize(raw: String): String {
        return raw.lowercase().replace(Regex("[^a-z0-9]"), "")
    }

    fun aliasPackageHints(normalizedQuery: String): List<String> {
        return when (normalizedQuery) {
            "youtube", "yt" -> listOf("com.google.android.youtube")
            "whatsapp", "wa" -> listOf("com.whatsapp", "com.whatsapp.w4b")
            "chrome", "googlechrome" -> listOf("com.android.chrome")
            "gmail", "mail" -> listOf("com.google.android.gm")
            "maps", "googlemaps" -> listOf("com.google.android.apps.maps")
            "playstore", "googleplay" -> listOf("com.android.vending")
            "instagram", "ig", "insta" -> listOf("com.instagram.android")
            "spotify" -> listOf("com.spotify.music")
            "telegram", "tg" -> listOf("org.telegram.messenger")
            "zomato", "zomoto", "zomatto" -> listOf("com.application.zomato")
            "swiggy", "swigy" -> listOf("in.swiggy.android")
            "blinkit" -> listOf("com.grofers.customerapp")
            "zepto" -> listOf("com.zeptoconsumerapp")
            "uber" -> listOf("com.ubercab")
            "ola" -> listOf("com.olacabs.customer")
            "rapido" -> listOf("com.rapido.passenger")
            "amazon" -> listOf("in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping")
            "flipkart" -> listOf("com.flipkart.android")
            "netflix" -> listOf("com.netflix.mediaclient")
            "hotstar", "jiohotstar" -> listOf("in.startv.hotstar")
            "primevideo", "amazonprime" -> listOf("com.amazon.avod.thirdpartyclient")
            "settings" -> listOf("com.android.settings")
            "clock", "alarm" -> listOf("com.google.android.deskclock", "com.android.deskclock")
            "calendar" -> listOf("com.google.android.calendar", "com.android.calendar")
            "calculator" -> listOf("com.google.android.calculator", "com.android.calculator2")
            "camera" -> listOf("com.android.camera", "com.google.android.GoogleCamera", "com.android.camera2")
            "contacts" -> listOf("com.google.android.contacts", "com.android.contacts")
            "phone", "dialer" -> listOf("com.google.android.dialer", "com.android.dialer")
            "messages", "sms" -> listOf("com.google.android.apps.messaging", "com.android.mms")
            else -> emptyList()
        }
    }

    fun findBestMatch(query: String, installedApps: List<InstalledAppInfo>): InstalledAppInfo? {
        val cleaned = query.trim()
        if (cleaned.isBlank() || installedApps.isEmpty()) return null
        val normQuery = normalize(cleaned)
        if (normQuery.isBlank()) return null

        // 1. Exact display name match
        installedApps.firstOrNull { it.displayName.equals(cleaned, ignoreCase = true) }?.let { return it }

        // 2. Exact normalized match ("you tube" -> "youtube" matches "YouTube")
        installedApps.firstOrNull { it.normalizedName == normQuery }?.let { return it }

        // 3. Alias package match ("yt" -> com.google.android.youtube)
        val hints = aliasPackageHints(normQuery)
        if (hints.isNotEmpty()) {
            installedApps.firstOrNull { it.packageName in hints }?.let { return it }
        }

        // 4. Prefix or substring match
        installedApps.firstOrNull {
            it.normalizedName.startsWith(normQuery) || normQuery.startsWith(it.normalizedName)
        }?.let { return it }

        if (normQuery.length >= 3) {
            installedApps.firstOrNull {
                it.normalizedName.contains(normQuery) || normalize(it.packageName).contains(normQuery)
            }?.let { return it }
        }

        // 5. Typo-tolerant Levenshtein distance match (e.g., "youtbe" -> "YouTube")
        if (normQuery.length >= 4) {
            val maxDist = if (normQuery.length >= 7) 2 else 1
            var bestApp: InstalledAppInfo? = null
            var bestDist = Int.MAX_VALUE
            for (app in installedApps) {
                if (kotlin.math.abs(app.normalizedName.length - normQuery.length) > maxDist) continue
                val d = levenshtein(normQuery, app.normalizedName)
                if (d <= maxDist && d < bestDist) {
                    bestDist = d
                    bestApp = app
                }
            }
            if (bestApp != null) return bestApp
        }

        return null
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = min(
                    min(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost
                )
            }
            curr.copyInto(prev)
        }
        return prev[b.length]
    }
}

object FastPathType {
    const val OPEN_APP = "OPEN_APP"
    const val YOUTUBE_SEARCH = "YOUTUBE_SEARCH"
    const val GO_HOME = "GO_HOME"
    const val GO_BACK = "GO_BACK"
}

@Immutable
data class FastPathMatch(
    val type: String,
    val arg: String,
    val title: String,
    val steps: List<String>,
    val runningPhaseLabel: String,
    val isLowRiskAppLaunch: Boolean
)

object FastPathMatcher {
    private val youtubeSearchOnRegex = Regex(
        """^(?:please\s+|can\s+you\s+)?(?:search|find|look\s+up)(?:\s+for)?\s+(.+?)\s+(?:on|in)\s+you\s*tube[.!?]*$""",
        RegexOption.IGNORE_CASE
    )
    private val youtubeSearchForRegex = Regex(
        """^(?:please\s+|can\s+you\s+)?(?:search\s+you\s*tube\s+(?:for\s+)?|you\s*tube\s+search\s+(?:for\s+)?)(.+?)[.!?]*$""",
        RegexOption.IGNORE_CASE
    )
    private val goHomeRegex = Regex(
        """^(?:please\s+|can\s+you\s+)?(?:go\s+(?:to\s+)?)?(?:home|home\s+screen)(?:\s+now)?[.!?]*$""",
        RegexOption.IGNORE_CASE
    )
    private val goBackRegex = Regex(
        """^(?:please\s+|can\s+you\s+)?(?:go|press|navigate)\s+back[.!?]*$""",
        RegexOption.IGNORE_CASE
    )
    private val openAppRegex = Regex(
        """^(?:please\s+|can\s+you\s+)?(?:open|launch|start)\s+(?:the\s+)?(.+?)(?:\s+app)?[.!?]*$""",
        RegexOption.IGNORE_CASE
    )
    private val multiStepConnectorRegex = Regex(
        """(\band\b|\bthen\b|\bto\b|\bfor\b|\bon\b|\bsearch\b|\bsend\b|\bplay\b|\btype\b|\bmessage\b|\bcall\b|\bturn\b|\bclick\b|\btap\b|\bwatch\b|,|;)""",
        RegexOption.IGNORE_CASE
    )

    fun match(rawCommand: String): FastPathMatch? {
        val text = rawCommand.trim().replace(Regex("\\s+"), " ")
        if (text.isBlank()) return null

        val ytMatch = youtubeSearchOnRegex.matchEntire(text) ?: youtubeSearchForRegex.matchEntire(text)
        if (ytMatch != null) {
            val query = ytMatch.groupValues[1].trim().trim('"', '\'')
            if (query.isNotBlank()) {
                return FastPathMatch(
                    type = FastPathType.YOUTUBE_SEARCH,
                    arg = query,
                    title = "Search YouTube: ${query.take(40)}",
                    steps = listOf("Open YouTube search for \"$query\""),
                    runningPhaseLabel = "Searching YouTube…",
                    isLowRiskAppLaunch = true
                )
            }
        }

        if (goHomeRegex.matches(text)) {
            return FastPathMatch(
                type = FastPathType.GO_HOME,
                arg = "",
                title = "Go to Home screen",
                steps = listOf("Navigate directly to Android Home screen"),
                runningPhaseLabel = "Going Home…",
                isLowRiskAppLaunch = false
            )
        }

        if (goBackRegex.matches(text)) {
            return FastPathMatch(
                type = FastPathType.GO_BACK,
                arg = "",
                title = "Go Back",
                steps = listOf("Press Android Back navigation"),
                runningPhaseLabel = "Going Back…",
                isLowRiskAppLaunch = false
            )
        }

        val openMatch = openAppRegex.matchEntire(text)
        if (openMatch != null) {
            val rawApp = openMatch.groupValues[1].trim()
            if (rawApp.isNotBlank() && rawApp.length <= 36 && !multiStepConnectorRegex.containsMatchIn(rawApp)) {
                val prettyName = prettifyAppName(rawApp)
                return FastPathMatch(
                    type = FastPathType.OPEN_APP,
                    arg = rawApp,
                    title = "Open $prettyName",
                    steps = listOf("Launch $prettyName directly on-device"),
                    runningPhaseLabel = "Opening $prettyName…",
                    isLowRiskAppLaunch = true
                )
            }
        }

        return null
    }

    fun prettifyAppName(raw: String): String {
        val compact = AppNameFuzzyMatcher.normalize(raw)
        return when (compact) {
            "youtube", "yt" -> "YouTube"
            "whatsapp", "wa" -> "WhatsApp"
            "gmail" -> "Gmail"
            "instagram", "ig" -> "Instagram"
            "spotify" -> "Spotify"
            "chrome" -> "Chrome"
            "playstore", "googleplay" -> "Play Store"
            "maps", "googlemaps" -> "Maps"
            "settings" -> "Settings"
            else -> raw.split(" ")
                .filter { it.isNotBlank() }
                .joinToString(" ") { part ->
                    part.replaceFirstChar { ch -> ch.uppercase() }
                }
        }
    }
}

object HybridToolRouting {

    private val greetingRegex = Regex(
        """^(?:hi+|hello+|hey+|hii+|heyy+|good\s+(?:morning|afternoon|evening|night)|how\s+are\s+you(?:\s+doing)?|what'?s\s+up|thank\s+you(?:\s+so\s+much)?|thanks(?:\s+a\s+lot)?|namaste|నమస్తే|హలో|ధన్యవాదాలు)(?:\s+(?:agent|assistant|buddy|friend|bro))?[.!?,\s]*$""",
        RegexOption.IGNORE_CASE
    )
    private val openYoutubeAndPlayRegex = Regex(
        """^(?:please\s+|can\s+you\s+)?(?:open|launch|start|go\s+to)\s+(?:the\s+)?you\s*tube(?:\s+app)?\s+(?:and|then)\s+(?:play|watch|search\s+and\s+play|find\s+and\s+play)\s+(.+?)[.!?]*$""",
        RegexOption.IGNORE_CASE
    )
    private val playOnYoutubeRegex = Regex(
        """^(?:please\s+|can\s+you\s+)?(?:play|watch)\s+(.+?)\s+(?:on|in)\s+you\s*tube[.!?]*$""",
        RegexOption.IGNORE_CASE
    )
    private val openSettingShortcutRegex = Regex(
        """^(?:please\s+|can\s+you\s+)?(?:open|show|go\s+to)\s+(wi[\s-]*fi|bluetooth|sound|volume|display|brightness|battery|location|gps)\s+settings?[.!?]*$""",
        RegexOption.IGNORE_CASE
    )
    private val scrollCommandRegex = Regex(
        """^(?:please\s+|can\s+you\s+)?(?:scroll|swipe)\s+(down|up|left|right)(?:\s+(?:a\s+bit|more|again))?[.!?]*$""",
        RegexOption.IGNORE_CASE
    )
    private val openRecentsRegex = Regex(
        """^(?:please\s+|can\s+you\s+)?(?:open|show)\s+(?:recent\s+apps?|recents|app\s+switcher)|^recent\s+apps$|^switch\s+apps?[.!?]*$""",
        RegexOption.IGNORE_CASE
    )
    private val openNotificationsRegex = Regex(
        """^(?:please\s+|can\s+you\s+)?(?:open|show|pull\s+down)\s+notifications?(?:\s+shade|\s+panel)?[.!?]*$""",
        RegexOption.IGNORE_CASE
    )
    private val openQuickSettingsRegex = Regex(
        """^(?:please\s+|can\s+you\s+)?(?:open|show|pull\s+down)\s+quick\s+settings(?:\s+panel)?[.!?]*$""",
        RegexOption.IGNORE_CASE
    )
    private val setVolumePercentRegex = Regex(
        """^(?:please\s+|can\s+you\s+)?(?:set\s+)?(?:(media|music|ring|ringtone|alarm|notification|call)\s+)?volume\s+(?:to\s+)?(\d{1,3})\s*%?[.!?]*$""",
        RegexOption.IGNORE_CASE
    )
    private val muteVolumeRegex = Regex(
        """^(?:please\s+|can\s+you\s+)?mute(?:\s+(media|music|ring|ringtone|alarm|notification))?(?:\s+volume)?[.!?]*$""",
        RegexOption.IGNORE_CASE
    )
    private val maxVolumeRegex = Regex(
        """^(?:please\s+|can\s+you\s+)?(?:max|full)\s+(?:(media|music|ring|ringtone|alarm)\s+)?volume[.!?]*$""",
        RegexOption.IGNORE_CASE
    )
    private val tapTargetRegex = Regex(
        """^(?:please\s+|can\s+you\s+)?(?:tap|click|press)\s+(?:on\s+)?(?:the\s+)?(.+?)(?:\s+button)?[.!?]*$""",
        RegexOption.IGNORE_CASE
    )
    private val sendWhatsAppDirectRegex = Regex(
        """^(?:please\s+|can\s+you\s+)?(?:(?:open|go\s+to)\s+whats\s*app\s+(?:and|then)\s+)?(?:send|message)\s+(?:a\s+message\s+)?["']?(.+?)["']?\s+to\s+([a-zA-Z0-9 +\-]{2,32}?)(?:\s+(?:on|via|in)\s+whats\s*app)?[.!?]*$""",
        RegexOption.IGNORE_CASE
    )
    private val sendSmsDirectRegex = Regex(
        """^(?:please\s+|can\s+you\s+)?send\s+(?:an?\s+)?(?:sms|text(?:\s+message)?)\s+["']?(.+?)["']?\s+to\s+([a-zA-Z0-9 +\-]{2,32})[.!?]*$""",
        RegexOption.IGNORE_CASE
    )
    private val pronounContacts = setOf("him", "her", "them", "that", "there", "it")
    private val bookRideWithAppAndDestRegex = Regex(
        """^(?:please\s+|can\s+you\s+)?(?:(?:open|launch|go\s+to)\s+(uber|ola|rapido)\s+(?:and|then)\s+)?(?:book|get(?:\s+me)?|request|order|call)\s+(?:a\s+|an\s+)?(?:(?:ride|cab|taxi|auto|bike)\s+(?:in|on|via|with|using)\s+(uber|ola|rapido)|(uber|ola|rapido)(?:\s+(?:ride|cab|taxi|auto|bike))?|(?:ride|cab|taxi|auto|bike))(?:\s+to\s+(.+?))?(?:\s+(?:in|on|via|with|using)\s+(uber|ola|rapido))?[.!?]*$""",
        RegexOption.IGNORE_CASE
    )
    private val youtubeFollowUpPlayOnCurrentScreenRegex = Regex(
        """^(?:please\s+|can\s+you\s+)?(?:play|open|watch|click|tap)\s+(?:the\s+)?(?:(first|1st|second|2nd|third|3rd|next|top|this|that)\s+(?:video|one|song|short|result)|a\s+video|the\s+video)[.!?]*$""",
        RegexOption.IGNORE_CASE
    )
    private val youtubeFollowUpSearchRegex = Regex(
        """^(?:please\s+|can\s+you\s+)?(?:search|find|look\s+up)(?:\s+for)?\s+(.+?)[.!?]*$""",
        RegexOption.IGNORE_CASE
    )

    private val rideConfirmButtonRegex = Regex(
        """\b(confirm\s+(?:uber|ola|rapido|ride|cab|auto|bike|pickup|booking|drop|trip|order|and\s+request|location)?|book\s+(?:now|ride|cab|auto|bike|uber|ola|rapido)|request\s+(?:uber|ola|rapido|ride|cab)|choose\s+(?:uber|ola|rapido)\s*\w*\s*confirm|pay\s+now|place\s+booking|confirm\s*&\s*book)\b""",
        RegexOption.IGNORE_CASE
    )

    fun isRideConfirmButton(label: String): Boolean {
        val cleaned = label.trim().lowercase()
        if (cleaned.isBlank()) return false
        if (cleaned == "confirm" || cleaned == "book" || cleaned == "book now" || cleaned == "pay" || cleaned == "pay now") {
            return true
        }
        return rideConfirmButtonRegex.containsMatchIn(cleaned)
    }

    fun normalizeRideAppName(raw: String?): String {
        val compact = AppNameFuzzyMatcher.normalize(raw.orEmpty())
        return when {
            compact.contains("ola") -> "Ola"
            compact.contains("rapido") -> "Rapido"
            else -> "Uber"
        }
    }

    fun ridePackageForApp(raw: String?): String {
        return when (normalizeRideAppName(raw)) {
            "Ola" -> "com.olacabs.customer"
            "Rapido" -> "com.rapido.passenger"
            else -> "com.ubercab"
        }
    }

    fun buildRideReadyMessage(rideApp: String): String {
        val pretty = normalizeRideAppName(rideApp)
        return "I've set your destination in $pretty. Please review the fare and tap Confirm to book."
    }

    fun buildImmediateAckPhrase(toolCall: RoutedToolCall): String {
        return when (toolCall.tool) {
            ToolName.BOOK_RIDE -> "On it, opening ${normalizeRideAppName(toolCall.appName)}…"
            ToolName.PLAY_YOUTUBE_VIDEO -> "Playing that on YouTube…"
            ToolName.SEARCH_YOUTUBE -> "Searching YouTube…"
            ToolName.OPEN_APP -> "On it, opening ${FastPathMatcher.prettifyAppName(toolCall.appName ?: "the app")}…"
            ToolName.SEND_MESSAGE -> {
                val appLabel = if (toolCall.messagingApp?.contains("whatsapp", ignoreCase = true) == true) "WhatsApp" else "Messages"
                "On it, opening $appLabel…"
            }
            ToolName.OPEN_MAPS -> "Opening Maps…"
            ToolName.CALL_CONTACT -> "Calling ${toolCall.contactOrNumber ?: "contact"}…"
            ToolName.SET_ALARM -> "Setting your alarm…"
            ToolName.SET_TIMER -> "Starting your timer…"
            ToolName.OPEN_SETTING -> "Opening Settings…"
            ToolName.TAP_ON_SCREEN -> "Tapping ${toolCall.targetText ?: "that"}…"
            ToolName.SCROLL_SCREEN -> "Scrolling ${toolCall.direction ?: "down"}…"
            ToolName.UI_AUTOMATE -> "On it, working on your screen…"
            else -> "On it…"
        }
    }

    fun isPackageMatchingAppName(currentPackage: String?, appName: String?): Boolean {
        val pkg = currentPackage?.trim()?.lowercase().orEmpty()
        val app = appName?.trim().orEmpty()
        if (pkg.isBlank() || app.isBlank()) return false
        val norm = AppNameFuzzyMatcher.normalize(app)
        if (norm.isBlank()) return false
        val hints = AppNameFuzzyMatcher.aliasPackageHints(norm)
        if (hints.any { it.equals(pkg, ignoreCase = true) }) return true
        return pkg.contains(norm)
    }

    /**
     * Post-router context guard: if currentForegroundApp is ALREADY the app the user is referring to
     * and the command asks to act inside it (not merely switch back to it), NEVER relaunch with open_app.
     */
    fun applyForegroundContextGuard(
        toolCall: RoutedToolCall,
        userGoal: String,
        currentForegroundApp: String?
    ): RoutedToolCall {
        val fg = currentForegroundApp?.trim().orEmpty()
        if (fg.isBlank()) return toolCall

        if (toolCall.tool == ToolName.OPEN_APP && isPackageMatchingAppName(fg, toolCall.appName)) {
            val lowerGoal = userGoal.trim().lowercase()
            val isActionInsideApp = lowerGoal.contains("play") ||
                lowerGoal.contains("video") ||
                lowerGoal.contains("search") ||
                lowerGoal.contains("scroll") ||
                lowerGoal.contains("tap") ||
                lowerGoal.contains("click") ||
                lowerGoal.contains("first") ||
                lowerGoal.contains("second") ||
                lowerGoal.contains("book") ||
                lowerGoal.contains("ride")
            if (isActionInsideApp) {
                return RoutedToolCall(
                    tool = ToolName.UI_AUTOMATE,
                    goal = "Act directly on the currently open app ($fg) WITHOUT relaunching it: $userGoal",
                    title = userGoal.take(56),
                    steps = listOf(
                        "Inspect current screen in $fg",
                        "Execute directly on screen: ${userGoal.take(80)}",
                        "Verify screen changed before completing"
                    ),
                    riskLevel = RiskLevel.LOW,
                    reason = "Acting directly on currently open app"
                )
            }
        }
        return toolCall
    }

    fun buildRouterSystemPrompt(
        installedAppSample: List<String>,
        safetyRules: SafetyRules,
        personalSqlContext: String = ""
    ): String {
        val appsHint = installedAppSample.take(30).joinToString(", ")
        return buildString {
            appendLine("You are an Android Hybrid Tool Router. Output ONLY ONE valid JSON object (no markdown, no explanation).")
            appendLine("Available tools:")
            appendLine("""0) {"tool":"reply_to_user","message":"Warm natural reply","reason":"Conversational reply or knowledge question"}""")
            appendLine("""1) {"tool":"play_youtube_video","query":"song or video name","reason":"Play video on YouTube"}""")
            appendLine("""2) {"tool":"open_app","appName":"App Name","reason":"Open app ONLY when user asks ONLY to open an app and it is not already in foreground"}""")
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
            appendLine("""19) {"tool":"book_ride","appName":"Uber|Ola|Rapido","destination":"Airport","reason":"Open ride app and set destination, stopping before final Confirm/Book"}""")
            appendLine("""20) {"tool":"ui_automate","goal":"full multi-step goal","title":"Short title","steps":["Step 1","Step 2"],"riskLevel":"MEDIUM","reason":"Multi-step UI automation on current screen or app"}""")
            appendLine("CRITICAL RULES:")
            appendLine("- If `currentForegroundApp` is ALREADY the app the user is referring to (e.g., YouTube is already open and the user says 'play a video', 'play the first one', 'search lofi', or 'scroll down'), DO NOT relaunch the app from scratch. Act directly on the current screen using `tap_on_screen`, `scroll_screen`, `play_youtube_video`, or `ui_automate`.")
            appendLine("- Greetings, small talk, thanks, or general conversational questions ('Hi', 'Hello', 'Good morning', 'How are you?', 'Thank you', or Telugu equivalents) MUST ALWAYS route to reply_to_user(message) — NEVER open_app or ui_automate!")
            appendLine("- Use RECENT CONVERSATION and COMPLETED ACTION LOGS to resolve follow-ups like 'play the first one', 'send him I am on the way', 'scroll down', or 'pause'.")
            appendLine("- NEVER pick open_app for compound requests like 'open YouTube and play...', 'open WhatsApp and send hi to Ravi', or 'open Uber and book a cab'.")
            appendLine("- For booking a cab/ride/auto/bike in Uber, Ola, or Rapido (defaulting to Uber if not specified), ALWAYS pick book_ride(appName, destination). Ride booking is ALLOWED and stops safely before final Confirm/Pay.")
            appendLine("- For playing any video/song on YouTube (even in Telugu or English), ALWAYS pick play_youtube_video.")
            appendLine("- For sending a WhatsApp or SMS message, ALWAYS pick send_message with resolved contactOrNumber, messagingApp, and messageText.")
            appendLine("- For tapping a single visible button or text on the current screen (e.g., 'click Skip', 'tap Subscribe', 'press Like'), pick tap_on_screen(targetText).")
            appendLine("- For volume changes, pick set_volume(stream, levelPercent).")
            appendLine("- For system actions (back, home, recents, notifications, quick settings), pick the matching native tool.")
            appendLine("- For multi-step workflows inside any app, pick ui_automate.")
            appendLine("- ${SystemPromptBuilder.INDIAN_ENGLISH_VOICE_GRACE_RULE}")
            if (safetyRules.blockSensitiveFinancial) {
                appendLine("- NEVER route bank transfers, UPI PIN entry, OTP reading, or password actions.")
            }
            if (appsHint.isNotBlank()) {
                appendLine("Installed apps sample: $appsHint")
            }
            if (personalSqlContext.isNotBlank()) {
                appendLine(personalSqlContext)
            }
        }
    }

    fun buildRouterUserPrompt(
        userGoal: String,
        currentForegroundApp: String?,
        conversationHistory: List<ChatMessage>,
        actionLogs: List<ActionLogEntry> = ConversationRepository.getRecentActionLogs()
    ): String {
        val fgApp = currentForegroundApp?.trim().takeIf { !it.isNullOrBlank() } ?: "unknown"
        val recentTurns = ConversationMemoryHelper.buildRecentChatMessages(conversationHistory, maxTurns = 10)
        return buildString {
            appendLine("currentForegroundApp: $fgApp")
            if (recentTurns.isNotEmpty()) {
                appendLine("recentConversation (last ${recentTurns.size} messages):")
                recentTurns.forEach { msg ->
                    val role = if (msg.sender == MessageSender.USER) "User" else "Agent"
                    val snippet = msg.text.replace(Regex("\\s+"), " ").trim().take(200)
                    appendLine("- $role: $snippet")
                }
            }
            if (actionLogs.isNotEmpty()) {
                appendLine("recentCompletedActions:")
                actionLogs.takeLast(6).forEach { log ->
                    appendLine("- (${log.status}) ${log.summary.take(180)}")
                }
            }
            appendLine()
            append("CURRENT USER REQUEST: $userGoal")
        }
    }

    /**
     * Ultra-fast local router for greetings, deterministic native tools, ride booking,
     * foreground-aware follow-ups, and dedicated YouTube/messaging patterns.
     * Strictly NEVER launches an app on greetings and NEVER swallows compound multi-step commands into open_app.
     */
    fun matchDirectLocalTool(
        rawCommand: String,
        currentForegroundApp: String? = null
    ): RoutedToolCall? {
        val text = rawCommand.trim().replace(Regex("\\s+"), " ")
        if (text.isBlank()) return null
        val fgPkg = currentForegroundApp?.trim()?.lowercase().orEmpty()

        // 0. Natural greetings & small talk -> reply_to_user immediately (NEVER open_app!)
        if (greetingRegex.matches(text)) {
            val lower = text.lowercase()
            val reply = when {
                lower.contains("good morning") -> "Good morning! How can I help you today?"
                lower.contains("good afternoon") -> "Good afternoon! What can I do for you?"
                lower.contains("good evening") -> "Good evening! How can I help?"
                lower.contains("good night") -> "Good night! Let me know if you need an alarm set."
                lower.contains("how are you") -> "I'm doing great and ready to help! What would you like me to do?"
                lower.contains("thank") || lower.contains("ధన్యవాదాలు") -> "You're welcome! Let me know anytime you need help."
                lower.contains("నమస్తే") || lower.contains("హలో") -> "నమస్తే! నేను మీకు ఎలా సహాయం చేయగలను?"
                else -> "Hello! How can I help you today?"
            }
            return RoutedToolCall(
                tool = ToolName.REPLY_TO_USER,
                replyMessage = reply,
                title = "Reply",
                steps = listOf("Respond conversationally"),
                riskLevel = RiskLevel.LOW,
                reason = "Conversational greeting"
            )
        }

        // 0b. Executive On-Device File Generation requests ("create an Excel monthly expense tracker", "make a PDF study plan", etc.)
        val detectedFile = com.example.files.FileRequestDetector.detect(text)
        if (detectedFile != null) {
            return RoutedToolCall(
                tool = ToolName.GENERATE_FILE,
                label = detectedFile.format.id,
                query = detectedFile.cleanGoal,
                goal = detectedFile.cleanGoal,
                title = "Generate ${detectedFile.format.badgeLabel} Document",
                steps = listOf(
                    "Build executive ${detectedFile.format.badgeLabel} blueprint",
                    "Render formatted ${detectedFile.format.badgeLabel} on device",
                    "Attach ready-to-open file card"
                ),
                riskLevel = RiskLevel.LOW,
                reason = "Executive file generation (${detectedFile.format.badgeLabel})"
            )
        }

        // 0c. Ride booking requests (Uber / Ola / Rapido, defaulting to Uber)
        val rideMatch = bookRideWithAppAndDestRegex.matchEntire(text)
        if (rideMatch != null) {
            val rawApp = rideMatch.groupValues[1].ifBlank {
                rideMatch.groupValues[2].ifBlank {
                    rideMatch.groupValues[3].ifBlank { rideMatch.groupValues[5] }
                }
            }
            val rideApp = normalizeRideAppName(rawApp)
            val destination = rideMatch.groupValues[4].trim().trim('"', '\'').takeIf { it.isNotBlank() }
            val title = if (destination != null) {
                "Book $rideApp to ${destination.take(36)}"
            } else {
                "Book a ride in $rideApp"
            }
            val steps = buildList {
                add("Open $rideApp")
                if (destination != null) {
                    add("Enter destination \"$destination\" and select drop location")
                    add("Reach ride-selection screen and stop before Confirm/Book")
                } else {
                    add("Open ride destination screen and wait for your selection")
                }
            }
            return RoutedToolCall(
                tool = ToolName.BOOK_RIDE,
                appName = rideApp,
                destination = destination,
                title = title,
                steps = steps,
                riskLevel = RiskLevel.MEDIUM,
                reason = if (destination != null) "Booking $rideApp ride to $destination" else "Opening $rideApp to book a ride"
            )
        }

        // 0d. Foreground-aware YouTube follow-ups when YouTube is ALREADY open on screen
        //     (NEVER relaunch YouTube with open_app when it's already in the foreground!)
        if (fgPkg.contains("youtube")) {
            if (youtubeFollowUpPlayOnCurrentScreenRegex.matches(text)) {
                return RoutedToolCall(
                    tool = ToolName.UI_AUTOMATE,
                    goal = "YouTube is ALREADY open on screen. Do NOT relaunch YouTube. Directly tap and play the requested video on the current screen: \"$text\"",
                    title = "Play video on current YouTube screen",
                    steps = listOf(
                        "Inspect current YouTube screen",
                        "Tap the requested non-ad video card directly",
                        "Verify video player opened"
                    ),
                    riskLevel = RiskLevel.LOW,
                    reason = "Playing video directly on YouTube"
                )
            }
            val fgSearchMatch = youtubeFollowUpSearchRegex.matchEntire(text)
            if (fgSearchMatch != null) {
                val query = fgSearchMatch.groupValues[1].trim().trim('"', '\'')
                if (query.isNotBlank()) {
                    return RoutedToolCall(
                        tool = ToolName.UI_AUTOMATE,
                        goal = "YouTube is ALREADY open on screen. Do NOT relaunch YouTube. Tap the Search icon on the current YouTube screen, type \"$query\", and submit the search.",
                        title = "Search \"$query\" in YouTube",
                        steps = listOf(
                            "Tap Search inside current YouTube screen",
                            "Type \"$query\" and submit",
                            "Verify search results appeared"
                        ),
                        riskLevel = RiskLevel.LOW,
                        reason = "Searching \"$query\" in YouTube"
                    )
                }
            }
        }

        // 1. Dedicated fast compound YouTube playback: "open youtube and play <query>" / "play <query> on youtube"
        val ytPlayMatch = openYoutubeAndPlayRegex.matchEntire(text) ?: playOnYoutubeRegex.matchEntire(text)
        if (ytPlayMatch != null) {
            val query = ytPlayMatch.groupValues[1]
                .trim()
                .removePrefix("a ")
                .removePrefix("the ")
                .trim('"', '\'')
            if (query.isNotBlank()) {
                return RoutedToolCall(
                    tool = ToolName.PLAY_YOUTUBE_VIDEO,
                    query = query,
                    title = "Play \"$query\" on YouTube",
                    steps = listOf(
                        "Open YouTube search results for \"$query\"",
                        "Wait for video results to load",
                        "Tap the first non-ad playable video",
                        "Confirm video playback started"
                    ),
                    riskLevel = RiskLevel.LOW,
                    reason = "Playing $query on YouTube"
                )
            }
        }

        // 2. Direct scroll gesture on current screen ("scroll down", "scroll up")
        val scrollMatch = scrollCommandRegex.matchEntire(text)
        if (scrollMatch != null) {
            val dir = scrollMatch.groupValues[1].lowercase()
            return RoutedToolCall(
                tool = ToolName.SCROLL_SCREEN,
                direction = dir,
                title = "Scroll $dir",
                steps = listOf("Scroll $dir on current screen"),
                riskLevel = RiskLevel.LOW,
                reason = "Scrolling $dir"
            )
        }

        // 3. System global actions (Recents, Notifications, Quick Settings)
        if (openRecentsRegex.matches(text)) {
            return RoutedToolCall(
                tool = ToolName.OPEN_RECENTS,
                title = "Open Recent Apps",
                steps = listOf("Open Android Recent Apps switcher"),
                riskLevel = RiskLevel.LOW,
                reason = "Opening Recent Apps"
            )
        }
        if (openNotificationsRegex.matches(text)) {
            return RoutedToolCall(
                tool = ToolName.OPEN_NOTIFICATIONS,
                title = "Open Notifications",
                steps = listOf("Open Android Notification shade"),
                riskLevel = RiskLevel.LOW,
                reason = "Opening Notifications"
            )
        }
        if (openQuickSettingsRegex.matches(text)) {
            return RoutedToolCall(
                tool = ToolName.OPEN_QUICK_SETTINGS,
                title = "Open Quick Settings",
                steps = listOf("Open Android Quick Settings panel"),
                riskLevel = RiskLevel.LOW,
                reason = "Opening Quick Settings"
            )
        }

        // 4. Direct Volume controls via AudioManager
        val volMatch = setVolumePercentRegex.matchEntire(text)
        if (volMatch != null) {
            val stream = volMatch.groupValues[1].trim().lowercase().ifBlank { "media" }
            val level = volMatch.groupValues[2].toIntOrNull()?.coerceIn(0, 100) ?: 50
            return RoutedToolCall(
                tool = ToolName.SET_VOLUME,
                stream = stream,
                levelPercent = level,
                title = "Set $stream volume to $level%",
                steps = listOf("Adjust $stream volume to $level%"),
                riskLevel = RiskLevel.LOW,
                reason = "Setting $stream volume to $level%"
            )
        }
        val muteMatch = muteVolumeRegex.matchEntire(text)
        if (muteMatch != null) {
            val stream = muteMatch.groupValues[1].trim().lowercase().ifBlank { "media" }
            return RoutedToolCall(
                tool = ToolName.SET_VOLUME,
                stream = stream,
                levelPercent = 0,
                title = "Mute $stream volume",
                steps = listOf("Set $stream volume to 0%"),
                riskLevel = RiskLevel.LOW,
                reason = "Muting $stream volume"
            )
        }
        val maxVolMatch = maxVolumeRegex.matchEntire(text)
        if (maxVolMatch != null) {
            val stream = maxVolMatch.groupValues[1].trim().lowercase().ifBlank { "media" }
            return RoutedToolCall(
                tool = ToolName.SET_VOLUME,
                stream = stream,
                levelPercent = 100,
                title = "Max $stream volume",
                steps = listOf("Set $stream volume to 100%"),
                riskLevel = RiskLevel.LOW,
                reason = "Setting $stream volume to 100%"
            )
        }

        // 5. Direct explicit WhatsApp / SMS send command (still ALWAYS requires Approval Card!)
        val hasWhatsAppMention = text.contains("whatsapp", ignoreCase = true) || text.contains("whats app", ignoreCase = true)
        if (hasWhatsAppMention) {
            val waMatch = sendWhatsAppDirectRegex.matchEntire(text)
            if (waMatch != null) {
                val msgBody = waMatch.groupValues[1].trim().trim('"', '\'')
                val contact = waMatch.groupValues[2].trim()
                if (msgBody.isNotBlank() && contact.isNotBlank() && contact.lowercase() !in pronounContacts) {
                    return buildSendMessageToolCall(contact, "whatsapp", msgBody)
                }
            }
        }
        val smsMatch = sendSmsDirectRegex.matchEntire(text)
        if (smsMatch != null) {
            val msgBody = smsMatch.groupValues[1].trim().trim('"', '\'')
            val contact = smsMatch.groupValues[2].trim()
            if (msgBody.isNotBlank() && contact.isNotBlank() && contact.lowercase() !in pronounContacts) {
                return buildSendMessageToolCall(contact, "sms", msgBody)
            }
        }

        // 6. Direct Android settings panel shortcuts ("open wifi settings", "open bluetooth settings")
        val settingMatch = openSettingShortcutRegex.matchEntire(text)
        if (settingMatch != null) {
            val setting = settingMatch.groupValues[1].trim()
            val pretty = setting.replaceFirstChar { it.uppercase() }
            return RoutedToolCall(
                tool = ToolName.OPEN_SETTING,
                settingName = setting.lowercase(),
                title = "Open $pretty Settings",
                steps = listOf("Open Android $pretty Settings screen"),
                riskLevel = RiskLevel.LOW,
                reason = "Opening $pretty Settings"
            )
        }

        // 7. Single-step fast-path (pure open_app, pure search_youtube, press_home, press_back)
        val fast = FastPathMatcher.match(text)
        if (fast != null) {
            return when (fast.type) {
                FastPathType.OPEN_APP -> RoutedToolCall(
                    tool = ToolName.OPEN_APP,
                    appName = fast.arg,
                    title = fast.title,
                    steps = fast.steps,
                    riskLevel = RiskLevel.LOW,
                    reason = fast.runningPhaseLabel
                )
                FastPathType.YOUTUBE_SEARCH -> RoutedToolCall(
                    tool = ToolName.SEARCH_YOUTUBE,
                    query = fast.arg,
                    title = fast.title,
                    steps = fast.steps,
                    riskLevel = RiskLevel.LOW,
                    reason = fast.runningPhaseLabel
                )
                FastPathType.GO_HOME -> RoutedToolCall(
                    tool = ToolName.PRESS_HOME,
                    title = fast.title,
                    steps = fast.steps,
                    riskLevel = RiskLevel.LOW,
                    reason = fast.runningPhaseLabel
                )
                FastPathType.GO_BACK -> RoutedToolCall(
                    tool = ToolName.PRESS_BACK,
                    title = fast.title,
                    steps = fast.steps,
                    riskLevel = RiskLevel.LOW,
                    reason = fast.runningPhaseLabel
                )
                else -> null
            }
        }

        // 8. Direct on-screen tap ("click skip", "tap subscribe", "press play")
        val tapMatch = tapTargetRegex.matchEntire(text)
        if (tapMatch != null) {
            val target = tapMatch.groupValues[1].trim().trim('"', '\'')
            val lowerTarget = target.lowercase()
            if (target.isNotBlank() &&
                target.length <= 42 &&
                lowerTarget != "back" &&
                lowerTarget != "home" &&
                !lowerTarget.contains(" and ") &&
                !lowerTarget.contains(" then ")
            ) {
                return RoutedToolCall(
                    tool = ToolName.TAP_ON_SCREEN,
                    targetText = target,
                    title = "Tap \"$target\"",
                    steps = listOf("Find and tap \"$target\" on current screen"),
                    riskLevel = RiskLevel.LOW,
                    reason = "Tapping \"$target\""
                )
            }
        }

        return null
    }

    private val moshi: Moshi by lazy { Moshi.Builder().build() }
    private val mapAdapter by lazy {
        val type = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        moshi.adapter<Map<String, Any?>>(type)
    }

    fun mapToJson(map: Map<String, Any?>): String = mapAdapter.toJson(map)

    /**
     * Extracts the raw JSON object slice from markdown fences or prose wrappers purely in Kotlin.
     */
    fun extractJsonSlice(rawText: String): String? {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) return null

        val fenceRegex = Regex("""```(?:json)?\s*([\s\S]*?)```""", RegexOption.IGNORE_CASE)
        val candidate = fenceRegex.find(trimmed)?.groupValues?.getOrNull(1)?.trim() ?: trimmed

        val firstBrace = candidate.indexOf('{')
        if (firstBrace < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in firstBrace until candidate.length) {
            val ch = candidate[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\' && inString) {
                escaped = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (ch == '{') depth++
                else if (ch == '}') {
                    depth--
                    if (depth == 0) {
                        return candidate.substring(firstBrace, i + 1)
                    }
                }
            }
        }

        val lastBrace = candidate.lastIndexOf('}')
        if (lastBrace > firstBrace) {
            return candidate.substring(firstBrace, lastBrace + 1)
        }
        return null
    }

    /**
     * Resilient JSON extractor that handles ```json fences and leading/trailing prose.
     */
    fun extractJsonObject(rawText: String): JSONObject? {
        val slice = extractJsonSlice(rawText) ?: return null
        return runCatching { JSONObject(slice) }.getOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    fun parseRoutedToolJson(rawReply: String, fallbackGoal: String): RoutedToolCall? {
        val slice = extractJsonSlice(rawReply) ?: return null
        val root = runCatching { mapAdapter.fromJson(slice) }.getOrNull() ?: return null
        val tool = ((root["tool"] as? String) ?: (root["action"] as? String))?.trim()?.lowercase().orEmpty()
        if (tool.isBlank()) return null

        val args = (root["args"] as? Map<String, Any?>)
            ?: (root["arguments"] as? Map<String, Any?>)
            ?: root

        fun str(vararg keys: String): String? {
            for (k in keys) {
                val fromArgs = args[k]?.toString()?.trim()
                if (!fromArgs.isNullOrBlank() && fromArgs != "null") return fromArgs
                val fromRoot = root[k]?.toString()?.trim()
                if (!fromRoot.isNullOrBlank() && fromRoot != "null") return fromRoot
            }
            return null
        }

        fun intVal(vararg keys: String): Int? {
            for (k in keys) {
                val raw = args[k] ?: root[k] ?: continue
                when (raw) {
                    is Number -> return raw.toInt()
                    is String -> raw.trim().toDoubleOrNull()?.toInt()?.let { return it }
                }
            }
            return null
        }

        val appName = str("appName", "app_name", "app")
        val query = str("query", "searchQuery", "video")
        val destination = str("destination", "place", "location")
        val hour = intVal("hour")
        val minute = intVal("minute")
        val seconds = intVal("seconds", "durationSeconds")
        val label = str("label", "message")
        val settingName = str("settingName", "setting", "name")
        val contactOrNumber = str("contactOrNumber", "contact", "nameOrNumber", "number", "recipient")
        val messagingApp = str("messagingApp", "platform", "app")
        val messageText = str("messageText", "text", "body")
        val direction = str("direction")
        val targetText = str("targetText", "target", "elementText")
        val stream = str("stream", "audioStream")
        val levelPercent = intVal("levelPercent", "level", "percent")
        val replyMessage = str("message", "reply", "replyMessage", "text")
        val goal = str("goal") ?: fallbackGoal.takeIf { it.isNotBlank() }
        val reason = str("reason").orEmpty()

        val parsedSteps = (root["steps"] as? List<*>)
            ?.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotBlank() } }
            .orEmpty()

        val canonicalTool = when (tool) {
            "reply_to_user", "reply", "chat", "respond", "answer" -> ToolName.REPLY_TO_USER
            "play_youtube", "play_youtube_video", "youtube_play" -> ToolName.PLAY_YOUTUBE_VIDEO
            "open_app", "launch_app" -> ToolName.OPEN_APP
            "search_youtube", "youtube_search" -> ToolName.SEARCH_YOUTUBE
            "search_web", "web_search", "google_search" -> ToolName.SEARCH_WEB
            "open_maps", "navigate", "maps" -> ToolName.OPEN_MAPS
            "set_alarm", "alarm" -> ToolName.SET_ALARM
            "set_timer", "timer" -> ToolName.SET_TIMER
            "open_setting", "open_settings" -> ToolName.OPEN_SETTING
            "call_contact", "call", "make_call" -> ToolName.CALL_CONTACT
            "send_message", "draft_message", "message", "send_whatsapp", "send_sms" -> ToolName.SEND_MESSAGE
            "book_ride", "book_cab", "book_uber", "book_ola", "book_rapido", "ride_booking" -> ToolName.BOOK_RIDE
            "tap_on_screen", "tap_screen", "tap", "click" -> ToolName.TAP_ON_SCREEN
            "scroll_screen", "scroll", "swipe" -> ToolName.SCROLL_SCREEN
            "press_home", "go_home", "home" -> ToolName.PRESS_HOME
            "press_back", "go_back", "back" -> ToolName.PRESS_BACK
            "open_recents", "recents", "recent_apps" -> ToolName.OPEN_RECENTS
            "open_notifications", "notifications" -> ToolName.OPEN_NOTIFICATIONS
            "open_quick_settings", "quick_settings" -> ToolName.OPEN_QUICK_SETTINGS
            "set_volume", "volume" -> ToolName.SET_VOLUME
            "ui_automate", "automate" -> ToolName.UI_AUTOMATE
            "generate_file", "create_file", "export_file", "make_pdf", "make_excel", "make_docx", "make_csv" -> ToolName.GENERATE_FILE
            else -> return null
        }

        val appPretty = if (messagingApp?.lowercase()?.contains("whatsapp") == true || messagingApp?.lowercase() == "wa") {
            "WhatsApp"
        } else {
            "SMS"
        }
        val normalizedRideApp = if (canonicalTool == ToolName.BOOK_RIDE) normalizeRideAppName(appName) else appName

        val defaultTitle = str("title") ?: when (canonicalTool) {
            ToolName.REPLY_TO_USER -> "Reply"
            ToolName.PLAY_YOUTUBE_VIDEO -> "Play \"${query ?: fallbackGoal}\" on YouTube"
            ToolName.OPEN_APP -> "Open ${FastPathMatcher.prettifyAppName(appName ?: "App")}"
            ToolName.SEARCH_YOUTUBE -> "Search YouTube: ${query ?: fallbackGoal}"
            ToolName.SEARCH_WEB -> "Search Web: ${query ?: fallbackGoal}"
            ToolName.OPEN_MAPS -> "Navigate to ${destination ?: fallbackGoal}"
            ToolName.SET_ALARM -> "Set Alarm for ${hour ?: 7}:${(minute ?: 0).toString().padStart(2, '0')}"
            ToolName.SET_TIMER -> "Set Timer (${seconds ?: 60}s)"
            ToolName.OPEN_SETTING -> "Open ${(settingName ?: "Device").replaceFirstChar { it.uppercase() }} Settings"
            ToolName.CALL_CONTACT -> "Call ${contactOrNumber ?: "Contact"}"
            ToolName.SEND_MESSAGE -> "Send $appPretty to ${contactOrNumber ?: "Contact"}"
            ToolName.BOOK_RIDE -> if (!destination.isNullOrBlank()) {
                "Book $normalizedRideApp to ${destination.take(36)}"
            } else {
                "Book a ride in $normalizedRideApp"
            }
            ToolName.TAP_ON_SCREEN -> "Tap \"${targetText ?: fallbackGoal}\""
            ToolName.SCROLL_SCREEN -> "Scroll ${direction ?: "down"}"
            ToolName.PRESS_HOME -> "Go to Home screen"
            ToolName.PRESS_BACK -> "Go Back"
            ToolName.OPEN_RECENTS -> "Open Recent Apps"
            ToolName.OPEN_NOTIFICATIONS -> "Open Notifications"
            ToolName.OPEN_QUICK_SETTINGS -> "Open Quick Settings"
            ToolName.SET_VOLUME -> "Set ${stream ?: "media"} volume to ${levelPercent ?: 50}%"
            ToolName.GENERATE_FILE -> "Generate ${(label ?: "PDF").uppercase()} Document"
            else -> fallbackGoal.take(56).ifBlank { "Run On-Device Task" }
        }

        val defaultSteps = parsedSteps.ifEmpty {
            when (canonicalTool) {
                ToolName.REPLY_TO_USER -> listOf("Reply to user")
                ToolName.PLAY_YOUTUBE_VIDEO -> listOf(
                    "Open YouTube search results for \"${query ?: fallbackGoal}\"",
                    "Wait for video results to load",
                    "Tap the first non-ad playable video",
                    "Verify playback opened"
                )
                ToolName.OPEN_APP -> listOf("Launch ${FastPathMatcher.prettifyAppName(appName ?: "app")} directly on-device")
                ToolName.SEARCH_YOUTUBE -> listOf("Open YouTube search for \"${query ?: fallbackGoal}\"")
                ToolName.SEARCH_WEB -> listOf("Search the web for \"${query ?: fallbackGoal}\"")
                ToolName.OPEN_MAPS -> listOf("Open Maps navigation to \"${destination ?: fallbackGoal}\"")
                ToolName.SET_ALARM -> listOf("Create alarm for ${hour ?: 7}:${(minute ?: 0).toString().padStart(2, '0')}")
                ToolName.SET_TIMER -> listOf("Start timer for ${seconds ?: 60} seconds")
                ToolName.OPEN_SETTING -> listOf("Open Android ${settingName ?: "device"} settings")
                ToolName.CALL_CONTACT -> listOf(
                    "Look up phone number for ${contactOrNumber ?: "contact"}",
                    "Place phone call"
                )
                ToolName.SEND_MESSAGE -> listOf(
                    "Resolve contact ${contactOrNumber ?: "recipient"}",
                    "Open $appPretty chat with message: \"${messageText.orEmpty().take(80)}\"",
                    "Tap the Send button and verify delivery"
                )
                ToolName.BOOK_RIDE -> buildList {
                    add("Open $normalizedRideApp")
                    if (!destination.isNullOrBlank()) {
                        add("Enter destination \"$destination\" and pick drop location")
                        add("Reach ride selection screen and stop before Confirm/Book")
                    } else {
                        add("Open ride destination screen and wait for your selection")
                    }
                }
                ToolName.TAP_ON_SCREEN -> listOf("Find and tap \"${targetText ?: fallbackGoal}\" on current screen")
                ToolName.SCROLL_SCREEN -> listOf("Scroll ${direction ?: "down"} on current screen")
                ToolName.PRESS_HOME -> listOf("Navigate to Android Home screen")
                ToolName.PRESS_BACK -> listOf("Press Android Back button")
                ToolName.OPEN_RECENTS -> listOf("Open Android Recent Apps")
                ToolName.OPEN_NOTIFICATIONS -> listOf("Open Android Notifications")
                ToolName.OPEN_QUICK_SETTINGS -> listOf("Open Android Quick Settings")
                ToolName.SET_VOLUME -> listOf("Set ${stream ?: "media"} volume to ${levelPercent ?: 50}%")
                else -> listOf(
                    "Inspect current screen via Accessibility Service",
                    "Complete task: ${(goal ?: fallbackGoal).take(80)}",
                    "Verify final screen state before finishing"
                )
            }
        }

        val computedRisk = when (canonicalTool) {
            ToolName.CALL_CONTACT, ToolName.SEND_MESSAGE, ToolName.BOOK_RIDE -> RiskLevel.MEDIUM
            ToolName.UI_AUTOMATE -> RiskLevel.fromRaw(str("riskLevel") ?: "MEDIUM")
            else -> RiskLevel.LOW
        }

        return RoutedToolCall(
            tool = canonicalTool,
            appName = normalizedRideApp,
            query = query,
            destination = destination,
            hour = hour,
            minute = minute,
            seconds = seconds,
            label = label,
            settingName = settingName,
            contactOrNumber = contactOrNumber,
            messagingApp = messagingApp,
            messageText = messageText,
            direction = direction,
            targetText = targetText,
            stream = stream,
            levelPercent = levelPercent,
            replyMessage = replyMessage,
            goal = goal,
            title = defaultTitle,
            steps = defaultSteps,
            riskLevel = computedRisk,
            reason = reason.ifBlank { defaultTitle }
        )
    }

    fun buildSendMessageToolCall(
        contactOrNumber: String,
        messagingApp: String,
        messageText: String
    ): RoutedToolCall {
        val isWa = messagingApp.lowercase().contains("whatsapp") || messagingApp.lowercase() == "wa"
        val appLabel = if (isWa) "WhatsApp" else "SMS"
        return RoutedToolCall(
            tool = ToolName.SEND_MESSAGE,
            contactOrNumber = contactOrNumber,
            messagingApp = if (isWa) "whatsapp" else "sms",
            messageText = messageText,
            title = "Send $appLabel to $contactOrNumber",
            steps = listOf(
                "Resolve phone number for $contactOrNumber",
                "Open $appLabel chat with: \"${messageText.take(80)}\"",
                "Tap the Send button and verify the message sent"
            ),
            riskLevel = RiskLevel.MEDIUM,
            reason = "Sending $appLabel message to $contactOrNumber"
        )
    }
}

object ConversationMemoryHelper {
    private val transientPlaceholderTexts = setOf(
        "Running on-device…",
        "Running instantly on-device…",
        "Review the steps below and tap Approve to run on your phone."
    )

    fun buildRecentChatMessages(
        conversationHistory: List<ChatMessage>,
        maxTurns: Int = 15
    ): List<ChatMessage> {
        return conversationHistory
            .filter { !it.excludeFromContext && !it.isError && !it.isBlockedBySafety && it.text.isNotBlank() }
            .filter { it.sender == MessageSender.USER || it.sender == MessageSender.AGENT }
            .filterNot { it.sender == MessageSender.AGENT && it.text.trim() in transientPlaceholderTexts }
            .takeLast(maxTurns)
    }

    fun formatRecentAgentHistory(
        conversationHistory: List<ChatMessage>,
        maxTurns: Int = 8
    ): String {
        val turns = buildRecentChatMessages(conversationHistory, maxTurns)
        if (turns.isEmpty()) return ""
        return turns.joinToString("\n") { msg ->
            val role = if (msg.sender == MessageSender.USER) "User" else "Agent"
            val snippet = msg.text.replace(Regex("\\s+"), " ").trim().take(180)
            "$role: $snippet"
        }
    }
}

object SendButtonMatcher {
    private val exactSendLabels = setOf(
        "send",
        "send message",
        "send sms",
        "send mms",
        "send text"
    )
    private val forbiddenSendPhrases = listOf(
        "send money",
        "send payment",
        "send location",
        "send contact",
        "send file",
        "send photo"
    )

    fun scoreCandidate(
        text: String,
        contentDescription: String,
        viewIdResourceName: String,
        isClickable: Boolean
    ): Int {
        val t = text.trim().lowercase()
        val d = contentDescription.trim().lowercase()
        val id = viewIdResourceName.trim().lowercase()
        val combined = "$t $d"

        if (forbiddenSendPhrases.any { combined.contains(it) }) return 0

        var score = 0
        if (id.endsWith(":id/send") || id.endsWith("/send") || id.contains("send_message_button") || id.contains("send_button") || id.contains("send_btn")) {
            score += 100
        } else if (id.contains("send")) {
            score += 65
        }

        if (d in exactSendLabels || t in exactSendLabels) {
            score += 90
        } else if (d.startsWith("send ") || t.startsWith("send ")) {
            score += 55
        }

        if (score > 0 && isClickable) {
            score += 20
        }
        return score
    }

    fun findBestSendButton(nodes: List<ScreenNode>): ScreenNode? {
        return nodes
            .map { node ->
                node to scoreCandidate(
                    text = node.text,
                    contentDescription = node.contentDescription,
                    viewIdResourceName = node.viewIdResourceName,
                    isClickable = node.isClickable
                )
            }
            .filter { it.second >= 65 }
            .maxByOrNull { it.second }
            ?.first
    }
}

object ConfirmationFormatter {
    private val forbiddenSuccessPrefixRegex = Regex("""yes,?\s*completed[!:,.\-\s]*""", RegexOption.IGNORE_CASE)

    private fun stripAccidentalSuccessPrefix(raw: String): String {
        return forbiddenSuccessPrefixRegex.replace(raw, "").trim()
    }

    fun formatToolResult(result: ToolExecutionResult, elapsed: String? = null): String {
        return when (result) {
            is ToolExecutionResult.VerifiedSuccess -> {
                if (!result.customUserMessage.isNullOrBlank()) {
                    val custom = result.customUserMessage.trim()
                    if (!elapsed.isNullOrBlank() && !custom.contains(elapsed)) "$custom ($elapsed)" else custom
                } else {
                    formatSuccess(result.summary, elapsed)
                }
            }
            is ToolExecutionResult.PartialSuccess -> formatPartialSuccess(result.whatWorked, result.whatFailed, elapsed)
            is ToolExecutionResult.Failed -> formatStrictFailure(result.reason, elapsed)
        }
    }

    fun formatSuccess(detail: String, elapsed: String? = null): String {
        val cleaned = detail.trim().trimEnd('.')
        val body = when {
            cleaned.isBlank() -> "Yes, completed!"
            cleaned.startsWith("Yes, completed", ignoreCase = true) -> {
                val rest = cleaned.substring("Yes, completed".length).trimStart('!', ':', ' ', '-')
                if (rest.isBlank()) "Yes, completed!" else "Yes, completed! $rest."
            }
            else -> "Yes, completed! $cleaned."
        }
        return if (!elapsed.isNullOrBlank()) "$body ($elapsed)" else body
    }

    fun formatPartialSuccess(whatWorked: String, whatFailed: String, elapsed: String? = null): String {
        val worked = stripAccidentalSuccessPrefix(whatWorked).trim().trimEnd('.')
            .removePrefix("I ").removePrefix("i ")
        val failed = stripAccidentalSuccessPrefix(whatFailed).trim().trimEnd('.')
            .removePrefix("I couldn't ").removePrefix("couldn't ")
        val body = "I ${worked.ifBlank { "started the action" }}, but I couldn't ${failed.ifBlank { "finish the final step" }}."
        return if (!elapsed.isNullOrBlank() && !body.contains(elapsed)) "$body ($elapsed)" else body
    }

    fun formatStrictFailure(reason: String, elapsed: String? = null): String {
        val cleaned = stripAccidentalSuccessPrefix(reason).trim().trimEnd('.')
        val body = if (cleaned.isBlank()) {
            "I couldn't complete that — unknown error."
        } else if (cleaned.equals(AgentReliabilityConstants.ACCESSIBILITY_OFF_MESSAGE.trimEnd('.'), ignoreCase = true)) {
            AgentReliabilityConstants.ACCESSIBILITY_OFF_MESSAGE
        } else if (cleaned.startsWith("I couldn't complete that", ignoreCase = true)) {
            "$cleaned."
        } else {
            "I couldn't complete that — $cleaned."
        }
        return if (!elapsed.isNullOrBlank() && !body.contains(elapsed)) "$body ($elapsed)" else body
    }

    fun formatFailure(reason: String, elapsed: String? = null): String {
        return formatStrictFailure(reason, elapsed)
    }
}

sealed class DoneValidationResult {
    data class Accepted(val evidence: String) : DoneValidationResult()
    data class Rejected(val reason: String, val shouldRetry: Boolean) : DoneValidationResult()
}

object UiAutomationVerification {
    fun validateDoneAction(
        stepNumber: Int,
        maxSteps: Int,
        anyRealActionPerformed: Boolean,
        anyScreenChanged: Boolean,
        lastActionChangedScreen: Boolean,
        evidence: String?,
        visibleScreenText: String
    ): DoneValidationResult {
        if (stepNumber <= 1 && !anyRealActionPerformed) {
            return DoneValidationResult.Rejected(
                reason = "Cannot accept done on Step 1 before taking a real action on screen.",
                shouldRetry = stepNumber < maxSteps
            )
        }
        if (!anyScreenChanged) {
            return DoneValidationResult.Rejected(
                reason = "No action took effect on the screen",
                shouldRetry = false
            )
        }
        if (!lastActionChangedScreen) {
            return DoneValidationResult.Rejected(
                reason = "Last action produced zero screen change.",
                shouldRetry = stepNumber < maxSteps
            )
        }
        val trimmedEvidence = evidence?.trim().orEmpty()
        if (trimmedEvidence.isBlank()) {
            return DoneValidationResult.Rejected(
                reason = "Missing exact visible screen evidence proving completion.",
                shouldRetry = stepNumber < maxSteps
            )
        }
        val normalizedScreen = visibleScreenText.lowercase()
        val evidenceTokens = trimmedEvidence.lowercase()
            .split(Regex("[^a-z0-9\\u0C00-\\u0C7F]+"))
            .filter { it.length >= 2 }
        val matchesVisible = normalizedScreen.contains(trimmedEvidence.lowercase()) ||
            (evidenceTokens.isNotEmpty() && evidenceTokens.count { normalizedScreen.contains(it) } >= (evidenceTokens.size + 1) / 2)
        if (!matchesVisible && normalizedScreen.isNotBlank()) {
            return DoneValidationResult.Rejected(
                reason = "Reported evidence \"$trimmedEvidence\" is not visible on the current screen.",
                shouldRetry = stepNumber < maxSteps
            )
        }
        return DoneValidationResult.Accepted(trimmedEvidence)
    }
}

data class WakeWordMatch(
    val matchedTrigger: String,
    val inlineCommand: String?
)

object WakeWordMatcher {

    private val defaultAgentAliases = listOf(
        "hey agent",
        "hay agent",
        "a agent",
        "hi agent",
        "ok agent",
        "okay agent",
        "hey personal agent",
        "hey assistant",
        "హే ఏజెంట్",
        "ఓకే ఏజెంట్",
        "హాయ్ ఏజెంట్"
    )

    fun normalizeForMatch(raw: String): String {
        return raw.lowercase()
            .replace(Regex("""[.,!?;:'"()\-]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    /**
     * Checks if [transcript] contains the active [wakePhrase] (or natural phonetic alias when using
     * the default "Hey Agent" / "OK Agent" phrase) and extracts any trailing one-shot command
     * (e.g., "Hey Agent open YouTube" -> inlineCommand = "open YouTube").
     */
    fun extractWakeMatch(
        transcript: String,
        wakePhrase: String = VoiceSettings.DEFAULT_WAKE_PHRASE
    ): WakeWordMatch? {
        val rawClean = transcript.trim()
        if (rawClean.isBlank()) return null

        val normTranscript = normalizeForMatch(rawClean)
        val normPrimary = normalizeForMatch(wakePhrase).ifBlank { "hey agent" }

        val candidates = linkedSetOf(normPrimary)
        if (normPrimary == "hey agent" || normPrimary == "ok agent" || normPrimary == "okay agent") {
            candidates.addAll(defaultAgentAliases)
        } else if (normPrimary.startsWith("hey ")) {
            val name = normPrimary.removePrefix("hey ").trim()
            if (name.isNotBlank()) {
                candidates.add("hi $name")
                candidates.add("ok $name")
                candidates.add("okay $name")
            }
        } else if (normPrimary.startsWith("ok ")) {
            val name = normPrimary.removePrefix("ok ").trim()
            if (name.isNotBlank()) {
                candidates.add("okay $name")
                candidates.add("hey $name")
            }
        }

        // Longest trigger first so "hey personal agent" matches before "hey agent"
        val sortedCandidates = candidates.sortedByDescending { it.length }
        for (trigger in sortedCandidates) {
            val pattern = Regex("""(?:^|\s)${Regex.escape(trigger)}(?:$|\s)(.*)$""", RegexOption.IGNORE_CASE)
            val m = pattern.find(normTranscript)
            if (m != null) {
                val trailingNorm = m.groupValues[1].trim()
                val trailing = if (trailingNorm.isBlank()) {
                    null
                } else {
                    // Preserve original capitalization/punctuation from raw transcript after the wake words
                    val wordCount = trigger.split(" ").filter { it.isNotBlank() }.size
                    val rawAfter = extractRawAfterTrigger(rawClean, trigger, wordCount)
                    rawAfter?.ifBlank { trailingNorm } ?: trailingNorm
                }
                return WakeWordMatch(
                    matchedTrigger = trigger,
                    inlineCommand = trailing
                )
            }
        }
        return null
    }

    private fun extractRawAfterTrigger(raw: String, normTrigger: String, wordCount: Int): String? {
        val tokens = normTrigger.split(" ").filter { it.isNotBlank() }
        if (tokens.isEmpty() || wordCount <= 0) return null
        val regexStr = tokens.joinToString("""[\s,.:;!?'"-]+""") { Regex.escape(it) }
        val rawRegex = Regex(regexStr, RegexOption.IGNORE_CASE)
        val match = rawRegex.find(raw) ?: return null
        return raw.substring(match.range.last + 1).trimStart(' ', ',', '.', '!', '?', ':', ';', '-').trim()
    }
}

object SpeechTextCleaner {
    private val codeBlockRegex = Regex("""```[\s\S]*?```""")
    private val inlineCodeRegex = Regex("""`([^`]+)`""")
    private val markdownLinkRegex = Regex("""\[([^\]]+)]\(([^)]+)\)""")
    private val urlRegex = Regex("""https?://\S+""")
    private val headingRegex = Regex("""(?m)^\s{0,3}#{1,6}\s+""")
    private val bulletRegex = Regex("""(?m)^\s*[-*•]\s+""")
    private val numberedRegex = Regex("""(?m)^\s*\d+[.)]\s+""")
    private val formattingCharsRegex = Regex("""[*_~>|]+""")
    private val timingSuffixRegex = Regex("""\s*\(\d+(?:\.\d+)?s\)\s*$""")
    private val whitespaceRegex = Regex("""\s+""")

    fun containsTeluguScript(text: String): Boolean {
        return text.any { ch -> ch in '\u0C00'..'\u0C7F' }
    }

    fun resolveEffectiveLanguage(text: String, preferred: VoiceLanguage): VoiceLanguage {
        if (containsTeluguScript(text)) return VoiceLanguage.TE_IN
        return when (preferred) {
            VoiceLanguage.TE_IN -> VoiceLanguage.TE_IN
            VoiceLanguage.EN_IN -> VoiceLanguage.EN_IN
        }
    }

    /**
     * Strips markdown, code fences, URLs, and timing badges so neural/on-device TTS sounds natural.
     */
    fun cleanForSpeech(raw: String): String {
        if (raw.isBlank()) return ""
        var text = raw.trim()
        text = timingSuffixRegex.replace(text, "")
        text = codeBlockRegex.replace(text, " ")
        text = markdownLinkRegex.replace(text, "$1")
        text = urlRegex.replace(text, "")
        text = inlineCodeRegex.replace(text, "$1")
        text = headingRegex.replace(text, "")
        text = bulletRegex.replace(text, "")
        text = numberedRegex.replace(text, "")
        text = formattingCharsRegex.replace(text, "")
        text = text.replace("\n", ". ")
        text = whitespaceRegex.replace(text, " ")
        text = text.replace(Regex("""\.\s*\."""), ".")
        return text.trim()
    }

    /**
     * Extracts complete natural sentences from a streaming buffer, returning (readySentences, remainingBuffer).
     */
    fun extractReadySentences(buffer: String): Pair<List<String>, String> {
        if (buffer.isBlank()) return emptyList<String>() to ""
        val ready = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < buffer.length) {
            val ch = buffer[i]
            val isSentenceEnd = ch == '.' || ch == '!' || ch == '?' || ch == '।' || ch == '\n'
            val nextIsBoundary = i + 1 >= buffer.length || buffer[i + 1].isWhitespace()
            if (isSentenceEnd && nextIsBoundary) {
                val slice = buffer.substring(start, i + 1).trim()
                // Avoid splitting abbreviations like "Mr." or "Dr." or tiny fragments
                val looksLikeAbbrev = slice.endsWith("Mr.", true) ||
                    slice.endsWith("Mrs.", true) ||
                    slice.endsWith("Dr.", true) ||
                    slice.endsWith("Ms.", true) ||
                    slice.endsWith("e.g.", true) ||
                    slice.endsWith("i.e.", true)
                if (!looksLikeAbbrev && slice.length >= 16) {
                    val cleaned = cleanForSpeech(slice)
                    if (cleaned.isNotBlank()) ready.add(cleaned)
                    start = i + 1
                }
            }
            i++
        }
        val remainder = if (start < buffer.length) buffer.substring(start) else ""
        return ready to remainder
    }

    /**
     * Splits a complete response into comfortably sized sentences for fast first-sentence neural synthesis.
     */
    fun splitIntoSpeakableChunks(raw: String, maxChunkChars: Int = 220): List<String> {
        val cleaned = cleanForSpeech(raw)
        if (cleaned.isBlank()) return emptyList()
        if (cleaned.length <= maxChunkChars) return listOf(cleaned)

        val chunks = mutableListOf<String>()
        val (sentences, tail) = extractReadySentences(cleaned)
        val allParts = buildList {
            addAll(sentences)
            if (tail.isNotBlank()) add(tail.trim())
        }
        var current = StringBuilder()
        for (part in allParts) {
            if (current.isEmpty()) {
                if (part.length <= maxChunkChars) {
                    current.append(part)
                } else {
                    chunks.addAll(hardSplitLongSentence(part, maxChunkChars))
                }
            } else if (current.length + 1 + part.length <= maxChunkChars) {
                current.append(' ').append(part)
            } else {
                chunks.add(current.toString().trim())
                current = StringBuilder()
                if (part.length <= maxChunkChars) {
                    current.append(part)
                } else {
                    chunks.addAll(hardSplitLongSentence(part, maxChunkChars))
                }
            }
        }
        if (current.isNotBlank()) chunks.add(current.toString().trim())
        return chunks.filter { it.isNotBlank() }
    }

    private fun hardSplitLongSentence(sentence: String, maxChars: Int): List<String> {
        val result = mutableListOf<String>()
        var remaining = sentence.trim()
        while (remaining.length > maxChars) {
            val cut = remaining.lastIndexOf(' ', maxChars).takeIf { it > maxChars / 2 } ?: maxChars
            result.add(remaining.substring(0, cut).trim())
            remaining = remaining.substring(cut).trim()
        }
        if (remaining.isNotBlank()) result.add(remaining)
        return result
    }
}

enum class UnifiedRouteDecision {
    DIRECT_CHAT,
    LOCAL_TOOL,
    AI_TOOL_ROUTER
}

object SmartIntentClassifier {
    private val conversationalStarterPattern = Regex(
        """^(?:hi|hello|hey|good\s+(?:morning|afternoon|evening|night)|thanks?|thank\s+you|what|why|how|who|when|where|which|explain|tell\s+me\s+about|write|draft|summarize|translate|compare|give\s+me|help\s+me\s+(?:understand|study|learn|prepare|write)|define|difference\s+between|meaning\s+of|solve|calculate|list\s+|suggest|recommend|is\s+|are\s+|does\s+|do\s+|was\s+|were\s+|did\s+|can\s+you\s+(?:explain|tell|write|summarize|translate|help\s+me\s+understand))\b""",
        RegexOption.IGNORE_CASE
    )

    private val explicitPhoneImperativePattern = Regex(
        """\b(?:open\s+[a-z0-9]|launch\s+[a-z0-9]|start\s+[a-z0-9]|play\s+.+\s+(?:on\s+youtube|song|video|music)|play\s+(?:youtube|music|song|the\s+(?:first|second|third|next)\s+(?:video|one)|a\s+video)|call\s+\S+|dial\s+\S+|ring\s+\S+|send\s+(?:a\s+)?(?:whatsapp|sms|text|message)|whatsapp\s+\S+|text\s+\S+\s+(?:that|saying|hi|hello)|set\s+(?:an?\s+)?(?:alarm|timer)|navigate\s+to|directions?\s+to|route\s+to|turn\s+(?:on|off)\s+(?:wi-?fi|bluetooth|flashlight|location|hotspot|dnd|airplane)|toggle\s+(?:wi-?fi|bluetooth|location)|scroll\s+(?:down|up|left|right)|swipe\s+(?:down|up|left|right)|tap\s+(?:on\s+)?\S+|click\s+(?:on\s+)?\S+|press\s+(?:back|home|\S+)|go\s+(?:back|home)|set\s+volume|increase\s+volume|decrease\s+volume|mute\s+(?:phone|media|ring)|unmute|open\s+(?:notifications?|quick\s+settings?|recents?|settings?)|book\s+(?:a\s+|an\s+)?(?:ride|cab|taxi|auto|bike|uber|ola|rapido)|order\s+.+\s+(?:on|from)\s+(?:zomato|swiggy))\b""",
        RegexOption.IGNORE_CASE
    )

    private val generalPhoneActionPattern = Regex(
        """\b(?:open|launch|play|call|dial|send|message|whatsapp|sms|alarm|timer|navigate|directions?|turn\s+on|turn\s+off|toggle|enable|disable|scroll|swipe|tap|click|press|volume|mute|unmute|brightness|notifications?|recents?|quick\s+settings?|search\s+on|find\s+on|order\s+on|book\s+a\s+ride|book\s+a\s+cab|book\s+uber|book\s+ola|book\s+rapido)\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Unified Smart Mode router: decides without any manual Chat/Agent switch whether
     * an utterance goes directly to rich formatted chat, instant deterministic local tool,
     * or the AI Hybrid Tool Router.
     */
    fun decideUnifiedRoute(
        rawInput: String,
        currentForegroundApp: String? = null
    ): UnifiedRouteDecision {
        val text = rawInput.trim()
        if (text.isBlank()) return UnifiedRouteDecision.DIRECT_CHAT

        val localMatch = HybridToolRouting.matchDirectLocalTool(text, currentForegroundApp)
        if (localMatch != null) {
            return if (localMatch.tool == ToolName.REPLY_TO_USER) {
                UnifiedRouteDecision.DIRECT_CHAT
            } else {
                UnifiedRouteDecision.LOCAL_TOOL
            }
        }

        if (conversationalStarterPattern.containsMatchIn(text)) {
            return if (explicitPhoneImperativePattern.containsMatchIn(text)) {
                UnifiedRouteDecision.AI_TOOL_ROUTER
            } else {
                UnifiedRouteDecision.DIRECT_CHAT
            }
        }

        return if (explicitPhoneImperativePattern.containsMatchIn(text) || generalPhoneActionPattern.containsMatchIn(text)) {
            UnifiedRouteDecision.AI_TOOL_ROUTER
        } else {
            UnifiedRouteDecision.DIRECT_CHAT
        }
    }

    /**
     * Determines whether a prompt in Unified Smart Mode or Live Voice should pass through the
     * Hybrid Tool Router vs. go straight to low-latency streaming Gemini chat.
     */
    fun looksLikePhoneAction(rawInput: String, currentForegroundApp: String? = null): Boolean {
        return decideUnifiedRoute(rawInput, currentForegroundApp) != UnifiedRouteDecision.DIRECT_CHAT
    }
}

/**
 * Builds accurate, date-aware, Gemini-style system prompts shared across
 * Built-in Firebase AI Logic, Gemini Native, Anthropic, and OpenAI-Compatible providers.
 */
object SystemPromptBuilder {
    const val INDIAN_ENGLISH_VOICE_GRACE_RULE =
        "The user often speaks in natural Indian English or Telugu-influenced English via voice-to-text, which may contain minor transcription typos or pauses. Always infer their true intent gracefully and respond helpfully instead of failing."

    const val STRICT_ACCURACY_RULE =
        "Never fabricate or guess facts, dates, prices, or current events. If you do not have live web data for a real-time question, state that clearly instead of guessing."

    fun formatCurrentDateTime(nowMillis: Long = System.currentTimeMillis()): String {
        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(nowMillis))
        val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(nowMillis))
        return "Current date: $dateStr, Time: $timeStr"
    }

    fun buildRichAssistantSystemPrompt(
        personalSqlContext: String,
        voiceLanguage: VoiceLanguage,
        isLiveVoice: Boolean = false,
        hasLiveWebSearch: Boolean = false,
        nowMillis: Long = System.currentTimeMillis()
    ): String = buildString {
        appendLine("You are My Personal Agent, a smart, warm, privacy-first personal AI assistant running on the user's Android phone.")
        appendLine(formatCurrentDateTime(nowMillis))
        if (isLiveVoice) {
            appendLine("You are in a real-time hands-free voice conversation. Keep answers natural, conversational, and concise (2 to 4 short spoken sentences unless the user asks for deep detail). Avoid markdown asterisks, code blocks, or long bullet lists.")
        } else {
            appendLine("Format replies like the Google Gemini app: lead with a clear direct answer, then organize details with short headings (## / ###), bullet points, **bold** key terms, code blocks when helpful, and tasteful relevant emojis.")
            appendLine("Always remember and use prior conversation history when answering follow-up questions.")
        }
        appendLine(STRICT_ACCURACY_RULE)
        if (hasLiveWebSearch) {
            appendLine("Live Google Search grounding is enabled for up-to-date facts — rely on verified search results for current events, scores, prices, and recent releases.")
        }
        appendLine(INDIAN_ENGLISH_VOICE_GRACE_RULE)
        when (voiceLanguage) {
            VoiceLanguage.TE_IN -> {
                appendLine("The user's voice language is set to Telugu (తెలుగు). Respond naturally in clear Telugu unless the user explicitly asks for English.")
            }
            VoiceLanguage.EN_IN -> {
                appendLine("Respond naturally in clear English (or Telugu if the user writes or speaks in Telugu).")
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

/**
 * Pure-Kotlin accumulation buffer for Patient Voice Recognition.
 * Appends multiple speech segments cleanly across pauses without duplicating overlapping edges.
 */
class PatientSpeechBuffer {
    private var committed: String = ""
    private var livePartial: String = ""

    fun clear() {
        committed = ""
        livePartial = ""
    }

    fun updatePartial(partial: String): String {
        livePartial = partial.trim()
        return combinedText()
    }

    fun commitSegment(segment: String): String {
        val cleaned = segment.trim()
        livePartial = ""
        if (cleaned.isNotBlank()) {
            committed = mergeWithoutOverlap(committed, cleaned)
        }
        return committed
    }

    fun finalizeAll(): String {
        if (livePartial.isNotBlank()) {
            committed = mergeWithoutOverlap(committed, livePartial)
            livePartial = ""
        }
        return committed.trim()
    }

    fun hasText(): Boolean = committed.isNotBlank() || livePartial.isNotBlank()

    fun combinedText(): String = mergeWithoutOverlap(committed, livePartial)

    companion object {
        fun mergeWithoutOverlap(existing: String, addition: String): String {
            val a = existing.trim()
            val b = addition.trim()
            if (a.isEmpty()) return b
            if (b.isEmpty()) return a
            if (b.startsWith(a, ignoreCase = true)) return b
            if (a.endsWith(b, ignoreCase = true)) return a

            val aWords = a.split(Regex("\\s+"))
            val bWords = b.split(Regex("\\s+"))
            val maxOverlap = min(aWords.size, bWords.size).coerceAtMost(5)
            for (k in maxOverlap downTo 1) {
                val tail = aWords.takeLast(k).joinToString(" ")
                val head = bWords.take(k).joinToString(" ")
                if (tail.equals(head, ignoreCase = true)) {
                    val rest = bWords.drop(k).joinToString(" ")
                    return if (rest.isBlank()) a else "$a $rest"
                }
            }
            return "$a $b"
        }
    }
}

object SmartMemorySuggestionDetector {
    private val memoryCueRegex = Regex(
        """\b(my\s+name\s+is|i\s+am\s+allergic\s+to|i'?m\s+allergic\s+to|my\s+favorite|my\s+favourite|i\s+prefer|i\s+live\s+in|i\s+work\s+(?:at|as)|my\s+birthday\s+is|my\s+dog'?s\s+name|my\s+cat'?s\s+name|i\s+don'?t\s+eat|i'?m\s+vegetarian|i'?m\s+vegan)\b""",
        RegexOption.IGNORE_CASE
    )
    private val taskCueRegex = Regex(
        """\b(i\s+need\s+to|i\s+have\s+to|i\s+must|don'?t\s+let\s+me\s+forget\s+to|gotta\s+|i\s+should\s+(?:buy|pay|call|submit|finish|book|email|send|pick\s+up))\s+(.{4,90})""",
        RegexOption.IGNORE_CASE
    )

    fun detect(userText: String, anchorMessageId: String): MemorySuggestion? {
        val cleaned = userText.trim().replace(Regex("\\s+"), " ")
        if (cleaned.length < 8 || cleaned.length > 260) return null
        if (SafetyGuardrail.detectSensitiveViolation(cleaned) != null) return null
        // Skip if user already issued an explicit SQL command ("Remember that..." / "Add task...")
        if (SqlMemoryCommandParser.parse(cleaned) != null) return null

        val taskMatch = taskCueRegex.find(cleaned)
        if (taskMatch != null) {
            val extracted = taskMatch.groupValues[2].trim().trimEnd('.', '!', '?')
            if (extracted.length >= 4) {
                val title = extracted.replaceFirstChar { it.uppercase() }.take(80)
                return MemorySuggestion(
                    anchorMessageId = anchorMessageId,
                    kind = SuggestionKind.TASK,
                    category = "Task",
                    title = title,
                    content = title
                )
            }
        }

        if (memoryCueRegex.containsMatchIn(cleaned)) {
            val category = when {
                cleaned.contains("prefer", ignoreCase = true) ||
                    cleaned.contains("favorite", ignoreCase = true) ||
                    cleaned.contains("favourite", ignoreCase = true) -> "Preference"
                cleaned.contains("allergic", ignoreCase = true) ||
                    cleaned.contains("vegetarian", ignoreCase = true) ||
                    cleaned.contains("vegan", ignoreCase = true) -> "Health"
                else -> "Fact"
            }
            val title = cleaned.split(" ").take(5).joinToString(" ").replaceFirstChar { it.uppercase() }
            return MemorySuggestion(
                anchorMessageId = anchorMessageId,
                kind = SuggestionKind.MEMORY,
                category = category,
                title = title,
                content = cleaned
            )
        }
        return null
    }
}

object DefaultQuickChips {
    val items: List<String> = listOf(
        "Plan my day",
        "Draft a reply",
        "Open Settings"
    )
}
