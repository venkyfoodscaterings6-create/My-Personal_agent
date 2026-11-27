package com.example

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

private val Context.personalAgentDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "my_personal_agent_prefs"
)

data class PersistedAppState(
    val sessionId: String,
    val providers: List<AiProvider>,
    val providerResolvedUrls: Map<String, String>,
    val chatMessages: List<ChatMessage>,
    val approvalHistory: List<ApprovalRecord>,
    val safetyRules: SafetyRules,
    val voiceSettings: VoiceSettings,
    val quickActionChips: List<String>,
    val themePreference: ThemePreference,
    val agentMode: AgentMode,
    val connectionTestPassed: Boolean,
    val builtInGeminiSettings: BuiltInGeminiSettings = BuiltInGeminiSettings()
)

class LocalStorage(private val context: Context) {

    private val moshi: Moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    private val providerListAdapter: JsonAdapter<List<AiProvider>> by lazy {
        moshi.adapter(Types.newParameterizedType(List::class.java, AiProvider::class.java))
    }
    private val chatListAdapter: JsonAdapter<List<ChatMessage>> by lazy {
        moshi.adapter(Types.newParameterizedType(List::class.java, ChatMessage::class.java))
    }
    private val approvalListAdapter: JsonAdapter<List<ApprovalRecord>> by lazy {
        moshi.adapter(Types.newParameterizedType(List::class.java, ApprovalRecord::class.java))
    }
    private val stringListAdapter: JsonAdapter<List<String>> by lazy {
        moshi.adapter(Types.newParameterizedType(List::class.java, String::class.java))
    }
    private val safetyRulesAdapter: JsonAdapter<SafetyRules> by lazy {
        moshi.adapter(SafetyRules::class.java)
    }

    private object Keys {
        val SESSION_ID = stringPreferencesKey("session_id")
        val PROVIDERS_JSON = stringPreferencesKey("providers_json")
        val CHAT_MESSAGES_JSON = stringPreferencesKey("chat_messages_json")
        val APPROVAL_HISTORY_JSON = stringPreferencesKey("approval_history_json")
        val SAFETY_RULES_JSON = stringPreferencesKey("safety_rules_json")
        val QUICK_CHIPS_JSON = stringPreferencesKey("quick_chips_json")
        val THEME_PREFERENCE = stringPreferencesKey("theme_preference")
        val AGENT_MODE = stringPreferencesKey("agent_mode")
        val CONNECTION_TEST_PASSED = booleanPreferencesKey("connection_test_passed")
        val AUTO_SEND_VOICE = booleanPreferencesKey("auto_send_voice_commands")
        val SPEAK_REPLIES_ALOUD = booleanPreferencesKey("speak_replies_aloud")
        val VOICE_LANGUAGE = stringPreferencesKey("voice_language")
        val PERSISTENT_ASK_NOTIFICATION = booleanPreferencesKey("persistent_ask_notification_enabled")
        val WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
        val WAKE_PHRASE = stringPreferencesKey("wake_phrase")
        val USE_GEMINI_NEURAL_VOICE = booleanPreferencesKey("use_gemini_neural_voice")
        val VOICE_PERSONA = stringPreferencesKey("voice_persona")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val WAKE_SENSITIVITY = stringPreferencesKey("wake_sensitivity")
        val USE_BUILT_IN_GEMINI = booleanPreferencesKey("use_built_in_gemini")
        val BUILT_IN_GEMINI_MODEL = stringPreferencesKey("built_in_gemini_model")
        val GOOGLE_SEARCH_ENABLED = booleanPreferencesKey("google_search_enabled")
        val INJECT_SQL_MEMORY = booleanPreferencesKey("inject_sql_memory")
    }

