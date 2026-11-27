package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.example.AgentSqlLogEntity
import com.example.MemoryEntity
import com.example.TaskEntity
import com.example.ui.theme.LocalAgentGlassColors
import com.example.ui.theme.MonoCodeStyle
import com.example.ui.theme.glassPanel
import com.example.ui.theme.nestedGlassCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoryDatabaseScreen(
    memories: List<MemoryEntity>,
    tasks: List<TaskEntity>,
    logs: List<AgentSqlLogEntity>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onAddMemory: (category: String, title: String, content: String, pinned: Boolean) -> Unit,
    onToggleMemoryPin: (Long, Boolean) -> Unit,
    onDeleteMemory: (Long) -> Unit,
    onAddTask: (title: String, notes: String, priority: String, dueLabel: String) -> Unit,
    onToggleTaskCompleted: (Long, Boolean) -> Unit,
    onDeleteTask: (Long) -> Unit,
    onClearCompletedTasks: () -> Unit,
    onClearLogs: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showAddMemoryDialog by remember { mutableStateOf(false) }
    var showAddTaskDialog by remember { mutableStateOf(false) }

    val glass = LocalAgentGlassColors.current

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding(),
        containerColor = Color.Transparent,
        floatingActionButton = {
            if (selectedTab == 0 || selectedTab == 1) {
                FloatingActionButton(
                    onClick = {
                        if (selectedTab == 0) showAddMemoryDialog = true
                        else showAddTaskDialog = true
                    },
                    containerColor = glass.accentBlue,
                    contentColor = Color.White,
                    modifier = Modifier.testTag("sql_fab_add")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = if (selectedTab == 0) "Add Memory" else "Add Task"
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Frosted Glass Top bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(glass.topBarFill)
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("memory_db_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back to Chat"
                            )
                        }
                        Column {
                            Text(
                                text = "Agent Memory & SQL DB",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "On-device SQLite · auto-injected into Gemini & Agent",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Rounded.Storage,
                        contentDescription = null,
                        tint = glass.accentBlue,
                        modifier = Modifier
                            .padding(end = 14.dp)
                            .size(22.dp)
                    )
                }

                HorizontalDivider(thickness = 0.5.dp, color = glass.hairlineDivider)
            }

            // SQL Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text("Search SQL tables (memories, tasks, logs)…") },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .testTag("sql_search_input")
            )

            // 3-tab SQL table switcher with live row counts
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .glassPanel(cornerRadius = 16.dp)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val tabs = listOf(
                    "Memories (${memories.size})",
                    "Tasks (${tasks.count { !it.isCompleted }})",
                    "Logs (${logs.size})"
                )
                tabs.forEachIndexed { idx, label ->
                    val selected = selectedTab == idx
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0f)
                            )
                            .clickable { selectedTab = idx }
                            .testTag("sql_tab_$idx"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            when (selectedTab) {
                0 -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (memories.isEmpty()) {
                            item {
                                EmptySqlHintCard(
                                    title = "No memories saved yet",
                                    body = "Tap + or say \"Remember that I wake up at 6:30 AM\" in Chat or Live Voice. Your agent automatically uses these SQL memories to personalize every reply."
                                )
                            }
                        } else {
                            items(memories, key = { it.id }) { mem ->
                                MemoryRowCard(
                                    memory = mem,
                                    onTogglePin = { onToggleMemoryPin(mem.id, !mem.isPinned) },
                                    onDelete = { onDeleteMemory(mem.id) }
                                )
                            }
                        }
                        item { Spacer(modifier = Modifier.height(72.dp)) }
                    }
                }

                1 -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (tasks.any { it.isCompleted }) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = onClearCompletedTasks) {
                                        Text("Clear Completed")
                                    }
                                }
                            }
                        }
                        if (tasks.isEmpty()) {
                            item {
                                EmptySqlHintCard(
                                    title = "No tasks in SQL database",
                                    body = "Tap + or say \"Add task call dentist tomorrow\" in Chat or Live Voice. Ask \"What are my tasks?\" anytime."
                                )
                            }
                        } else {
                            items(tasks, key = { it.id }) { task ->
                                TaskRowCard(
                                    task = task,
                                    onToggle = { onToggleTaskCompleted(task.id, !task.isCompleted) },
                                    onDelete = { onDeleteTask(task.id) }
                                )
                            }
                        }
                        item { Spacer(modifier = Modifier.height(72.dp)) }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (logs.isNotEmpty()) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = onClearLogs) {
                                        Text("Clear SQL Logs")
                                    }
                                }
                            }
                        }
                        if (logs.isEmpty()) {
                            item {
                                EmptySqlHintCard(
                                    title = "No SQL interaction logs yet",
                                    body = "Every Chat, Google Search Grounding query, Live Voice turn, and phone automation is indexed here for instant SQL search."
                                )
                            }
                        } else {
                            items(logs, key = { it.id }) { log ->
                                SqlLogRowCard(log = log)
                            }
                        }
                        item { Spacer(modifier = Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }

    if (showAddMemoryDialog) {
        AddMemoryDialog(
            onDismiss = { showAddMemoryDialog = false },
            onSave = { cat, title, content, pinned ->
                onAddMemory(cat, title, content, pinned)
                showAddMemoryDialog = false
            }
        )
    }

    if (showAddTaskDialog) {
        AddTaskDialog(
            onDismiss = { showAddTaskDialog = false },
            onSave = { title, notes, priority, due ->
                onAddTask(title, notes, priority, due)
                showAddTaskDialog = false
            }
        )
    }
}

