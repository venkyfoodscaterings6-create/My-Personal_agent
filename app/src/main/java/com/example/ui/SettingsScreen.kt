package com.example.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon as AndroidIcon
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.AgentQuickSettingsTileService
import com.example.AgentUiState
import com.example.AiProvider
import com.example.ApiFormat
import com.example.BuiltInGeminiSettings
import com.example.DefaultQuickChips
import com.example.EndpointResolver
import com.example.GeminiBuiltInModel
import com.example.GeminiVoicePersona
import com.example.ProviderPresets
import com.example.R
import com.example.SafetyRules
import com.example.SettingsFocusSection
import com.example.ThemePreference
import com.example.VoiceLanguage
import com.example.VoiceSettings
import com.example.WakeSensitivity
import com.example.ui.theme.LocalAgentGlassColors
import com.example.ui.theme.MonoCodeStyle
import com.example.ui.theme.glassPanel
import com.example.ui.theme.nestedGlassCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    uiState: AgentUiState,
    onBack: () -> Unit,
    onSelectBuiltInGemini: (GeminiBuiltInModel) -> Unit,
    onUpdateBuiltInGeminiSettings: ((BuiltInGeminiSettings) -> BuiltInGeminiSettings) -> Unit,
    onOpenMemoryDatabase: () -> Unit,
    onSaveProvider: (
        existingId: String?,
        displayName: String,
        baseUrl: String,
        apiKey: String,
        modelName: String,
        apiFormat: ApiFormat,
        makeDefault: Boolean
    ) -> Unit,
    onDeleteProvider: (String) -> Unit,
    onSelectDefaultProvider: (String) -> Unit,
    onFetchModels: (rawBaseUrl: String, apiFormat: ApiFormat, apiKey: String) -> Unit,
    onClearFetchedModels: () -> Unit,
    onSaveQuickActionChips: (List<String>) -> Unit,
    onUpdateSafetyRules: ((SafetyRules) -> SafetyRules) -> Unit,
    onUpdateVoiceSettings: ((VoiceSettings) -> VoiceSettings) -> Unit,
    onToggleWakeWord: (Boolean) -> Unit,
    onUpdateWakePhrase: (String) -> Unit,
    onTestVoiceWakeUp: () -> Unit,
    onTestVoice: () -> Unit,
    onShowMessage: (String) -> Unit,
    onOpenPermissionsCenter: () -> Unit,
    onSetThemePreference: (ThemePreference) -> Unit,
    onClearFocusSection: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()

    var showProviderEditor by remember { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<AiProvider?>(null) }
    var providerToDelete by remember { mutableStateOf<AiProvider?>(null) }

    var newChipDraft by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(uiState.settingsFocusSection) {
        when (uiState.settingsFocusSection) {
            SettingsFocusSection.PROVIDERS -> {
                listState.scrollToItem(0)
                onClearFocusSection()
            }
            null -> Unit
        }
    }

    val glass = LocalAgentGlassColors.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding()
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
                    modifier = Modifier.testTag("settings_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back to Chat"
                    )
                }
                Text(
                    text = "Settings & API Keys",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            HorizontalDivider(thickness = 0.5.dp, color = glass.hairlineDivider)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // SECTION 0: Built-in Gemini AI, Google Search Grounding & SQL Memory
            item(key = "section_builtin_gemini") {
                FlatSettingsSection(
                    title = "Built-in Gemini AI & SQL Memory",
                    subtitle = "Works instantly with Firebase AI Logic (no API key needed) alongside your custom providers.",
                    headerAction = {
                        FilledTonalButton(
                            onClick = onOpenMemoryDatabase,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("settings_open_sql_db_button")
                        ) {
                            Text("SQL DB")
                        }
                    }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            GeminiBuiltInModel.entries.forEach { model ->
                                val selected = uiState.isUsingBuiltInGemini &&
                                    uiState.builtInGeminiSettings.selectedModel == model
                                InputChip(
                                    selected = selected,
                                    onClick = { onSelectBuiltInGemini(model) },
                                    label = { Text(model.displayName) },
                                    modifier = Modifier.testTag("settings_gemini_${model.name.lowercase()}")
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        FlatSafetyToggle(
                            title = "Web Search (Live Facts)",
                            subtitle = "Enables live Google Search Grounding for Built-in Gemini and Gemini Native providers with clickable source chips.",
                            checked = uiState.builtInGeminiSettings.googleSearchEnabled,
                            onCheckedChange = { checked ->
                                onUpdateBuiltInGeminiSettings { it.copy(googleSearchEnabled = checked) }
                            },
                            testTag = "toggle_google_search_grounding"
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        FlatSafetyToggle(
                            title = "Personalize with on-device SQL Memory & Tasks",
                            subtitle = "${uiState.memories.size} memories · ${uiState.openTasksCount} open tasks · ${uiState.sqlLogs.size} SQL logs stored locally.",
                            checked = uiState.builtInGeminiSettings.injectSqlMemory,
                            onCheckedChange = { checked ->
                                onUpdateBuiltInGeminiSettings { it.copy(injectSqlMemory = checked) }
                            },
                            testTag = "toggle_inject_sql_memory"
                        )
                    }
                }
            }

            // SECTION 1: Custom AI Providers
            item(key = "section_providers") {
                FlatSettingsSection(
                    title = "Custom AI Providers",
                    subtitle = "Unlimited OpenAI-compatible, local/Tailscale, Anthropic, or Gemini endpoints.",
                    headerAction = {
                        FilledTonalButton(
                            onClick = {
                                editingProvider = null
                                onClearFetchedModels()
                                showProviderEditor = true
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("add_provider_button")
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add")
                        }
                    }
                ) {
                    if (uiState.providers.isEmpty()) {
                        Text(
                            text = "No custom endpoints saved yet. Tap Add to enter any Base URL, optional API key, and model name.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            uiState.providers.forEach { provider ->
                                FlatProviderRow(
                                    provider = provider,
                                    resolvedUrl = uiState.providerResolvedUrls[provider.id] ?: provider.baseUrl,
                                    onSetDefault = { onSelectDefaultProvider(provider.id) },
                                    onEdit = {
                                        editingProvider = provider
                                        onClearFetchedModels()
                                        showProviderEditor = true
                                    },
                                    onDelete = { providerToDelete = provider }
                                )
                            }
                        }
                    }
                }
            }

            // SECTION 2: Quick Action Chips
            item(key = "section_chips") {
                FlatSettingsSection(
                    title = "Starter Suggestion Chips",
                    subtitle = "Shown on the empty chat greeting screen for one-tap sending.",
                    headerAction = {
                        TextButton(onClick = { onSaveQuickActionChips(DefaultQuickChips.items) }) {
                            Text("Reset")
                        }
                    }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            uiState.quickActionChips.forEach { chip ->
                                InputChip(
                                    selected = false,
                                    onClick = { onSaveQuickActionChips(uiState.quickActionChips - chip) },
                                    label = { Text(chip) },
                                    trailingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "Remove $chip",
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = newChipDraft,
                                onValueChange = { newChipDraft = it },
                                label = { Text("Add starter chip") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("new_quick_chip_input")
                            )
                            FilledTonalButton(
                                onClick = {
                                    val trimmed = newChipDraft.trim()
                                    if (trimmed.isNotBlank() && trimmed !in uiState.quickActionChips) {
                                        onSaveQuickActionChips(uiState.quickActionChips + trimmed)
                                        newChipDraft = ""
                                    }
                                },
                                enabled = newChipDraft.isNotBlank(),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .height(52.dp)
                                    .testTag("add_quick_chip_button")
                            ) {
                                Text("Add")
                            }
                        }
                    }
                }
            }

            // SECTION 3: Voice & Speech (English + Telugu) & Zero-Clutter Triggers
            item(key = "section_voice") {
                FlatSettingsSection(
                    title = "Voice, Wake Word & Speech",
                    subtitle = "Hands-free \"Hey Agent\" voice wake-up, Quick Settings Tile, and spoken replies."
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Hands-Free Wake Word ("Hey Agent") — responds aloud like "Hey Google"
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .nestedGlassCard(
                                    cornerRadius = 16.dp,
                                    borderColorOverride = if (uiState.voiceSettings.wakeWordEnabled) {
                                        glass.accentBlue.copy(alpha = 0.7f)
                                    } else {
                                        null
                                    }
                                )
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FlatSafetyToggle(
                                title = stringResource(R.string.settings_wake_word_title),
                                subtitle = stringResource(R.string.settings_wake_word_subtitle),
                                checked = uiState.voiceSettings.wakeWordEnabled,
                                onCheckedChange = onToggleWakeWord,
                                testTag = "toggle_wake_word"
                            )

                            Text(
                                text = stringResource(R.string.settings_wake_phrase_label) + ": \"" + uiState.voiceSettings.wakePhrase + "\"",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                VoiceSettings.PRESET_WAKE_PHRASES.forEach { phrase ->
                                    val selected = uiState.voiceSettings.wakePhrase.equals(phrase, ignoreCase = true)
                                    InputChip(
                                        selected = selected,
                                        onClick = { onUpdateWakePhrase(phrase) },
                                        label = { Text(phrase) },
                                        modifier = Modifier.testTag("wake_phrase_${phrase.lowercase().replace(" ", "_")}")
                                    )
                                }
                            }

                            Text(
                                text = "Background Mic Sensitivity (Adaptive Noise Filter)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                                    .padding(3.dp),
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                WakeSensitivity.entries.forEach { preset ->
                                    val selected = uiState.voiceSettings.wakeSensitivity == preset
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(36.dp)
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(
                                                if (selected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                                            )
                                            .clickable {
                                                onUpdateVoiceSettings { it.copy(wakeSensitivity = preset) }
                                            }
                                            .testTag("wake_sensitivity_${preset.name.lowercase()}"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = preset.displayName,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (selected) MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilledTonalButton(
                                    onClick = onTestVoiceWakeUp,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("test_wake_up_button")
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_test_wake_button),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        }.onFailure {
                                            onShowMessage("Open Android Settings -> Apps -> Default apps -> Digital assistant app.")
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("set_default_assistant_button")
                                ) {
                                    Text(
                                        text = "Default Assistant",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        // Quick Settings Tile setup guide + Android 13+ one-tap Add Tile button
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .nestedGlassCard(cornerRadius = 16.dp)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_qs_tile_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.settings_qs_tile_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(R.string.settings_qs_tile_steps),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                FilledTonalButton(
                                    onClick = {
                                        val sbm = context.getSystemService(StatusBarManager::class.java)
                                        if (sbm == null) {
                                            onShowMessage("Swipe down twice -> Edit to add the Personal Agent tile.")
                                        } else {
                                            runCatching {
                                                sbm.requestAddTileService(
                                                    ComponentName(context, AgentQuickSettingsTileService::class.java),
                                                    context.getString(R.string.qs_tile_label),
                                                    AndroidIcon.createWithResource(context, R.drawable.ic_robot_tile),
                                                    context.mainExecutor
                                                ) { resultCode ->
                                                    when (resultCode) {
                                                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                                                            onShowMessage("Added \"Personal Agent\" to Quick Settings!")
                                                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                                                            onShowMessage("\"Personal Agent\" tile is already in Quick Settings.")
                                                        else -> Unit
                                                    }
                                                }
                                            }.onFailure {
                                                onShowMessage("Swipe down twice -> Edit to add the Personal Agent tile.")
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("add_qs_tile_button")
                                ) {
                                    Text(stringResource(R.string.settings_qs_tile_add_button))
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        FlatSafetyToggle(
                            title = stringResource(R.string.settings_persistent_notif_title),
                            subtitle = stringResource(R.string.settings_persistent_notif_subtitle),
                            checked = uiState.voiceSettings.persistentAskNotificationEnabled,
                            onCheckedChange = { checked ->
                                onUpdateVoiceSettings { it.copy(persistentAskNotificationEnabled = checked) }
                            },
                            testTag = "toggle_persistent_ask_notification"
                        )
                        if (uiState.voiceSettings.persistentAskNotificationEnabled &&
                            !uiState.permissionsStatus.notificationsGranted
                        ) {
                            Text(
                                text = "Allow Notifications in Permissions Center to display the persistent Ask Agent button.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        FlatSafetyToggle(
                            title = "Auto-send voice commands",
                            subtitle = "Automatically sends recognized speech right after you finish talking.",
                            checked = uiState.voiceSettings.autoSendVoiceCommands,
                            onCheckedChange = { checked ->
                                onUpdateVoiceSettings { it.copy(autoSendVoiceCommands = checked) }
                            },
                            testTag = "toggle_auto_send_voice"
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        FlatSafetyToggle(
                            title = "Speak AI replies aloud (Text-to-Speech)",
                            subtitle = "Reads replies aloud with a quick Stop button in the top bar.",
                            checked = uiState.voiceSettings.speakRepliesAloud,
                            onCheckedChange = { checked ->
                                onUpdateVoiceSettings { it.copy(speakRepliesAloud = checked) }
                            },
                            testTag = "toggle_speak_replies"
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        // Voice Studio: Gemini Neural Voice, Persona, Speech Speed & Test Voice
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .nestedGlassCard(cornerRadius = 16.dp)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            FlatSafetyToggle(
                                title = stringResource(R.string.settings_neural_voice_title),
                                subtitle = stringResource(R.string.settings_neural_voice_subtitle),
                                checked = uiState.voiceSettings.useGeminiNeuralVoice,
                                onCheckedChange = { checked ->
                                    onUpdateVoiceSettings { it.copy(useGeminiNeuralVoice = checked) }
                                },
                                testTag = "toggle_neural_voice"
                            )

                            Text(
                                text = "Voice Persona",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                GeminiVoicePersona.entries.forEach { persona ->
                                    val selected = uiState.voiceSettings.voicePersona == persona
                                    InputChip(
                                        selected = selected,
                                        onClick = {
                                            onUpdateVoiceSettings { it.copy(voicePersona = persona) }
                                        },
                                        label = { Text("${persona.displayName} · ${persona.styleHint}") },
                                        modifier = Modifier.testTag("voice_persona_${persona.name.lowercase()}")
                                    )
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Speech Speed",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = String.format(Locale.US, "%.2fx", uiState.voiceSettings.speechRate),
                                        style = MonoCodeStyle,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Slider(
                                    value = uiState.voiceSettings.speechRate.coerceIn(0.85f, 1.30f),
                                    onValueChange = { value ->
                                        val snapped = (Math.round(value * 20f) / 20f).coerceIn(0.85f, 1.30f)
                                        onUpdateVoiceSettings { it.copy(speechRate = snapped) }
                                    },
                                    valueRange = 0.85f..1.30f,
                                    steps = 8,
                                    modifier = Modifier.testTag("speech_speed_slider")
                                )
                            }

                            FilledTonalButton(
                                onClick = onTestVoice,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("test_voice_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.VolumeUp,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.settings_test_voice_button))
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        Text(
                            text = "Voice Recognition Language",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .nestedGlassCard(cornerRadius = 14.dp)
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            VoiceLanguage.entries.forEach { lang ->
                                val selected = uiState.voiceSettings.voiceLanguage == lang
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(42.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0f)
                                        )
                                        .clickable {
                                            onUpdateVoiceSettings { it.copy(voiceLanguage = lang) }
                                        }
                                        .testTag("voice_lang_${lang.name.lowercase()}"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = when (lang) {
                                            VoiceLanguage.EN_IN -> "English (India)"
                                            VoiceLanguage.TE_IN -> "తెలుగు"
                                        },
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // SECTION 4: Permissions Center Link
            item(key = "section_permissions_link") {
                FlatSettingsSection(
                    title = "Permissions Center",
                    subtitle = "${uiState.permissionsStatus.grantedCount} of 6 Android permissions granted (Mic, Accessibility, Overlay, Contacts, Calls, Notifications).",
                    headerAction = {
                        FilledTonalButton(
                            onClick = onOpenPermissionsCenter,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("settings_open_permissions_button")
                        ) {
                            Text("Manage")
                        }
                    }
                ) {
                    Text(
                        text = "Grant permissions in one tap or run the Full Self-Test inside Setup & Test.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // SECTION 5: Safety & Approval Rules
            item(key = "section_safety") {
                FlatSettingsSection(
                    title = "Safety & Approval Rules",
                    subtitle = "LOW and MEDIUM risk actions run immediately without approval. HIGH risk actions always ask for approval, and financial/OTP/password actions stay hard-blocked."
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FlatSafetyToggle(
                            title = "Auto-run LOW & MEDIUM risk actions without approval",
                            subtitle = "Runs app launches, media, alarms, calls, messages, and routine automation immediately. HIGH risk actions still require your Approval Card.",
                            checked = uiState.safetyRules.autoRunLowAndMediumRisk,
                            onCheckedChange = { checked ->
                                onUpdateSafetyRules {
                                    it.copy(
                                        autoRunLowAndMediumRisk = checked,
                                        alwaysAskApproval = !checked,
                                        instantRunLowRiskAppLaunch = checked
                                    )
                                }
                            },
                            testTag = "toggle_auto_run_low_medium_risk"
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        FlatSafetyToggle(
                            title = "Hard-block banking, UPI, OTP, and password actions",
                            subtitle = "Blocks locally on-device before planning and during live screen control.",
                            checked = uiState.safetyRules.blockSensitiveFinancial,
                            onCheckedChange = { checked ->
                                onUpdateSafetyRules { it.copy(blockSensitiveFinancial = checked) }
                            },
                            testTag = "toggle_block_sensitive_financial"
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        FlatSafetyToggle(
                            title = "Show step-by-step plan in action cards",
                            checked = uiState.safetyRules.showStepPlan,
                            onCheckedChange = { checked ->
                                onUpdateSafetyRules { it.copy(showStepPlan = checked) }
                            },
                            testTag = "toggle_show_step_plan"
                        )
                    }
                }
            }

            // SECTION 6: Appearance
            item(key = "section_appearance") {
                FlatSettingsSection(
                    title = "Appearance",
                    subtitle = "Spatial dark frosted-glass or matching light frosted-ice theme."
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .nestedGlassCard(cornerRadius = 14.dp)
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ThemePreference.entries.forEach { option ->
                            val selected = uiState.themePreference == option
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0f)
                                    )
                                    .clickable { onSetThemePreference(option) }
                                    .testTag("theme_option_${option.name.lowercase()}"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = option.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    if (showProviderEditor) {
        ProviderEditorDialog(
            initialProvider = editingProvider,
            isFetchingModels = uiState.isFetchingModels,
            fetchedModels = uiState.fetchedModels,
            fetchModelsNote = uiState.fetchModelsNote,
            onFetchModels = onFetchModels,
            onClearFetchedModels = onClearFetchedModels,
            onDismiss = {
                showProviderEditor = false
                onClearFetchedModels()
            },
            onSave = { displayName, baseUrl, apiKey, modelName, apiFormat, makeDefault ->
                onSaveProvider(
                    editingProvider?.id,
                    displayName,
                    baseUrl,
                    apiKey,
                    modelName,
                    apiFormat,
                    makeDefault
                )
                showProviderEditor = false
                onClearFetchedModels()
            }
        )
    }

    providerToDelete?.let { provider ->
        AlertDialog(
            onDismissRequest = { providerToDelete = null },
            containerColor = glass.dialogFill,
            shape = RoundedCornerShape(24.dp),
            title = { Text("Delete \"${provider.displayName}\"?") },
            text = { Text("This removes the saved endpoint and API key from this device.") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteProvider(provider.id)
                        providerToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { providerToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun FlatSettingsSection(
    title: String,
    subtitle: String,
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(cornerRadius = 22.dp)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (headerAction != null) {
                Spacer(modifier = Modifier.width(8.dp))
                headerAction()
            }
        }
        content()
    }
}

@Composable
private fun FlatProviderRow(
    provider: AiProvider,
    resolvedUrl: String,
    onSetDefault: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val glass = LocalAgentGlassColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .nestedGlassCard(
                cornerRadius = 16.dp,
                borderColorOverride = if (provider.isDefault) glass.accentBlue.copy(alpha = 0.72f) else null
            )
            .padding(10.dp)
            .testTag("provider_card_${provider.id}"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                IconButton(
                    onClick = onSetDefault,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (provider.isDefault) Icons.Rounded.Star else Icons.Outlined.StarOutline,
                        contentDescription = if (provider.isDefault) "Active provider" else "Set active",
                        tint = if (provider.isDefault) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = provider.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = provider.modelName,
                        style = MonoCodeStyle,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row {
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Edit", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = "Delete",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Text(
            text = resolvedUrl,
            style = MonoCodeStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun FlatSafetyToggle(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderEditorDialog(
    initialProvider: AiProvider?,
    isFetchingModels: Boolean,
    fetchedModels: List<String>,
    fetchModelsNote: String?,
    onFetchModels: (rawBaseUrl: String, apiFormat: ApiFormat, apiKey: String) -> Unit,
    onClearFetchedModels: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (
        displayName: String,
        baseUrl: String,
        apiKey: String,
        modelName: String,
        apiFormat: ApiFormat,
        makeDefault: Boolean
    ) -> Unit
) {
    var displayName by remember(initialProvider?.id) {
        mutableStateOf(initialProvider?.displayName ?: "")
    }
    var baseUrl by remember(initialProvider?.id) {
        mutableStateOf(initialProvider?.baseUrl ?: "")
    }
    var apiKey by remember(initialProvider?.id) {
        mutableStateOf(initialProvider?.apiKey ?: "")
    }
    var modelName by remember(initialProvider?.id) {
        mutableStateOf(initialProvider?.modelName ?: "")
    }
    var apiFormat by remember(initialProvider?.id) {
        mutableStateOf(initialProvider?.apiFormat ?: ApiFormat.OPENAI_COMPATIBLE)
    }
    var makeDefault by remember(initialProvider?.id) {
        mutableStateOf(initialProvider?.isDefault ?: true)
    }
    var showApiKey by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }
    var modelPlaceholder by remember { mutableStateOf("e.g., deepseek-chat, gpt-4o-mini, llama3.2") }
    var showModelPicker by remember { mutableStateOf(false) }

    // Off-main-thread debounced URL normalization + validation
    var baseUrlValid by remember { mutableStateOf(initialProvider != null) }
    var resolvedPreview by remember { mutableStateOf("") }

    LaunchedEffect(baseUrl, apiFormat, modelName) {
        delay(50L)
        val (valid, preview) = withContext(Dispatchers.Default) {
            val ok = EndpointResolver.isValidHttpUrl(baseUrl)
            val resolved = if (ok) {
                EndpointResolver.resolveChatEndpoint(baseUrl, apiFormat, modelName)
            } else {
                ""
            }
            ok to resolved
        }
        baseUrlValid = valid
        resolvedPreview = preview
    }

    LaunchedEffect(fetchedModels) {
        if (fetchedModels.isNotEmpty()) {
            showModelPicker = true
        }
    }

    val canSave = baseUrlValid && modelName.isNotBlank()

    val glass = LocalAgentGlassColors.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = glass.dialogFill,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 720.dp)
                .border(1.dp, glass.luminousBorderBrush(), RoundedCornerShape(24.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                Text(
                    text = if (initialProvider == null) "Add Custom AI Provider" else "Edit AI Provider",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 1. Display Name
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("Display Name") },
                        placeholder = { Text("My Custom API") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("provider_display_name_input")
                    )

                    // 2. Base URL + Live Resolved Endpoint Preview
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text("Base URL") },
                            placeholder = { Text("https://api.example.com/v1") },
                            singleLine = true,
                            isError = baseUrl.isNotBlank() && !baseUrlValid,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("provider_base_url_input")
                        )

                        if (baseUrl.isBlank()) {
                            Text(
                                text = "Supports https:// or local/Tailscale http://",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else if (!baseUrlValid) {
                            Text(
                                text = "Base URL must start with https:// or http://",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else if (resolvedPreview.isNotBlank()) {
                            Text(
                                text = "Resolves to: $resolvedPreview",
                                style = MonoCodeStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("resolved_endpoint_preview")
                            )
                        }
                    }

                    // 3. API Key (Masked + Eye Toggle + Blank Allowed)
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key (optional for local servers)") },
                        placeholder = { Text("sk-... or leave blank") },
                        singleLine = true,
                        visualTransformation = if (showApiKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = { showApiKey = !showApiKey },
                                modifier = Modifier.testTag("toggle_api_key_visibility")
                            ) {
                                Icon(
                                    imageVector = if (showApiKey) {
                                        Icons.Outlined.VisibilityOff
                                    } else {
                                        Icons.Outlined.Visibility
                                    },
                                    contentDescription = if (showApiKey) "Hide API Key" else "Show API Key"
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("provider_api_key_input")
                    )

                    // 4. Model Name (Editable text field + Fetch Models button right next to it)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = modelName,
                                onValueChange = { modelName = it },
                                label = { Text("Model Name") },
                                placeholder = { Text(modelPlaceholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("provider_model_name_input")
                            )

                            FilledTonalButton(
                                onClick = { onFetchModels(baseUrl, apiFormat, apiKey) },
                                enabled = baseUrlValid && !isFetchingModels,
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                                modifier = Modifier
                                    .height(54.dp)
                                    .testTag("fetch_models_button")
                            ) {
                                if (isFetchingModels) {
                                    CircularProgressIndicator(
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(16.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Outlined.CloudDownload,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Fetch\nModels", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        if (!fetchModelsNote.isNullOrBlank()) {
                            Text(
                                text = fetchModelsNote,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Expandable API Format & Quick Presets (instant toggle, no animation lag)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { advancedExpanded = !advancedExpanded }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .testTag("expand_format_presets_row"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "API Format & Quick Presets",
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Text(
                                    text = apiFormat.displayName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Icon(
                                imageVector = if (advancedExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = "Toggle format and presets"
                            )
                        }

                        if (advancedExpanded) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Quick Presets (pre-fills Base URL only)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    ProviderPresets.all.forEach { preset ->
                                        AssistChip(
                                            onClick = {
                                                baseUrl = preset.baseUrl
                                                apiFormat = preset.apiFormat
                                                modelPlaceholder = preset.modelPlaceholder
                                            },
                                            label = { Text(preset.label) },
                                            modifier = Modifier.testTag("preset_chip_${preset.label.lowercase()}")
                                        )
                                    }
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                                ApiFormat.entries.forEach { format ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { apiFormat = format }
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = apiFormat == format,
                                            onClick = { apiFormat = format }
                                        )
                                        Column {
                                            Text(
                                                text = format.displayName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = format.subtitle,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { makeDefault = !makeDefault },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = makeDefault, onCheckedChange = { makeDefault = it })
                        Text("Set as active provider", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            onSave(
                                displayName.ifBlank { "My Custom API" },
                                baseUrl,
                                apiKey,
                                modelName,
                                apiFormat,
                                makeDefault
                            )
                        },
                        enabled = canSave,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .testTag("save_provider_button")
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }

    if (showModelPicker && fetchedModels.isNotEmpty()) {
        SearchableModelsPickerDialog(
            models = fetchedModels,
            onSelect = { selected ->
                modelName = selected
                showModelPicker = false
                onClearFetchedModels()
            },
            onDismiss = {
                showModelPicker = false
                onClearFetchedModels()
            }
        )
    }
}

@Composable
private fun SearchableModelsPickerDialog(
    models: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filtered = remember(models, searchQuery) {
        if (searchQuery.isBlank()) models
        else models.filter { it.contains(searchQuery.trim(), ignoreCase = true) }
    }

    val glass = LocalAgentGlassColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = glass.dialogFill,
        shape = RoundedCornerShape(24.dp),
        title = { Text("Select Model (${models.size})") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search model IDs…") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("model_picker_search_input")
                )

                if (filtered.isEmpty()) {
                    Text(
                        text = "No matching models found.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(filtered, key = { it }) { modelId ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                    .clickable { onSelect(modelId) }
                                    .padding(horizontal = 12.dp, vertical = 11.dp)
                            ) {
                                Text(
                                    text = modelId,
                                    style = MonoCodeStyle,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