    // All DataStore reads, Moshi decoding, and URL normalization run strictly on Dispatchers.IO.
    val stateFlow: Flow<PersistedAppState> = context.personalAgentDataStore.data
        .map { prefs ->
            val sessionId = prefs[Keys.SESSION_ID].orEmpty().ifBlank { UUID.randomUUID().toString() }
            val providers = decodeList(prefs[Keys.PROVIDERS_JSON], providerListAdapter)
            val resolvedMap = providers.associate { p ->
                p.id to EndpointResolver.resolveChatEndpoint(p.baseUrl, p.apiFormat, p.modelName)
            }
            val chatMessages = decodeList(prefs[Keys.CHAT_MESSAGES_JSON], chatListAdapter)
            val approvals = decodeList(prefs[Keys.APPROVAL_HISTORY_JSON], approvalListAdapter)
            val safetyRules = prefs[Keys.SAFETY_RULES_JSON]?.let { json ->
                runCatching { safetyRulesAdapter.fromJson(json) }.getOrNull()
            } ?: SafetyRules()
            val quickChips = prefs[Keys.QUICK_CHIPS_JSON]?.let { json ->
                runCatching { stringListAdapter.fromJson(json) }.getOrNull()
            } ?: DefaultQuickChips.items

            val theme = prefs[Keys.THEME_PREFERENCE]?.let { raw ->
                runCatching { ThemePreference.valueOf(raw) }.getOrNull()
            } ?: ThemePreference.SYSTEM

            val mode = when (
                prefs[Keys.AGENT_MODE]?.let { raw ->
                    runCatching { AgentMode.valueOf(raw) }.getOrNull()
                }
            ) {
                AgentMode.AGENT_CONTROL -> AgentMode.AGENT_CONTROL
                else -> AgentMode.CHAT_ONLY
            }

            val voiceLanguage = prefs[Keys.VOICE_LANGUAGE]?.let { raw ->
                runCatching { VoiceLanguage.valueOf(raw) }.getOrNull()
            } ?: VoiceLanguage.EN_IN

            val voicePersona = prefs[Keys.VOICE_PERSONA]?.let { raw ->
                runCatching { GeminiVoicePersona.valueOf(raw) }.getOrNull()
            } ?: GeminiVoicePersona.KORE

            val wakeSensitivity = prefs[Keys.WAKE_SENSITIVITY]?.let { raw ->
                runCatching { WakeSensitivity.valueOf(raw) }.getOrNull()
            } ?: WakeSensitivity.BALANCED

            val speechRate = (prefs[Keys.SPEECH_RATE] ?: 1.0f).coerceIn(0.8f, 1.35f)

            val voiceSettings = VoiceSettings(
                autoSendVoiceCommands = prefs[Keys.AUTO_SEND_VOICE] ?: true,
                speakRepliesAloud = prefs[Keys.SPEAK_REPLIES_ALOUD] ?: true,
                voiceLanguage = voiceLanguage,
                persistentAskNotificationEnabled = prefs[Keys.PERSISTENT_ASK_NOTIFICATION] ?: true,
                wakeWordEnabled = prefs[Keys.WAKE_WORD_ENABLED] ?: false,
                wakePhrase = prefs[Keys.WAKE_PHRASE]?.takeIf { it.isNotBlank() }
                    ?: VoiceSettings.DEFAULT_WAKE_PHRASE,
                useGeminiNeuralVoice = prefs[Keys.USE_GEMINI_NEURAL_VOICE] ?: true,
                voicePersona = voicePersona,
                speechRate = speechRate,
                wakeSensitivity = wakeSensitivity
            )

            val builtInModel = prefs[Keys.BUILT_IN_GEMINI_MODEL]?.let { raw ->
                runCatching { GeminiBuiltInModel.valueOf(raw) }.getOrNull()
            } ?: GeminiBuiltInModel.FLASH

            val builtInGeminiSettings = BuiltInGeminiSettings(
                useBuiltInGemini = prefs[Keys.USE_BUILT_IN_GEMINI] ?: true,
                selectedModel = builtInModel,
                googleSearchEnabled = prefs[Keys.GOOGLE_SEARCH_ENABLED] ?: true,
                injectSqlMemory = prefs[Keys.INJECT_SQL_MEMORY] ?: true
            )

            PersistedAppState(
                sessionId = sessionId,
                providers = providers,
                providerResolvedUrls = resolvedMap,
                chatMessages = chatMessages,
                approvalHistory = approvals,
                safetyRules = safetyRules,
                voiceSettings = voiceSettings,
                quickActionChips = quickChips,
                themePreference = theme,
                agentMode = mode,
                connectionTestPassed = prefs[Keys.CONNECTION_TEST_PASSED] ?: false,
                builtInGeminiSettings = builtInGeminiSettings
            )
        }
        .flowOn(Dispatchers.IO)

    suspend fun ensureSessionId() = withContext(Dispatchers.IO) {
        context.personalAgentDataStore.edit { prefs ->
            if (prefs[Keys.SESSION_ID].isNullOrBlank()) {
                prefs[Keys.SESSION_ID] = UUID.randomUUID().toString()
            }
        }
    }

