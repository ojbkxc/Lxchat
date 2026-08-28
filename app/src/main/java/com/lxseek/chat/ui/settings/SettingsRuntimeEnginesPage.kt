package com.lxseek.chat.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.runtime.RuntimeEngineType
import com.lxseek.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

/**
 * 「运行时引擎」设置管理页：展示 Node/pygon/ffmpeg 引擎的安装/运行状态，提供
 * 安装（按需下载）/启动/停止/卸载/版本清理等按钮。下载与启停皆走运行时管理器的
 * 现有链路（AI 侧为 Dangerous 级需审批；此处为用户手动操作）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRuntimeEnginesPage(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val market = viewModel.pluginMarket
    val runtimeEngineManager = market.runtimeEngineManager
    val scope = rememberCoroutineScope()
    val catalog by market.catalog.collectAsState()

    // 强制刷新目录，确保 RUNTIME 引擎条目可安装。
    var refreshedOnce by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!refreshedOnce) {
            market.refreshCatalog()
            refreshedOnce = true
        }
    }

    BackHandler { onBack() }

    val known = remember { KNOWN_ENGINES }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_runtime_engines)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { market.refreshCatalog() } }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.runtime_engine_refresh),
                        )
                    }
                },
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.runtime_engines_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            if (runtimeEngineManager == null) {
                Text(
                    text = stringResource(R.string.runtime_engine_no_manager),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                known.forEach { engineId ->
                    val status = runtimeEngineManager.status(engineId)
                    val meta = catalog.firstOrNull { it.id == engineId }
                    EngineRow(
                        engineId = engineId,
                        installed = status.installed,
                        installedVersion = status.installedVersion,
                        running = status.running,
                        canInstall = meta != null,
                        onAction = { action ->
                            scope.launch {
                                when (action) {
                                    EngineAction.Start -> {
                                        runCatching {
                                            runtimeEngineManager.ensureStarted(engineId, null, null)
                                        }.onFailure { viewModel.emitSnackbar(it.message ?: "启动失败") }
                                    }
                                    EngineAction.Stop -> runtimeEngineManager.stop(engineId)
                                    EngineAction.Install -> {
                                        runCatching {
                                            market.installRuntimeInternal(meta!!)
                                        }.onFailure { viewModel.emitSnackbar(it.message ?: "安装失败") }
                                    }
                                    EngineAction.Uninstall -> {
                                        runCatching { market.uninstall(engineId) }
                                            .onFailure { viewModel.emitSnackbar(it.message ?: "卸载失败") }
                                    }
                                }
                            }
                        }
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

private enum class EngineAction { Start, Stop, Install, Uninstall }

private val KNOWN_ENGINES = listOf(
    RuntimeEngineType.NODE_INKOS,
    RuntimeEngineType.PYTHON_WEB_NOVEL,
    "runtime-python",
    "runtime-ffmpeg",
)

@Composable
private fun EngineRow(
    engineId: String,
    installed: Boolean,
    installedVersion: String?,
    running: Boolean,
    canInstall: Boolean,
    onAction: (EngineAction) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = engineId,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (running) {
                    Text(
                        text = stringResource(R.string.runtime_engine_status_running),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            val stateText = when {
                installed && installedVersion != null ->
                    stringResource(R.string.runtime_engine_status_installed, installedVersion)
                else -> stringResource(R.string.runtime_engine_status_not_installed)
            }
            Text(
                text = stateText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    installed && running -> {
                        OutlinedButton(onClick = { onAction(EngineAction.Stop) }) {
                            Text(stringResource(R.string.runtime_engine_action_stop))
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { onAction(EngineAction.Uninstall) }) {
                            Text(stringResource(R.string.runtime_engine_action_uninstall))
                        }
                    }
                    installed -> {
                        OutlinedButton(onClick = { onAction(EngineAction.Start) }) {
                            Text(stringResource(R.string.runtime_engine_action_start))
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { onAction(EngineAction.Uninstall) }) {
                            Text(stringResource(R.string.runtime_engine_action_uninstall))
                        }
                    }
                    else -> {
                        OutlinedButton(
                            onClick = { onAction(EngineAction.Install) },
                            enabled = canInstall,
                        ) {
                            Text(stringResource(R.string.runtime_engine_action_install))
                        }
                    }
                }
            }
        }
    }
}