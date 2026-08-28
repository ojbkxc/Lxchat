package com.lxseek.chat.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.data.McpServerConfig
import com.lxseek.chat.data.McpTransportType
import com.lxseek.chat.mcp.McpConnectionStatus
import com.lxseek.chat.mcp.McpServerSnapshot
import java.util.UUID

internal data class McpEditorRoute(
    val initial: McpServerConfig,
    val isNew: Boolean,
)

internal data class McpHeaderDraft(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val value: String = "",
    val revealValue: Boolean = false,
)

internal data class McpEnvDraft(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val value: String = "",
    val revealValue: Boolean = false,
)

internal data class McpStatusUiState(
    val status: McpConnectionStatus,
    val enabledToolCount: Int,
    val error: String?,
)

internal fun buildMcpHeaders(headers: List<McpHeaderDraft>): Map<String, String> {
    return buildMap {
        headers
            .filterNot { it.name.isBlank() && it.value.isBlank() }
            .forEach { header ->
                put(header.name.trim(), header.value.trim())
            }
    }
}

internal fun buildMcpEnv(env: List<McpEnvDraft>): Map<String, String> {
    return buildMap {
        env
            .filterNot { it.name.isBlank() && it.value.isBlank() }
            .forEach { entry ->
                put(entry.name.trim(), entry.value.trim())
            }
    }
}

