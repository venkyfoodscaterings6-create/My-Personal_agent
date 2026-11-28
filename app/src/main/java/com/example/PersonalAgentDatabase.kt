package com.example

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

@Immutable
@Entity(
    tableName = "agent_memories",
    indices = [
        Index(value = ["isPinned", "updatedAt"])
    ]
)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val category: String = "Preference",
    val title: String,
    val content: String,
    val isPinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Immutable
@Entity(
    tableName = "agent_tasks",
    indices = [
        Index(value = ["isCompleted", "createdAt"])
    ]
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val notes: String = "",
    val priority: String = "MEDIUM",
    val dueLabel: String = "",
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Immutable
@Entity(
    tableName = "agent_sql_logs",
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["timestampMillis"])
    ]
)
data class AgentSqlLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: String,
    val mode: String,
    val userPrompt: String,
    val agentResponse: String,
    val toolUsed: String = "",
    val usedGoogleSearch: Boolean = false,
    val timestampMillis: Long = System.currentTimeMillis()
)

@Immutable
@Entity(
    tableName = "chat_threads",
    indices = [
        Index(value = ["updatedAt"])
    ]
)
data class ChatThreadEntity(
    @PrimaryKey val id: String,
    val title: String,
    val preview: String = "",
    val messagesJson: String = "[]",
    val messageCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Indexed per-message table for zero-lag chat loading and strict sessionId isolation.
 * Loading a chat fetches only the latest 30 rows using the (sessionId, timestamp) index,
 * with lazy pagination on scroll-up.
 */
@Immutable
@Entity(
    tableName = "chat_messages",
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["timestamp"]),
        Index(value = ["sessionId", "timestamp"])
    ]
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val timestamp: Long,
    val messageJson: String
)

data class ChatMessagePage(
    val messages: List<ChatMessage>,
    val hasMoreOlder: Boolean,
    val totalCount: Int
)

@Dao
interface AgentMemoryDao {
    @Query("SELECT * FROM agent_memories ORDER BY isPinned DESC, updatedAt DESC")
    fun observeAllMemories(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM agent_memories ORDER BY isPinned DESC, updatedAt DESC LIMIT :limit")
    suspend fun getTopMemories(limit: Int = 15): List<MemoryEntity>

    @Query(
        "SELECT * FROM agent_memories " +
            "WHERE title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%' OR category LIKE '%' || :query || '%' " +
            "ORDER BY isPinned DESC, updatedAt DESC"
    )
    fun searchMemories(query: String): Flow<List<MemoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemory(memory: MemoryEntity): Long

    @Query("UPDATE agent_memories SET isPinned = :pinned, updatedAt = :now WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM agent_memories WHERE id = :id")
    suspend fun deleteMemoryById(id: Long)
}

@Dao
interface AgentTaskDao {
    @Query("SELECT * FROM agent_tasks ORDER BY isCompleted ASC, createdAt DESC")
    fun observeAllTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM agent_tasks WHERE isCompleted = 0 ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getPendingTasks(limit: Int = 12): List<TaskEntity>

    @Query(
        "SELECT * FROM agent_tasks " +
            "WHERE title LIKE '%' || :query || '%' OR notes LIKE '%' || :query || '%' " +
            "ORDER BY isCompleted ASC, createdAt DESC"
    )
    fun searchTasks(query: String): Flow<List<TaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: TaskEntity): Long

    @Query("UPDATE agent_tasks SET isCompleted = :completed WHERE id = :id")
    suspend fun setTaskCompleted(id: Long, completed: Boolean)

    @Query("DELETE FROM agent_tasks WHERE id = :id")
    suspend fun deleteTaskById(id: Long)

    @Query("DELETE FROM agent_tasks WHERE isCompleted = 1")
    suspend fun clearCompletedTasks()
}

@Dao
interface AgentSqlLogDao {
    @Query("SELECT * FROM agent_sql_logs ORDER BY timestampMillis DESC LIMIT 200")
    fun observeRecentLogs(): Flow<List<AgentSqlLogEntity>>

