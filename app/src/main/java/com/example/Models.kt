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
    APPROVALS("approvals", "Approvals"),
    SETUP("setup", "Setup & Test"),
    SETTINGS("settings", "Settings / API Keys")
}

enum class SettingsFocusSection {
    PROVIDERS
}

enum class AgentMode {
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
    EN_IN("en-IN", "EN", "English"),
    TE_IN("te-IN", "తెలుగు", "Telugu (తెలుగు)")
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
    val alwaysAskApproval: Boolean = true,
    val blockSensitiveFinancial: Boolean = true,
    val showStepPlan: Boolean = true,
    val instantRunLowRiskAppLaunch: Boolean = false
)

@Immutable
data class VoiceSettings(
    val autoSendVoiceCommands: Boolean = true,
    val speakRepliesAloud: Boolean = true,
    val voiceLanguage: VoiceLanguage = VoiceLanguage.EN_IN,
    val persistentAskNotificationEnabled: Boolean = true,
    val wakeWordEnabled: Boolean = false,
    val wakePhrase: String = DEFAULT_WAKE_PHRASE
) {
    companion object {
        const val DEFAULT_WAKE_PHRASE = "Hey Agent"
        val PRESET_WAKE_PHRASES = listOf("Hey Agent", "OK Agent", "Hey Buddy")
    }
}

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
    val reason: String = ""
)

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
        get() = tool in setOf(
            ToolName.CALL_CONTACT,
            ToolName.SEND_MESSAGE,
            ToolName.UI_AUTOMATE
        )

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
    val durationLabel: String? = null
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
                """\b(bank(ing|s)?|net\s*banking|neft|imps|rtgs|ifsc|wire\s+transfer|transfer\s+money|send\s+money|pay\s+bill|debit\s+card|credit\s+card|cvv|card\s+number)\b""",
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

    fun detectSensitiveViolation(vararg texts: String?): String? {
        val combined = texts.filterNotNull().joinToString("\n")
        if (combined.isBlank()) return null
        return sensitivePatterns.firstOrNull { it.regex.containsMatchIn(combined) }?.category
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
            "settings" -> listOf("com.android.settings")
            "clock", "alarm" -> listOf("com.google.android.deskclock", "com.android.deskclock")
            "camera" -> listOf("com.android.camera", "com.google.android.GoogleCamera")
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

    /**
     * Ultra-fast local router for greetings, deterministic native tools, and dedicated YouTube/messaging patterns.
     * Strictly NEVER launches an app on greetings and NEVER swallows compound multi-step commands into open_app.
     */
    fun matchDirectLocalTool(rawCommand: String): RoutedToolCall? {
        val text = rawCommand.trim().replace(Regex("\\s+"), " ")
        if (text.isBlank()) return null

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
            "tap_on_screen", "tap_screen", "tap", "click" -> ToolName.TAP_ON_SCREEN
            "scroll_screen", "scroll", "swipe" -> ToolName.SCROLL_SCREEN
            "press_home", "go_home", "home" -> ToolName.PRESS_HOME
            "press_back", "go_back", "back" -> ToolName.PRESS_BACK
            "open_recents", "recents", "recent_apps" -> ToolName.OPEN_RECENTS
            "open_notifications", "notifications" -> ToolName.OPEN_NOTIFICATIONS
            "open_quick_settings", "quick_settings" -> ToolName.OPEN_QUICK_SETTINGS
            "set_volume", "volume" -> ToolName.SET_VOLUME
            "ui_automate", "automate" -> ToolName.UI_AUTOMATE
            else -> return null
        }

        val appPretty = if (messagingApp?.lowercase()?.contains("whatsapp") == true || messagingApp?.lowercase() == "wa") {
            "WhatsApp"
        } else {
            "SMS"
        }

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
            ToolName.TAP_ON_SCREEN -> "Tap \"${targetText ?: fallbackGoal}\""
            ToolName.SCROLL_SCREEN -> "Scroll ${direction ?: "down"}"
            ToolName.PRESS_HOME -> "Go to Home screen"
            ToolName.PRESS_BACK -> "Go Back"
            ToolName.OPEN_RECENTS -> "Open Recent Apps"
            ToolName.OPEN_NOTIFICATIONS -> "Open Notifications"
            ToolName.OPEN_QUICK_SETTINGS -> "Open Quick Settings"
            ToolName.SET_VOLUME -> "Set ${stream ?: "media"} volume to ${levelPercent ?: 50}%"
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
                    "Place phone call after your approval"
                )
                ToolName.SEND_MESSAGE -> listOf(
                    "Resolve contact ${contactOrNumber ?: "recipient"}",
                    "Open $appPretty chat with message: \"${messageText.orEmpty().take(80)}\"",
                    "Tap the Send button and verify delivery"
                )
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
            ToolName.CALL_CONTACT, ToolName.SEND_MESSAGE -> RiskLevel.MEDIUM
            ToolName.UI_AUTOMATE -> RiskLevel.fromRaw(str("riskLevel") ?: "MEDIUM")
            else -> RiskLevel.LOW
        }

        return RoutedToolCall(
            tool = canonicalTool,
            appName = appName,
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

    fun formatFailure(reason: String, elapsed: String? = null): String {
        val cleaned = reason.trim()
        val body = when {
            cleaned.isBlank() -> "I couldn't complete that task."
            cleaned.startsWith("I couldn't", ignoreCase = true) ||
                cleaned.startsWith("Could not", ignoreCase = true) ||
                cleaned.startsWith("Unable to", ignoreCase = true) ||
                cleaned.startsWith("Enable ", ignoreCase = true) -> cleaned
            else -> "I couldn't complete that: $cleaned"
        }
        return if (!elapsed.isNullOrBlank() && !body.contains(elapsed)) "$body ($elapsed)" else body
    }
}

data class WakeWordMatch(
    val matchedTrigger: String,
    val inlineCommand: String?
)

object WakeWordMatcher {

    private val defaultAgentAliases = listOf(
        "hey agent",
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

object DefaultQuickChips {
    val items: List<String> = listOf(
        "Plan my day",
        "Draft a reply",
        "Open Settings"
    )
}
