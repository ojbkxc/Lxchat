package com.lxseek.chat.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.androidcontrol.AndroidUiControllerService
import com.lxseek.chat.model.ModelId
import com.lxseek.chat.model.apiModelName
import com.lxseek.chat.runtime.RuntimeEnginePlugin
import com.lxseek.chat.runtime.Version
import com.lxseek.chat.util.TtsManager
import com.lxseek.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/** Live snapshot of the accessibility bridge, mirroring the device-control page's cheap read. */
private data class RuntimeAccessibilityStatus(
    val enabled: Boolean,
    val connected: Boolean,
    val activePackage: String?,
) {
    companion object {
        fun read(): RuntimeAccessibilityStatus {
            val svc = AndroidUiControllerService.instance
            return RuntimeAccessibilityStatus(
                enabled = svc != null,
                connected = svc?.isConnected() == true,
                activePackage = svc?.activePackage(),
            )
        }
    }
}

/**
 * Agent / runtime status board. Aggregates the live state of everything that keeps LxChat running:
 * the accessibility bridge that powers device control, the TTS engine for speech, the model
 * configuration for generation, and the runtime engines (Node.js / Python / ffmpeg) that power
 * script-based skills. Status is refreshed while the page is visible so toggles made in other
 * screens appear immediately.
 */
