package com.example

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Immutable
@Entity(tableName = "agent_memories")
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
@Entity(tableName = "agent_tasks")
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
@Entity(tableName = "agent_sql_logs")
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
@Entity(tableName = "chat_threads")
data class ChatThreadEntity(
    @PrimaryKey val id: String,
    val title: String,
    val preview: String = "",
    val messagesJson: String = "[]",
    val messageCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
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

@Database(
    entities = [
        MemoryEntity::class,
        TaskEntity::class,
        AgentSqlLogEntity::class,
        ChatThreadEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class PersonalAgentDatabase : RoomDatabase() {
    abstract fun memoryDao(): AgentMemoryDao
    abstract fun taskDao(): AgentTaskDao
    abstract fun sqlLogDao(): AgentSqlLogDao
    abstract fun chatThreadDao(): ChatThreadDao

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

        fun getInstance(context: Context): PersonalAgentDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PersonalAgentDatabase::class.java,
                    "personal_agent_sql.db"
                ).addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration(dropAllTables = false)
                    .build()
                    .also { instance = it }
            }
        }
    }
}

class AgentSqlRepository(private val database: PersonalAgentDatabase) {
    val allMemories: Flow<List<MemoryEntity>> = database.memoryDao().observeAllMemories()
    val allTasks: Flow<List<TaskEntity>> = database.taskDao().observeAllTasks()
    val recentLogs: Flow<List<AgentSqlLogEntity>> = database.sqlLogDao().observeRecentLogs()
    val allThreads: Flow<List<ChatThreadEntity>> = database.chatThreadDao().observeAllThreads()

    fun searchThreads(query: String): Flow<List<ChatThreadEntity>> =
        if (query.isBlank()) allThreads else database.chatThreadDao().searchThreads(query.trim())

    suspend fun getThreadById(id: String): ChatThreadEntity? =
        database.chatThreadDao().getThreadById(id)

    suspend fun upsertThread(
        id: String,
        messages: List<ChatMessage>,
        messagesJson: String,
        existingCreatedAt: Long? = null
    ) {
        val now = System.currentTimeMillis()
        val existing = database.chatThreadDao().getThreadById(id)
        val firstUserText = messages.firstOrNull { it.sender == MessageSender.USER && it.text.isNotBlank() }?.text
        val title = when {
            !firstUserText.isNullOrBlank() -> firstUserText.replace(Regex("\\s+"), " ").trim().take(44)
            existing != null && existing.title.isNotBlank() -> existing.title
            else -> "New Conversation"
        }
        val latestNonEmpty = messages.lastOrNull { it.text.isNotBlank() }?.text.orEmpty()
        val preview = latestNonEmpty.replace(Regex("\\s+"), " ").trim().take(72)
        database.chatThreadDao().upsertThread(
            ChatThreadEntity(
                id = id,
                title = title,
                preview = preview,
                messagesJson = messagesJson,
                messageCount = messages.size,
                createdAt = existing?.createdAt ?: existingCreatedAt ?: now,
                updatedAt = now
            )
        )
    }

    suspend fun deleteThread(id: String) {
        database.chatThreadDao().deleteThreadById(id)
    }

    suspend fun countThreads(): Int = database.chatThreadDao().countThreads()

    fun searchMemories(query: String): Flow<List<MemoryEntity>> =
        if (query.isBlank()) allMemories else database.memoryDao().searchMemories(query.trim())

    fun searchTasks(query: String): Flow<List<TaskEntity>> =
        if (query.isBlank()) allTasks else database.taskDao().searchTasks(query.trim())

    fun searchLogs(query: String): Flow<List<AgentSqlLogEntity>> =
        if (query.isBlank()) recentLogs else database.sqlLogDao().searchLogs(query.trim())

    suspend fun addMemory(
        category: String,
        title: String,
        content: String,
        isPinned: Boolean = false
    ): Long {
        val now = System.currentTimeMillis()
        return database.memoryDao().upsertMemory(
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

    suspend fun toggleMemoryPin(id: Long, pinned: Boolean) {
        database.memoryDao().setPinned(id, pinned)
    }

    suspend fun deleteMemory(id: Long) {
        database.memoryDao().deleteMemoryById(id)
    }

    suspend fun addTask(
        title: String,
        notes: String = "",
        priority: String = "MEDIUM",
        dueLabel: String = ""
    ): Long {
        return database.taskDao().upsertTask(
            TaskEntity(
                title = title.trim(),
                notes = notes.trim(),
                priority = priority.trim().uppercase().ifBlank { "MEDIUM" },
                dueLabel = dueLabel.trim()
            )
        )
    }

    suspend fun setTaskCompleted(id: Long, completed: Boolean) {
        database.taskDao().setTaskCompleted(id, completed)
    }

    suspend fun deleteTask(id: Long) {
        database.taskDao().deleteTaskById(id)
    }

    suspend fun clearCompletedTasks() {
        database.taskDao().clearCompletedTasks()
    }

    suspend fun recordInteractionLog(
        sessionId: String,
        mode: String,
        userPrompt: String,
        agentResponse: String,
        toolUsed: String = "",
        usedGoogleSearch: Boolean = false
    ) {
        if (userPrompt.isBlank() && agentResponse.isBlank()) return
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

    suspend fun clearAllLogs() {
        database.sqlLogDao().clearAllLogs()
    }

    /**
     * Builds a compact prompt context block from the user's SQL memories and pending tasks
     * so Gemini and Custom AI providers personalize every response automatically.
     */
    suspend fun buildPersonalContextBlock(): String {
        val memories = database.memoryDao().getTopMemories(limit = 12)
        val tasks = database.taskDao().getPendingTasks(limit = 8)
        if (memories.isEmpty() && tasks.isEmpty()) return ""

        return buildString {
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
