package com.example

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ActionLogEntry(
    val timestampMillis: Long = System.currentTimeMillis(),
    val summary: String,
    val status: String
)

/**
 * Process-wide singleton repository for the active conversation session and completed action logs.
 *
 * Shared by MainActivity / AgentViewModel, VoiceOverlayActivity (Quick Settings tile, notification,
 * wake word), and AgentAccessibilityService so follow-up commands ALWAYS append to and read from
 * the exact same active chat session without ever resetting or losing context.
 */
object ConversationRepository {

    private const val MAX_ACTION_LOGS = 20

    private val lock = Any()

    private val _activeSessionId = MutableStateFlow("")
    val activeSessionId: StateFlow<String> = _activeSessionId.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _actionLogs = MutableStateFlow<List<ActionLogEntry>>(emptyList())
    val actionLogs: StateFlow<List<ActionLogEntry>> = _actionLogs.asStateFlow()

    fun syncFromPersisted(sessionId: String, persistedMessages: List<ChatMessage>) {
        synchronized(lock) {
            if (sessionId.isNotBlank()) {
                _activeSessionId.value = sessionId
            }
            _messages.value = persistedMessages
        }
    }

    /**
     * Appends one or more messages into the active session (in-memory + persisted DataStore).
     * Never resets sessionId.
     */
    suspend fun appendMessages(
        storage: LocalStorage,
        newMessages: List<ChatMessage>,
        newApproval: ApprovalRecord? = null
    ): List<ChatMessage> {
        if (newMessages.isEmpty() && newApproval == null) return _messages.value
        val updated = synchronized(lock) {
            val base = _messages.value
            val existingIds = base.mapTo(HashSet()) { it.id }
            val toAdd = newMessages.filter { it.id !in existingIds }
            val merged = base + toAdd
            _messages.value = merged
            merged
        }
        storage.appendChatAndApproval(newMessages, newApproval)
        return updated
    }

    /**
     * In-memory append for deterministic unit testing or synchronous state alignment
     * without resetting the active session.
     */
    fun appendMessagesInMemory(newMessages: List<ChatMessage>): List<ChatMessage> {
        return synchronized(lock) {
            val base = _messages.value
            val existingIds = base.mapTo(HashSet()) { it.id }
            val merged = base + newMessages.filter { it.id !in existingIds }
            _messages.value = merged
            merged
        }
    }

    fun recordActionLog(summary: String, status: String) {
        val cleaned = summary.trim()
        if (cleaned.isBlank()) return
        synchronized(lock) {
            val next = (_actionLogs.value + ActionLogEntry(summary = cleaned, status = status))
                .takeLast(MAX_ACTION_LOGS)
            _actionLogs.value = next
        }
    }

    fun getRecentMessages(maxMessages: Int = 10): List<ChatMessage> {
        val current = _messages.value
        return ConversationMemoryHelper.buildRecentChatMessages(current, maxTurns = maxMessages)
    }

    fun getRecentActionLogs(maxLogs: Int = 6): List<ActionLogEntry> {
        return _actionLogs.value.takeLast(maxLogs)
    }

    /**
     * Combines the latest persisted/in-memory messages so callers (including VoiceOverlayActivity)
     * never drop messages written milliseconds earlier by AgentAccessibilityService.
     */
    fun mergeWithPersisted(persistedMessages: List<ChatMessage>): List<ChatMessage> {
        return synchronized(lock) {
            if (persistedMessages.isEmpty()) return@synchronized _messages.value
            val byId = LinkedHashMap<String, ChatMessage>()
            persistedMessages.forEach { byId[it.id] = it }
            _messages.value.forEach { msg ->
                if (!byId.containsKey(msg.id)) {
                    byId[msg.id] = msg
                }
            }
            val merged = byId.values.toList()
            _messages.value = merged
            merged
        }
    }

    fun formatRecentConversationBlock(
        fallbackMessages: List<ChatMessage> = emptyList(),
        maxMessages: Int = 10
    ): String {
        val sourceMessages = if (fallbackMessages.isNotEmpty()) {
            mergeWithPersisted(fallbackMessages)
        } else {
            _messages.value
        }
        val recentTurns = ConversationMemoryHelper.buildRecentChatMessages(sourceMessages, maxTurns = maxMessages)
        val recentLogs = _actionLogs.value.takeLast(6)

        if (recentTurns.isEmpty() && recentLogs.isEmpty()) return ""

        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        return buildString {
            if (recentTurns.isNotEmpty()) {
                appendLine("RECENT CONVERSATION (last ${recentTurns.size} messages):")
                recentTurns.forEach { msg ->
                    val role = if (msg.sender == MessageSender.USER) "User" else "Agent"
                    val snippet = msg.text.replace(Regex("\\s+"), " ").trim().take(200)
                    appendLine("- $role: $snippet")
                }
            }
            if (recentLogs.isNotEmpty()) {
                appendLine("RECENT COMPLETED ACTION LOGS:")
                recentLogs.forEach { log ->
                    val ts = timeFmt.format(Date(log.timestampMillis))
                    appendLine("- [$ts] (${log.status}) ${log.summary.take(180)}")
                }
            }
        }.trim()
    }

    /**
     * Only called when the user explicitly clicks "New Chat" or switches saved chat threads.
     * NEVER called from VoiceOverlayActivity, Quick Settings tile, or notifications.
     */
    fun resetSessionExplicitly(newSessionId: String, initialMessages: List<ChatMessage> = emptyList()) {
        synchronized(lock) {
            _activeSessionId.value = newSessionId
            _messages.value = initialMessages
            _actionLogs.value = emptyList()
        }
    }

    fun clearForTest(sessionId: String = "test-session") {
        synchronized(lock) {
            _activeSessionId.value = sessionId
            _messages.value = emptyList()
            _actionLogs.value = emptyList()
        }
    }
}
