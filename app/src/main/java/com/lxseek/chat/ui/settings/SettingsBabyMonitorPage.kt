package com.lxseek.chat.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lxseek.chat.LxChatApplication
import com.lxseek.chat.R
import com.lxseek.chat.baby.BabyModelManager
import com.lxseek.chat.baby.BabyMonitorController
import com.lxseek.chat.baby.BabyMonitorStore
import com.lxseek.chat.baby.BabySensitivity
import com.lxseek.chat.im.ImPlatform
import kotlinx.coroutines.launch

/**
 * 婴儿哭声监护设置页（用户 2026-09-02 需求）：
 *  - 哭声检测是单独的触发开关（与 IM 渠道配置解耦）；
 *  - 开启后默认通知所有已启用 IM 渠道；开关下方可展开当前启用渠道列表，
 *    用复选框精确选择通知哪几个（全不勾 = 全部）。
 *
 * 页面结构：
 *  1. 权限卡片（RECORD_AUDIO；Android 13+ 还会补 POST_NOTIFICATIONS 引导）
 *  2. YAMNet 模型卡片（下载 / 进度 / 已就绪 / 失败重试）
 *  3. 总开关卡片（前置校验：权限 + 模型）
 *  4. 通知渠道卡片（启用渠道复选框列表；空选 = 全部）
 *  5. 灵敏度参数卡片（持续命中次数 Slider、冷却分钟 Slider）
 */