internal fun isValidMcpUrl(value: String): Boolean {
    val uri = runCatching { java.net.URI(value.trim()) }.getOrNull() ?: return false
    return (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
        uri.host != null &&
        uri.userInfo == null &&
        uri.fragment == null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun McpServerEditor(
    initial: McpServerConfig,
    snapshot: McpServerSnapshot?,
    isNew: Boolean,
    onBack: () -> Unit,
    onSave: (McpServerConfig) -> Unit,
    onRefresh: () -> Unit,
) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var headerRows by remember(initial.id) {
        mutableStateOf(
            initial.headers.map { (name, value) ->
                McpHeaderDraft(name = name, value = value)
            },
        )
    }
    var envRows by remember(initial.id) {
        mutableStateOf(
            initial.env.map { (name, value) ->
                McpEnvDraft(name = name, value = value)
            },
        )
    }
    val parsedHeaders = remember(headerRows) { buildMcpHeaders(headerRows) }
    val parsedEnv = remember(envRows) { buildMcpEnv(envRows) }
    val validUrl = remember(draft.url) { isValidMcpUrl(draft.url) }
    val canSave = draft.name.isNotBlank() && validUrl
    val scrollState = rememberScrollState()
    fun save() {
        if (!canSave) return
        onSave(
            draft.copy(
                name = draft.name.trim(),
                url = draft.url.trim(),
                headers = parsedHeaders,
                env = parsedEnv,
            ),
        )
    }
    fun updateHeader(updated: McpHeaderDraft) {
        headerRows = headerRows.map { current ->
            if (current.id == updated.id) updated else current
        }
    }
    fun updateEnv(updated: McpEnvDraft) {
        envRows = envRows.map { current ->
            if (current.id == updated.id) updated else current
        }
    }

    CollapsingSettingsScaffold(
        title = stringResource(if (isNew) R.string.mcp_add_server else R.string.mcp_edit_server),
        onBack = onBack,
        scrollState = scrollState,
        actions = {
            IconButton(
                onClick = ::save,
                enabled = canSave,
            ) {
                Icon(Icons.Default.Save, stringResource(R.string.save))
            }
        },
    ) {
        SettingsGroupColumn {
            SettingsGroup(
                title = stringResource(R.string.mcp_connection),
                items = listOf(
                    {
                        SettingsIconContent(icon = Icons.Default.SwapHoriz) {
                            Text(
                                stringResource(R.string.mcp_transport),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(10.dp))
                            val transports = McpTransportType.entries
                            PillTabSwitcher(
                                tabs = listOf(
                                    stringResource(R.string.mcp_transport_streamable_http),
                                    stringResource(R.string.mcp_transport_sse),
                                ),
                                selectedIndex = transports.indexOf(draft.transport).coerceAtLeast(0),
                                onSelect = { index ->
                                    transports.getOrNull(index)?.let { selected ->
                                        draft = draft.copy(transport = selected)
                                    }
                                },
                                allowLabelOverflow = true,
                            )
                        }
                    },
                    {
                        SettingsIconContent(icon = Icons.Default.Label) {
                            McpLabeledField(
                                label = stringResource(R.string.mcp_name),
                                value = draft.name,
                                onValueChange = { draft = draft.copy(name = it) },
                            )
                        }
                    },
                    {
                        SettingsIconContent(icon = Icons.Default.Link) {
                            McpLabeledField(
                                label = stringResource(R.string.mcp_url),
                                value = draft.url,
                                onValueChange = { draft = draft.copy(url = it) },
                                isError = draft.url.isNotBlank() && !validUrl,
                                supportingText = if (draft.url.isNotBlank() && !validUrl) {
                                    stringResource(R.string.mcp_url_error)
                                } else {
                                    null
                                },
                                keyboardType = KeyboardType.Uri,
                            )
                        }
                    },
                ),
            )
            SettingsGroup(
                title = stringResource(R.string.mcp_headers),
                items = buildList {
                    if (headerRows.isEmpty()) {
                        add {
                            SettingsItem(
                                headlineContent = {
                                    Text(
                                        stringResource(R.string.mcp_no_headers),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                supportingContent = {
                                    Text(stringResource(R.string.mcp_headers_desc))
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.Code,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                    } else {
                        headerRows.forEach { header ->
                            add {
                                key(header.id) {
                                    McpHeaderItem(
                                        header = header,
                                        onHeaderChange = ::updateHeader,
                                        onDelete = {
                                            headerRows = headerRows.filterNot {
                                                it.id == header.id
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    add {
                        SettingsAddItem(
                            label = stringResource(R.string.mcp_add_header),
                            onClick = {
                                headerRows = headerRows + McpHeaderDraft()
                            },
                        )
                    }
                },
            )
            SettingsGroup(
                title = stringResource(R.string.mcp_env_vars),
                items = buildList {
                    if (envRows.isEmpty()) {
                        add {
                            SettingsItem(
                                headlineContent = {
                                    Text(
                                        stringResource(R.string.mcp_no_env_vars),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                supportingContent = {
                                    Text(stringResource(R.string.mcp_env_vars_desc))
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.Code,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                    } else {
                        envRows.forEach { env ->
                            add {
                                key(env.id) {
                                    McpEnvItem(
                                        env = env,
                                        onEnvChange = ::updateEnv,
                                        onDelete = {
                                            envRows = envRows.filterNot {
                                                it.id == env.id
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    add {
                        SettingsAddItem(
                            label = stringResource(R.string.mcp_add_env_var),
                            onClick = {
                                envRows = envRows + McpEnvDraft()
                            },
                        )
                    }
                },
            )
            if (!isNew) {
                SettingsGroup(
                    title = stringResource(R.string.mcp_status),
                    items = listOf {
                        SettingsItem(
                            headlineContent = {
                                McpStatusText(
                                    snapshot = snapshot,
                                    includeError = true,
                                )
                            },
                            leadingContent = {
                                McpStatusIcon(snapshot?.status ?: McpConnectionStatus.IDLE)
                            },
                            trailingContent = {
                                IconButton(onClick = onRefresh) {
                                    Icon(Icons.Default.Refresh, stringResource(R.string.mcp_refresh))
                                }
                            },
                        )
                    },
                )
            }
            if (snapshot?.tools?.isNotEmpty() == true) {
                SettingsGroup(
                    title = stringResource(R.string.mcp_tools_count, snapshot.tools.size),
                    items = snapshot.tools.sortedBy { it.remote.name }.map { tool ->
                        {
                            val enabled = tool.remote.name !in draft.disabledTools
                            fun setEnabled(checked: Boolean) {
                                draft = draft.copy(
                                    disabledTools = if (checked) {
                                        draft.disabledTools - tool.remote.name
                                    } else {
                                        draft.disabledTools + tool.remote.name
                                    },
                                )
                            }
                            SettingsItem(
                                headlineContent = { Text(tool.remote.name) },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.Build,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = enabled,
                                        onCheckedChange = ::setEnabled,
                                    )
                                },
                                modifier = Modifier.clickable {
                                    setEnabled(!enabled)
                                },
                            )
                        }
                    },
                )
            }
        }
    }
}