    @Query(
        "SELECT * FROM agent_sql_logs " +
            "WHERE userPrompt LIKE '%' || :query || '%' OR agentResponse LIKE '%' || :query || '%' OR toolUsed LIKE '%' || :query || '%' " +
            "ORDER BY timestampMillis DESC LIMIT 200"
    )
    fun searchLogs(query: String): Flow<List<AgentSqlLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: AgentSqlLogEntity): Long

    @Query("DELETE FROM agent_sql_logs")
    suspend fun clearAllLogs()
}

@Dao
interface ChatThreadDao {
    @Query("SELECT * FROM chat_threads ORDER BY updatedAt DESC")
    fun observeAllThreads(): Flow<List<ChatThreadEntity>>

    @Query(
        "SELECT * FROM chat_threads " +
            "WHERE title LIKE '%' || :query || '%' OR preview LIKE '%' || :query || '%' " +
            "ORDER BY updatedAt DESC"
    )
    fun searchThreads(query: String): Flow<List<ChatThreadEntity>>

    @Query("SELECT * FROM chat_threads WHERE id = :id LIMIT 1")
    suspend fun getThreadById(id: String): ChatThreadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertThread(thread: ChatThreadEntity)

    @Query("DELETE FROM chat_threads WHERE id = :id")
    suspend fun deleteThreadById(id: String)

    @Query("SELECT COUNT(*) FROM chat_threads")
    suspend fun countThreads(): Int
}

@Dao
interface ChatMessageDao {
    @Query(
        "SELECT * FROM chat_messages " +
            "WHERE sessionId = :sessionId " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit"
    )
    suspend fun getRecentMessagesDesc(sessionId: String, limit: Int = 30): List<ChatMessageEntity>

    @Query(
        "SELECT * FROM chat_messages " +
            "WHERE sessionId = :sessionId AND timestamp < :beforeTimestamp " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit"
    )
    suspend fun getMessagesBeforeDesc(
        sessionId: String,
        beforeTimestamp: Long,
        limit: Int = 30
    ): List<ChatMessageEntity>

