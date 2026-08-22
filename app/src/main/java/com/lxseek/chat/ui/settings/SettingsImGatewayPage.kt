package com.lxseek.chat.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.im.ImGatewayConfig
import com.lxseek.chat.model.ModelId
import com.lxseek.chat.model.apiModelName
import com.lxseek.chat.viewmodel.ChatViewModel

/** Local, unsaved form state mirroring an [ImGatewayConfig]. */
private data class ImGatewayFormState(
    val enabled: Boolean,
    val platform: String,
    val baseUrl: String,
    val token: String,
    val pollIntervalMs: String,
    val autoReplyModel: String,
    val proactiveEnabled: Boolean,
    val proactiveIdleMinutes: String,
    val proactiveSilentStart: String,
    val proactiveSilentEnd: String,
    val proactiveIgnoreGroups: Boolean,
    val humanizeMessages: Boolean,
) {
    companion object {
        fun from(config: ImGatewayConfig): ImGatewayFormState = ImGatewayFormState(
            enabled = config.enabled,
            platform = config.platform,
            baseUrl = config.baseUrl,
            token = config.token,
            pollIntervalMs = config.pollIntervalMs.toString(),
            autoReplyModel = config.autoReplyModel,
            proactiveEnabled = config.proactiveEnabled,
            proactiveIdleMinutes = config.proactiveIdleMinutes.toString(),
            proactiveSilentStart = config.proactiveSilentStart,
            proactiveSilentEnd = config.proactiveSilentEnd,
            proactiveIgnoreGroups = config.proactiveIgnoreGroups,
            humanizeMessages = config.humanizeMessages,
        )
    }

    fun toConfig(): ImGatewayConfig = ImGatewayConfig(
        enabled = enabled,
        platform = platform.trim().ifBlank { "wechat" },
        baseUrl = baseUrl.trim(),
        token = token,
        pollIntervalMs = pollIntervalMs.trim().toLongOrNull()?.takeIf { it > 0 } ?: 5_000L,
        autoReplyModel = autoReplyModel.trim(),
        proactiveEnabled = proactiveEnabled,
        proactiveIdleMinutes = proactiveIdleMinutes.trim().toIntOrNull()?.coerceAtLeast(1) ?: 120,
        proactiveSilentStart = proactiveSilentStart.trim(),
        proactiveSilentEnd = proactiveSilentEnd.trim(),
        proactiveIgnoreGroups = proactiveIgnoreGroups,
        humanizeMessages = humanizeMessages,
    )
}

/**
 * IM gateway configuration page. Reads the persisted [ImGatewayConfig] through
 * [ChatViewModel.settings][ChatViewModel.settings] and persists edits back through the repository,
 * which the [com.lxseek.chat.im.ImBridgeService] observes to (re)build the active channel.
 */
