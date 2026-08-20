package com.lxseek.chat.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.speech.ModelCapability
import com.lxseek.chat.viewmodel.ChatViewModel

private val NETWORK_TTS_VOICES = listOf(
    "alloy", "echo", "fable", "onyx", "nova", "shimmer",
)

/**
 * Provider-backed ASR model selection for the settings page. Shows a synced-model picker filtered
 * to recognizer (Whisper-family) models; the chosen model drives the online transcription engine's
 * base URL / key / model id (wired in ChatViewModel).
 */
@Composable
fun AsrProviderModelSection(viewModel: ChatViewModel) {
    val availableModels by viewModel.settings.availableModels.collectAsState()
    val modelAliases by viewModel.settings.modelAliases.collectAsState()
    val asrProviderModel by viewModel.settings.asrProviderModel.collectAsState()

    SettingsGroup(
        title = stringResource(R.string.asr_provider_model_title),
        items = listOf({
            ProviderSpeechModelPicker(
                selectedModel = asrProviderModel,
                availableModels = availableModels,
                modelAliases = modelAliases,
                isLikelyCapable = { ModelCapability.isLikelyAsrModel(it) },
                onSelect = { viewModel.settings.setAsrProviderModel(it) },
            )
        })
    )
}

/**
 * Provider-backed TTS model selection plus a voice dropdown. Selecting a synthesizer model routes
 * read-aloud through the provider's `/audio/speech` endpoint (wired in ChatViewModel); clearing it
 * keeps the system TTS engine in charge.
 */
@Composable
fun TtsProviderModelSection(viewModel: ChatViewModel) {
    val availableModels by viewModel.settings.availableModels.collectAsState()
    val modelAliases by viewModel.settings.modelAliases.collectAsState()
    val ttsProviderModel by viewModel.settings.ttsProviderModel.collectAsState()
    val ttsProviderVoice by viewModel.settings.ttsProviderVoice.collectAsState()

    SettingsGroup(
        title = stringResource(R.string.tts_provider_model_title),
        items = listOf(
            {
                ProviderSpeechModelPicker(
                    selectedModel = ttsProviderModel,
                    availableModels = availableModels,
                    modelAliases = modelAliases,
                    isLikelyCapable = { ModelCapability.isLikelyTtsModel(it) },
                    onSelect = { viewModel.settings.setTtsProviderModel(it) },
                )
            },
            {
                ProviderVoiceItem(
                    selected = ttsProviderVoice,
                    onSelect = { viewModel.settings.setTtsProviderVoice(it) },
                )
            },
        )
    )
}

@Composable
private fun ProviderVoiceItem(
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    SettingsItem(
        headlineContent = { Text(stringResource(R.string.tts_provider_voice)) },
        supportingContent = { Text(selected, style = MaterialTheme.typography.bodySmall) },
        leadingContent = {
            Icon(Icons.Default.Mic, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        },
        trailingContent = {
            Icon(Icons.Default.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        modifier = Modifier.clickable { expanded = true },
    )
    Box {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 6.dp,
            shape = RoundedCornerShape(12.dp),
        ) {
            NETWORK_TTS_VOICES.forEach { voice ->
                DropdownMenuItem(
                    text = { Text(voice) },
                    trailingIcon = {
                        if (voice == selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    },
                    onClick = { onSelect(voice); expanded = false },
                )
            }
        }
    }
}