    @Query("SELECT COUNT(*) FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun countMessagesForSession(sessionId: String): Int

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getAllForSessionAsc(sessionId: String): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(entity: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(entities: List<ChatMessageEntity>)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: String)
}

@Database(
    entities = [
        MemoryEntity::class,
        TaskEntity::class,
        AgentSqlLogEntity::class,
        ChatThreadEntity::class,
        ChatMessageEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class PersonalAgentDatabase : RoomDatabase() {
    abstract fun memoryDao(): AgentMemoryDao
    abstract fun taskDao(): AgentTaskDao
    abstract fun sqlLogDao(): AgentSqlLogDao
    abstract fun chatThreadDao(): ChatThreadDao
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        @Volatile
        private var instance: PersonalAgentDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chat_threads` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `preview` TEXT NOT NULL,
                        `messagesJson` TEXT NOT NULL,
                        `messageCount` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chat_messages` (
                        `id` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `messageJson` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_sessionId` ON `chat_messages` (`sessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_timestamp` ON `chat_messages` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_sessionId_timestamp` ON `chat_messages` (`sessionId`, `timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_memories_isPinned_updatedAt` ON `agent_memories` (`isPinned`, `updatedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_tasks_isCompleted_createdAt` ON `agent_tasks` (`isCompleted`, `createdAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_sql_logs_sessionId` ON `agent_sql_logs` (`sessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_sql_logs_timestampMillis` ON `agent_sql_logs` (`timestampMillis`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_threads_updatedAt` ON `chat_threads` (`updatedAt`)")
            }
        }

        fun getInstance(context: Context): PersonalAgentDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PersonalAgentDatabase::class.java,
                    "personal_agent_sql.db"
                )
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration(dropAllTables = false)
                    .build()
                    .also { instance = it }
            }
        }
    }
}

class AgentSqlRepository(private val database: PersonalAgentDatabase) {

    private val moshi: Moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    private val singleMessageAdapter: JsonAdapter<ChatMessage> by lazy {
        moshi.adapter(ChatMessage::class.java)
    }

    private val legacyListAdapter: JsonAdapter<List<ChatMessage>> by lazy {
        moshi.adapter(Types.newParameterizedType(List::class.java, ChatMessage::class.java))
    }

    // 100% of Room Flow observers run strictly on Dispatchers.IO
    val allMemories: Flow<List<MemoryEntity>> = database.memoryDao()
        .observeAllMemories()
        .flowOn(Dispatchers.IO)

    val allTasks: Flow<List<TaskEntity>> = database.taskDao()
        .observeAllTasks()
        .flowOn(Dispatchers.IO)

    val recentLogs: Flow<List<AgentSqlLogEntity>> = database.sqlLogDao()
        .observeRecentLogs()
        .flowOn(Dispatchers.IO)

    val allThreads: Flow<List<ChatThreadEntity>> = database.chatThreadDao()
        .observeAllThreads()
        .flowOn(Dispatchers.IO)

    fun searchThreads(query: String): Flow<List<ChatThreadEntity>> =
        if (query.isBlank()) allThreads
        else database.chatThreadDao().searchThreads(query.trim()).flowOn(Dispatchers.IO)

    suspend fun getThreadById(id: String): ChatThreadEntity? = withContext(Dispatchers.IO) {
        database.chatThreadDao().getThreadById(id)
    }

    /**
     * Loads only the most recent [limit] (default 30) messages for [sessionId] strictly on Dispatchers.IO.
     * Seamlessly migrates legacy messagesJson blobs into indexed chat_messages rows once if needed.
     */
    suspend fun loadRecentMessagesPage(
        sessionId: String,
        limit: Int = DEFAULT_PAGE_SIZE
    ): ChatMessagePage = withContext(Dispatchers.IO) {
        if (sessionId.isBlank()) {
            return@withContext ChatMessagePage(emptyList(), hasMoreOlder = false, totalCount = 0)
        }
        val msgDao = database.chatMessageDao()
        var totalCount = msgDao.countMessagesForSession(sessionId)

        // Seamless one-time backfill if thread existed in v2 (messagesJson blob) before v3 indexed table
        if (totalCount == 0) {
            val legacyThread = database.chatThreadDao().getThreadById(sessionId)
            val legacyJson = legacyThread?.messagesJson
            if (!legacyJson.isNullOrBlank() && legacyJson != "[]") {
                val decoded = runCatching { legacyListAdapter.fromJson(legacyJson).orEmpty() }
                    .getOrElse { emptyList() }
                if (decoded.isNotEmpty()) {
                    val entities = decoded.map { msg ->
                        ChatMessageEntity(
                            id = msg.id,
                            sessionId = sessionId,
                            timestamp = msg.timestampMillis,
                            messageJson = singleMessageAdapter.toJson(msg)
                        )
                    }
                    msgDao.upsertMessages(entities)
                    totalCount = entities.size
                }
            }
        }

        val descRows = msgDao.getRecentMessagesDesc(sessionId, limit)
        val chronological = descRows.asReversed().mapNotNull { row ->
            runCatching { singleMessageAdapter.fromJson(row.messageJson) }.getOrNull()
        }
        ChatMessagePage(
            messages = chronological,
            hasMoreOlder = totalCount > chronological.size,
            totalCount = totalCount
        )
    }

    /**
     * Lazy load-more pagination for scrolling up in a chat session (strictly on Dispatchers.IO).
     */
    suspend fun loadOlderMessagesPage(
        sessionId: String,
        beforeTimestamp: Long,
        loadedCountSoFar: Int,
        limit: Int = DEFAULT_PAGE_SIZE
    ): ChatMessagePage = withContext(Dispatchers.IO) {
        if (sessionId.isBlank()) {
            return@withContext ChatMessagePage(emptyList(), hasMoreOlder = false, totalCount = loadedCountSoFar)
        }
        val msgDao = database.chatMessageDao()
        val totalCount = msgDao.countMessagesForSession(sessionId)
        val olderDesc = msgDao.getMessagesBeforeDesc(sessionId, beforeTimestamp, limit)
        val chronological = olderDesc.asReversed().mapNotNull { row ->
            runCatching { singleMessageAdapter.fromJson(row.messageJson) }.getOrNull()
        }
        val newLoaded = loadedCountSoFar + chronological.size
        ChatMessagePage(
            messages = chronological,
            hasMoreOlder = olderDesc.size >= limit && totalCount > newLoaded,
            totalCount = totalCount
        )
    }

    /**
     * Persists one or more messages strictly into [sessionId]'s indexed rows and updates thread metadata,
     * running 100% on Dispatchers.IO (never serializing full history blobs on the Main thread).
     */
    suspend fun saveMessagesToSession(
        sessionId: String,
        messagesToUpsert: List<ChatMessage>,
        existingCreatedAt: Long? = null
    ) = withContext(Dispatchers.IO) {
        if (sessionId.isBlank() || messagesToUpsert.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        val msgDao = database.chatMessageDao()
        val entities = messagesToUpsert.map { msg ->
            ChatMessageEntity(
                id = msg.id,
                sessionId = sessionId,
                timestamp = msg.timestampMillis,
                messageJson = singleMessageAdapter.toJson(msg)
            )
        }
        msgDao.upsertMessages(entities)

        val totalCount = msgDao.countMessagesForSession(sessionId)
        val existing = database.chatThreadDao().getThreadById(sessionId)
        val firstUserText = messagesToUpsert.firstOrNull { it.sender == MessageSender.USER && it.text.isNotBlank() }?.text
        val title = when {
            existing != null && existing.title.isNotBlank() && existing.title != "New Conversation" -> existing.title
            !firstUserText.isNullOrBlank() -> firstUserText.replace(Regex("\\s+"), " ").trim().take(44)
            else -> "New Conversation"
        }
        val latestNonEmpty = messagesToUpsert.lastOrNull { it.text.isNotBlank() }?.text
            ?: existing?.preview.orEmpty()
        val preview = latestNonEmpty.replace(Regex("\\s+"), " ").trim().take(72)

        database.chatThreadDao().upsertThread(
            ChatThreadEntity(
                id = sessionId,
                title = title,
                preview = preview,
                messagesJson = "[]", // Indexed chat_messages table is source of truth (zero giant JSON lag)
                messageCount = totalCount,
                createdAt = existing?.createdAt ?: existingCreatedAt ?: now,
                updatedAt = now
            )
        )
    }

    /**
     * Updates an action card and optionally appends a completion message strictly inside [sessionId]
     * on Dispatchers.IO — safe to invoke even after the user has switched to a New Chat!
     */
    suspend fun updateActionInSession(
        sessionId: String,
        actionId: String,
        status: ApprovalStatus,
        completedSteps: Int,
        activeStep: Int,
        blockedReason: String? = null,
        durationLabel: String? = null,
        appendChatMessage: ChatMessage? = null
    ) = withContext(Dispatchers.IO) {
        if (sessionId.isBlank()) return@withContext
        val msgDao = database.chatMessageDao()
        val recentRows = msgDao.getRecentMessagesDesc(sessionId, limit = 40)
        val toUpsert = mutableListOf<ChatMessage>()
        for (row in recentRows) {
            val decoded = runCatching { singleMessageAdapter.fromJson(row.messageJson) }.getOrNull() ?: continue
            if (decoded.actionPlan?.actionId == actionId) {
                toUpsert.add(
                    decoded.copy(
                        approvalStatus = status,
                        completedStepsCount = completedSteps,
                        activeStepIndex = activeStep,
                        isBlockedBySafety = status == ApprovalStatus.BLOCKED || decoded.isBlockedBySafety,
                        blockedReason = blockedReason ?: decoded.blockedReason,
                        durationLabel = durationLabel ?: decoded.durationLabel
                    )
                )
            }
        }
        if (appendChatMessage != null) {
            toUpsert.add(appendChatMessage)
        }
        if (toUpsert.isNotEmpty()) {
            saveMessagesToSession(sessionId, toUpsert)
        }
    }

    suspend fun upsertThread(
        id: String,
        messages: List<ChatMessage>,
        messagesJson: String? = null,
        existingCreatedAt: Long? = null
    ) = withContext(Dispatchers.IO) {
        saveMessagesToSession(
            sessionId = id,
            messagesToUpsert = messages,
            existingCreatedAt = existingCreatedAt
        )
    }

    suspend fun deleteThread(id: String) = withContext(Dispatchers.IO) {
        database.chatMessageDao().deleteMessagesForSession(id)
        database.chatThreadDao().deleteThreadById(id)
    }

    suspend fun countThreads(): Int = withContext(Dispatchers.IO) {
        database.chatThreadDao().countThreads()
    }

    fun searchMemories(query: String): Flow<List<MemoryEntity>> =
        if (query.isBlank()) allMemories
        else database.memoryDao().searchMemories(query.trim()).flowOn(Dispatchers.IO)

    fun searchTasks(query: String): Flow<List<TaskEntity>> =
        if (query.isBlank()) allTasks
        else database.taskDao().searchTasks(query.trim()).flowOn(Dispatchers.IO)

    fun searchLogs(query: String): Flow<List<AgentSqlLogEntity>> =
        if (query.isBlank()) recentLogs
        else database.sqlLogDao().searchLogs(query.trim()).flowOn(Dispatchers.IO)

    suspend fun getPendingTasks(limit: Int = 10): List<TaskEntity> = withContext(Dispatchers.IO) {
        database.taskDao().getPendingTasks(limit)
    }

    suspend fun getTopMemories(limit: Int = 10): List<MemoryEntity> = withContext(Dispatchers.IO) {
        database.memoryDao().getTopMemories(limit)
    }

    suspend fun addMemory(
        category: String,
        title: String,
        content: String,
        isPinned: Boolean = false
    ): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        database.memoryDao().upsertMemory(
            MemoryEntity(
                category = category.trim().ifBlank { "Preference" },
                title = title.trim().ifBlank { content.trim().take(36) },
                content = content.trim(),
                isPinned = isPinned,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun toggleMemoryPin(id: Long, pinned: Boolean) = withContext(Dispatchers.IO) {
        database.memoryDao().setPinned(id, pinned)
    }

    suspend fun deleteMemory(id: Long) = withContext(Dispatchers.IO) {
        database.memoryDao().deleteMemoryById(id)
    }

    suspend fun addTask(
        title: String,
        notes: String = "",
        priority: String = "MEDIUM",
        dueLabel: String = ""
    ): Long = withContext(Dispatchers.IO) {
        database.taskDao().upsertTask(
            TaskEntity(
                title = title.trim(),
                notes = notes.trim(),
                priority = priority.trim().uppercase().ifBlank { "MEDIUM" },
                dueLabel = dueLabel.trim()
            )
        )
    }

    suspend fun setTaskCompleted(id: Long, completed: Boolean) = withContext(Dispatchers.IO) {
        database.taskDao().setTaskCompleted(id, completed)
    }

    suspend fun deleteTask(id: Long) = withContext(Dispatchers.IO) {
        database.taskDao().deleteTaskById(id)
    }

    suspend fun clearCompletedTasks() = withContext(Dispatchers.IO) {
        database.taskDao().clearCompletedTasks()
    }

    suspend fun recordInteractionLog(
        sessionId: String,
        mode: String,
        userPrompt: String,
        agentResponse: String,
        toolUsed: String = "",
        usedGoogleSearch: Boolean = false
    ) = withContext(Dispatchers.IO) {
        if (userPrompt.isBlank() && agentResponse.isBlank()) return@withContext
        database.sqlLogDao().insertLog(
            AgentSqlLogEntity(
                sessionId = sessionId,
                mode = mode,
                userPrompt = userPrompt.take(500),
                agentResponse = agentResponse.take(1500),
                toolUsed = toolUsed,
                usedGoogleSearch = usedGoogleSearch
            )
        )
    }

    suspend fun clearAllLogs() = withContext(Dispatchers.IO) {
        database.sqlLogDao().clearAllLogs()
    }

    /**
     * Builds a compact prompt context block from the user's SQL memories and pending tasks
     * strictly on Dispatchers.IO so Gemini and Custom AI providers personalize every response.
     */
    suspend fun buildPersonalContextBlock(): String = withContext(Dispatchers.IO) {
        val memories = database.memoryDao().getTopMemories(limit = 12)
        val tasks = database.taskDao().getPendingTasks(limit = 8)
        if (memories.isEmpty() && tasks.isEmpty()) return@withContext ""

        buildString {
            if (memories.isNotEmpty()) {
                appendLine("USER SAVED MEMORIES (from on-device SQL database):")
                memories.forEach { m ->
                    val pinTag = if (m.isPinned) "[Pinned] " else ""
                    appendLine("- $pinTag[${m.category}] ${m.title}: ${m.content}")
                }
            }
            if (tasks.isNotEmpty()) {
                appendLine("USER OPEN TASKS (from on-device SQL database):")
                tasks.forEach { t ->
                    val due = if (t.dueLabel.isNotBlank()) " (Due: ${t.dueLabel})" else ""
                    appendLine("- [${t.priority}] ${t.title}$due")
                }
            }
        }.trim()
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 30
    }
}

sealed class SqlDirectIntent {
    data class SaveMemory(val category: String, val title: String, val content: String) : SqlDirectIntent()
    data class AddTask(val title: String, val priority: String = "MEDIUM") : SqlDirectIntent()
    data object ListTasks : SqlDirectIntent()
    data object ListMemories : SqlDirectIntent()
}

object SqlMemoryCommandParser {
    private val rememberRegex = Regex(
        """^(?:please\s+)?(?:remember(?:\s+that)?|save\s+(?:to\s+)?(?:my\s+)?memory(?:\s+that)?|note\s+that)\s+(.+?)[.!?]*$""",
        RegexOption.IGNORE_CASE
    )
    private val addTaskRegex = Regex(
        """^(?:please\s+)?(?:add\s+(?:a\s+)?(?:task|to-?do|reminder)\s*(?:to\s+)?|remind\s+me\s+to\s+)(.+?)[.!?]*$""",
        RegexOption.IGNORE_CASE
    )
    private val listTasksRegex = Regex(
        """^(?:what\s+are|show|list|read)\s+(?:my\s+)?(?:open\s+|pending\s+)?(?:tasks|to-?dos?|reminders?)[.!?]*$""",
        RegexOption.IGNORE_CASE
    )
    private val listMemoriesRegex = Regex(
        """^(?:what\s+do\s+you\s+remember(?:\s+about\s+me)?|show\s+(?:my\s+)?memories|list\s+(?:my\s+)?memories)[.!?]*$""",
        RegexOption.IGNORE_CASE
    )

    fun parse(rawInput: String): SqlDirectIntent? {
        val text = rawInput.trim().replace(Regex("\\s+"), " ")
        if (text.isBlank()) return null

        val rememberMatch = rememberRegex.matchEntire(text)
        if (rememberMatch != null) {
            val fact = rememberMatch.groupValues[1].trim()
            if (fact.length >= 3) {
                val category = when {
                    fact.contains("prefer", ignoreCase = true) || fact.contains("like", ignoreCase = true) -> "Preference"
                    fact.contains("every ", ignoreCase = true) || fact.contains("morning", ignoreCase = true) -> "Routine"
                    else -> "Fact"
                }
                val shortTitle = fact.split(" ").take(5).joinToString(" ").replaceFirstChar { it.uppercase() }
                return SqlDirectIntent.SaveMemory(category = category, title = shortTitle, content = fact)
            }
        }

        val taskMatch = addTaskRegex.matchEntire(text)
        if (taskMatch != null) {
            val taskText = taskMatch.groupValues[1].trim()
            if (taskText.length >= 2) {
                val priority = when {
                    taskText.contains("urgent", ignoreCase = true) || taskText.contains("asap", ignoreCase = true) -> "HIGH"
                    else -> "MEDIUM"
                }
                return SqlDirectIntent.AddTask(
                    title = taskText.replaceFirstChar { it.uppercase() },
                    priority = priority
                )
            }
        }

        if (listTasksRegex.matches(text)) return SqlDirectIntent.ListTasks
        if (listMemoriesRegex.matches(text)) return SqlDirectIntent.ListMemories
        return null
    }
}
