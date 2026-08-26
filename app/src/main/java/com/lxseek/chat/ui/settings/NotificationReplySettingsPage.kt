package com.lxseek.chat.ui.settings

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
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

    // 编辑用的本地编辑态（进入页面时从配置填充）。
    var enabled by remember(config) { mutableStateOf(config.enabled) }
    var packagesText by remember(config) { mutableStateOf(config.packages.joinToString(",")) }
    var contactsText by remember(config) {
        mutableStateOf(config.contacts.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }
    var promptText by remember(config) { mutableStateOf(config.promptHeader) }
    var cooldownSeconds by remember(config) { mutableStateOf((config.cooldownMs / 1000).toString()) }

    fun save() {
        scope.launch {
            val parsedPackages = packagesText.split(',')
                .map { it.trim() }.filter { it.isNotEmpty() }
            val parsedContacts = linkedMapOf<String, String>()
            contactsText.lineSequence().forEach { line ->
                val idx = line.indexOf('=')
                if (idx > 0) {
                    val name = line.substring(0, idx).trim()
                    val id = line.substring(idx + 1).trim()
                    if (name.isNotEmpty() && id.isNotEmpty()) parsedContacts[name] = id
                }
            }
            val cooldown = cooldownSeconds.trim().toLongOrNull()?.coerceAtLeast(0) ?: 0L
            store.update {
                it.copy(
                    enabled = enabled,
                    packages = parsedPackages,
                    contacts = parsedContacts,
                    promptHeader = promptText.trim(),
                    cooldownMs = cooldown * 1000,
                )
            }
            Toast.makeText(context, context.getString(R.string.notification_reply_saved), Toast.LENGTH_SHORT).show()
        }
    }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_notification_reply),
        onBack = onBack,
        scrollState = rememberScrollState(),
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
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
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.notification_reply_grant_hint))
        }

        Spacer(Modifier.height(12.dp))

        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.notification_reply_packages),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = packagesText,
                    onValueChange = { packagesText = it },
                    singleLine = true,
                    label = { Text("com.tencent.mm") },
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                Text(
                    text = stringResource(R.string.notification_reply_contacts),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.notification_reply_contacts_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = contactsText,
                    onValueChange = { contactsText = it },
                    minLines = 3,
                    label = { Text("张三=wxid_xxx") },
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider(Modifier.padding(vertical = 12.dp))

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
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                Text(
                    text = stringResource(R.string.notification_reply_cooldown),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = cooldownSeconds,
                    onValueChange = { cooldownSeconds = it.filter(Char::isDigit) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
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