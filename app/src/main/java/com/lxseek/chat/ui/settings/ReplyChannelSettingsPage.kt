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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.channel.ReplyChannelConfig
import com.lxseek.chat.channel.ReplyChannelStore
import com.lxseek.chat.channel.SmtpProviderPresets
import com.lxseek.chat.channel.SmtpSender
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
 * 3. 邮箱卡片（开关 + 发件邮箱 + SMTP 服务器/端口/加密方式 + 授权码 + 默认收件人）
 * 4. 渠道选择卡片（勾选回复时启用哪些渠道）
 * 5. 保存按钮
 *
 * 邮件用手写轻量 SMTP 客户端直连（[SmtpSender]），无需申请第三方发信 API key，
 * 不引入 JavaMail，APK 体积零增量。填发件邮箱会自动带出常用邮箱的 SMTP 服务器。
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
    var emailFrom by remember(config) { mutableStateOf(config.emailFrom) }
    var emailPassword by remember(config) { mutableStateOf(config.emailPassword) }
    var emailSmtpHost by remember(config) { mutableStateOf(config.emailSmtpHost) }
    var emailSmtpPort by remember(config) { mutableStateOf(config.emailSmtpPort.toString()) }
    var emailSmtpSecurity by remember(config) { mutableStateOf(config.emailSmtpSecurity) }
    var emailDefaultTo by remember(config) { mutableStateOf(config.emailDefaultTo) }

    // 渠道选择（additionalChannels）。
    val selectedChannels = remember(config) { config.additionalChannels.toMutableStateList() }
    var emailSecurityMenuExpanded by remember { mutableStateOf(false) }

    fun toggleChannel(id: String, on: Boolean) {
        if (on) {
            if (id !in selectedChannels) selectedChannels.add(id)
        } else {
            selectedChannels.remove(id)
        }
    }

    /** 发件邮箱变化时，若 SMTP 服务器尚未填写，则从常见邮箱服务商预设自动带出。 */
    fun onEmailFromChanged(value: String) {
        emailFrom = value
        if (emailSmtpHost.isBlank()) {
            SmtpProviderPresets.suggestFor(value)?.let {
                emailSmtpHost = it.host
                emailSmtpPort = it.port.toString()
                emailSmtpSecurity = when (it.security) {
                    SmtpSender.Security.STARTTLS -> ReplyChannelConfig.SECURITY_STARTTLS
                    SmtpSender.Security.NONE -> ReplyChannelConfig.SECURITY_NONE
                    else -> ReplyChannelConfig.SECURITY_SSL
                }
            }
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
                    emailFrom = emailFrom.trim(),
                    emailPassword = emailPassword,
                    emailSmtpHost = emailSmtpHost.trim(),
                    emailSmtpPort = emailSmtpPort.trim().toIntOrNull() ?: 465,
                    emailSmtpSecurity = emailSmtpSecurity,
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

        // ── 3. 邮箱卡片（SMTP 直连，填发件邮箱自动带出常用邮箱服务器） ──
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

                OutlinedTextField(
                    value = emailFrom,
                    onValueChange = { onEmailFromChanged(it) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.reply_channel_email_from)) },
                    placeholder = { Text("xxx@qq.com") },
                    supportingText = { Text(stringResource(R.string.reply_channel_email_from_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = emailSmtpHost,
                    onValueChange = { emailSmtpHost = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.reply_channel_email_smtp_host)) },
                    placeholder = { Text("smtp.qq.com") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = emailSmtpPort,
                        onValueChange = { emailSmtpPort = it.filter(Char::isDigit).take(5) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.reply_channel_email_smtp_port)) },
                        placeholder = { Text("465") },
                        modifier = Modifier.weight(1f),
                    )
                    // 加密方式下拉
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = stringResource(securityLabel(emailSmtpSecurity)),
                            onValueChange = { /* 只读 */ },
                            readOnly = true,
                            singleLine = true,
                            label = { Text(stringResource(R.string.reply_channel_email_smtp_security)) },
                            trailingIcon = {
                                IconButton(onClick = { emailSecurityMenuExpanded = true }) {
                                    Icon(Icons.Default.ExpandMore, contentDescription = null)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        DropdownMenu(
                            expanded = emailSecurityMenuExpanded,
                            onDismissRequest = { emailSecurityMenuExpanded = false },
                        ) {
                            ReplyChannelConfig.EMAIL_SECURITY_OPTIONS.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(securityLabel(s)) },
                                    onClick = {
                                        emailSmtpSecurity = s
                                        emailSecurityMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = emailPassword,
                    onValueChange = { emailPassword = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(stringResource(R.string.reply_channel_email_password)) },
                    supportingText = { Text(stringResource(R.string.reply_channel_email_password_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
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

/** SMTP 加密方式 → 显示标签资源 ID。 */
private fun securityLabel(security: String): Int = when (security) {
    ReplyChannelConfig.SECURITY_STARTTLS -> R.string.reply_channel_email_security_starttls
    ReplyChannelConfig.SECURITY_NONE -> R.string.reply_channel_email_security_none
    else -> R.string.reply_channel_email_security_ssl
}