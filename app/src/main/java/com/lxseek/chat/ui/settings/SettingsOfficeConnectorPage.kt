package com.lxseek.chat.ui.settings

import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lxseek.chat.im.office.OfficeApprovalReply
import com.lxseek.chat.im.office.OfficeApprovalRequest
import com.lxseek.chat.im.office.OfficeConnectionState
import com.lxseek.chat.im.office.OfficeConnectionStatus
import com.lxseek.chat.im.office.OfficeConnectorApi
import com.lxseek.chat.im.office.OfficeConnectorService
import com.lxseek.chat.im.office.OfficeConnectorSettings
import com.lxseek.chat.im.office.OfficeConnectorStore
import com.lxseek.chat.im.office.OfficeHarnessSession
import com.lxseek.chat.im.office.OfficeHarnessUpdate

import com.lxseek.chat.util.DebugLog
import com.lxseek.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * AI Office Connector 设置页面。
 *
 * 让本机（Lxchat App）主动连接公网 Office，接收任务并执行。本机无需公网 IP、
 * 端口转发或 WebSocket 服务。
 *
 * 页面功能：
 * - Office Base URL 输入（HTTPS 必需，loopback 例外）
 * - Device Token 输入（密码字段，加密存储）
 * - 工作区 alias 配置（每行 `alias=路径`）
 * - Instruction Preset alias 配置（每行 `alias=预设文本`）
 * - 连接状态显示（disconnected/connecting/connected/error/reconnecting）
 * - 连接/断开按钮
 * - 从 Office Base URL 自动展示全部固定 Hook URL
 *
 * Device Token 只写入安全存储（[com.lxseek.chat.util.SecretCrypto]），不回传、不日志输出。
 */
