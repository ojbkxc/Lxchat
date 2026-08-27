package com.lxseek.chat.ui.settings

import android.widget.Toast
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
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.channel.ReplyChannelConfig
import com.lxseek.chat.channel.ReplyChannelStore
import kotlinx.coroutines.launch

/**
 * 多回复渠道设置页。
 *
 * 与 [NotificationReplySettingsPage] 并列：后者管「抓通知 → AI 生成回复 → 微信发回」的主链路，
 * 本页管「除微信外，再把回复推送到 Telegram / Bark / 邮箱」的附加出口。配置写到独立的
 * DataStore（[ReplyChannelStore]），由 [com.lxseek.chat.notification.NotificationAutoReplyService.sendReply]
 * 读取并按 [ReplyChannelConfig.additionalChannels] 顺序逐渠道发送。
 *
 * 页面布局：
 * 1. Telegram 卡片（开关 + Bot Token + Base URL）
 * 2. Bark 卡片（开关 + Server URL + Device Key）
 * 3. 邮箱卡片（开关 + Provider 下拉 + API Key + From + Mailgun 域名 + 默认收件人）
 * 4. 渠道选择卡片（勾选回复时启用哪些渠道）
 * 5. 保存按钮
 *
 * 邮件用 HTTP API（Resend/SendGrid/Mailgun），不引入 JavaMail，APK 体积零增量。
 */