    suspend fun saveProviders(providers: List<AiProvider>) = withContext(Dispatchers.IO) {
        val json = providerListAdapter.toJson(providers)
        context.personalAgentDataStore.edit { prefs ->
            prefs[Keys.PROVIDERS_JSON] = json
        }
    }

    suspend fun saveChatMessages(messages: List<ChatMessage>) = withContext(Dispatchers.IO) {
        val json = chatListAdapter.toJson(messages)
        context.personalAgentDataStore.edit { prefs ->
            prefs[Keys.CHAT_MESSAGES_JSON] = json
        }
    }

    suspend fun saveApprovalHistory(records: List<ApprovalRecord>) = withContext(Dispatchers.IO) {
        val json = approvalListAdapter.toJson(records)
        context.personalAgentDataStore.edit { prefs ->
            prefs[Keys.APPROVAL_HISTORY_JSON] = json
        }
    }

    suspend fun saveChatAndApprovals(
        messages: List<ChatMessage>,
        records: List<ApprovalRecord>
    ) = withContext(Dispatchers.IO) {
        val chatJson = chatListAdapter.toJson(messages)
        val approvalsJson = approvalListAdapter.toJson(records)
        context.personalAgentDataStore.edit { prefs ->
            prefs[Keys.CHAT_MESSAGES_JSON] = chatJson
            prefs[Keys.APPROVAL_HISTORY_JSON] = approvalsJson
        }
    }

    suspend fun saveSafetyRules(rules: SafetyRules) = withContext(Dispatchers.IO) {
        val json = safetyRulesAdapter.toJson(rules)
        context.personalAgentDataStore.edit { prefs ->
            prefs[Keys.SAFETY_RULES_JSON] = json
        }
    }

    suspend fun saveVoiceSettings(settings: VoiceSettings) = withContext(Dispatchers.IO) {
        context.personalAgentDataStore.edit { prefs ->
            prefs[Keys.AUTO_SEND_VOICE] = settings.autoSendVoiceCommands
            prefs[Keys.SPEAK_REPLIES_ALOUD] = settings.speakRepliesAloud
            prefs[Keys.VOICE_LANGUAGE] = settings.voiceLanguage.name
            prefs[Keys.PERSISTENT_ASK_NOTIFICATION] = settings.persistentAskNotificationEnabled
            prefs[Keys.WAKE_WORD_ENABLED] = settings.wakeWordEnabled
            prefs[Keys.WAKE_PHRASE] = settings.wakePhrase.ifBlank { VoiceSettings.DEFAULT_WAKE_PHRASE }
            prefs[Keys.USE_GEMINI_NEURAL_VOICE] = settings.useGeminiNeuralVoice
            prefs[Keys.VOICE_PERSONA] = settings.voicePersona.name
            prefs[Keys.SPEECH_RATE] = settings.speechRate.coerceIn(0.8f, 1.35f)
            prefs[Keys.WAKE_SENSITIVITY] = settings.wakeSensitivity.name
        }
    }

    suspend fun saveBuiltInGeminiSettings(settings: BuiltInGeminiSettings) = withContext(Dispatchers.IO) {
        context.personalAgentDataStore.edit { prefs ->
            prefs[Keys.USE_BUILT_IN_GEMINI] = settings.useBuiltInGemini
            prefs[Keys.BUILT_IN_GEMINI_MODEL] = settings.selectedModel.name
            prefs[Keys.GOOGLE_SEARCH_ENABLED] = settings.googleSearchEnabled
            prefs[Keys.INJECT_SQL_MEMORY] = settings.injectSqlMemory
        }
    }

    suspend fun readLatestState(): PersistedAppState = withContext(Dispatchers.IO) {
        stateFlow.first()
    }

    suspend fun appendChatAndApproval(
        newMessages: List<ChatMessage>,
        newApproval: ApprovalRecord? = null
    ) = withContext(Dispatchers.IO) {
        context.personalAgentDataStore.edit { prefs ->
            val existingMessages = decodeList(prefs[Keys.CHAT_MESSAGES_JSON], chatListAdapter)
            val updatedMessages = existingMessages + newMessages
            prefs[Keys.CHAT_MESSAGES_JSON] = chatListAdapter.toJson(updatedMessages)

            if (newApproval != null) {
                val existingApprovals = decodeList(prefs[Keys.APPROVAL_HISTORY_JSON], approvalListAdapter)
                val updatedApprovals = listOf(newApproval) + existingApprovals.filterNot { it.actionId == newApproval.actionId }
                prefs[Keys.APPROVAL_HISTORY_JSON] = approvalListAdapter.toJson(updatedApprovals)
            }
        }
    }