@Composable
fun SettingsOfficeConnectorPage(
    @Suppress("UNUSED_PARAMETER") viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { OfficeConnectorStore(context) }
    val scope = rememberCoroutineScope()

    val savedSettings by store.settings.collectAsState(initial = OfficeConnectorSettings())
    val savedToken by store.deviceToken.collectAsState(initial = "")

    // 表单状态（从持久化配置初始化）
    var baseUrl by remember { mutableStateOf("") }
    var deviceId by remember { mutableStateOf("") }
    var deviceToken by remember { mutableStateOf("") }
    var maxConcurrency by remember { mutableStateOf("1") }
    var heartbeatSeconds by remember { mutableStateOf("30") }
    var workspacesText by remember { mutableStateOf("") }
    var presetsText by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var service by remember { mutableStateOf<OfficeConnectorService?>(null) }
    var initialized by remember { mutableStateOf(false) }

    // 从持久化配置填充表单（仅一次）
    LaunchedEffect(savedSettings, savedToken) {
        if (!initialized && savedSettings.baseUrl.isNotBlank()) {
            baseUrl = savedSettings.baseUrl
            deviceId = savedSettings.deviceId
            deviceToken = savedToken
            maxConcurrency = savedSettings.maxConcurrency.toString()
            heartbeatSeconds = savedSettings.heartbeatSeconds.toString()
            workspacesText = formatMap(savedSettings.workspaces)
            presetsText = formatMap(savedSettings.instructionPresets)
            initialized = true
        }
    }

    // 自动生成 deviceId（如果为空）
    LaunchedEffect(Unit) {
        if (deviceId.isBlank()) deviceId = "lxchat-${UUID.randomUUID().toString().take(8)}"
    }

    // 页面退出时停止服务
    DisposableEffect(service) {
        onDispose {
            service?.stop()
        }
    }

    val connectionStatus = service?.let { it.status.collectAsState().value }
        ?: OfficeConnectionStatus()

    val hookUrls = remember(baseUrl) {
        if (baseUrl.isNotBlank()) OfficeConnectorApi.hookUrls(baseUrl) else emptyMap()
    }

    CollapsingSettingsScaffold(
        title = "AI Office Connector",
        onBack = onBack,
    ) {
        SettingsGroupColumn {
            // ── 连接配置 ──────────────────────────────────────────────
            SettingsGroup(title = "连接配置", items = listOf(
                {
                    SettingsIconContent(icon = Icons.Default.Cloud) {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text("Office Base URL") },
                            placeholder = { Text("https://office.example.com") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                {
                    SettingsIconContent(icon = Icons.Default.Lock) {
                        OutlinedTextField(
                            value = deviceToken,
                            onValueChange = { deviceToken = it },
                            label = { Text("Device Token") },
                            placeholder = { Text("至少 32 位字符") },
                            singleLine = true,
                            visualTransformation = if (showToken)
                                VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { showToken = !showToken }) {
                                    Icon(
                                        if (showToken) Icons.Default.VisibilityOff
                                        else Icons.Default.Visibility,
                                        contentDescription = null,
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Device ID: $deviceId",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                {
                    SettingsIconContent(icon = Icons.Default.Cloud) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            OutlinedTextField(
                                value = maxConcurrency,
                                onValueChange = { maxConcurrency = it.filter { c -> c.isDigit() } },
                                label = { Text("最大并发") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = heartbeatSeconds,
                                onValueChange = { heartbeatSeconds = it.filter { c -> c.isDigit() } },
                                label = { Text("心跳间隔(秒)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                },
            ))

            // ── 工作区与预设 ──────────────────────────────────────────
            SettingsGroup(title = "工作区与预设", items = listOf(
                {
                    SettingsIconContent(icon = Icons.Default.Storage) {
                        Text(
                            text = "工作区 alias（每行 `alias=绝对路径`）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = workspacesText,
                            onValueChange = { workspacesText = it },
                            placeholder = { Text("main=/path/to/workspace") },
                            minLines = 3,
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                {
                    SettingsIconContent(icon = Icons.Default.WorkOutline) {
                        Text(
                            text = "Instruction Preset alias（每行 `alias=预设文本`）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = presetsText,
                            onValueChange = { presetsText = it },
                            placeholder = { Text("default=你是一个代码助手...") },
                            minLines = 3,
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
            ))

            // ── 连接状态与控制 ────────────────────────────────────────
            SettingsGroup(title = "连接状态", items = listOf(
                {
                    SettingsIconContent(icon = Icons.Default.Link) {
                        ConnectionStateIndicator(connectionStatus)
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (service == null) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            connectToOffice(
                                                store = store,
                                                baseUrl = baseUrl,
                                                deviceId = deviceId,
                                                deviceToken = deviceToken,
                                                maxConcurrency = maxConcurrency,
                                                heartbeatSeconds = heartbeatSeconds,
                                                workspacesText = workspacesText,
                                                presetsText = presetsText,
                                                scope = scope,
                                                currentService = service,
                                                onServiceChange = { service = it },
                                                context = context,
                                            )
                                        }
                                    },
                                    enabled = baseUrl.isNotBlank() && deviceToken.length >= 32,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null,
                                        modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("连接")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        service?.stop()
                                        service = null
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error,
                                    ),
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = null,
                                        modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("断开")
                                }
                            }
                        }
                    }
                },
            ))

            // ── 固定 Hook URL ────────────────────────────────────────
            if (hookUrls.isNotEmpty()) {
                SettingsGroup(title = "固定 Hook URL", items = listOf(
                    {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            hookUrls.forEach { (name, url) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                ) {
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.width(100.dp),
                                    )
                                    Text(
                                        text = url,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                ))
            }
        }
    }
}

// ── 辅助组件与函数 ──────────────────────────────────────────────────────

/** 连接状态指示器（带颜色圆点 + 文字）。 */
@Composable
private fun ConnectionStateIndicator(status: OfficeConnectionStatus) {
    val (color, label) = when (status.state) {
        OfficeConnectionState.DISCONNECTED ->
            MaterialTheme.colorScheme.outline to "未连接"
        OfficeConnectionState.CONNECTING ->
            MaterialTheme.colorScheme.tertiary to "连接中…"
        OfficeConnectionState.CONNECTED ->
            MaterialTheme.colorScheme.primary to "已连接"
        OfficeConnectionState.ERROR ->
            MaterialTheme.colorScheme.error to "错误"
        OfficeConnectionState.RECONNECTING ->
            MaterialTheme.colorScheme.tertiary to "重连中…"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (status.state == OfficeConnectionState.CONNECTING ||
            status.state == OfficeConnectionState.RECONNECTING
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = color,
            )
        } else {
            Card(
                modifier = Modifier.size(12.dp),
                colors = CardDefaults.cardColors(containerColor = color),
            ) {}
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = color,
        )
    }
    if (status.error != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = status.error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (status.connected) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = "重连次数: ${status.reconnects} · 已提供任务: ${status.jobsOffered}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 连接到 Office：保存配置 → 创建 Service → 启动。 */
private suspend fun connectToOffice(
    store: OfficeConnectorStore,
    baseUrl: String,
    deviceId: String,
    deviceToken: String,
    maxConcurrency: String,
    heartbeatSeconds: String,
    workspacesText: String,
    presetsText: String,
    scope: kotlinx.coroutines.CoroutineScope,
    currentService: OfficeConnectorService?,
    onServiceChange: (OfficeConnectorService?) -> Unit,
    context: android.content.Context,
) {
    if (baseUrl.isBlank() || deviceToken.length < 32) {
        Toast.makeText(context, "Base URL 和 Device Token（≥32位）必填", Toast.LENGTH_SHORT).show()
        return
    }
    val settings = OfficeConnectorSettings(
        baseUrl = baseUrl.trim(),
        deviceId = deviceId.trim(),
        maxConcurrency = maxConcurrency.toIntOrNull()?.coerceIn(1, 4) ?: 1,
        heartbeatSeconds = heartbeatSeconds.toIntOrNull()?.coerceIn(10, 300) ?: 30,
        workspaces = parseMap(workspacesText),
        instructionPresets = parseMap(presetsText),
        enabled = true,
    )
    try {
        store.save(settings, deviceToken)
    } catch (e: Exception) {
        DebugLog.e("OfficeSettings", "保存配置失败: ${e.message}", e)
        Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        return
    }
    currentService?.stop()
    val service = OfficeConnectorService(
        settings = settings,
        deviceToken = deviceToken,
        scope = scope,
        createHarness = ::stubHarnessFactory,
    )
    onServiceChange(service)
    service.start()
    Toast.makeText(context, "正在连接 AI Office…", Toast.LENGTH_SHORT).show()
}

/**
 * Stub Harness 会话工厂。
 *
 * 提供可编译的默认实现；真正的桥接（创建独立 Harness Session、调用 GenerationManager）
 * 应在后续任务中实现。当前 stub 会回传状态消息并返回占位结果。
 */
private fun stubHarnessFactory(workspaceAlias: String): OfficeHarnessSession =
    object : OfficeHarnessSession {
        private var cancelled = false

        override suspend fun createSession(): String {
            return "office-stub-${System.currentTimeMillis()}"
        }

        override suspend fun ask(
            prompt: String,
            onUpdate: suspend (OfficeHarnessUpdate) -> Unit,
            onApproval: suspend (OfficeApprovalRequest) -> OfficeApprovalReply,
        ): String {
            onUpdate(OfficeHarnessUpdate("status",
                "（stub）Workspace=$workspaceAlias，Harness 桥接尚未实现。"))
            onUpdate(OfficeHarnessUpdate("text",
                "已接收 Office 任务，但本机 Harness 桥接尚未接入 GenerationManager。"))
            return buildString {
                appendLine("# AI Office Handoff（Stub）")
                appendLine()
                appendLine("Workspace: $workspaceAlias")
                appendLine()
                appendLine("本机 Harness 桥接尚未实现。请在后续任务中接入 GenerationManager，")
                appendLine("为每个 Office Job 创建独立的 Harness Session 执行任务。")
            }
        }

        override suspend fun cancel() {
            cancelled = true
        }
    }

/** 解析多行文本为 Map：每行 `alias=value`。 */
private fun parseMap(text: String): Map<String, String> =
    text.lines()
        .mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val eq = trimmed.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val key = trimmed.substring(0, eq).trim()
            val value = trimmed.substring(eq + 1).trim()
            if (key.isEmpty() || value.isEmpty()) null else key to value
        }
        .toMap()

/** 格式化 Map 为多行文本：每行 `alias=value`。 */
private fun formatMap(map: Map<String, String>): String =
    map.entries.joinToString("\n") { "${it.key}=${it.value}" }