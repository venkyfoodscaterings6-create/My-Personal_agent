package com.example

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ActionLogEntry(
    val timestampMillis: Long = System.currentTimeMillis(),
    val summary: String,
    val status: String
)

/**
 * Process-wide singleton repository for per-session conversation history and action logs.
 *
 * Enforces strict sessionId isolation:
 * - Follow-ups from VoiceOverlayActivity / Quick Settings / notification attach to the active sessionId.
 * - Background tasks or late completions tagged with an old sessionId are stored ONLY in their
 *   original session bucket and NEVER bleed into a newly opened New Chat or another switched session.
 */
object ConversationRepository {

    private const val MAX_ACTION_LOGS = 20

    private val lock = Any()

    private val sessionMessages = LinkedHashMap<String, List<ChatMessage>>()
    private val sessionActionLogs = LinkedHashMap<String, List<ActionLogEntry>>()

    private val _activeSessionId = MutableStateFlow("")
    val activeSessionId: StateFlow<String> = _activeSessionId.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _actionLogs = MutableStateFlow<List<ActionLogEntry>>(emptyList())
    val actionLogs: StateFlow<List<ActionLogEntry>> = _actionLogs.asStateFlow()

    fun syncFromPersisted(sessionId: String, persistedMessages: List<ChatMessage>) {
        if (sessionId.isBlank()) return
        synchronized(lock) {
            val currentActive = _activeSessionId.value
            // Never allow a stale persisted emission from an old session to overwrite an active switched session.
            if (currentActive.isNotBlank() && currentActive != sessionId) {
                sessionMessages[sessionId] = persistedMessages
                return
            }
            _activeSessionId.value = sessionId
            sessionMessages[sessionId] = persistedMessages
            _messages.value = persistedMessages
            _actionLogs.value = sessionActionLogs[sessionId].orEmpty()
        }
    }

    /**
     * Appends one or more messages into the specified [targetSessionId] (or active session if null).
     * If [targetSessionId] differs from [activeSessionId], updates ONLY that session's isolated bucket
     * and never mutates the active UI's [messages] flow.
     */
    suspend fun appendMessages(
        storage: LocalStorage,
        newMessages: List<ChatMessage>,
        newApproval: ApprovalRecord? = null,
        targetSessionId: String? = null
    ): List<ChatMessage> {
        if (newMessages.isEmpty() && newApproval == null) return _messages.value
        val effectiveSessionId = targetSessionId?.takeIf { it.isNotBlank() } ?: _activeSessionId.value
        val updated = if (effectiveSessionId.isNotBlank()) {
            appendToSessionInternal(effectiveSessionId, newMessages).first
        } else {
            appendMessagesInMemory(newMessages)
        }
        storage.appendChatAndApproval(
            newMessages = newMessages,
            newApproval = newApproval,
            targetSessionId = effectiveSessionId.takeIf { it.isNotBlank() }
        )
        return updated
    }

    /**
     * Strictly session-isolated in-memory append.
     * Returns true if [targetSessionId] is the currently active session (and UI state updated),
     * or false if the message was isolated to an inactive/background session without touching active chat.
     */
    fun appendToSessionInMemory(
        targetSessionId: String,
        newMessages: List<ChatMessage>
    ): Boolean {
        if (targetSessionId.isBlank() || newMessages.isEmpty()) return false
        return appendToSessionInternal(targetSessionId, newMessages).second
    }

    private fun appendToSessionInternal(
        targetSessionId: String,
        newMessages: List<ChatMessage>
    ): Pair<List<ChatMessage>, Boolean> {
        return synchronized(lock) {
            val base = sessionMessages[targetSessionId].orEmpty()
            val existingIds = base.mapTo(HashSet()) { it.id }
            val merged = base + newMessages.filter { it.id !in existingIds }
            sessionMessages[targetSessionId] = merged
            val isActive = _activeSessionId.value.isBlank() || _activeSessionId.value == targetSessionId
            if (isActive) {
                if (_activeSessionId.value.isBlank()) {
                    _activeSessionId.value = targetSessionId
                }
                _messages.value = merged
            }
            merged to isActive
        }
    }

    /**
     * In-memory append into the currently active session.
     */
    fun appendMessagesInMemory(newMessages: List<ChatMessage>): List<ChatMessage> {
        return synchronized(lock) {
            val activeId = _activeSessionId.value
            val base = if (activeId.isNotBlank()) sessionMessages[activeId] ?: _messages.value else _messages.value
            val existingIds = base.mapTo(HashSet()) { it.id }
            val merged = base + newMessages.filter { it.id !in existingIds }
            if (activeId.isNotBlank()) {
                sessionMessages[activeId] = merged
            }
            _messages.value = merged
            merged
        }
    }

    fun getMessagesForSession(sessionId: String): List<ChatMessage> {
        if (sessionId.isBlank()) return emptyList()
        return synchronized(lock) {
            sessionMessages[sessionId].orEmpty()
        }
    }

    fun recordActionLog(
        summary: String,
        status: String,
        targetSessionId: String? = null
    ) {
        val cleaned = summary.trim()
        if (cleaned.isBlank()) return
        synchronized(lock) {
            val effectiveSession = targetSessionId?.takeIf { it.isNotBlank() } ?: _activeSessionId.value
            val currentLogs = if (effectiveSession.isNotBlank()) {
                sessionActionLogs[effectiveSession].orEmpty()
            } else {
                _actionLogs.value
            }
            val next = (currentLogs + ActionLogEntry(summary = cleaned, status = status))
                .takeLast(MAX_ACTION_LOGS)
            if (effectiveSession.isNotBlank()) {
                sessionActionLogs[effectiveSession] = next
            }
            if (effectiveSession.isBlank() || effectiveSession == _activeSessionId.value) {
                _actionLogs.value = next
            }
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
     * Combines the latest persisted/in-memory messages for the active session so callers
     * (including VoiceOverlayActivity) never drop messages written milliseconds earlier
     * by AgentAccessibilityService in the same session.
     */
    fun mergeWithPersisted(
        persistedMessages: List<ChatMessage>,
        sessionId: String? = null
    ): List<ChatMessage> {
        return synchronized(lock) {
            val activeId = _activeSessionId.value
            if (!sessionId.isNullOrBlank() && activeId.isNotBlank() && sessionId != activeId) {
                return@synchronized _messages.value
            }
            if (persistedMessages.isEmpty()) return@synchronized _messages.value
            val byId = LinkedHashMap<String, ChatMessage>()
            persistedMessages.forEach { byId[it.id] = it }
            _messages.value.forEach { msg ->
                if (!byId.containsKey(msg.id)) {
                    byId[msg.id] = msg
                }
            }
            val merged = byId.values.toList()
            if (activeId.isNotBlank()) {
                sessionMessages[activeId] = merged
            }
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
     * Cleanly isolates active messages and action logs to ONLY [newSessionId].
     */
    fun resetSessionExplicitly(newSessionId: String, initialMessages: List<ChatMessage> = emptyList()) {
        synchronized(lock) {
            _activeSessionId.value = newSessionId
            sessionMessages[newSessionId] = initialMessages
            _messages.value = initialMessages
            _actionLogs.value = sessionActionLogs[newSessionId].orEmpty()
        }
    }

    fun clearForTest(sessionId: String = "test-session") {
        synchronized(lock) {
            sessionMessages.clear()
            sessionActionLogs.clear()
            _activeSessionId.value = sessionId
            sessionMessages[sessionId] = emptyList()
            _messages.value = emptyList()
            _actionLogs.value = emptyList()
        }
    }
}
