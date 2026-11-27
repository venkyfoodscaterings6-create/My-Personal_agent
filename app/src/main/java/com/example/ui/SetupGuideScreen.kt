package com.example.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.AgentUiState
import com.example.EndpointTestDetail
import com.example.R
import com.example.SelfTestItem
import com.example.SettingsFocusSection
import com.example.ui.theme.LocalAgentGlassColors
import com.example.ui.theme.LocalAgentSemanticColors
import com.example.ui.theme.MonoCodeStyle
import com.example.ui.theme.glassPanel
import com.example.ui.theme.nestedGlassCard

@Composable
fun SetupGuideScreen(
    uiState: AgentUiState,
    onBack: () -> Unit,
    onJumpToSettings: (SettingsFocusSection) -> Unit,
    onRunConnectionTest: () -> Unit,
    onRunFullSelfTest: () -> Unit,
    onRequestMicrophonePermission: () -> Unit,
    onRequestContactsPermission: () -> Unit,
    onRequestCallPhonePermission: () -> Unit,
    onRequestNotificationsPermission: () -> Unit,
    onOpenOverlayPermissionSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val totalSteps = 3
    val percent = (uiState.completedSetupSteps * 100) / totalSteps
    val perms = uiState.permissionsStatus

    val glass = LocalAgentGlassColors.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        // Frosted Glass Top Bar with Back Arrow
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
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("setup_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back to Chat"
                    )
                }
                Text(
                    text = stringResource(R.string.setup_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            HorizontalDivider(thickness = 0.5.dp, color = glass.hairlineDivider)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Frosted 3-step progress summary
            item(key = "progress") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassPanel(cornerRadius = 20.dp)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${uiState.completedSetupSteps} of $totalSteps steps completed",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("setup_progress_label")
                        )
                        Text(
                            text = "$percent%",
                            style = MonoCodeStyle.copy(color = MaterialTheme.colorScheme.primary)
                        )
                    }
                    LinearProgressIndicator(
                        progress = { uiState.completedSetupSteps / totalSteps.toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                }
            }

            // PERMISSIONS CENTER (All 6 Android permissions with live badges & one-tap Allow/Grant)
            item(key = "permissions_center") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassPanel(cornerRadius = 22.dp)
                        .padding(14.dp)
                        .testTag("permissions_center_section"),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.permissions_center_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.permissions_center_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${perms.grantedCount}/6",
                            style = MonoCodeStyle.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                    PermissionActionRow(
                        icon = Icons.Outlined.Mic,
                        title = "Microphone",
                        subtitle = "Voice commands in English & Telugu",
                        granted = perms.microphoneGranted,
                        actionLabel = stringResource(R.string.permission_allow),
                        testTag = "perm_mic_button",
                        onAction = onRequestMicrophonePermission
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    PermissionActionRow(
                        icon = Icons.Outlined.AccessibilityNew,
                        title = "Accessibility Service",
                        subtitle = "On-device screen control & auto-playing YouTube videos",
                        granted = perms.accessibilityEnabled,
                        actionLabel = stringResource(R.string.permission_grant),
                        testTag = "perm_accessibility_button",
                        onAction = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    )

                    if (!perms.accessibilityEnabled) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .nestedGlassCard(cornerRadius = 14.dp)
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Android 13+ \"Restricted settings\" hint",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Text(
                                text = "If Accessibility is greyed out for a sideloaded APK, open App Info → tap ⋮ (top right) → \"Allow restricted settings\", then return to Accessibility Settings.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = {
                                    runCatching {
                                        val intent = Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", context.packageName, null)
                                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(intent)
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp)
                                    .testTag("setup_open_app_info_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.setup_open_app_info))
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    PermissionActionRow(
                        icon = Icons.Outlined.Layers,
                        title = "Display Over Other Apps",
                        subtitle = "Floating Stop Agent pill shown over YouTube & other apps",
                        granted = perms.overlayGranted,
                        actionLabel = stringResource(R.string.permission_grant),
                        testTag = "perm_overlay_button",
                        onAction = onOpenOverlayPermissionSettings
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    PermissionActionRow(
                        icon = Icons.Outlined.Contacts,
                        title = "Contacts",
                        subtitle = "Resolve contact names for approved calls & messages",
                        granted = perms.contactsGranted,
                        actionLabel = stringResource(R.string.permission_allow),
                        testTag = "perm_contacts_button",
                        onAction = onRequestContactsPermission
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    PermissionActionRow(
                        icon = Icons.Outlined.Call,
                        title = "Phone Calls",
                        subtitle = "Place approved calls directly without extra dialer taps",
                        granted = perms.callPhoneGranted,
                        actionLabel = stringResource(R.string.permission_allow),
                        testTag = "perm_call_phone_button",
                        onAction = onRequestCallPhonePermission
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    PermissionActionRow(
                        icon = Icons.Outlined.Notifications,
                        title = "Notifications",
                        subtitle = "Ongoing status & quick Stop Agent button in notification shade",
                        granted = perms.notificationsGranted,
                        actionLabel = stringResource(R.string.permission_allow),
                        testTag = "perm_notifications_button",
                        onAction = onRequestNotificationsPermission
                    )
                }
            }

            // RUN FULL SELF-TEST SECTION
            item(key = "full_self_test") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassPanel(cornerRadius = 22.dp)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.self_test_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.self_test_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = onRunFullSelfTest,
                        enabled = !uiState.isRunningSelfTest,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("run_full_self_test_button")
                    ) {
                        if (uiState.isRunningSelfTest) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Running Diagnostics…")
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.FactCheck,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.self_test_button))
                        }
                    }

                    uiState.selfTestReport?.let { report ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Result: ${report.passedCount}/${report.totalCount} checks passed",
                                style = MonoCodeStyle.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                            report.items.forEach { item ->
                                SelfTestItemRow(item)
                            }
                        }
                    }
                }
            }

            // STEP 1: Add a Custom AI API Key
            item(key = "step1") {
                FlatStepSection(
                    stepNumber = 1,
                    title = "Add a Custom AI API Key",
                    subtitle = if (uiState.step1Complete) {
                        val active = uiState.activeProvider
                        "Active: ${active?.displayName ?: ""} (${active?.modelName ?: ""})"
                    } else {
                        "Connect any OpenAI-compatible Base URL, local server, Anthropic, or Gemini endpoint."
                    },
                    isComplete = uiState.step1Complete
                ) {
                    OutlinedButton(
                        onClick = { onJumpToSettings(SettingsFocusSection.PROVIDERS) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("setup_go_to_api_keys_button")
                    ) {
                        Text(stringResource(R.string.setup_go_to_api_keys))
                    }
                }
            }

            // STEP 2: Enable My Personal Agent in Android Accessibility Settings
            item(key = "step2") {
                FlatStepSection(
                    stepNumber = 2,
                    title = "Enable \"My Personal Agent\" in Android Accessibility",
                    subtitle = if (uiState.step2Complete) {
                        "Accessibility Service is active. Your agent can control your phone directly."
                    } else {
                        "Enables 100% free on-device phone control—no laptop, no cloud server, and no monthly credits."
                    },
                    isComplete = uiState.step2Complete
                ) {
                    Button(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("setup_open_accessibility_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.setup_open_accessibility))
                    }
                }
            }

            // STEP 3: Run Connection Test
            item(key = "step3") {
                FlatStepSection(
                    stepNumber = 3,
                    title = "Run Connection Test",
                    subtitle = "Sends \"Reply with OK\" to your active custom Base URL + Model to confirm your API key works.",
                    isComplete = uiState.step3Complete
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val active = uiState.activeProvider
                        if (active != null) {
                            Text(
                                text = "${active.displayName} · ${active.modelName}\n${uiState.activeProviderResolvedUrl}",
                                style = MonoCodeStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Button(
                            onClick = onRunConnectionTest,
                            enabled = !uiState.isTestingConnection && uiState.activeProvider != null,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("setup_test_connection_button")
                        ) {
                            if (uiState.isTestingConnection) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Testing…")
                            } else {
                                Icon(
                                    imageVector = Icons.Outlined.NetworkCheck,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.setup_test_connection))
                            }
                        }

                        uiState.connectionTestSummary?.providerResult?.let { result ->
                            FlatTestResultRow(result)
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun PermissionActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean,
    actionLabel: String,
    testTag: String,
    onAction: () -> Unit
) {
    val semantic = LocalAgentSemanticColors.current
    val badgeBg = if (granted) semantic.successContainer else semantic.mediumRiskContainer
    val badgeFg = if (granted) semantic.successContent else semantic.mediumRiskContent

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (granted) semantic.successContent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeBg)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = stringResource(
                            if (granted) R.string.permission_granted else R.string.permission_required
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeFg,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (!granted) {
            FilledTonalButton(
                onClick = onAction,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier
                    .height(38.dp)
                    .testTag(testTag)
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelMedium)
            }
        } else {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = "Granted",
                tint = semantic.successContent,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun SelfTestItemRow(item: SelfTestItem) {
    val semantic = LocalAgentSemanticColors.current
    val bg = if (item.passed) semantic.successContainer else semantic.highRiskContainer
    val fg = if (item.passed) semantic.successContent else semantic.highRiskContent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = if (item.passed) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(18.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.labelMedium,
                color = fg,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = item.detail,
                style = MaterialTheme.typography.bodySmall,
                color = fg
            )
        }
    }
}

@Composable
private fun FlatStepSection(
    stepNumber: Int,
    title: String,
    subtitle: String,
    isComplete: Boolean,
    content: @Composable () -> Unit
) {
    val semantic = LocalAgentSemanticColors.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(
                cornerRadius = 20.dp,
                borderColorOverride = if (isComplete) semantic.successContent.copy(alpha = 0.55f) else null
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
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
                if (isComplete) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "Complete",
                        tint = semantic.successContent,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Text(
                        text = "$stepNumber.",
                        style = MonoCodeStyle.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Text(
                text = if (isComplete) "Done" else "Pending",
                style = MaterialTheme.typography.labelSmall,
                color = if (isComplete) semantic.successContent else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        content()
    }
}

@Composable
private fun FlatTestResultRow(detail: EndpointTestDetail) {
    val semantic = LocalAgentSemanticColors.current
    val bg = if (detail.success) semantic.successContainer else semantic.highRiskContainer
    val fg = if (detail.success) semantic.successContent else semantic.highRiskContent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = if (detail.success) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(18.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = detail.label,
                style = MaterialTheme.typography.labelMedium,
                color = fg,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = detail.message,
                style = MaterialTheme.typography.bodySmall,
                color = fg
            )
        }
    }
}