@Composable
fun ReplyChannelSettingsPage(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { ReplyChannelStore(context.applicationContext) }

    val config by store.config.collectAsState(initial = ReplyChannelConfig())

    // 本地编辑态（config 变更时重置）。
    var telegramEnabled by remember(config) { mutableStateOf(config.telegramEnabled) }
    var telegramBotToken by remember(config) { mutableStateOf(config.telegramBotToken) }
    var telegramBaseUrl by remember(config) { mutableStateOf(config.telegramBaseUrl) }

    var barkEnabled by remember(config) { mutableStateOf(config.barkEnabled) }
    var barkServerUrl by remember(config) { mutableStateOf(config.barkServerUrl) }
    var barkDeviceKey by remember(config) { mutableStateOf(config.barkDeviceKey) }

    var emailEnabled by remember(config) { mutableStateOf(config.emailEnabled) }
    var emailProvider by remember(config) { mutableStateOf(config.emailProvider) }
    var emailApiKey by remember(config) { mutableStateOf(config.emailApiKey) }
    var emailFrom by remember(config) { mutableStateOf(config.emailFrom) }
    var emailMailgunDomain by remember(config) { mutableStateOf(config.emailMailgunDomain) }
    var emailDefaultTo by remember(config) { mutableStateOf(config.emailDefaultTo) }

    // 渠道选择（additionalChannels）。
    val selectedChannels = remember(config) { config.additionalChannels.toMutableStateList() }
    var emailProviderMenuExpanded by remember { mutableStateOf(false) }

    fun toggleChannel(id: String, on: Boolean) {
        if (on) {
            if (id !in selectedChannels) selectedChannels.add(id)
        } else {
            selectedChannels.remove(id)
        }
    }

    fun save() {
        scope.launch {
            store.update {
                it.copy(
                    telegramEnabled = telegramEnabled,
                    telegramBotToken = telegramBotToken.trim(),
                    telegramBaseUrl = telegramBaseUrl.trim(),
                    barkEnabled = barkEnabled,
                    barkServerUrl = barkServerUrl.trim(),
                    barkDeviceKey = barkDeviceKey.trim(),
                    emailEnabled = emailEnabled,
                    emailProvider = emailProvider,
                    emailApiKey = emailApiKey.trim(),
                    emailFrom = emailFrom.trim(),
                    emailMailgunDomain = emailMailgunDomain.trim(),
                    emailDefaultTo = emailDefaultTo.trim(),
                    additionalChannels = selectedChannels.toList(),
                )
            }
            Toast.makeText(context, context.getString(R.string.reply_channel_saved), Toast.LENGTH_SHORT).show()
        }
    }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_reply_channel),
        onBack = onBack,
        scrollState = rememberScrollState(),
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // ── 1. Telegram 卡片 ──
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.reply_channel_telegram),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.reply_channel_telegram_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = telegramEnabled, onCheckedChange = { telegramEnabled = it })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = telegramBotToken,
                    onValueChange = { telegramBotToken = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.reply_channel_telegram_token)) },
                    placeholder = { Text("123456789:ABCdef...") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = telegramBaseUrl,
                    onValueChange = { telegramBaseUrl = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.reply_channel_telegram_base_url)) },
                    placeholder = { Text("https://api.telegram.org/") },
                    supportingText = { Text(stringResource(R.string.reply_channel_telegram_base_url_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 2. Bark 卡片 ──
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.reply_channel_bark),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.reply_channel_bark_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = barkEnabled, onCheckedChange = { barkEnabled = it })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = barkServerUrl,
                    onValueChange = { barkServerUrl = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.reply_channel_bark_server)) },
                    placeholder = { Text("https://api.day.app") },
                    supportingText = { Text(stringResource(R.string.reply_channel_bark_server_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = barkDeviceKey,
                    onValueChange = { barkDeviceKey = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.reply_channel_bark_device_key)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 3. 邮箱卡片 ──
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.reply_channel_email),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.reply_channel_email_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = emailEnabled, onCheckedChange = { emailEnabled = it })
                }
                Spacer(Modifier.height(8.dp))

                // Provider 下拉
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = emailProvider,
                        onValueChange = { /* 只读 */ },
                        readOnly = true,
                        singleLine = true,
                        label = { Text(stringResource(R.string.reply_channel_email_provider)) },
                        trailingIcon = {
                            IconButton(onClick = { emailProviderMenuExpanded = true }) {
                                Icon(Icons.Default.ExpandMore, contentDescription = null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DropdownMenu(
                        expanded = emailProviderMenuExpanded,
                        onDismissRequest = { emailProviderMenuExpanded = false },
                    ) {
                        ReplyChannelConfig.EMAIL_PROVIDERS.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p) },
                                onClick = { emailProvider = p; emailProviderMenuExpanded = false },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = emailApiKey,
                    onValueChange = { emailApiKey = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.reply_channel_email_api_key)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = emailFrom,
                    onValueChange = { emailFrom = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.reply_channel_email_from)) },
                    placeholder = { Text("LxChat <noreply@example.com>") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (emailProvider == "mailgun") {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = emailMailgunDomain,
                        onValueChange = { emailMailgunDomain = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.reply_channel_email_mailgun_domain)) },
                        placeholder = { Text("mg.example.com") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = emailDefaultTo,
                    onValueChange = { emailDefaultTo = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.reply_channel_email_default_to)) },
                    supportingText = { Text(stringResource(R.string.reply_channel_email_default_to_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 4. 渠道选择卡片 ──
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.reply_channel_select),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.reply_channel_select_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                ChannelToggleRow(
                    label = stringResource(R.string.reply_channel_telegram),
                    checked = ReplyChannelConfig.CHANNEL_TELEGRAM in selectedChannels,
                    onCheckedChange = { toggleChannel(ReplyChannelConfig.CHANNEL_TELEGRAM, it) },
                )
                ChannelToggleRow(
                    label = stringResource(R.string.reply_channel_bark),
                    checked = ReplyChannelConfig.CHANNEL_BARK in selectedChannels,
                    onCheckedChange = { toggleChannel(ReplyChannelConfig.CHANNEL_BARK, it) },
                )
                ChannelToggleRow(
                    label = stringResource(R.string.reply_channel_email),
                    checked = ReplyChannelConfig.CHANNEL_EMAIL in selectedChannels,
                    onCheckedChange = { toggleChannel(ReplyChannelConfig.CHANNEL_EMAIL, it) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── 5. 保存按钮 ──
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

/** 渠道选择行：标签 + Switch。 */
@Composable
private fun ChannelToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}