@Composable
fun SettingsBabyMonitorPage(
    onBack: () -> Unit,
    onNavigateImGateway: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = remember(context) { context.applicationContext as LxChatApplication }
    val store = remember(context) { BabyMonitorStore(context.applicationContext) }
    val modelManager = remember(context) { BabyModelManager.getInstance(context) }
    val bridge = remember(context) { app.container.imBridgeService }

    val config by store.config.collectAsState(initial = BabyMonitorStore.Config())
    val modelState by modelManager.state.collectAsState()

    // 已启用渠道快照（选中渠道的显示名与平台标签从这取）。
    var activeChannels by remember { mutableStateOf<Map<String, Pair<String, String>>>(emptyMap()) }
    LaunchedEffect(Unit) {
        // 每 2s 轻量刷新一次渠道映射（ImBridgeService 在配置变更时重建 map）。
        while (true) {
            activeChannels = bridge.channels().entries.associate { (id, ch) ->
                val platformLabel = ImPlatform.entries.firstOrNull { it.id == id.substringBefore(':') }?.label
                    ?: id.substringBefore(':')
                id to (ch.displayName to platformLabel)
            }
            kotlinx.coroutines.delay(2_000)
        }
    }

    // 权限申请 launcher。
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_baby_monitor),
        onBack = onBack,
        scrollState = rememberScrollState(),
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // ── 1. 权限卡片 ──
        val hasRecordPermission = remember(context) {
            BabyMonitorController.hasRecordPermission(context)
        }
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.baby_monitor_permission_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.baby_monitor_permission_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!hasRecordPermission) {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }) {
                        Text(stringResource(R.string.baby_monitor_permission_grant))
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 2. 模型下载卡片 ──
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.ChildCare,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.baby_monitor_model_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.baby_monitor_model_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                when (val s = modelState) {
                    is BabyModelManager.State.Downloaded -> {
                        Text(
                            text = stringResource(R.string.baby_monitor_model_ready),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    is BabyModelManager.State.Downloading, is BabyModelManager.State.Progress -> {
                        val p = s as? BabyModelManager.State.Progress
                        if (p != null && p.total > 0) {
                            LinearProgressIndicator(
                                progress = { (p.downloaded.toFloat() / p.total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${formatBytes(p.downloaded)} / ${formatBytes(p.total)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    is BabyModelManager.State.Failed -> {
                        Text(
                            text = stringResource(R.string.baby_monitor_model_failed, s.reason),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { modelManager.startDownload(scope) }) {
                            Text(stringResource(R.string.baby_monitor_model_retry))
                        }
                    }
                    else -> {
                        OutlinedButton(onClick = { modelManager.startDownload(scope) }) {
                            Icon(Icons.Filled.Download, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.baby_monitor_model_download))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 3. 总开关 ──
        Card(colors = CardDefaults.cardColors()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.baby_monitor_enabled),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.baby_monitor_enabled_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = config.enabled,
                    onCheckedChange = { want ->
                        scope.launch {
                            val ok = BabyMonitorController.setEnabled(context, want)
                            if (!ok) {
                                // 前置条件缺失：保持关闭并提示（权限缺失才引导系统页）。
                                if (!BabyMonitorController.hasRecordPermission(context) &&
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                                ) {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        }
                    },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 4. 通知渠道选择 ──
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.baby_monitor_channels_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.baby_monitor_channels_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (activeChannels.isEmpty()) {
                    Text(
                        text = stringResource(R.string.baby_monitor_channels_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { onNavigateImGateway() }) {
                        Text(stringResource(R.string.baby_monitor_goto_im))
                    }
                } else {
                    activeChannels.forEach { (channelId, names) ->
                        val (displayName, platformLabel) = names
                        val checked = channelId in config.selectedChannels
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { want ->
                                    scope.launch {
                                        val next = if (want) {
                                            config.selectedChannels + channelId
                                        } else {
                                            config.selectedChannels - channelId
                                        }
                                        store.setSelectedChannels(next)
                                    }
                                },
                            )
                            Column {
                                Text(
                                    text = displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = platformLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text(
                        text = if (config.selectedChannels.isEmpty()) {
                            stringResource(R.string.baby_monitor_channels_all)
                        } else {
                            stringResource(
                                R.string.baby_monitor_channels_selected,
                                config.selectedChannels.size,
                            )
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (config.selectedChannels.isEmpty()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 5a. 灵敏度档位 + 免打扰时段 ──
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.baby_monitor_sensitivity_preset_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.baby_monitor_sensitivity_preset_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    BabySensitivity.entries.forEach { level ->
                        val selected = config.sensitivity == level
                        OutlinedButton(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp),
                            onClick = { scope.launch { store.setSensitivity(level) } },
                        ) {
                            Text(
                                text = stringResource(
                                    when (level) {
                                        BabySensitivity.SENSITIVE -> R.string.baby_monitor_sensitivity_sensitive
                                        BabySensitivity.NORMAL -> R.string.baby_monitor_sensitivity_normal
                                        BabySensitivity.STABLE -> R.string.baby_monitor_sensitivity_stable
                                    },
                                ),
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()

                // 免打扰时段开关 + 起止时间（分钟粒度，跨午夜由 Store 语义处理）。
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.baby_monitor_quiet_title),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.baby_monitor_quiet_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = config.quietHoursEnabled,
                        onCheckedChange = { scope.launch { store.setQuietHoursEnabled(it) } },
                    )
                }
                if (config.quietHoursEnabled) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.baby_monitor_quiet_start,
                            formatTime(config.quietStartMin),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = config.quietStartMin.toFloat(),
                        onValueChange = { scope.launch { store.setQuietStartMin(it.toInt()) } },
                        valueRange = 0f..1439f,
                    )
                    Text(
                        text = stringResource(
                            R.string.baby_monitor_quiet_end,
                            formatTime(config.quietEndMin),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = config.quietEndMin.toFloat(),
                        onValueChange = { scope.launch { store.setQuietEndMin(it.toInt()) } },
                        valueRange = 0f..1439f,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 5. 灵敏度参数 ──
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.baby_monitor_sensitivity_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(
                        R.string.baby_monitor_sustain_desc,
                        config.sustainHits,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = config.sustainHits.toFloat(),
                    onValueChange = { scope.launch { store.setSustainHits(it.toInt()) } },
                    valueRange = 1f..8f,
                    steps = 6,
                )
                Text(
                    text = stringResource(
                        R.string.baby_monitor_cooldown_desc,
                        config.cooldownMinutes,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = config.cooldownMinutes.toFloat(),
                    onValueChange = { scope.launch { store.setCooldownMinutes(it.toInt()) } },
                    valueRange = 1f..30f,
                    steps = 28,
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

/** 把当天分钟数格式化为 HH:mm。 */
private fun formatTime(minuteOfDay: Int): String {
    val m = ((minuteOfDay % 1440) + 1440) % 1440
    return String.format("%02d:%02d", m / 60, m % 60)
}
