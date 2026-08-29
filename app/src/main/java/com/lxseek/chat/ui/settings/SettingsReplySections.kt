package com.lxseek.chat.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lxseek.chat.R
import com.lxseek.chat.channel.ReplyChannelConfig
import com.lxseek.chat.channel.ReplyChannelStore
import com.lxseek.chat.channel.SmtpProviderPresets
import com.lxseek.chat.channel.SmtpSender
import com.lxseek.chat.sms.SmsCommandConfigStore
import com.lxseek.chat.ui.components.MembershipGatedContent
import com.lxseek.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════════════════
// SMS Command Section
// ═══════════════════════════════════════════════════════════════

/**
 * SMS command section — embedded inside the notification reply page.
 */
@Composable
internal fun SmsCommandSection(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { SmsCommandConfigStore(context.applicationContext) }

    val config by store.config.collectAsState(initial = com.lxseek.chat.sms.SmsCommandConfig())

    var enabled by remember(config) { mutableStateOf(config.enabled) }
    var newSender by remember { mutableStateOf("") }

    val smsPermissionGranted = remember(context) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* recompose will re-read checkSelfPermission */ }

    fun save() {
        scope.launch {
            store.update { it.copy(enabled = enabled) }
            Toast.makeText(context, context.getString(R.string.sms_command_saved), Toast.LENGTH_SHORT).show()
        }
    }

    fun addSender() {
        val s = newSender.trim()
        if (s.isEmpty()) return
        scope.launch {
            store.update { cfg ->
                if (cfg.allowedSenders.any { store.normalizeSender(it) == store.normalizeSender(s) }) cfg
                else cfg.copy(allowedSenders = cfg.allowedSenders + s)
            }
            newSender = ""
        }
    }

    fun removeSender(s: String) {
        scope.launch { store.update { cfg -> cfg.copy(allowedSenders = cfg.allowedSenders - s) } }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // ── Section header ──
    Text(
        text = stringResource(R.string.settings_sms_command),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(vertical = 4.dp),
    )

    Spacer(modifier = Modifier.height(8.dp))

    // ── 1. SMS permission card ──
    Card(colors = CardDefaults.cardColors()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (smsPermissionGranted) Icons.Default.Check else Icons.Default.Warning,
                contentDescription = null,
                tint = if (smsPermissionGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(if (smsPermissionGranted) R.string.sms_command_permission_granted else R.string.sms_command_permission_denied),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (smsPermissionGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(R.string.sms_command_permission_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!smsPermissionGranted) {
                OutlinedButton(onClick = {
                    permissionLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS))
                }) { Text(stringResource(R.string.sms_command_grant)) }
            }
        }
    }

    Spacer(Modifier.height(12.dp))

    // ── 2. Master toggle ──
    Card(colors = CardDefaults.cardColors()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = stringResource(R.string.sms_command_enabled), style = MaterialTheme.typography.titleMedium)
                Text(text = stringResource(R.string.sms_command_enabled_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
    }

    Spacer(Modifier.height(12.dp))

    // ── 3. Whitelist card ──
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(16.dp)) {
            Text(text = stringResource(R.string.sms_command_whitelist), style = MaterialTheme.typography.titleSmall)
            Text(text = stringResource(R.string.sms_command_whitelist_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))

            if (config.allowedSenders.isNotEmpty()) {
                config.allowedSenders.forEachIndexed { idx, sender ->
                    if (idx > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = sender, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { removeSender(sender) }) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = stringResource(R.string.sms_command_remove), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            } else {
                Text(text = stringResource(R.string.sms_command_whitelist_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = newSender, onValueChange = { newSender = it }, singleLine = true, label = { Text(stringResource(R.string.sms_command_whitelist_hint)) }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { addSender() }, enabled = newSender.isNotBlank()) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = stringResource(R.string.sms_command_add))
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))

    // ── 4. Help card ──
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(16.dp)) {
            Text(text = stringResource(R.string.sms_command_help), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Text(text = stringResource(R.string.sms_command_help_content), style = MaterialTheme.typography.bodySmall)
        }
    }

    Spacer(Modifier.height(12.dp))

    // ── Save button ──
    Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.sms_command_saved))
    }
}

// ═══════════════════════════════════════════════════════════════
// Reply Channel Section
// ═══════════════════════════════════════════════════════════════

/**
 * Reply channel section — embedded inside the notification reply page.
 */