@Composable
fun SettingsImGatewayPage(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val config by viewModel.settings.imGatewayConfig.collectAsState()
    val context = LocalContext.current

    // Models available for the auto-reply picker. We union synced provider models with custom
    // models so the user can pick anything they have configured elsewhere in the app.
    val availableModels by viewModel.settings.availableModels.collectAsState()
    val customModels by viewModel.settings.customModels.collectAsState()
    val modelAliases by viewModel.settings.modelAliases.collectAsState()
    val enabledModels by viewModel.settings.enabledModels.collectAsState()

    var form by remember { mutableStateOf(ImGatewayFormState.from(config)) }
    var initialized by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }

    // Candidate list for the picker: enabled models first, then any other known models, deduped.
    val pickerModels = remember(availableModels, customModels, enabledModels) {
        val synced = availableModels.values.flatten()
        val all = (enabledModels.asSequence() + synced.asSequence() + customModels.asSequence())
            .distinct()
            .sorted()
            .toList()
        all
    }

    // Apply the persisted config once it has loaded (the StateFlow may start with the default
    // before DataStore emits). Never overwrite an already-populated form after the user edits.
    LaunchedEffect(config) {
        if (!initialized && config != ImGatewayConfig()) {
            form = ImGatewayFormState.from(config)
            initialized = true
        }
    }

    fun save() {
        if (form.enabled && form.baseUrl.isBlank()) {
            Toast.makeText(context, context.getString(R.string.im_gateway_validation), Toast.LENGTH_SHORT).show()
            return
        }
        viewModel.settings.saveImGatewayConfig(form.toConfig())
        Toast.makeText(context, context.getString(R.string.im_gateway_saved), Toast.LENGTH_SHORT).show()
    }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_im_gateway),
        onBack = onBack,
        scrollState = rememberScrollState(),
    ) {
        // Status summary
        val configured = config.isConfigured
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(
                    if (configured) R.string.im_gateway_configured else R.string.im_gateway_not_configured,
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.im_gateway_howto),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        )

        SettingsGroup(title = stringResource(R.string.settings_group_im), items = listOf(
            {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.im_gateway_enabled),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.im_gateway_enabled_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = form.enabled,
                        onCheckedChange = { form = form.copy(enabled = it) },
                    )
                }
            },
            {
                OutlinedTextField(
                    value = form.platform,
                    onValueChange = { form = form.copy(platform = it) },
                    label = { Text(stringResource(R.string.im_gateway_platform)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
            },
        ))

        SettingsGroup(title = stringResource(R.string.settings_group_connection), items = listOf(
            {
                OutlinedTextField(
                    value = form.baseUrl,
                    onValueChange = { form = form.copy(baseUrl = it) },
                    label = { Text(stringResource(R.string.im_gateway_base_url)) },
                    placeholder = { Text("http(s)://host:port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
            },
            {
                OutlinedTextField(
                    value = form.token,
                    onValueChange = { form = form.copy(token = it) },
                    label = { Text(stringResource(R.string.im_gateway_token)) },
                    singleLine = true,
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(
                                imageVector = if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(R.string.im_gateway_show_token),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
            },
            {
                OutlinedTextField(
                    value = form.pollIntervalMs,
                    onValueChange = { input ->
                        if (input.all(Char::isDigit) || input.isEmpty()) form = form.copy(pollIntervalMs = input)
                    },
                    label = { Text(stringResource(R.string.im_gateway_poll_interval)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
            },
            {
                // Auto-reply model picker: a clickable row that opens a searchable dialog instead
                // of a free-form text field, so users select a real configured model.
                val currentModel = form.autoReplyModel
                val parsed = currentModel.takeIf { it.isNotBlank() && it.contains(":") }
                    ?.let { ModelId.parse(it) }
                val displayName = parsed?.let { modelAliases[currentModel] ?: it.apiModelName }
                    ?: stringResource(R.string.im_gateway_auto_reply_model_current_default)
                val providerName = parsed?.providerName
                Surface(
                    onClick = { showModelPicker = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.im_gateway_auto_reply_model),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            if (providerName != null) {
                                Text(
                                    text = "$displayName · $providerName",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    text = displayName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        ))

        SettingsGroup(title = stringResource(R.string.settings_group_proactive), items = listOf(
            {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.im_gateway_proactive_enabled),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.im_gateway_proactive_enabled_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = form.proactiveEnabled,
                        onCheckedChange = { form = form.copy(proactiveEnabled = it) },
                    )
                }
            },
            {
                OutlinedTextField(
                    value = form.proactiveIdleMinutes,
                    onValueChange = { input ->
                        if (input.all(Char::isDigit) || input.isEmpty()) form = form.copy(proactiveIdleMinutes = input)
                    },
                    label = { Text(stringResource(R.string.im_gateway_proactive_idle_minutes)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
            },
            {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = form.proactiveSilentStart,
                        onValueChange = { form = form.copy(proactiveSilentStart = it) },
                        label = { Text(stringResource(R.string.im_gateway_proactive_silent_start)) },
                        placeholder = { Text("HH:MM") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = form.proactiveSilentEnd,
                        onValueChange = { form = form.copy(proactiveSilentEnd = it) },
                        label = { Text(stringResource(R.string.im_gateway_proactive_silent_end)) },
                        placeholder = { Text("HH:MM") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            },
            {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.im_gateway_proactive_ignore_groups),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.im_gateway_proactive_ignore_groups_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = form.proactiveIgnoreGroups,
                        onCheckedChange = { form = form.copy(proactiveIgnoreGroups = it) },
                    )
                }
            },
            {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.im_gateway_humanize),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.im_gateway_humanize_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = form.humanizeMessages,
                        onCheckedChange = { form = form.copy(humanizeMessages = it) },
                    )
                }
            },
        ))

        Divider(modifier = Modifier.padding(vertical = 4.dp))

        Button(
            onClick = ::save,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(imageVector = Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.im_gateway_save))
        }
        Spacer(modifier = Modifier.height(8.dp))
    }

    if (showModelPicker) {
        ImGatewayModelPickerDialog(
            models = pickerModels,
            modelAliases = modelAliases,
            selected = form.autoReplyModel,
            onSelect = { form = form.copy(autoReplyModel = it.orEmpty()) },
            onDismiss = { showModelPicker = false },
        )
    }
}

/**
 * Searchable model picker for the IM gateway auto-reply model field. Mirrors the
 * [com.lxseek.chat.ui.tasks.TaskEditorSupportingComponents.ModelPickerDialog] UX but adds a
 * real-time search box so users can filter long model lists. A "default" option (mapped to the
 * empty string) is always offered at the top.
 */
@Composable
private fun ImGatewayModelPickerDialog(
    models: List<String>,
    modelAliases: Map<String, String>,
    selected: String,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // Filter against alias, bare model id, and provider name so users can search any of them.
    val filtered = remember(models, modelAliases, query) {
        if (query.isBlank()) {
            models
        } else {
            val q = query.trim().lowercase()
            models.filter { model ->
                val parsed = ModelId.parse(model)
                val alias = modelAliases[model]?.lowercase().orEmpty()
                val bare = parsed.apiModelName.lowercase()
                val provider = parsed.providerName.lowercase()
                val full = model.lowercase()
                alias.contains(q) || bare.contains(q) || provider.contains(q) || full.contains(q)
            }
        }
    }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.im_gateway_auto_reply_model_select), fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.im_gateway_auto_reply_model_search_hint)) },
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
                if (models.isEmpty()) {
                    Text(
                        text = stringResource(R.string.im_gateway_auto_reply_model_no_models),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    ) {
                        item {
                            ModelPickerRow(
                                label = stringResource(R.string.im_gateway_auto_reply_model_default),
                                sub = null,
                                selected = selected.isBlank(),
                                onClick = { onSelect(null); onDismiss() },
                            )
                        }
                        if (filtered.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.im_gateway_auto_reply_model_no_models),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                )
                            }
                        } else {
                            items(filtered, key = { it }) { model ->
                                val parsed = ModelId.parse(model)
                                ModelPickerRow(
                                    label = modelAliases[model] ?: parsed.apiModelName,
                                    sub = parsed.providerName,
                                    selected = selected == model,
                                    onClick = { onSelect(model); onDismiss() },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.provider_close)) } },
    )
}

/** Single row inside [ImGatewayModelPickerDialog]: radio + label + optional provider subtitle. */
@Composable
private fun ModelPickerRow(label: String, sub: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (sub != null) {
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}