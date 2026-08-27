package com.lxseek.chat.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.core.content.ContextCompat
import com.lxseek.chat.R
import com.lxseek.chat.sms.SmsCommandConfigStore
import com.lxseek.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

/**
 * SMS 命令系统设置页。
 *
 * 页面布局：
 * 1. 短信权限状态卡片（RECEIVE_SMS / READ_SMS）
 * 2. 总开关卡片
 * 3. 白名单号码卡片（列表 + 添加 + 删除）
 * 4. 命令帮助说明卡片
 *
 * 真正的监听逻辑见 [com.lxseek.chat.sms.SmsCommandReceiver]，
 * 执行逻辑见 [com.lxseek.chat.sms.SmsCommandExecutorService]。
 */
@Composable
fun SmsCommandSettingsPage(
    @Suppress("UNUSED_PARAMETER") viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { SmsCommandConfigStore(context.applicationContext) }

    val config by store.config.collectAsState(initial = com.lxseek.chat.sms.SmsCommandConfig())

    var enabled by remember(config) { mutableStateOf(config.enabled) }
    var newSender by remember { mutableStateOf("") }

    // 短信权限状态
    val smsPermissionGranted = remember(context) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* 结果变化时 recompose 会重新读取 checkSelfPermission */ }

    fun save() {
        scope.launch {
            store.update {
                it.copy(enabled = enabled)
            }
            Toast.makeText(
                context,
                context.getString(R.string.sms_command_saved),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun addSender() {
        val s = newSender.trim()
        if (s.isEmpty()) return
        scope.launch {
            store.update { cfg ->
                if (cfg.allowedSenders.any { store.normalizeSender(it) == store.normalizeSender(s) }) {
                    cfg // 已存在，不重复添加
                } else {
                    cfg.copy(allowedSenders = cfg.allowedSenders + s)
                }
            }
            newSender = ""
        }
    }

    fun removeSender(s: String) {
        scope.launch {
            store.update { cfg ->
                cfg.copy(allowedSenders = cfg.allowedSenders - s)
            }
        }
    }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_sms_command),
        onBack = onBack,
        scrollState = rememberScrollState(),
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // ── 1. 短信权限状态卡片 ──
        Card(colors = CardDefaults.cardColors()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (smsPermissionGranted) Icons.Default.Check else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (smsPermissionGranted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            if (smsPermissionGranted) R.string.sms_command_permission_granted
                            else R.string.sms_command_permission_denied
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (smsPermissionGranted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = stringResource(R.string.sms_command_permission_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!smsPermissionGranted) {
                    OutlinedButton(onClick = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.RECEIVE_SMS,
                                Manifest.permission.READ_SMS,
                            ),
                        )
                    }) {
                        Text(stringResource(R.string.sms_command_grant))
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
                        text = stringResource(R.string.sms_command_enabled),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.sms_command_enabled_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 3. 白名单号码卡片 ──
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.sms_command_whitelist),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.sms_command_whitelist_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                // 已添加号码列表
                if (config.allowedSenders.isNotEmpty()) {
                    config.allowedSenders.forEachIndexed { idx, sender ->
                        if (idx > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = sender,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(onClick = { removeSender(sender) }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.sms_command_remove),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                } else {
                    Text(
                        text = stringResource(R.string.sms_command_whitelist_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // 添加新号码
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newSender,
                        onValueChange = { newSender = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.sms_command_whitelist_hint)) },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = { addSender() },
                        enabled = newSender.isNotBlank(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.sms_command_add),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 4. 命令帮助说明卡片 ──
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.sms_command_help),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.sms_command_help_content),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── 保存按钮 ──
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text(stringResource(android.R.string.cancel))
            }
            Button(onClick = { save() }, modifier = Modifier.weight(1f)) {
                Text(stringResource(android.R.string.ok))
            }
        }
    }
}