    suspend fun updateActionStatusInStorage(
        actionId: String,
        status: ApprovalStatus,
        completedSteps: Int,
        activeStep: Int,
        blockedReason: String? = null,
        notes: String? = null,
        durationLabel: String? = null,
        appendChatMessage: ChatMessage? = null
    ) = withContext(Dispatchers.IO) {
        context.personalAgentDataStore.edit { prefs ->
            val existingMessages = decodeList(prefs[Keys.CHAT_MESSAGES_JSON], chatListAdapter)
            val mappedMessages = existingMessages.map { msg ->
                if (msg.actionPlan?.actionId == actionId) {
                    msg.copy(
                        approvalStatus = status,
                        completedStepsCount = completedSteps,
                        activeStepIndex = activeStep,
                        isBlockedBySafety = status == ApprovalStatus.BLOCKED || msg.isBlockedBySafety,
                        blockedReason = blockedReason ?: msg.blockedReason,
                        durationLabel = durationLabel ?: msg.durationLabel
                    )
                } else {
                    msg
                }
            }
            val finalMessages = if (appendChatMessage != null) mappedMessages + appendChatMessage else mappedMessages
            prefs[Keys.CHAT_MESSAGES_JSON] = chatListAdapter.toJson(finalMessages)

            val existingApprovals = decodeList(prefs[Keys.APPROVAL_HISTORY_JSON], approvalListAdapter)
            val finalApprovals = existingApprovals.map { rec ->
                if (rec.actionId == actionId) {
                    rec.copy(
                        status = status,
                        notes = notes ?: rec.notes
                    )
                } else {
                    rec
                }
            }
            prefs[Keys.APPROVAL_HISTORY_JSON] = approvalListAdapter.toJson(finalApprovals)
        }
    }

    suspend fun saveQuickActionChips(chips: List<String>) = withContext(Dispatchers.IO) {
        val json = stringListAdapter.toJson(chips)
        context.personalAgentDataStore.edit { prefs ->
            prefs[Keys.QUICK_CHIPS_JSON] = json
        }
    }

    suspend fun saveThemePreference(theme: ThemePreference) = withContext(Dispatchers.IO) {
        context.personalAgentDataStore.edit { prefs ->
            prefs[Keys.THEME_PREFERENCE] = theme.name
        }
    }

    suspend fun saveAgentMode(mode: AgentMode) = withContext(Dispatchers.IO) {
        context.personalAgentDataStore.edit { prefs ->
            prefs[Keys.AGENT_MODE] = mode.name
        }
    }

    suspend fun saveConnectionTestPassed(passed: Boolean) = withContext(Dispatchers.IO) {
        context.personalAgentDataStore.edit { prefs ->
            prefs[Keys.CONNECTION_TEST_PASSED] = passed
        }
    }

    suspend fun startNewChatSession(): String = withContext(Dispatchers.IO) {
        val newId = UUID.randomUUID().toString()
        val emptyJson = chatListAdapter.toJson(emptyList())
        context.personalAgentDataStore.edit { prefs ->
            prefs[Keys.CHAT_MESSAGES_JSON] = emptyJson
            prefs[Keys.SESSION_ID] = newId
        }
        newId
    }

    suspend fun switchActiveChatSession(
        sessionId: String,
        messages: List<ChatMessage>
    ) = withContext(Dispatchers.IO) {
        val json = chatListAdapter.toJson(messages)
        context.personalAgentDataStore.edit { prefs ->
            prefs[Keys.SESSION_ID] = sessionId
            prefs[Keys.CHAT_MESSAGES_JSON] = json
        }
    }

    fun encodeChatMessages(messages: List<ChatMessage>): String {
        return chatListAdapter.toJson(messages)
    }

    fun decodeChatMessages(json: String?): List<ChatMessage> {
        return decodeList(json, chatListAdapter)
    }

    private fun <T> decodeList(
        json: String?,
        adapter: JsonAdapter<List<T>>
    ): List<T> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching { adapter.fromJson(json).orEmpty() }.getOrElse { emptyList() }
    }
}