@Composable
private fun EmptySqlHintCard(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(cornerRadius = 18.dp)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MemoryRowCard(
    memory: MemoryEntity,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit
) {
    val glass = LocalAgentGlassColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(
                cornerRadius = 18.dp,
                fillColor = if (memory.isPinned) glass.accentBlue.copy(alpha = 0.16f) else glass.panelFill,
                borderColorOverride = if (memory.isPinned) glass.accentBlue.copy(alpha = 0.55f) else null
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = memory.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Text(
                    text = memory.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row {
                IconButton(onClick = onTogglePin, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = if (memory.isPinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
                        contentDescription = "Pin memory",
                        tint = if (memory.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "Delete memory",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Text(
            text = memory.content,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TaskRowCard(
    task: TaskEntity,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(cornerRadius = 18.dp)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = if (task.isCompleted) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = "Toggle task",
            tint = if (task.isCompleted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.titleSmall.copy(
                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None
                ),
                color = if (task.isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
            val meta = buildList {
                add(task.priority)
                if (task.dueLabel.isNotBlank()) add("Due: ${task.dueLabel}")
                if (task.notes.isNotBlank()) add(task.notes)
            }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Outlined.DeleteOutline,
                contentDescription = "Delete task",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun SqlLogRowCard(log: AgentSqlLogEntity) {
    val timeText = remember(log.timestampMillis) {
        SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(log.timestampMillis))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(cornerRadius = 18.dp)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = log.mode,
                    style = MonoCodeStyle,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                if (log.usedGoogleSearch) {
                    Icon(
                        imageVector = Icons.Rounded.Public,
                        contentDescription = "Google Search Grounded",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(14.dp)
                    )
                }
                if (log.toolUsed.isNotBlank()) {
                    Text(
                        text = "· ${log.toolUsed}",
                        style = MonoCodeStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = timeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (log.userPrompt.isNotBlank()) {
            Text(
                text = "You: ${log.userPrompt}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (log.agentResponse.isNotBlank()) {
            Text(
                text = log.agentResponse,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddMemoryDialog(
    onDismiss: () -> Unit,
    onSave: (category: String, title: String, content: String, pinned: Boolean) -> Unit
) {
    val categories = listOf("Preference", "Fact", "Routine", "Contact Note")
    var category by remember { mutableStateOf("Preference") }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var pinned by remember { mutableStateOf(true) }

    val glass = LocalAgentGlassColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = glass.dialogFill,
        shape = RoundedCornerShape(24.dp),
        title = { Text("Save Agent Memory") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    categories.forEach { cat ->
                        InputChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat) }
                        )
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Short Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Detail to remember") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { pinned = !pinned }
                ) {
                    Checkbox(checked = pinned, onCheckedChange = { pinned = it })
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Pin to top of AI prompt context")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(category, title, content, pinned) },
                enabled = content.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddTaskDialog(
    onDismiss: () -> Unit,
    onSave: (title: String, notes: String, priority: String, dueLabel: String) -> Unit
) {
    val priorities = listOf("LOW", "MEDIUM", "HIGH")
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("MEDIUM") }
    var dueLabel by remember { mutableStateOf("") }

    val glass = LocalAgentGlassColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = glass.dialogFill,
        shape = RoundedCornerShape(24.dp),
        title = { Text("Add Personal Task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Task Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    priorities.forEach { p ->
                        InputChip(
                            selected = priority == p,
                            onClick = { priority = p },
                            label = { Text(p) }
                        )
                    }
                }
                OutlinedTextField(
                    value = dueLabel,
                    onValueChange = { dueLabel = it },
                    label = { Text("Due (optional, e.g. Today 6 PM)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (optional)") },
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title, notes, priority, dueLabel) },
                enabled = title.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