@Composable
fun SettingsRuntimeStatusPage(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val settings = viewModel.settings
    var a11y by remember { mutableStateOf(RuntimeAccessibilityStatus.read()) }
    val ttsAvailable by TtsManager.isAvailable.collectAsState()
    val ttsPlaying by TtsManager.isPlaying.collectAsState()
    val selectedModel by settings.selectedModel.collectAsState()
    val customModels by settings.customModels.collectAsState()
    val enabledModels by settings.enabledModels.collectAsState()

    // ── Runtime engines state ──
    val market = viewModel.pluginMarket
    val runtimeEngineManager = market.runtimeEngineManager
    val scope = rememberCoroutineScope()
    val catalog by market.catalog.collectAsState()
    var installingEngines by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selectedVersions = remember { mutableStateMapOf<String, String>() }
    val engineLogs = remember { mutableStateMapOf<String, String>() }
    var refreshedOnce by remember { mutableStateOf(false) }
    val known = remember { KNOWN_ENGINES }

    // Poll for the accessibility bridge's live state (it is tied to the system service lifecycle).
    LaunchedEffect(Unit) {
        while (true) {
            a11y = RuntimeAccessibilityStatus.read()
            delay(1_000L)
        }
    }

    // 强制刷新目录，确保 RUNTIME 引擎条目可安装。
    LaunchedEffect(Unit) {
        if (!refreshedOnce) {
            market.refreshCatalog()
            refreshedOnce = true
        }
    }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_runtime_status),
        onBack = onBack,
        scrollState = rememberScrollState(),
        actions = {
            IconButton(onClick = { scope.launch { market.refreshCatalog() } }) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.runtime_engine_refresh),
                )
            }
        },
    ) {
        Text(
            text = stringResource(R.string.runtime_status_howto),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        )

        SettingsGroup(title = stringResource(R.string.runtime_accessibility_title), items = listOf(
            {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Android,
                        contentDescription = null,
                        tint = if (a11y.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        StatusLine(
                            title = stringResource(
                                if (a11y.enabled) R.string.runtime_bridge_enabled else R.string.runtime_bridge_disabled,
                            ),
                            positive = a11y.enabled,
                        )
                        StatusLine(
                            title = stringResource(
                                if (a11y.connected) R.string.runtime_bridge_connected else R.string.runtime_bridge_disconnected,
                            ),
                            positive = a11y.connected,
                        )
                        if (!a11y.activePackage.isNullOrBlank()) {
                            StatusLine(
                                title = a11y.activePackage.orEmpty(),
                                positive = false,
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = { a11y = RuntimeAccessibilityStatus.read() }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.runtime_refresh),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }) {
                            Text(stringResource(R.string.runtime_open_settings))
                        }
                    }
                }
            },
        ))

        SettingsGroup(title = stringResource(R.string.runtime_tts_title), items = listOf(
            {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = if (ttsAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        StatusLine(
                            title = stringResource(
                                if (ttsAvailable) R.string.runtime_tts_ready else R.string.runtime_tts_missing,
                            ),
                            positive = ttsAvailable,
                        )
                        StatusLine(
                            title = stringResource(
                                if (ttsPlaying) R.string.runtime_tts_playing else R.string.runtime_tts_idle,
                            ),
                            positive = ttsPlaying,
                        )
                    }
                }
            },
        ))

        SettingsGroup(title = stringResource(R.string.runtime_model_title), items = listOf(
            {
                val parsed = ModelId.parse(selectedModel)
                val alias = settings.modelAliases.value[selectedModel]
                val displayName = alias ?: parsed.apiModelName
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        StatusLine(title = displayName.ifBlank { stringResource(R.string.runtime_model_unset) }, positive = displayName.isNotBlank())
                        StatusLine(
                            title = stringResource(R.string.runtime_model_counts, enabledModels.size, customModels.size),
                            positive = false,
                        )
                    }
                }
            },
        ))

        // ── Runtime engines section ──
        Text(
            text = stringResource(R.string.runtime_engines_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )
        if (runtimeEngineManager == null) {
            Text(
                text = stringResource(R.string.runtime_engine_no_manager),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            known.forEach { engineId ->
                val status = runtimeEngineManager.status(engineId)
                val meta = catalog.firstOrNull { it.id == engineId }
                val isInstalling = engineId in installingEngines
                val min = Version.parse(meta?.minVersion)
                val availableVersions = (meta?.versions ?: emptyList())
                    .sortedWith(compareByDescending { Version.parse(it) })
                    .ifEmpty { meta?.version?.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList() }
                val effectiveSelected = selectedVersions[engineId]
                    ?: availableVersions.firstOrNull { min == Version.NONE || Version.parse(it).satisfiesMin(min) }
                    ?: availableVersions.firstOrNull()
                EngineRow(
                    engineId = engineId,
                    installed = status.installed,
                    installedVersion = status.installedVersion,
                    running = status.running,
                    canInstall = meta != null && !isInstalling,
                    isInstalling = isInstalling,
                    versions = availableVersions,
                    selectedVersion = effectiveSelected,
                    onVersionSelected = { selectedVersions[engineId] = it },
                    log = engineLogs[engineId].orEmpty(),
                    onAction = { action ->
                        scope.launch {
                            when (action) {
                                EngineAction.Start -> {
                                    runCatching {
                                        runtimeEngineManager.ensureStarted(engineId, null, null)
                                    }.onFailure { e ->
                                        viewModel.emitSnackbar(formatEngineError(context, R.string.runtime_engine_op_start, e))
                                    }
                                }
                                EngineAction.Stop -> runtimeEngineManager.stop(engineId)
                                is EngineAction.Install -> {
                                    val version = action.selectedVersion
                                    engineLogs[engineId] = ""
                                    installingEngines = installingEngines + engineId
                                    runCatching {
                                        market.installRuntimeInternal(meta!!, version) { line ->
                                            scope.launch { engineLogs[engineId] = engineLogs.getValue(engineId) + line + "\n" }
                                        }
                                    }.onFailure { e ->
                                        scope.launch {
                                            val msg = e.message ?: e::class.simpleName ?: ""
                                            engineLogs[engineId] = engineLogs.getValue(engineId) + "[$engineId] 失败: $msg\n"
                                        }
                                        viewModel.emitSnackbar(formatEngineError(context, R.string.runtime_engine_op_install, e))
                                    }
                                    installingEngines = installingEngines - engineId
                                }
                                EngineAction.Uninstall -> {
                                    runCatching { market.uninstall(engineId) }
                                        .onFailure { e ->
                                            viewModel.emitSnackbar(formatEngineError(context, R.string.runtime_engine_op_uninstall, e))
                                        }
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

@Composable
private fun StatusLine(title: String, positive: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = if (positive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (positive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Runtime engines helpers ──

private sealed interface EngineAction {
    data object Start : EngineAction
    data object Stop : EngineAction
    data class Install(val selectedVersion: String?) : EngineAction
    data object Uninstall : EngineAction
}

/**
 * The only true runtime engines displayed on this page: Node.js, Python and ffmpeg.
 * webnovel-writer / inkos are SKILL plugins (script applications depending on these
 * runtimes) and are installed from the skill market instead.
 */
private val KNOWN_ENGINES = listOf(
    "runtime-node",
    "runtime-python",
    "runtime-ffmpeg",
)

/**
 * Format an engine operation failure into a human-readable snackbar message, classifying by
 * exception type so the user can tell network errors apart from install/state errors.
 */
private fun formatEngineError(context: Context, opResId: Int, e: Throwable): String {
    val op = context.getString(opResId)
    val detail = when (e) {
        is IOException -> context.getString(R.string.runtime_engine_err_network, e.message ?: "")
        is IllegalStateException -> context.getString(R.string.runtime_engine_err_state, e.message ?: "")
        is IllegalArgumentException -> context.getString(R.string.runtime_engine_err_param, e.message ?: "")
        else -> {
            val msg = e.message ?: e::class.simpleName ?: ""
            context.getString(R.string.runtime_engine_err_unknown, msg)
        }
    }
    return context.getString(R.string.runtime_engine_op_failed, op, detail)
}

@Composable
private fun EngineRow(
    engineId: String,
    installed: Boolean,
    installedVersion: String?,
    running: Boolean,
    canInstall: Boolean,
    isInstalling: Boolean,
    versions: List<String>,
    selectedVersion: String?,
    onVersionSelected: (String) -> Unit,
    log: String,
    onAction: (EngineAction) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = RuntimeEnginePlugin.engineDisplayName(engineId),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (isInstalling) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
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
                isInstalling -> stringResource(R.string.runtime_engine_status_installing)
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
                        VersionSelector(
                            engineId = engineId,
                            versions = versions,
                            selected = selectedVersion,
                            onSelected = onVersionSelected,
                            enabled = canInstall,
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { onAction(EngineAction.Install(selectedVersion)) },
                            enabled = canInstall,
                        ) {
                            Text(
                                text = if (isInstalling) {
                                    stringResource(R.string.runtime_engine_action_installing)
                                } else {
                                    stringResource(R.string.runtime_engine_action_install)
                                },
                            )
                        }
                    }
                }
            }

            if (log.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ) {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()).padding(10.dp),
                    ) {
                        Text(
                            text = log,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionSelector(
    engineId: String,
    versions: List<String>,
    selected: String?,
    onSelected: (String) -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.runtime_engine_version_pick,
                    selected ?: versions.firstOrNull().orEmpty(),
                ),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.width(4.dp))
            Icon(imageVector = Icons.Default.KeyboardArrowDown, contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            versions.forEach { v ->
                val recommended = engineId == "runtime-python" && v == "3.12.7"
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(v)
                            if (recommended) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.runtime_engine_version_recommended),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    leadingIcon = if (v == selected) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        null
                    },
                    onClick = {
                        onSelected(v)
                        expanded = false
                    },
                )
            }
        }
    }
}
