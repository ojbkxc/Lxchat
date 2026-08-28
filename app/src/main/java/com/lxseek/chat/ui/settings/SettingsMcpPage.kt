package com.lxseek.chat.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.data.McpServerConfig
import com.lxseek.chat.mcp.McpConnectionStatus
import com.lxseek.chat.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsMcpPage(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val servers by viewModel.settings.mcpServers.collectAsState()
    val snapshots by viewModel.mcpServerSnapshots.collectAsState()
    var editorRoute by remember { mutableStateOf<McpEditorRoute?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = editorRoute != null) {
        editorRoute = null
    }

    GuardedAnimatedContent(
        targetState = editorRoute,
        forward = editorRoute != null,
    ) { route ->
        if (route != null) {
            val target = route.initial
            McpServerEditor(
                initial = target,
                snapshot = snapshots[target.id],
                isNew = route.isNew,
                onBack = { editorRoute = null },
                onSave = { saved ->
                    if (route.isNew) {
                        viewModel.settings.addMcpServer(saved)
                    } else {
                        viewModel.settings.updateMcpServer(saved)
                    }
                    editorRoute = null
                },
                onRefresh = { viewModel.refreshMcpServer(target.id) },
            )
        } else {
            val scrollState = rememberScrollState()
            CollapsingSettingsScaffold(
                title = stringResource(R.string.mcp_title),
                onBack = onBack,
                scrollState = scrollState,
            ) {
                SettingsGroupColumn {
                    SettingsGroup(
                        title = stringResource(R.string.mcp_servers),
                        items = buildList {
                            if (servers.isEmpty()) {
                                add {
                                    SettingsItem(
                                        headlineContent = {
                                            Text(
                                                stringResource(R.string.mcp_no_servers),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        },
                                        supportingContent = {
                                            Text(stringResource(R.string.mcp_no_servers_desc))
                                        },
                                        leadingContent = {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_mcp),
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        },
                                    )
                                }
                            } else {
                                servers.forEach { server ->
                                    add {
                                        val snapshot = snapshots[server.id]
                                        var menuExpanded by remember(server.id) {
                                            mutableStateOf(false)
                                        }
                                        SettingsItem(
                                            headlineContent = {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text(
                                                        text = server.name.ifBlank { server.url },
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f, fill = false),
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                    McpStatusDot(
                                                        status = if (server.enabled) {
                                                            snapshot?.status ?: McpConnectionStatus.IDLE
                                                        } else {
                                                            McpConnectionStatus.IDLE
                                                        },
                                                    )
                                                }
                                            },
                                            supportingContent = {
                                                Text(
                                                    stringResource(
                                                        R.string.mcp_tools_enabled,
                                                        snapshot?.tools?.count { it.enabled } ?: 0,
                                                    ),
                                                )
                                            },
                                            leadingContent = {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_mcp),
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            },
                                            trailingContent = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Switch(
                                                        checked = server.enabled,
                                                        onCheckedChange = {
                                                            viewModel.settings.updateMcpServer(
                                                                server.copy(enabled = it),
                                                            )
                                                        },
                                                        modifier = Modifier.padding(end = 2.dp),
                                                    )
                                                    Box {
                                                        IconButton(
                                                            onClick = { menuExpanded = true },
                                                        ) {
                                                            Icon(
                                                                Icons.Default.MoreVert,
                                                                stringResource(R.string.options),
                                                            )
                                                        }
                                                        DropdownMenu(
                                                            expanded = menuExpanded,
                                                            onDismissRequest = {
                                                                menuExpanded = false
                                                            },
                                                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                                            tonalElevation = 6.dp,
                                                            shape = RoundedCornerShape(12.dp),
                                                        ) {
                                                            DropdownMenuItem(
                                                                text = {
                                                                    Text(stringResource(R.string.mcp_refresh))
                                                                },
                                                                leadingIcon = {
                                                                    Icon(
                                                                        Icons.Default.Refresh,
                                                                        null,
                                                                    )
                                                                },
                                                                onClick = {
                                                                    menuExpanded = false
                                                                    viewModel.refreshMcpServer(server.id)
                                                                },
                                                            )
                                                            DropdownMenuItem(
                                                                text = {
                                                                    Text(
                                                                        stringResource(R.string.delete),
                                                                        color = MaterialTheme.colorScheme.error,
                                                                    )
                                                                },
                                                                leadingIcon = {
                                                                    Icon(
                                                                        Icons.Default.Delete,
                                                                        null,
                                                                        tint = MaterialTheme.colorScheme.error,
                                                                    )
                                                                },
                                                                onClick = {
                                                                    menuExpanded = false
                                                                    deleteId = server.id
                                                                },
                                                            )
                                                        }
                                                    }
                                                }
                                            },
                                            modifier = Modifier.clickable {
                                                editorRoute = McpEditorRoute(
                                                    initial = server,
                                                    isNew = false,
                                                )
                                            },
                                            endPadding = 6.dp,
                                        )
                                    }
                                }
                            }
                            add {
                                SettingsAddItem(
                                    label = stringResource(R.string.mcp_add_server),
                                    onClick = {
                                        editorRoute = McpEditorRoute(
                                            initial = McpServerConfig(),
                                            isNew = true,
                                        )
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    deleteId?.let { id ->
        val server = servers.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = {
                Text(
                    text = stringResource(R.string.mcp_delete_title),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    stringResource(
                        R.string.mcp_delete_message,
                        server?.name?.ifBlank { server.url }.orEmpty(),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.settings.removeMcpServer(id)
                        deleteId = null
                    },
                ) {
                    Text(
                        stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteId = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
