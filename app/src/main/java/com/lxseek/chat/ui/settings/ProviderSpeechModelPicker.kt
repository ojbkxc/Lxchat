package com.lxseek.chat.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.model.ModelId
import com.lxseek.chat.model.apiModelName
import com.lxseek.chat.ui.components.providerIcon
import com.lxseek.chat.util.Constants

/**
 * Reusable model picker for the ASR and TTS settings pages. Renders a tappable row showing the
 * currently selected "Provider:modelId" (mirroring the chat/image-gen pickers) and, on tap, an
 * [AlertDialog] listing the synced provider models filtered to those that look capable of the
 * speech task via [isLikelyCapable] — with a "show all" escape hatch for unusually-named models.
 *
 * Holds no settings state; the caller owns the stored selection and writes it through the normal
 * SettingsRepository setters so the picker stays consistent across recomposition.
 */
@Composable
fun ProviderSpeechModelPicker(
    selectedModel: String?,
    availableModels: Map<String, List<String>>,
    modelAliases: Map<String, String>,
    enabled: Boolean = true,
    canClear: Boolean = true,
    isLikelyCapable: (String) -> Boolean,
    onSelect: (String?) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }

    val allModels = remember(availableModels) {
        availableModels.values.flatten().distinct().sorted()
    }
    val capableModels = remember(allModels) { allModels.filter { isLikelyCapable(it) } }

    ProviderSpeechModelRow(
        selectedModel = selectedModel,
        modelAliases = modelAliases,
        enabled = enabled,
        trailingContent = {
            if (selectedModel != null && canClear) {
                IconButton(onClick = { onSelect(null) }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.provider_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        onClick = { if (enabled) showDialog = true },
    )

    if (showDialog) {
        ProviderSpeechModelDialog(
            selectedModel = selectedModel,
            allModels = allModels,
            capableModels = capableModels,
            modelAliases = modelAliases,
            onDismiss = { showDialog = false },
            onSelect = { model ->
                onSelect(model)
                showDialog = false
            },
        )
    }
}

@Composable
private fun ProviderSpeechModelRow(
    selectedModel: String?,
    modelAliases: Map<String, String>,
    enabled: Boolean,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val parsed = selectedModel?.takeIf { it.contains(":") }?.let { ModelId.parse(it) }
    val displayName = parsed?.let { modelAliases[selectedModel] ?: it.apiModelName }
        ?: stringResource(R.string.speech_model_none)
    val providerName = parsed?.providerName
    val iconRes = providerName?.let { providerIcon(it) } ?: 0

    SettingsItem(
        headlineContent = {
            Text(
                displayName,
                color = if (parsed == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
        },
        supportingContent = if (providerName != null) {
            { Text(providerName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) }
        } else {
            { Text(stringResource(R.string.speech_models_empty), style = MaterialTheme.typography.bodySmall) }
        },
        leadingContent = {
            when {
                parsed == null -> Icon(Icons.Default.Chat, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                providerName.equals(Constants.PROVIDER_LOCAL, ignoreCase = true) -> Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                iconRes != 0 -> Icon(painterResource(iconRes), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                else -> Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            }
        },
        trailingContent = trailingContent,
        modifier = Modifier
            .heightIn(min = 64.dp)
            .clickable(enabled = enabled, onClick = onClick),
    )
}

@Composable
private fun ProviderSpeechModelDialog(
    selectedModel: String?,
    allModels: List<String>,
    capableModels: List<String>,
    modelAliases: Map<String, String>,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    var showAll by remember { mutableStateOf(false) }
    val pickList = if (showAll || capableModels.isEmpty()) allModels else capableModels

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.speech_model_select), fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (capableModels.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { showAll = !showAll }
                    ) {
                        Checkbox(checked = showAll, onCheckedChange = { showAll = it })
                        Text(stringResource(R.string.speech_model_show_all), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                when {
                    pickList.isEmpty() -> Text(
                        stringResource(R.string.speech_models_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(pickList, key = { it }) { model ->
                            val parsed = ModelId.parse(model)
                            val displayName = modelAliases[model] ?: parsed.apiModelName
                            SettingsItem(
                                headlineContent = {
                                    Text(displayName, fontWeight = if (selectedModel == model) FontWeight.Bold else FontWeight.Normal)
                                },
                                supportingContent = { Text(parsed.providerName, style = MaterialTheme.typography.bodySmall) },
                                leadingContent = {
                                    RadioButton(selected = selectedModel == model, onClick = { onSelect(model) })
                                },
                                modifier = Modifier.clickable { onSelect(model) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.provider_cancel)) } }
    )
}