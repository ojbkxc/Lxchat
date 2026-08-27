package com.lxseek.chat.ui.settings

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.core.app.NotificationManagerCompat
import com.lxseek.chat.R
import com.lxseek.chat.notification.ContactMapping
import com.lxseek.chat.notification.NotificationReplyConfig
import com.lxseek.chat.notification.NotificationReplyStore
import com.lxseek.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

/**
 * 系统通知自动回复设置页。
 *
 * 与 IM 渠道（微信）并列的独立能力：抓取白名单 App 的系统通知 → 提取发送者与内容 →
 * 交 [NotificationReplyStore] 配置 + 绑定好的 [com.lxseek.chat.im.weixin.WeixinChannel]
 * 发出 AI 自动回复。真正的监听逻辑见 notification.NotificationAutoReplyService。
 *
 * 页面布局：
 * 1. 授权状态卡片（通知使用权）
 * 2. 总开关卡片
 * 3. 监听 App 卡片（只读，固定微信）
 * 4. 联系人映射卡片（自动映射列表 + 删除 + 统计 + 手动添加折叠区）
 * 5. 提示词卡片（多行文本 + 默认提示词预览 + 恢复默认）
 * 6. 冷却时间卡片（Slider 0-300 秒）
 * 7. 模型选择卡片（下拉框）
 * 8. 配置自检按钮
 * 9. 保存 / 前往 IM 按钮
 */
