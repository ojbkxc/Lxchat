package com.lxseek.chat.ui.settings

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import com.lxseek.chat.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.mcp.McpConnectionStatus
import com.lxseek.chat.mcp.McpServerSnapshot
import com.lxseek.chat.util.noOpBringIntoView

@Composable
internal fun McpStatusDot(status: McpConnectionStatus) {
    val description = stringResource(
        when (status) {
            McpConnectionStatus.IDLE -> R.string.mcp_status_idle
            McpConnectionStatus.CONNECTING -> R.string.mcp_status_connecting
            McpConnectionStatus.CONNECTED -> R.string.mcp_status_connected
            McpConnectionStatus.ERROR -> R.string.mcp_status_error
        },
    )
    val color = when (status) {
        McpConnectionStatus.IDLE -> {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        }
        McpConnectionStatus.CONNECTING -> {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        }
        McpConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primary
        McpConnectionStatus.ERROR -> MaterialTheme.colorScheme.error
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color = color, shape = CircleShape)
            .semantics { contentDescription = description },
    )
}

@Composable
internal fun McpHeaderItem(
    header: McpHeaderDraft,
    onHeaderChange: (McpHeaderDraft) -> Unit,
    onDelete: () -> Unit,
) {
    SettingsItem(
        headlineContent = {
            Column(modifier = Modifier.fillMaxWidth()) {
                McpHeaderField(
                    label = stringResource(R.string.mcp_header_name),
                    value = header.name,
                    onValueChange = { onHeaderChange(header.copy(name = it)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                McpHeaderField(
                    label = stringResource(R.string.mcp_header_value),
                    value = header.value,
                    onValueChange = { onHeaderChange(header.copy(value = it)) },
                    password = !header.revealValue,
                    modifier = Modifier.fillMaxWidth(),
                    trailingContent = {
                        IconButton(
                            onClick = {
                                onHeaderChange(header.copy(revealValue = !header.revealValue))
                            },
                        ) {
                            Icon(
                                if (header.revealValue) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                stringResource(
                                    if (header.revealValue) {
                                        R.string.mcp_hide_header_value
                                    } else {
                                        R.string.mcp_show_header_value
                                    },
                                ),
                            )
                        }
                    },
                )
            }
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    stringResource(R.string.mcp_delete_header),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}

@Composable
internal fun McpEnvItem(
    env: McpEnvDraft,
    onEnvChange: (McpEnvDraft) -> Unit,
    onDelete: () -> Unit,
) {
    SettingsItem(
        headlineContent = {
            Column(modifier = Modifier.fillMaxWidth()) {
                McpHeaderField(
                    label = stringResource(R.string.mcp_env_var_name),
                    value = env.name,
                    onValueChange = { onEnvChange(env.copy(name = it)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                McpHeaderField(
                    label = stringResource(R.string.mcp_env_var_value),
                    value = env.value,
                    onValueChange = { onEnvChange(env.copy(value = it)) },
                    password = !env.revealValue,
                    modifier = Modifier.fillMaxWidth(),
                    trailingContent = {
                        IconButton(
                            onClick = {
                                onEnvChange(env.copy(revealValue = !env.revealValue))
                            },
                        ) {
                            Icon(
                                if (env.revealValue) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                stringResource(
                                    if (env.revealValue) {
                                        R.string.mcp_hide_env_value
                                    } else {
                                        R.string.mcp_show_env_value
                                    },
                                ),
                            )
                        }
                    },
                )
            }
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    stringResource(R.string.mcp_delete_env_var),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}

@Composable
private fun McpHeaderField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.noOpBringIntoView(),
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        visualTransformation = if (password) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        trailingIcon = trailingContent,
        shape = RoundedCornerShape(12.dp),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@Composable
internal fun McpStatusText(
    snapshot: McpServerSnapshot?,
    includeError: Boolean = false,
) {
    val state = McpStatusUiState(
        status = snapshot?.status ?: McpConnectionStatus.IDLE,
        enabledToolCount = snapshot?.tools?.count { it.enabled } ?: 0,
        error = snapshot?.error?.takeIf(String::isNotBlank),
    )
    Crossfade(
        targetState = state,
        animationSpec = tween(durationMillis = 250),
        label = "mcpStatusText",
    ) { current ->
        val color = when (current.status) {
            McpConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primary
            McpConnectionStatus.ERROR -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = when (current.status) {
                    McpConnectionStatus.IDLE -> stringResource(R.string.mcp_status_idle)
                    McpConnectionStatus.CONNECTING -> stringResource(R.string.mcp_status_connecting)
                    McpConnectionStatus.CONNECTED -> stringResource(R.string.mcp_status_connected)
                    McpConnectionStatus.ERROR -> stringResource(R.string.mcp_status_error)
                },
                color = color,
            )
            when {
                current.status == McpConnectionStatus.CONNECTED -> Text(
                    text = stringResource(
                        R.string.mcp_tools_enabled,
                        current.enabledToolCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                includeError && current.error != null -> Text(
                    text = current.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
internal fun McpStatusIcon(status: McpConnectionStatus) {
    Crossfade(
        targetState = status,
        animationSpec = tween(durationMillis = 250),
        label = "mcpStatusIcon",
    ) { current ->
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (current) {
                McpConnectionStatus.IDLE -> Icon(Icons.Default.CloudOff, null)
                McpConnectionStatus.CONNECTING -> CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                )
                McpConnectionStatus.CONNECTED -> Icon(
                    Icons.Default.CheckCircle,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                McpConnectionStatus.ERROR -> Icon(
                    Icons.Default.Error,
                    null,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
internal fun McpLabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Box(modifier = Modifier.noOpBringIntoView().padding(top = 8.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = isError,
                supportingText = supportingText?.let { text -> { Text(text) } },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                visualTransformation = if (password) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                trailingIcon = trailingContent,
                shape = RoundedCornerShape(12.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}