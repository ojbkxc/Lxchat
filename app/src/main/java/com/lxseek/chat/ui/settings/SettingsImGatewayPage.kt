package com.lxseek.chat.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lxseek.chat.LxChatApplication
import com.lxseek.chat.R
import com.lxseek.chat.im.ImGatewayConfig
import com.lxseek.chat.im.ImGatewayStore
import com.lxseek.chat.im.ImMultiGatewayConfig
import com.lxseek.chat.im.ImPlatform
import com.lxseek.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

/**
 * IM multi-channel management page. Lists every [ImPlatform] as a card showing bind status,
 * bound bots (with multi-bot support), and a bind entry point (QR placeholder or token form).
 *
 * Reads/writes the multi-channel config via [ImGatewayStore] (constructed from the local
 * context, which reuses the same encrypted DataStore as the rest of the app). The legacy
 * single-config [ChatViewModel.settings.imGatewayConfig] is still read for backward
 * compatibility: when no multi-config exists yet, a configured legacy bot is shown under
 * its platform with an "旧版" tag and a one-tap migrate action.
 */
@Composable
fun SettingsImGatewayPage(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { ImGatewayStore(context) }
    val multiConfig by store.multiConfig.collectAsState(initial = ImMultiGatewayConfig())
    val legacyConfig by viewModel.settings.imGatewayConfig.collectAsState()
    val scope = rememberCoroutineScope()

    // T31: ImBridgeService 用于连接测试；从 AppContainer 取单例。
    val bridgeService = remember(context) {
        (context.applicationContext as LxChatApplication).container.imBridgeService
    }
    // T31: Agent Preset 候选列表（来自全局 System Prompts）。
    val systemPrompts by viewModel.settings.systemPrompts.collectAsState()
    // 已绑定机器人设置面板所需：可用模型列表（Map<providerName, List<modelName>>）。
    val availableModels by viewModel.settings.availableModels.collectAsState()

    // Legacy fallback bot: shown only when the multi-config is empty and the legacy single
    // config is enabled/configured, so existing users see their prior gateway and can migrate.
    val legacyBot = remember(legacyConfig, multiConfig) {
        val multiEmpty = multiConfig.all.isEmpty()
        if (multiEmpty && (legacyConfig.isConfigured || legacyConfig.enabled)) legacyConfig else null
    }
    val legacyPlatform = legacyBot?.let { ImPlatform.of(it.platform) }

    var pendingRemove by remember { mutableStateOf<Pair<ImPlatform, ImGatewayConfig>?>(null) }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.im_channels_title),
        onBack = onBack,
        scrollState = rememberScrollState(),
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.im_channels_subtitle),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        )
        Text(
            text = stringResource(R.string.im_channels_howto),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        )

        ImPlatform.entries.forEach { platform ->
            val bots = multiConfig.botsFor(platform.id)
            val isLegacyForThis = legacyBot != null && legacyPlatform == platform
            val effectiveBots = if (bots.isEmpty() && isLegacyForThis) listOf(legacyBot!!) else bots

            PlatformChannelCard(
                platform = platform,
                bots = effectiveBots,
                isLegacyShowing = isLegacyForThis && bots.isEmpty(),
                agentPresets = systemPrompts,
                bridgeService = bridgeService,
                availableModels = availableModels,
                onAddBot = { config ->
                    scope.launch {
                        store.upsertBot(config)
                        Toast.makeText(context, context.getString(R.string.im_channel_bound_success), Toast.LENGTH_SHORT).show()
                    }
                },
                onUpdateBot = { config ->
                    scope.launch {
                        store.upsertBot(config)
                        Toast.makeText(context, context.getString(R.string.im_channel_settings_saved), Toast.LENGTH_SHORT).show()
                    }
                },
                onRemoveBot = { config -> pendingRemove = platform to config },
                onMigrateLegacy = {
                    legacyBot?.let { bot ->
                        scope.launch {
                            store.upsertBot(bot)
                            // Clear the legacy single config so it no longer shadows the multi-config.
                            viewModel.settings.saveImGatewayConfig(ImGatewayConfig())
                            Toast.makeText(context, context.getString(R.string.im_channel_migrated), Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    pendingRemove?.let { (platform, bot) ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text(stringResource(R.string.im_channel_remove_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.im_channel_remove_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        store.removeBot(platform.id, bot.effectiveChannelId)
                        Toast.makeText(context, context.getString(R.string.im_channel_removed), Toast.LENGTH_SHORT).show()
                    }
                    pendingRemove = null
                }) { Text(stringResource(R.string.im_channel_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text(stringResource(R.string.im_channel_cancel)) }
            },
        )
    }
}