@Composable
fun NotificationReplySettingsPage(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { NotificationReplyStore(context.applicationContext) }

    val config by store.config.collectAsState(initial = NotificationReplyConfig())

    // 可用模型列表（provider -> models），与 BotSettingsPanel 一致的扁平化策略。
    val availableModels by viewModel.settings.availableModels.collectAsState()

    // 本地编辑态（进入页面 / 配置变更时从 config 填充）。
    var enabled by remember(config) { mutableStateOf(config.enabled) }
    // packages 固定为 com.tencent.mm，不再可编辑；保存时写回固定值。
    val fixedPackages = remember { listOf("com.tencent.mm") }
    // 联系人映射：直接编辑 store（删除即时生效），不再用文本域。
    var promptText by remember(config) { mutableStateOf(config.promptHeader) }
    var cooldownSeconds by remember(config) { mutableStateOf((config.cooldownMs / 1000).toInt()) }
    var modelId by remember(config) { mutableStateOf(config.modelId) }
    var blacklistText by remember(config) { mutableStateOf(config.blacklist) }
    var onlyWhenLocked by remember(config) { mutableStateOf(config.onlyWhenLocked) }

    // 手动添加联系人折叠区状态。
    var showManualAdd by remember { mutableStateOf(false) }
    var manualName by remember { mutableStateOf("") }
    var manualUserId by remember { mutableStateOf("") }

    // 模型下拉框状态。
    var modelMenuExpanded by remember { mutableStateOf(false) }

    // 配置自检结果。
    var testReport by remember { mutableStateOf<String?>(null) }

    // 通知使用权授权状态：每次组合（含返回页面）都重新计算。
    val listenerGranted = remember(context) {
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
    }

    /** 立即删除一条联系人映射（即时写回 store，不需等保存）。 */
    fun deleteContact(name: String) {
        scope.launch {
            store.update { cfg ->
                cfg.copy(contacts = cfg.contacts - name)
            }
        }
    }

    /** 立即添加一条手动联系人映射。 */
    fun addManualContact() {
        val name = manualName.trim()
        val id = manualUserId.trim()
        if (name.isEmpty() || id.isEmpty()) return
        scope.launch {
            store.update { cfg ->
                cfg.copy(contacts = cfg.contacts + (name to ContactMapping(userId = id)))
            }
            manualName = ""
            manualUserId = ""
            showManualAdd = false
        }
    }

    fun save() {
        scope.launch {
            store.update {
                it.copy(
                    enabled = enabled,
                    packages = fixedPackages,
                    promptHeader = promptText.trim(),
                    cooldownMs = cooldownSeconds.toLong() * 1000,
                    modelId = modelId?.takeIf { id -> id.isNotBlank() },
                    blacklist = blacklistText,
                    onlyWhenLocked = onlyWhenLocked,
                )
            }
            Toast.makeText(context, context.getString(R.string.notification_reply_saved), Toast.LENGTH_SHORT).show()
        }
    }

    /** 配置自检：逐项检查并拼出诊断报告（不真的发消息，避免误发）。 */
    fun runSelfTest() {
        val sb = StringBuilder()
        val cfg = config
        // 1. 授权
        if (listenerGranted) {
            sb.appendLine("✓ ${context.getString(R.string.notification_reply_test_granted)}")
        } else {
            sb.appendLine("✗ ${context.getString(R.string.notification_reply_test_not_granted)}")
        }
        // 2. 总开关
        if (cfg.enabled) {
            sb.appendLine("✓ ${context.getString(R.string.notification_reply_test_enabled)}")
        } else {
            sb.appendLine("✗ ${context.getString(R.string.notification_reply_test_disabled)}")
        }
        // 3. 监听包名
        if (cfg.packages.contains("com.tencent.mm")) {
            sb.appendLine("✓ ${context.getString(R.string.notification_reply_test_pkg_ok)}")
        } else {
            sb.appendLine("✗ ${context.getString(R.string.notification_reply_test_pkg_missing)}")
        }
        // 4. 联系人映射数
        val n = cfg.contacts.size
        if (n > 0) {
            sb.appendLine("✓ ${context.getString(R.string.notification_reply_test_contacts_ok, n)}")
        } else {
            sb.appendLine("✗ ${context.getString(R.string.notification_reply_test_contacts_empty)}")
        }
        // 5. 模型
        val mid = cfg.modelId
        if (mid.isNullOrBlank()) {
            sb.appendLine("• ${context.getString(R.string.notification_reply_test_model_default)}")
        } else {
            sb.appendLine("✓ ${context.getString(R.string.notification_reply_test_model_set, mid)}")
        }
        // 6. 冷却
        sb.appendLine("• ${context.getString(R.string.notification_reply_test_cooldown, (cfg.cooldownMs / 1000).toString())}")
        // 7. 示例 prompt 预览
        val samplePrompt = buildSamplePrompt(cfg.promptHeader, "com.tencent.mm", "测试好友", "你好，这是测试消息")
        sb.appendLine()
        sb.appendLine(context.getString(R.string.notification_reply_test_sample_prompt))
        sb.append(samplePrompt.take(200))
        testReport = sb.toString()
    }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_notification_reply),
        onBack = onBack,
        scrollState = rememberScrollState(),
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // ── 1. 授权状态卡片 ──
        Card(colors = CardDefaults.cardColors()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (listenerGranted) Icons.Default.Check else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (listenerGranted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            if (listenerGranted) R.string.notification_reply_listener_granted
                            else R.string.notification_reply_listener_denied
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (listenerGranted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = stringResource(
                            if (listenerGranted) R.string.notification_reply_listener_granted_desc
                            else R.string.notification_reply_listener_denied_desc
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!listenerGranted) {
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }) {
                        Text(stringResource(R.string.notification_reply_grant))
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 2. 总开关卡片 ──
        Card(colors = CardDefaults.cardColors()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.notification_reply_enabled),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.notification_reply_enabled_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 3. 监听 App 卡片（只读） ──
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.notification_reply_packages),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.notification_reply_packages_fixed),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "com.tencent.mm",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 4. 联系人映射卡片 ──
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.notification_reply_contacts),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.notification_reply_contacts_auto_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                // 已映射好友数统计
                val contactCount = config.contacts.size
                Text(
                    text = if (contactCount == 0) {
                        stringResource(R.string.notification_reply_contacts_empty)
                    } else {
                        stringResource(R.string.notification_reply_contacts_count, contactCount)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (contactCount == 0) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary,
                )

                Spacer(Modifier.height(8.dp))

                // 映射列表（每行：昵称 → userId 截断 + 删除按钮）
                if (contactCount > 0) {
                    config.contacts.entries.forEachIndexed { idx, (name, mapping) ->
                        if (idx > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "→ ${mapping.userId.take(16)}${if (mapping.userId.length > 16) "…" else ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = { deleteContact(name) }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.notification_reply_delete_contact),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 手动添加折叠区（高级模式）
                TextButton(onClick = { showManualAdd = !showManualAdd }) {
                    Icon(Icons.Default.ExpandMore, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.notification_reply_manual_add))
                }
                AnimatedVisibility(visible = showManualAdd) {
                    Column {
                        OutlinedTextField(
                            value = manualName,
                            onValueChange = { manualName = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.notification_reply_manual_name)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = manualUserId,
                            onValueChange = { manualUserId = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.notification_reply_manual_user_id)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { addManualContact() },
                            enabled = manualName.isNotBlank() && manualUserId.isNotBlank(),
                        ) {
                            Text(stringResource(R.string.notification_reply_manual_add_btn))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 5. 提示词卡片 ──
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.notification_reply_prompt),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.notification_reply_prompt_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    minLines = 3,
                    placeholder = {
                        Text(stringResource(R.string.notification_reply_prompt_placeholder))
                    },
                    supportingText = {
                        Text(stringResource(R.string.notification_reply_prompt_vars))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { promptText = "" }) {
                    Text(stringResource(R.string.notification_reply_prompt_reset))
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 5.5 关键词黑名单卡片 ──
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.notification_reply_blacklist),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.notification_reply_blacklist_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = blacklistText,
                    onValueChange = { blacklistText = it },
                    minLines = 3,
                    placeholder = {
                        Text(stringResource(R.string.notification_reply_blacklist_placeholder))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 5.6 仅锁屏回复卡片 ──
        Card(colors = CardDefaults.cardColors()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.notification_reply_only_when_locked),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.notification_reply_only_when_locked_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = onlyWhenLocked, onCheckedChange = { onlyWhenLocked = it })
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 6. 冷却时间卡片（Slider） ──
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.notification_reply_cooldown),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.notification_reply_cooldown_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("0 s", style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = "$cooldownSeconds s",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text("300 s", style = MaterialTheme.typography.bodySmall)
                }
                Slider(
                    value = cooldownSeconds.toFloat(),
                    onValueChange = { cooldownSeconds = it.toInt() },
                    valueRange = 0f..300f,
                    steps = 29, // 步长 10：0,10,20,...,300 共 31 个点 → steps = 29
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 7. 模型选择卡片 ──
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.notification_reply_model),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.notification_reply_model_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                val flatModels = remember(availableModels) {
                    availableModels.flatMap { (provider, models) ->
                        models.map { model -> provider to model }
                    }
                }
                val currentModelDisplay = if (modelId.isNullOrBlank()) {
                    stringResource(R.string.im_channel_settings_follow_default)
                } else {
                    // modelId 是 Compose 委托属性（String?），无法智能转换；先读为局部非空值再操作
                    val mId: String = modelId ?: ""
                    val idx = mId.indexOf(':')
                    if (idx > 0) "${mId.substring(0, idx)} / ${mId.substring(idx + 1)}" else mId
                }

                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = currentModelDisplay,
                        onValueChange = { /* 只读，由下方 Dropdown 选择 */ },
                        readOnly = true,
                        singleLine = true,
                        label = { Text(stringResource(R.string.notification_reply_model)) },
                        trailingIcon = {
                            IconButton(onClick = { modelMenuExpanded = true }) {
                                Icon(Icons.Default.ExpandMore, contentDescription = null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DropdownMenu(
                        expanded = modelMenuExpanded,
                        onDismissRequest = { modelMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.im_channel_settings_follow_default)) },
                            onClick = { modelId = null; modelMenuExpanded = false },
                        )
                        flatModels.forEach { (provider, model) ->
                            DropdownMenuItem(
                                text = { Text("$provider / $model") },
                                onClick = {
                                    modelId = "$provider:$model"
                                    modelMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 8. 配置自检按钮 + 结果 ──
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.notification_reply_test),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.notification_reply_test_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { runSelfTest() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.notification_reply_test_btn))
                }
                AnimatedVisibility(visible = testReport != null) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = testReport.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── 9. 保存 / 前往 IM 按钮 ──
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.notification_reply_to_im))
            }
            Button(onClick = { save() }, modifier = Modifier.weight(1f)) {
                Text(stringResource(android.R.string.ok))
            }
        }
    }
}

/** 构造示例 prompt（与 NotificationAutoReplyService.buildPrompt 同逻辑），用于自检预览。 */
private fun buildSamplePrompt(header: String, appPackage: String, sender: String, content: String): String {
    val head = if (header.isBlank()) {
        "你正在代理我的微信账号做\"系统通知自动回复\"。好友「$sender」发来一条新消息，" +
            "请生成一条简短、得体、口语化的自动回复。不要编造事实，也不要在回复里泄露这是机器人。"
    } else {
        header.replace("{app}", appPackage).replace("{sender}", sender)
    }
    return buildString {
        append(head)
        append("\n消息内容：\n")
        append(content)
    }
}
