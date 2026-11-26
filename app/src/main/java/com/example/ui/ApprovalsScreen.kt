package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ActionPlan
import com.example.ApprovalRecord
import com.example.ApprovalStatus
import com.example.R
import com.example.RiskLevel
import com.example.ui.theme.LocalAgentSemanticColors
import com.example.ui.theme.MonoCodeStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ApprovalsFilter {
    PENDING,
    HISTORY
}

@Composable
fun ApprovalsScreen(
    approvalHistory: List<ApprovalRecord>,
    onBack: () -> Unit,
    onApproveAction: (String) -> Unit,
    onEditActionInstruction: (String, String) -> Unit,
    onCancelAction: (String) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedFilter by rememberSaveable { mutableStateOf(ApprovalsFilter.PENDING) }
    var showClearDialog by remember { mutableStateOf(false) }
    var editingRecord by remember { mutableStateOf<ApprovalRecord?>(null) }

    val pendingList = remember(approvalHistory) {
        approvalHistory.filter { it.status == ApprovalStatus.PENDING }
    }
    val historyList = remember(approvalHistory) {
        approvalHistory.filter { it.status != ApprovalStatus.PENDING }
    }
    val displayed = if (selectedFilter == ApprovalsFilter.PENDING) pendingList else historyList

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Flat Top Bar with Back Arrow
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
                    modifier = Modifier.testTag("approvals_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back to Chat"
                    )
                }
                Text(
                    text = "Approvals",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (approvalHistory.isNotEmpty()) {
                IconButton(
                    onClick = { showClearDialog = true },
                    modifier = Modifier.testTag("clear_history_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = stringResource(R.string.action_clear_history),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilterChip(
                selected = selectedFilter == ApprovalsFilter.PENDING,
                onClick = { selectedFilter = ApprovalsFilter.PENDING },
                label = { Text("Pending (${pendingList.size})") },
                modifier = Modifier.testTag("filter_pending_chip")
            )
            FilterChip(
                selected = selectedFilter == ApprovalsFilter.HISTORY,
                onClick = { selectedFilter = ApprovalsFilter.HISTORY },
                label = { Text("History (${historyList.size})") },
                modifier = Modifier.testTag("filter_history_chip")
            )
        }

        if (displayed.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (selectedFilter == ApprovalsFilter.PENDING) {
                            "No pending approvals"
                        } else {
                            "No approval history yet"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (selectedFilter == ApprovalsFilter.PENDING) {
                            "Actions waiting for your review appear here."
                        } else {
                            "Approved, cancelled, completed, failed, and blocked actions appear here."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    items = displayed,
                    key = { it.actionId },
                    contentType = { if (it.status == ApprovalStatus.PENDING) "pending" else "history" }
                ) { record ->
                    FlatApprovalRow(
                        record = record,
                        onApprove = { onApproveAction(record.actionId) },
                        onEdit = { editingRecord = record },
                        onCancel = { onCancelAction(record.actionId) }
                    )
                }
                item { Spacer(modifier = Modifier.height(12.dp)) }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear approval history?") },
            text = { Text("This deletes all pending and past approval records stored on this device.") },
            confirmButton = {
                Button(
                    onClick = {
                        showClearDialog = false
                        onClearHistory()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("confirm_clear_history_button")
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    editingRecord?.let { rec ->
        EditStepDialog(
            plan = ActionPlan(
                actionId = rec.actionId,
                title = rec.title,
                riskLevel = rec.riskLevel,
                steps = rec.steps,
                editedInstruction = rec.editedInstruction,
                isDemo = rec.isDemo
            ),
            onDismiss = { editingRecord = null },
            onSave = { updated ->
                onEditActionInstruction(rec.actionId, updated)
                editingRecord = null
            }
        )
    }
}

@Composable
private fun FlatApprovalRow(
    record: ApprovalRecord,
    onApprove: () -> Unit,
    onEdit: () -> Unit,
    onCancel: () -> Unit
) {
    val semantic = LocalAgentSemanticColors.current
    val (riskBg, riskFg) = when (record.riskLevel) {
        RiskLevel.LOW -> semantic.lowRiskContainer to semantic.lowRiskContent
        RiskLevel.MEDIUM -> semantic.mediumRiskContainer to semantic.mediumRiskContent
        RiskLevel.HIGH -> semantic.highRiskContainer to semantic.highRiskContent
    }
    val dateText = remember(record.timestampMillis) {
        SimpleDateFormat("MMM d · h:mm a", Locale.getDefault()).format(Date(record.timestampMillis))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(14.dp)
            .testTag("approval_record_${record.actionId}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(riskBg)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Shield,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = riskFg
                        )
                        Text(
                            text = record.riskLevel.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = riskFg,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Text(
                    text = dateText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                StatusPill(status = record.status)
                if (record.status == ApprovalStatus.PENDING) {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = "Edit step",
                            modifier = Modifier.size(17.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Text(
            text = record.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )

        if (record.steps.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                record.steps.forEachIndexed { idx, step ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "${idx + 1}.",
                            style = MonoCodeStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                        Text(
                            text = step,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        if (!record.editedInstruction.isNullOrBlank()) {
            Text(
                text = "Edited: ${record.editedInstruction}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (!record.notes.isNullOrBlank()) {
            Text(
                text = record.notes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (record.status == ApprovalStatus.PENDING) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onApprove,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                ) {
                    Text("Approve")
                }
                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}
