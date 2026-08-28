package com.lxseek.chat.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.lxseek.chat.im.ConnectionTestResult
import com.lxseek.chat.im.ImBridgeService
import com.lxseek.chat.im.ImGatewayConfig
import com.lxseek.chat.im.ImPlatform
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.launch

// ── Platform card ──────────────────────────────────────────────────────────

@Composable
internal fun PlatformChannelCard(
    platform: ImPlatform,
    bots: List<ImGatewayConfig>,
    isLegacyShowing: Boolean,
    agentPresets: List<com.lxseek.chat.data.SystemPromptEntry>,
    bridgeService: ImBridgeService,
    availableModels: Map<String, List<String>>,
    onAddBot: (ImGatewayConfig) -> Unit,
    onUpdateBot: (ImGatewayConfig) -> Unit,
    onRemoveBot: (ImGatewayConfig) -> Unit,
    onMigrateLegacy: () -> Unit,
) {
    val bound = bots.isNotEmpty()
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            // Header row: emoji + name + hint + status badge.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = platform.emoji(),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(platform.nameRes()),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(platform.hintRes()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BindStatusBadge(bound = bound, count = bots.size)
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(platform.descRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (platform in PUSH_PENDING_PLATFORMS) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.im_channel_push_pending),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Medium,
                )
            }

            // Bound bots list.
            if (bots.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                bots.forEachIndexed { idx, bot ->
                    if (idx > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    BotSummaryRow(
                        bot = bot,
                        platform = platform,
                        isLegacy = isLegacyShowing && idx == 0,
                        bridgeService = bridgeService,
                        availableModels = availableModels,
                        onUpdateBot = onUpdateBot,
                        onRemove = { onRemoveBot(bot) },
                        onMigrate = if (isLegacyShowing && idx == 0) onMigrateLegacy else null,
                    )
                }
            }

            // Inline bind form.
            if (expanded) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(10.dp))
                BindFormSection(
                    platform = platform,
                    agentPresets = agentPresets,
                    onConfirm = { config ->
                        onAddBot(config)
                        expanded = false
                    },
                    onCancel = { expanded = false },
                )
            }

            // Toggle button.
            if (!expanded) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { expanded = true }) {
                        Text(
                            text = stringResource(
                                if (bound) R.string.im_channel_add_bot else R.string.im_channel_bind,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** Green/gray status badge reflecting bind state (high-contrast per design preference). */
@Composable
private fun BindStatusBadge(bound: Boolean, count: Int) {
    val color = if (bound) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    ) {
        Text(
            text = if (bound) stringResource(R.string.im_channel_bound, count) else stringResource(R.string.im_channel_unbound),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

// ── Bot summary row ────────────────────────────────────────────────────────

@Composable
internal fun BotSummaryRow(
    bot: ImGatewayConfig,
    platform: ImPlatform,
    isLegacy: Boolean,
    bridgeService: ImBridgeService,
    availableModels: Map<String, List<String>>,
    onUpdateBot: (ImGatewayConfig) -> Unit,
    onRemove: () -> Unit,
    onMigrate: (() -> Unit)?,
) {
    val (title, subtitle) = botSummary(bot, platform)
    val scope = rememberCoroutineScope()
    val strTesting = stringResource(R.string.im_channel_test_running)
    val strSuccess = stringResource(R.string.im_channel_test_success)
    val strFailed = stringResource(R.string.im_channel_test_failed)
    val strTestBtn = stringResource(R.string.im_channel_test_connection)
    val strPresetLabel = stringResource(R.string.im_channel_agent_preset)
    val strPresetFollow = stringResource(R.string.im_channel_agent_preset_follow_default)
    val strSettings = stringResource(R.string.im_channel_settings)
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<ConnectionTestResult?>(null) }
    // 已绑定机器人设置面板默认收起；点击 ⚙️ 设置按钮切换。
    var settingsExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.CheckCircle, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title.ifBlank { stringResource(R.string.im_channel_bot_default_name) },
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "$strPresetLabel: ${bot.agentPreset.ifBlank { strPresetFollow }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isLegacy) {
                    Text(
                        text = stringResource(R.string.im_channel_legacy_tag),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold,
                    )
                }
            }
            TextButton(
                enabled = !testing,
                onClick = {
                    if (testing) return@TextButton
                    testing = true; testResult = null
                    DebugLog.d("ImGatewayUI", "testConnection clicked: ${bot.effectiveChannelId}")
                    scope.launch {
                        val result = bridgeService.testConnection(bot)
                        testResult = result; testing = false
                        DebugLog.d("ImGatewayUI", "testConnection done: $result")
                    }
                },
            ) {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strTesting)
                } else Text(strTestBtn)
            }
            // ⚙️ 设置按钮：展开/收起已绑定机器人的详细设置面板。
            TextButton(onClick = {
                settingsExpanded = !settingsExpanded
                DebugLog.d("ImGatewayUI", "settings toggle: ${bot.effectiveChannelId} expanded=$settingsExpanded")
            }) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = strSettings,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(strSettings)
            }
            if (onMigrate != null) {
                TextButton(onClick = onMigrate) { Text(stringResource(R.string.im_channel_migrate)) }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.im_channel_remove),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        testResult?.let { result ->
            Spacer(modifier = Modifier.height(2.dp))
            when (result) {
                is ConnectionTestResult.Success -> Text(
                    text = "$strSuccess\n${result.message}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(start = 30.dp),
                )
                is ConnectionTestResult.Failure -> Text(
                    text = "$strFailed\n${result.reason}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(start = 30.dp),
                )
            }
        }
        // 已绑定机器人设置面板：自动回复模型 / 主动消息 / 人性化消息。
        AnimatedVisibility(visible = settingsExpanded) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            BotSettingsPanel(
                bot = bot,
                availableModels = availableModels,
                onSave = { updated ->
                    onUpdateBot(updated)
                    // 保存后自动收起。
                    settingsExpanded = false
                },
            )
        }
    }
}