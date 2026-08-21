package com.lxseek.chat.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.lxseek.chat.viewmodel.ChatViewModel

/** Local, unsaved form state mirroring an [ImGatewayConfig]. */
private data class ImGatewayFormState(
    val enabled: Boolean,
    val platform: String,
    val baseUrl: String,
    val token: String,
    val pollIntervalMs: String,
    val autoReplyModel: String,
) {
    companion object {
        fun from(config: ImGatewayConfig): ImGatewayFormState = ImGatewayFormState(
            enabled = config.enabled,
            platform = config.platform,
            baseUrl = config.baseUrl,
            token = config.token,
            pollIntervalMs = config.pollIntervalMs.toString(),
            autoReplyModel = config.autoReplyModel,
        )
    }

    fun toConfig(): ImGatewayConfig = ImGatewayConfig(
        enabled = enabled,
        platform = platform.trim().ifBlank { "wechat" },
        baseUrl = baseUrl.trim(),
        token = token,
        pollIntervalMs = pollIntervalMs.trim().toLongOrNull()?.takeIf { it > 0 } ?: 5_000L,
        autoReplyModel = autoReplyModel.trim(),
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

    var form by remember { mutableStateOf(ImGatewayFormState.from(config)) }
    var initialized by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }

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
                OutlinedTextField(
                    value = form.autoReplyModel,
                    onValueChange = { form = form.copy(autoReplyModel = it) },
                    label = { Text(stringResource(R.string.im_gateway_auto_reply_model)) },
                    placeholder = { Text(stringResource(R.string.im_gateway_auto_reply_model_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
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
}