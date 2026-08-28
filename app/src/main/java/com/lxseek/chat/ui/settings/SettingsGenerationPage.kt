package com.lxseek.chat.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Switch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.ui.common.OpenAiServiceTierControlPanel
import com.lxseek.chat.ui.common.ThinkingControlPanel
import com.lxseek.chat.ui.common.openAiServiceTierShortLabel
import com.lxseek.chat.ui.common.thinkingControlShortLabel
import com.lxseek.chat.util.TtsManager
import com.lxseek.chat.viewmodel.ChatViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsGenerationPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val defaultTemperature by viewModel.settings.defaultTemperature.collectAsState()
    val defaultMaxTokens by viewModel.settings.defaultMaxTokens.collectAsState()
    val defaultTopP by viewModel.settings.defaultTopP.collectAsState()
    val defaultFrequencyPenalty by viewModel.settings.defaultFrequencyPenalty.collectAsState()
    val defaultPresencePenalty by viewModel.settings.defaultPresencePenalty.collectAsState()
    val thinkingEnabled by viewModel.settings.thinkingEnabled.collectAsState()
    val thinkingLevel by viewModel.settings.thinkingLevel.collectAsState()
    val thinkingBudgetEnabled by viewModel.settings.thinkingBudgetEnabled.collectAsState()
    val thinkingBudgetTokens by viewModel.settings.thinkingBudgetTokens.collectAsState()
    val openAiServiceTierEnabled by
        viewModel.settings.openAiServiceTierEnabled.collectAsState()
    val openAiServiceTier by viewModel.settings.openAiServiceTier.collectAsState()
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()
    val ttsEnabled by viewModel.settings.ttsEnabled.collectAsState()
    val ttsAutoPlay by viewModel.settings.ttsAutoPlay.collectAsState()
    val ttsLanguage by viewModel.settings.ttsLanguage.collectAsState()
    val ttsSpeechRate by viewModel.settings.ttsSpeechRate.collectAsState()
    val ttsAvailable by TtsManager.isAvailable.collectAsState()
    val ttsLangMissingData by TtsManager.langMissingData.collectAsState()
    val ttsDiagnostic = TtsManager.getDiagnosticInfo()
    val ttsContext = LocalContext.current
    val shareIncludeThinking by viewModel.settings.shareIncludeThinking.collectAsState()
    val shareIncludeTools by viewModel.settings.shareIncludeTools.collectAsState()
    val voiceConversationEnabled by viewModel.settings.voiceConversationEnabled.collectAsState()
    val asrEnginePref by viewModel.settings.asrEnginePref.collectAsState()
    val asrUseRemote by viewModel.settings.asrUseRemote.collectAsState()
    val voiceLanguage by viewModel.settings.voiceLanguage.collectAsState()
    val asrRemoteBaseUrl by viewModel.settings.asrRemoteBaseUrl.collectAsState()
    val asrRemoteApiKey by viewModel.settings.asrRemoteApiKey.collectAsState()
    val asrRemoteModel by viewModel.settings.asrRemoteModel.collectAsState()
    val vadThreshold by viewModel.settings.vadThreshold.collectAsState()
    val vadMinSilence by viewModel.settings.vadMinSilence.collectAsState()
    val vadMaxSpeech by viewModel.settings.vadMaxSpeech.collectAsState()

    CollapsingSettingsScaffold(
        title = stringResource(R.string.generation_title),
        onBack = onBack,
        floatingActionButton = { if (showDocFab) DocumentationFab("generation.md") }
    ) {
            SettingsGroupColumn {
                // Default Thinking
                SettingsGroup(
                    title = stringResource(R.string.default_thinking),
                    items = listOf(
                        {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.gen_thinking_enabled)) },
                                supportingContent = {
                                    Text(thinkingControlShortLabel(thinkingEnabled, thinkingLevel, thinkingBudgetEnabled, thinkingBudgetTokens))
                                },
                                leadingContent = {
                                    Icon(painterResource(id = R.drawable.neurology_24), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                },
                                trailingContent = {
                                    Switch(checked = thinkingEnabled, onCheckedChange = { viewModel.settings.setThinkingEnabled(it) })
                                },
                                modifier = Modifier.clickable { viewModel.settings.setThinkingEnabled(!thinkingEnabled) }
                            )
                        },
                        {
                            ThinkingControlPanel(
                                enabled = thinkingEnabled,
                                level = thinkingLevel,
                                budgetEnabled = thinkingBudgetEnabled,
                                budgetTokens = thinkingBudgetTokens,
                                onEnabledChange = { viewModel.settings.setThinkingEnabled(it) },
                                onLevelChange = { viewModel.settings.setThinkingLevel(it) },
                                onBudgetEnabledChange = { viewModel.settings.setThinkingBudgetEnabled(it) },
                                onBudgetTokensChange = { viewModel.settings.setThinkingBudgetTokens(it) },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                                showHeader = false,
                                providerName = null,
                                animateSections = true
                            )
                        }
                    )
                )

                // ── Section 3: Default OpenAI service tier ──
                SettingsGroup(
                    title = stringResource(R.string.default_service_tier),
                    items = listOf(
                        {
                            SettingsItem(
                                headlineContent = {
                                    Text(stringResource(R.string.openai_service_tier_title))
                                },
                                supportingContent = {
                                    Text(
                                        openAiServiceTierShortLabel(
                                            openAiServiceTierEnabled,
                                            openAiServiceTier,
                                        )
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.Speed,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = openAiServiceTierEnabled,
                                        onCheckedChange =
                                            viewModel.settings::setOpenAiServiceTierEnabled,
                                    )
                                },
                                modifier = Modifier.clickable {
                                    viewModel.settings.setOpenAiServiceTierEnabled(
                                        !openAiServiceTierEnabled,
                                    )
                                },
                            )
                        },
                        {
                            OpenAiServiceTierControlPanel(
                                enabled = openAiServiceTierEnabled,
                                tier = openAiServiceTier,
                                onEnabledChange =
                                    viewModel.settings::setOpenAiServiceTierEnabled,
                                onTierChange = viewModel.settings::setOpenAiServiceTier,
                                modifier = Modifier.padding(
                                    horizontal = 16.dp,
                                    vertical = 16.dp,
                                ),
                                showHeader = false,
                            )
                        },
                    ),
                )

                // ── Section 4: Generation Parameters ──
                SettingsGroup(
                    title = stringResource(R.string.generation_params),
                    items = listOf(
                        {
                            GenParamSlider(
                                label = stringResource(R.string.gen_temperature),
                                desc = stringResource(R.string.gen_temperature_desc),
                                value = defaultTemperature,
                                valueRange = 0f..2f,
                                format = { v -> String.format(Locale.US, "%.2f", v) },
                                onValueChange = { viewModel.settings.setDefaultTemperature(it) },
                                onReset = { viewModel.settings.setDefaultTemperature(null) }
                            )
                        },
                        {
                            val maxTokensPresets = intArrayOf(256, 512, 1024, 2048, 4096, 8192, 16384, 32768)
                            GenParamSlider(
                                label = stringResource(R.string.gen_max_tokens),
                                desc = stringResource(R.string.gen_max_tokens_desc),
                                value = defaultMaxTokens,
                                presets = maxTokensPresets,
                                format = { it.toString() },
                                onValueChange = { viewModel.settings.setDefaultMaxTokens(it) },
                                onReset = { viewModel.settings.setDefaultMaxTokens(null) }
                            )
                        },
                        {
                            GenParamSlider(
                                label = stringResource(R.string.gen_top_p),
                                desc = stringResource(R.string.gen_top_p_desc),
                                value = defaultTopP,
                                valueRange = 0f..1f,
                                format = { v -> String.format(Locale.US, "%.2f", v) },
                                onValueChange = { viewModel.settings.setDefaultTopP(it) },
                                onReset = { viewModel.settings.setDefaultTopP(null) }
                            )
                        },
                        {
                            GenParamSlider(
                                label = stringResource(R.string.gen_frequency_penalty),
                                desc = stringResource(R.string.gen_frequency_penalty_desc),
                                value = defaultFrequencyPenalty,
                                valueRange = -2f..2f,
                                format = { v -> String.format(Locale.US, "%.2f", v) },
                                onValueChange = { viewModel.settings.setDefaultFrequencyPenalty(it) },
                                onReset = { viewModel.settings.setDefaultFrequencyPenalty(null) }
                            )
                        },
                        {
                            GenParamSlider(
                                label = stringResource(R.string.gen_presence_penalty),
                                desc = stringResource(R.string.gen_presence_penalty_desc),
                                value = defaultPresencePenalty,
                                valueRange = -2f..2f,
                                format = { v -> String.format(Locale.US, "%.2f", v) },
                                onValueChange = { viewModel.settings.setDefaultPresencePenalty(it) },
                                onReset = { viewModel.settings.setDefaultPresencePenalty(null) }
                            )
                        }
                    )
                )

                // ── Section 5: Voice Read-Aloud (TTS) ──
                SettingsGroup(
                    title = stringResource(R.string.tts_title),
                    items = listOf(
                        {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.tts_enabled)) },
                                supportingContent = { Text(stringResource(R.string.tts_enabled_desc)) },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.VolumeUp,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = ttsEnabled,
                                        onCheckedChange = { viewModel.settings.setTtsEnabled(it) },
                                    )
                                },
                                modifier = Modifier.clickable { viewModel.settings.setTtsEnabled(!ttsEnabled) },
                            )
                        },
                        {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.tts_autoplay)) },
                                supportingContent = { Text(stringResource(R.string.tts_autoplay_desc)) },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = ttsAutoPlay,
                                        onCheckedChange = { viewModel.settings.setTtsAutoPlay(it) },
                                    )
                                },
                                modifier = Modifier.clickable { viewModel.settings.setTtsAutoPlay(!ttsAutoPlay) },
                            )
                        },
                        {
                            var langExpanded by remember { mutableStateOf(false) }
                            val langLabel = when (ttsLanguage) {
                                "en" -> stringResource(R.string.tts_language_en)
                                "zh" -> stringResource(R.string.tts_language_zh)
                                else -> stringResource(R.string.tts_language_system)
                            }
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.tts_language)) },
                                supportingContent = { Text(langLabel) },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.Language,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                trailingContent = {
                                    Box {
                                        TextButton(onClick = { langExpanded = true }) {
                                            Text(langLabel)
                                        }
                                        DropdownMenu(
                                            expanded = langExpanded,
                                            onDismissRequest = { langExpanded = false },
                                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                            tonalElevation = 6.dp,
                                            shape = RoundedCornerShape(12.dp),
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.tts_language_system)) },
                                                onClick = {
                                                    langExpanded = false
                                                    viewModel.settings.setTtsLanguage("system")
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.tts_language_en)) },
                                                onClick = {
                                                    langExpanded = false
                                                    viewModel.settings.setTtsLanguage("en")
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.tts_language_zh)) },
                                                onClick = {
                                                    langExpanded = false
                                                    viewModel.settings.setTtsLanguage("zh")
                                                },
                                            )
                                        }
                                    }
                                },
                            )
                        },
                        {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 16.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Speed,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.tts_speech_rate),
                                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = stringResource(R.string.tts_speech_rate_desc),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 4.dp),
                                        )
                                        Text(
                                            text = String.format(Locale.US, "%.1f×", ttsSpeechRate),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Slider(
                                            value = ttsSpeechRate,
                                            onValueChange = { viewModel.settings.setTtsSpeechRate(it) },
                                            valueRange = 0.5f..2.0f,
                                            modifier = Modifier.padding(top = 8.dp),
                                        )
                                    }
                                }
                            }
                        },
                        {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.tts_test)) },
                                supportingContent = { Text(stringResource(R.string.tts_test_desc)) },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                trailingContent = {
                                    TextButton(onClick = {
                                        TtsManager.reinit(ttsContext)
                                        TtsManager.testSpeak()
                                    }) {
                                        Text(stringResource(R.string.tts_test_run))
                                    }
                                },
                            )
                        },
                        {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.tts_system_settings)) },
                                supportingContent = { Text(stringResource(R.string.tts_system_settings_desc)) },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.Build,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                trailingContent = {
                                    TextButton(onClick = {
                                        ttsContext.startActivity(TtsManager.systemTtsSettingsIntent())
                                    }) {
                                        Text(stringResource(R.string.tts_open))
                                    }
                                },
                            )
                        },
                        {
                            if (ttsLangMissingData) {
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.tts_install_data)) },
                                    supportingContent = { Text(stringResource(R.string.tts_install_data_desc)) },
                                    leadingContent = {
                                        Icon(
                                            Icons.Default.Build,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    trailingContent = {
                                        TextButton(onClick = {
                                            ttsContext.startActivity(TtsManager.installTtsDataIntent())
                                        }) {
                                            Text(stringResource(R.string.tts_install))
                                        }
                                    },
                                )
                            }
                        },
                        {
                            if (!ttsDiagnostic.available) {
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.tts_install_google_tts)) },
                                    supportingContent = { Text(stringResource(R.string.tts_install_google_tts_desc)) },
                                    leadingContent = {
                                        Icon(
                                            Icons.Default.Build,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    trailingContent = {
                                        TextButton(onClick = {
                                            try {
                                                ttsContext.startActivity(TtsManager.installGoogleTtsIntent())
                                            } catch (_: android.content.ActivityNotFoundException) {
                                                val webIntent = android.content.Intent(
                                                    android.content.Intent.ACTION_VIEW,
                                                    android.net.Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.tts"),
                                                )
                                                try { ttsContext.startActivity(webIntent) } catch (_: Throwable) {}
                                            }
                                        }) {
                                            Text(stringResource(R.string.tts_install_google))
                                        }
                                    },
                                )
                            }
                        },

                    ),
                )

                TtsProviderModelSection(viewModel)

                // ── Section 6: Export ──
                SettingsGroup(
                    title = stringResource(R.string.share_export_title),
                    items = listOf(
                        {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.share_include_thinking)) },
                                supportingContent = { Text(stringResource(R.string.share_include_thinking_desc)) },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.Visibility,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = shareIncludeThinking,
                                        onCheckedChange = { viewModel.settings.setShareIncludeThinking(it) },
                                    )
                                },
                                modifier = Modifier.clickable { viewModel.settings.setShareIncludeThinking(!shareIncludeThinking) },
                            )
                        },
                        {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.share_include_tools)) },
                                supportingContent = { Text(stringResource(R.string.share_include_tools_desc)) },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.Memory,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = shareIncludeTools,
                                        onCheckedChange = { viewModel.settings.setShareIncludeTools(it) },
                                    )
                                },
                                modifier = Modifier.clickable { viewModel.settings.setShareIncludeTools(!shareIncludeTools) },
                            )
                        }
                    ),
                )

                // ── Section 7: Voice Conversation ──
                SettingsGroup(
                    title = stringResource(R.string.voice_conversation),
                    items = listOf(
                        {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.voice_conversation)) },
                                supportingContent = { Text(stringResource(R.string.voice_conversation_desc)) },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.Mic,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = voiceConversationEnabled,
                                        onCheckedChange = { viewModel.settings.setVoiceConversationEnabled(it) },
                                    )
                                },
                                modifier = Modifier.clickable { viewModel.settings.setVoiceConversationEnabled(!voiceConversationEnabled) },
                            )
                        },
                    ),
                )

                // ── Section 8: ASR (Speech Recognition) ──
                val asrScope = rememberCoroutineScope()
                SettingsGroup(
                    title = stringResource(R.string.asr_settings_title),
                    items = buildList {
                        add {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.asr_settings_title)) },
                                supportingContent = { Text(stringResource(R.string.asr_settings_desc)) },
                                leadingContent = {
                                    Icon(Icons.Default.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                },
                                trailingContent = {
                                    Text(
                                        when (asrEnginePref) {
                                            "system" -> stringResource(R.string.asr_engine_system)
                                            "vosk" -> stringResource(R.string.asr_engine_vosk)
                                            "whisper" -> stringResource(R.string.asr_engine_whisper)
                                            else -> stringResource(R.string.asr_engine_auto)
                                        },
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                modifier = Modifier.clickable {
                                    val next = when (asrEnginePref) {
                                        "auto" -> "system"
                                        "system" -> "vosk"
                                        "vosk" -> "whisper"
                                        else -> "auto"
                                    }
                                    viewModel.settings.setAsrEnginePref(next)
                                },
                            )
                        }
                        add {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.asr_use_remote)) },
                                supportingContent = { Text(stringResource(R.string.asr_use_remote_desc)) },
                                leadingContent = { Icon(Icons.Default.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                trailingContent = { Switch(checked = asrUseRemote, onCheckedChange = { viewModel.settings.setAsrUseRemote(it) }) },
                            )
                        }
                        if (asrUseRemote) {
                            add {
                                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    val asrPresets = remember {
                                        listOf(
                                            Triple("OpenAI Whisper", "whisper-1", "https://api.openai.com/v1"),
                                            Triple("Groq Whisper Large v3", "whisper-large-v3", "https://api.groq.com/openai/v1"),
                                            Triple("Groq Distil Whisper", "distil-whisper-large-v3", "https://api.groq.com/openai/v1"),
                                            Triple("OpenRouter Whisper", "openai/whisper-1", "https://openrouter.ai/api/v1"),
                                        )
                                    }
                                    val presetIdx = asrPresets.indexOfFirst { it.second == asrRemoteModel && it.third == asrRemoteBaseUrl }
                                    Text(
                                        text = if (presetIdx >= 0) stringResource(R.string.asr_model_label, asrPresets[presetIdx].first)
                                        else stringResource(R.string.asr_custom_model),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    TextButton(onClick = {
                                        val nextIdx = if (presetIdx < 0) 0 else (presetIdx + 1) % asrPresets.size
                                        val p = asrPresets[nextIdx]
                                        viewModel.settings.setAsrRemoteModel(p.second)
                                        viewModel.settings.setAsrRemoteBaseUrl(p.third)
                                    }) {
                                        Text(if (presetIdx >= 0) stringResource(R.string.asr_switch_preset) else stringResource(R.string.asr_select_preset))
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = asrRemoteBaseUrl,
                                        onValueChange = { viewModel.settings.setAsrRemoteBaseUrl(it) },
                                        label = { Text(stringResource(R.string.asr_remote_base_url)) },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp),
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = asrRemoteApiKey,
                                        onValueChange = { viewModel.settings.setAsrRemoteApiKey(it) },
                                        label = { Text(stringResource(R.string.asr_remote_api_key)) },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp),
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = asrRemoteModel,
                                        onValueChange = { viewModel.settings.setAsrRemoteModel(it) },
                                        label = { Text(stringResource(R.string.asr_remote_model)) },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp),
                                    )
                                }
                            }
                            add {
                                AsrProviderModelSection(viewModel)
                            }
                        }
                        if (!asrUseRemote) {
                            add {
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.asr_no_engine_available)) },
                                    supportingContent = { Text(stringResource(R.string.asr_use_remote_desc)) },
                                    leadingContent = { Icon(Icons.Default.Build, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                    trailingContent = {},
                                )
                            }
                        }
                        add {
                            SettingsVoskModelsSection(
                                context = ttsContext,
                                voskTranscriber = viewModel.voiceConversation.getVoskTranscriber(),
                                voiceLanguage = voiceLanguage,
                                onVoiceLanguageChange = { viewModel.settings.setVoiceLanguage(it) },
                            )
                        }
                    },
                )
            }

            if (showDocFab) { Spacer(modifier = Modifier.height(80.dp)) }
    }
}