@Composable
internal fun ReplyChannelSection(
    isPremium: Boolean = false,
    onUpgradeClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { ReplyChannelStore(context.applicationContext) }

    val config by store.config.collectAsState(initial = ReplyChannelConfig())

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

    val selectedChannels = remember(config) { config.additionalChannels.toMutableStateList() }
    var emailSecurityMenuExpanded by remember { mutableStateOf(false) }

    fun toggleChannel(id: String, on: Boolean) {
        if (on) { if (id !in selectedChannels) selectedChannels.add(id) }
        else { selectedChannels.remove(id) }
    }

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

    Spacer(modifier = Modifier.height(16.dp))

    // ── Section header ──
    Text(
        text = stringResource(R.string.settings_reply_channel),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(vertical = 4.dp),
    )

    Spacer(modifier = Modifier.height(8.dp))

    // ── 1. Telegram card ──
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.reply_channel_telegram), style = MaterialTheme.typography.titleMedium)
                    Text(text = stringResource(R.string.reply_channel_telegram_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = telegramEnabled, onCheckedChange = { telegramEnabled = it })
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = telegramBotToken, onValueChange = { telegramBotToken = it }, singleLine = true, label = { Text(stringResource(R.string.reply_channel_telegram_token)) }, placeholder = { Text("123456789:ABCdef...") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = telegramBaseUrl, onValueChange = { telegramBaseUrl = it }, singleLine = true, label = { Text(stringResource(R.string.reply_channel_telegram_base_url)) }, placeholder = { Text("https://api.telegram.org/") }, supportingText = { Text(stringResource(R.string.reply_channel_telegram_base_url_hint)) }, modifier = Modifier.fillMaxWidth())
        }
    }

    Spacer(Modifier.height(12.dp))

    // ── 2. Bark card ── (Premium-gated)
    Card(colors = CardDefaults.cardColors()) {
        MembershipGatedContent(
            isPremium = isPremium,
            featureName = stringResource(R.string.reply_channel_bark),
            onUpgradeClick = onUpgradeClick,
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.reply_channel_bark), style = MaterialTheme.typography.titleMedium)
                        Text(text = stringResource(R.string.reply_channel_bark_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = barkEnabled, onCheckedChange = { barkEnabled = it })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = barkServerUrl, onValueChange = { barkServerUrl = it }, singleLine = true, label = { Text(stringResource(R.string.reply_channel_bark_server)) }, placeholder = { Text("https://api.day.app") }, supportingText = { Text(stringResource(R.string.reply_channel_bark_server_hint)) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = barkDeviceKey, onValueChange = { barkDeviceKey = it }, singleLine = true, label = { Text(stringResource(R.string.reply_channel_bark_device_key)) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    Spacer(Modifier.height(12.dp))

    // ── 3. Email card ── (Premium-gated)
    Card(colors = CardDefaults.cardColors()) {
        MembershipGatedContent(
            isPremium = isPremium,
            featureName = stringResource(R.string.reply_channel_email),
            onUpgradeClick = onUpgradeClick,
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.reply_channel_email), style = MaterialTheme.typography.titleMedium)
                        Text(text = stringResource(R.string.reply_channel_email_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = emailEnabled, onCheckedChange = { emailEnabled = it })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = emailFrom, onValueChange = { onEmailFromChanged(it) }, singleLine = true, label = { Text(stringResource(R.string.reply_channel_email_from)) }, placeholder = { Text("xxx@qq.com") }, supportingText = { Text(stringResource(R.string.reply_channel_email_from_hint)) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = emailSmtpHost, onValueChange = { emailSmtpHost = it }, singleLine = true, label = { Text(stringResource(R.string.reply_channel_email_smtp_host)) }, placeholder = { Text("smtp.qq.com") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = emailSmtpPort, onValueChange = { emailSmtpPort = it.filter(Char::isDigits).take(5) }, singleLine = true, label = { Text(stringResource(R.string.reply_channel_email_smtp_port)) }, placeholder = { Text("465") }, modifier = Modifier.weight(1f))
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(value = stringResource(securityLabel(emailSmtpSecurity)), onValueChange = { }, readOnly = true, singleLine = true, label = { Text(stringResource(R.string.reply_channel_email_smtp_security)) }, trailingIcon = { IconButton(onClick = { emailSecurityMenuExpanded = true }) { Icon(Icons.Default.ExpandMore, contentDescription = null) } }, modifier = Modifier.fillMaxWidth())
                        DropdownMenu(expanded = emailSecurityMenuExpanded, onDismissRequest = { emailSecurityMenuExpanded = false }) {
                            ReplyChannelConfig.EMAIL_SECURITY_OPTIONS.forEach { s ->
                                DropdownMenuItem(text = { Text(stringResource(securityLabel(s))) }, onClick = { emailSmtpSecurity = s; emailSecurityMenuExpanded = false })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = emailPassword, onValueChange = { emailPassword = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text(stringResource(R.string.reply_channel_email_password)) }, supportingText = { Text(stringResource(R.string.reply_channel_email_password_hint)) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = emailDefaultTo, onValueChange = { emailDefaultTo = it }, singleLine = true, label = { Text(stringResource(R.string.reply_channel_email_default_to)) }, supportingText = { Text(stringResource(R.string.reply_channel_email_default_to_hint)) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
                Switch(checked = emailEnabled, onCheckedChange = { emailEnabled = it })
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = emailFrom, onValueChange = { onEmailFromChanged(it) }, singleLine = true, label = { Text(stringResource(R.string.reply_channel_email_from)) }, placeholder = { Text("xxx@qq.com") }, supportingText = { Text(stringResource(R.string.reply_channel_email_from_hint)) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = emailSmtpHost, onValueChange = { emailSmtpHost = it }, singleLine = true, label = { Text(stringResource(R.string.reply_channel_email_smtp_host)) }, placeholder = { Text("smtp.qq.com") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = emailSmtpPort, onValueChange = { emailSmtpPort = it.filter(Char::isDigit).take(5) }, singleLine = true, label = { Text(stringResource(R.string.reply_channel_email_smtp_port)) }, placeholder = { Text("465") }, modifier = Modifier.weight(1f))
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(value = stringResource(securityLabel(emailSmtpSecurity)), onValueChange = { }, readOnly = true, singleLine = true, label = { Text(stringResource(R.string.reply_channel_email_smtp_security)) }, trailingIcon = { IconButton(onClick = { emailSecurityMenuExpanded = true }) { Icon(Icons.Default.ExpandMore, contentDescription = null) } }, modifier = Modifier.fillMaxWidth())
                    DropdownMenu(expanded = emailSecurityMenuExpanded, onDismissRequest = { emailSecurityMenuExpanded = false }) {
                        ReplyChannelConfig.EMAIL_SECURITY_OPTIONS.forEach { s ->
                            DropdownMenuItem(text = { Text(stringResource(securityLabel(s))) }, onClick = { emailSmtpSecurity = s; emailSecurityMenuExpanded = false })
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = emailPassword, onValueChange = { emailPassword = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text(stringResource(R.string.reply_channel_email_password)) }, supportingText = { Text(stringResource(R.string.reply_channel_email_password_hint)) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = emailDefaultTo, onValueChange = { emailDefaultTo = it }, singleLine = true, label = { Text(stringResource(R.string.reply_channel_email_default_to)) }, supportingText = { Text(stringResource(R.string.reply_channel_email_default_to_hint)) }, modifier = Modifier.fillMaxWidth())
        }
    }

    Spacer(Modifier.height(12.dp))

    // ── 4. Channel selection card ──
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(16.dp)) {
            Text(text = stringResource(R.string.reply_channel_select), style = MaterialTheme.typography.titleSmall)
            Text(text = stringResource(R.string.reply_channel_select_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            ChannelToggleRow(label = stringResource(R.string.reply_channel_telegram), checked = ReplyChannelConfig.CHANNEL_TELEGRAM in selectedChannels, onCheckedChange = { toggleChannel(ReplyChannelConfig.CHANNEL_TELEGRAM, it) })
            ChannelToggleRow(label = stringResource(R.string.reply_channel_bark), checked = ReplyChannelConfig.CHANNEL_BARK in selectedChannels, onCheckedChange = { toggleChannel(ReplyChannelConfig.CHANNEL_BARK, it) })
            ChannelToggleRow(label = stringResource(R.string.reply_channel_email), checked = ReplyChannelConfig.CHANNEL_EMAIL in selectedChannels, onCheckedChange = { toggleChannel(ReplyChannelConfig.CHANNEL_EMAIL, it) })
        }
    }

    Spacer(Modifier.height(12.dp))

    // ── Save button ──
    Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.reply_channel_saved))
    }
}

/** Channel toggle row: label + Switch. */
@Composable
private fun ChannelToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** SMTP security → display label resource ID. */
private fun securityLabel(security: String): Int = when (security) {
    ReplyChannelConfig.SECURITY_STARTTLS -> R.string.reply_channel_email_security_starttls
    ReplyChannelConfig.SECURITY_NONE -> R.string.reply_channel_email_security_none
    else -> R.string.reply_channel_email_security_ssl
}