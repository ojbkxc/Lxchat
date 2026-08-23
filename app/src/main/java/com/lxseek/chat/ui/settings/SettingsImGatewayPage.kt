package com.lxseek.chat.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.im.ImGatewayConfig
import com.lxseek.chat.im.ImGatewayStore
import com.lxseek.chat.im.ImMultiGatewayConfig
import com.lxseek.chat.im.ImPlatform
import com.lxseek.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

// ── Platform display metadata ──────────────────────────────────────────────

/** Emoji glyph used as a lightweight platform icon (per project icon-style preference). */
private fun ImPlatform.emoji(): String = when (this) {
    ImPlatform.WECHAT -> "💬"
    ImPlatform.TELEGRAM -> "✈️"
    ImPlatform.LARK -> "🐦"
    ImPlatform.DINGTALK -> "📌"
    ImPlatform.WECOM -> "🏢"
    ImPlatform.QQ -> "🐧"
    ImPlatform.DISCORD -> "🎮"
    ImPlatform.SLACK -> "💼"
    ImPlatform.SMS -> "📱"
}

/** Localized display name resource for a platform. */
private fun ImPlatform.nameRes(): Int = when (this) {
    ImPlatform.WECHAT -> R.string.im_platform_wechat
    ImPlatform.TELEGRAM -> R.string.im_platform_telegram
    ImPlatform.LARK -> R.string.im_platform_lark
    ImPlatform.DINGTALK -> R.string.im_platform_dingtalk
    ImPlatform.WECOM -> R.string.im_platform_wecom
    ImPlatform.QQ -> R.string.im_platform_qq
    ImPlatform.DISCORD -> R.string.im_platform_discord
    ImPlatform.SLACK -> R.string.im_platform_slack
    ImPlatform.SMS -> R.string.im_platform_sms
}

/** Short bind-method hint shown under the platform name. */
private fun ImPlatform.hintRes(): Int = when (this) {
    ImPlatform.WECHAT -> R.string.im_hint_wechat
    ImPlatform.TELEGRAM -> R.string.im_hint_telegram
    ImPlatform.LARK -> R.string.im_hint_lark
    ImPlatform.DINGTALK -> R.string.im_hint_dingtalk
    ImPlatform.WECOM -> R.string.im_hint_wecom
    ImPlatform.QQ -> R.string.im_hint_qq
    ImPlatform.DISCORD -> R.string.im_hint_discord
    ImPlatform.SLACK -> R.string.im_hint_slack
    ImPlatform.SMS -> R.string.im_hint_sms
}

/** Longer description shown in the card body. */
private fun ImPlatform.descRes(): Int = when (this) {
    ImPlatform.WECHAT -> R.string.im_desc_wechat
    ImPlatform.TELEGRAM -> R.string.im_desc_telegram
    ImPlatform.LARK -> R.string.im_desc_lark
    ImPlatform.DINGTALK -> R.string.im_desc_dingtalk
    ImPlatform.WECOM -> R.string.im_desc_wecom
    ImPlatform.QQ -> R.string.im_desc_qq
    ImPlatform.DISCORD -> R.string.im_desc_discord
    ImPlatform.SLACK -> R.string.im_desc_slack
    ImPlatform.SMS -> R.string.im_desc_sms
}

/** Bind method drives which form section the platform card shows. */
private enum class BindMethod { QR, TOKEN, SMS }

private fun ImPlatform.bindMethod(): BindMethod = when (this) {
    ImPlatform.WECHAT, ImPlatform.WECOM, ImPlatform.QQ -> BindMethod.QR
    ImPlatform.TELEGRAM, ImPlatform.DISCORD, ImPlatform.SLACK,
    ImPlatform.DINGTALK, ImPlatform.LARK -> BindMethod.TOKEN
    ImPlatform.SMS -> BindMethod.SMS
}

/**
 * Platforms whose native long-connection channel is not yet plugged into [ImChannelFactory]
 * All push channels are now implemented in ImChannelFactory, so this set is empty.
 */
private val PUSH_PENDING_PLATFORMS: Set<ImPlatform> = emptySet()

// ── Credential field schema per platform ───────────────────────────────────

private enum class FieldKind { TEXT, SECRET, URL, NUMBER }

private data class CredField(
    val key: String,
    val labelRes: Int,
    val kind: FieldKind,
    val placeholder: String? = null,
    val required: Boolean = true,
)

/** Credential fields shown in the bind form for [platform]. */
private fun ImPlatform.credentialFields(): List<CredField> = when (this) {
    ImPlatform.WECHAT -> listOf(
        CredField("base_url", R.string.im_channel_field_base_url, FieldKind.URL, "http(s)://host:port"),
        CredField("token", R.string.im_channel_field_token, FieldKind.SECRET, required = false),
    )
    ImPlatform.WECOM -> listOf(
        CredField("corp_id", R.string.im_channel_field_corp_id, FieldKind.TEXT),
        CredField("corp_secret", R.string.im_channel_field_corp_secret, FieldKind.SECRET),
        CredField("agent_id", R.string.im_channel_field_agent_id, FieldKind.TEXT, required = false),
    )
    ImPlatform.QQ -> listOf(
        CredField("app_id", R.string.im_channel_field_app_id, FieldKind.TEXT),
        CredField("app_secret", R.string.im_channel_field_app_secret, FieldKind.SECRET),
    )
    ImPlatform.TELEGRAM -> listOf(
        CredField("bot_token", R.string.im_channel_field_bot_token, FieldKind.SECRET, placeholder = "123456:ABC-DEF..."),
    )
    ImPlatform.DISCORD -> listOf(
        CredField("bot_token", R.string.im_channel_field_bot_token, FieldKind.SECRET),
    )
    ImPlatform.SLACK -> listOf(
        CredField("bot_token", R.string.im_channel_field_bot_token, FieldKind.SECRET, placeholder = "xoxb-..."),
        CredField("app_token", R.string.im_channel_field_app_token, FieldKind.SECRET, placeholder = "xapp-..."),
    )
    ImPlatform.DINGTALK -> listOf(
        CredField("client_id", R.string.im_channel_field_client_id, FieldKind.TEXT),
        CredField("client_secret", R.string.im_channel_field_client_secret, FieldKind.SECRET),
    )
    ImPlatform.LARK -> listOf(
        CredField("app_id", R.string.im_channel_field_app_id, FieldKind.TEXT),
        CredField("app_secret", R.string.im_channel_field_app_secret, FieldKind.SECRET),
    )
    ImPlatform.SMS -> listOf(
        CredField("base_url", R.string.im_channel_field_base_url, FieldKind.URL, "http(s)://host:port"),
        CredField("token", R.string.im_channel_field_token, FieldKind.SECRET, required = false),
        CredField("poll_interval", R.string.im_channel_field_poll_interval, FieldKind.NUMBER, placeholder = "5000"),
    )
}

// ── Credential encode / decode (stored in ImGatewayConfig.token as JSON) ────

private val credJson = Json { ignoreUnknownKeys = true }

/** Encode a credential map as a JSON object string for storage in [ImGatewayConfig.token]. */
private fun encodeCredentials(map: Map<String, String>): String {
    val nonBlank = map.filterValues { it.isNotBlank() }
    if (nonBlank.isEmpty()) return ""
    return buildJsonObject {
        nonBlank.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    }.toString()
}

/**
 * Decode a credential map from [raw]. Returns empty when [raw] is blank or not a JSON object
 * (e.g. legacy wechat/sms configs that stored a bare auth token directly in `token`).
 */
private fun decodeCredentials(raw: String): Map<String, String> {
    val trimmed = raw.trimStart()
    if (trimmed.isEmpty() || !trimmed.startsWith("{")) return emptyMap()
    return runCatching {
        credJson.parseToJsonElement(raw).jsonObject.entries
            .associate { (k, v) -> k to v.jsonPrimitive.content }
    }.getOrDefault(emptyMap())
}

/** Mask a secret for display: keep first 4 and last 4 chars, hide the middle. */
private fun maskSecret(value: String): String {
    if (value.length <= 8) return "••••"
    return value.take(4) + "••••" + value.takeLast(4)
}

// ── Build / summarize an ImGatewayConfig from form values ───────────────────

/**
 * Build an [ImGatewayConfig] from the [values] entered for [platform], or null when required
 * fields are missing. A fresh [channelId] is generated so each bind creates a distinct bot.
 */
private fun buildBotConfig(platform: ImPlatform, values: Map<String, String>): ImGatewayConfig? {
    val fields = platform.credentialFields()
    // Validate required fields.
    if (fields.any { it.required && values[it.key].isNullOrBlank() }) return null

    val channelId = "${platform.id}:${UUID.randomUUID()}"

    return when (platform) {
        ImPlatform.WECHAT -> ImGatewayConfig(
            enabled = true,
            platform = platform.id,
            baseUrl = values["base_url"].orEmpty().trim(),
            token = values["token"].orEmpty(), // wechat auth token stays as a bare string (legacy compat)
            channelId = channelId,
            pollIntervalMs = 5_000L,
        )
        ImPlatform.SMS -> ImGatewayConfig(
            enabled = true,
            platform = platform.id,
            baseUrl = values["base_url"].orEmpty().trim(),
            token = values["token"].orEmpty(),
            channelId = channelId,
            pollIntervalMs = values["poll_interval"]?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: 5_000L,
        )
        // Each platform maps its credential fields to the flat config.token / config.baseUrl /
        // config.botId columns that its Channel implementation expects (NOT JSON-encoded).
        ImPlatform.TELEGRAM -> ImGatewayConfig(
            enabled = true, platform = platform.id, channelId = channelId, pollIntervalMs = 5_000L,
            baseUrl = "",
            token = values["bot_token"].orEmpty(),
        )
        ImPlatform.DISCORD -> ImGatewayConfig(
            enabled = true, platform = platform.id, channelId = channelId, pollIntervalMs = 5_000L,
            baseUrl = "",
            token = values["bot_token"].orEmpty(),
        )
        ImPlatform.SLACK -> ImGatewayConfig(
            enabled = true, platform = platform.id, channelId = channelId, pollIntervalMs = 5_000L,
            baseUrl = values["app_token"].orEmpty(),   // app token (xapp-)
            token = values["bot_token"].orEmpty(),      // bot token (xoxb-)
        )
        ImPlatform.DINGTALK -> ImGatewayConfig(
            enabled = true, platform = platform.id, channelId = channelId, pollIntervalMs = 5_000L,
            baseUrl = values["client_secret"].orEmpty(),
            token = values["client_id"].orEmpty(),
        )
        ImPlatform.LARK -> ImGatewayConfig(
            enabled = true, platform = platform.id, channelId = channelId, pollIntervalMs = 5_000L,
            baseUrl = values["app_secret"].orEmpty(),
            token = values["app_id"].orEmpty(),
        )
        ImPlatform.WECOM -> ImGatewayConfig(
            enabled = true, platform = platform.id, channelId = channelId, pollIntervalMs = 5_000L,
            baseUrl = values["corp_secret"].orEmpty(),
            token = values["corp_id"].orEmpty(),
            botId = values["agent_id"].orEmpty(),
        )
        ImPlatform.QQ -> ImGatewayConfig(
            enabled = true, platform = platform.id, channelId = channelId, pollIntervalMs = 5_000L,
            baseUrl = values["app_secret"].orEmpty(),
            token = values["app_id"].orEmpty(),
        )
    }
}

/** A short, human-readable summary (title + subtitle) of a bound bot for list display. */
private fun botSummary(bot: ImGatewayConfig, platform: ImPlatform): Pair<String, String> {
    val title = bot.botId.ifBlank { bot.effectiveChannelId }
    val subtitle = when (platform) {
        ImPlatform.WECHAT, ImPlatform.SMS -> {
            listOfNotNull(
                bot.baseUrl.takeIf { it.isNotBlank() },
                bot.token.takeIf { it.isNotBlank() }?.let { maskSecret(it) },
            ).joinToString(" · ")
        }
        ImPlatform.WECOM -> listOfNotNull(
            bot.token.takeIf { it.isNotBlank() },       // corp_id
            bot.botId.takeIf { it.isNotBlank() },        // agent_id
        ).joinToString(" · ")
        ImPlatform.QQ -> bot.token.takeIf { it.isNotBlank() }.orEmpty()  // app_id
        ImPlatform.TELEGRAM, ImPlatform.DISCORD ->
            bot.token.takeIf { it.isNotBlank() }?.let { maskSecret(it) }.orEmpty()
        ImPlatform.SLACK ->
            bot.token.takeIf { it.isNotBlank() }?.let { maskSecret(it) }.orEmpty()
        ImPlatform.DINGTALK -> bot.token.takeIf { it.isNotBlank() }.orEmpty()  // client_id
        ImPlatform.LARK -> bot.token.takeIf { it.isNotBlank() }.orEmpty()      // app_id
    }
    return title to subtitle
}

// ── Main page ──────────────────────────────────────────────────────────────

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
                onAddBot = { config ->
                    scope.launch {
                        store.upsertBot(config)
                        Toast.makeText(context, context.getString(R.string.im_channel_bound_success), Toast.LENGTH_SHORT).show()
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


// ── Platform card ──────────────────────────────────────────────────────────

@Composable
private fun PlatformChannelCard(
    platform: ImPlatform,
    bots: List<ImGatewayConfig>,
    isLegacyShowing: Boolean,
    onAddBot: (ImGatewayConfig) -> Unit,
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
private fun BotSummaryRow(
    bot: ImGatewayConfig,
    platform: ImPlatform,
    isLegacy: Boolean,
    onRemove: () -> Unit,
    onMigrate: (() -> Unit)?,
) {
    val (title, subtitle) = botSummary(bot, platform)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.ifBlank { stringResource(R.string.im_channel_bot_default_name) },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isLegacy) {
                Text(
                    text = stringResource(R.string.im_channel_legacy_tag),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Bold,
                )
            }
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
}

// ── Bind form ──────────────────────────────────────────────────────────────

@Composable
private fun BindFormSection(
    platform: ImPlatform,
    onConfirm: (ImGatewayConfig) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val method = platform.bindMethod()
    val fields = platform.credentialFields()

    // Per-field mutable state keyed by field key.
    val values = remember { mutableStateMapOf<String, String>().apply { fields.forEach { put(it.key, "") } } }
    val showSecret = remember { mutableStateMapOf<String, Boolean>().apply { fields.forEach { put(it.key, false) } } }
    var validationError by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (method == BindMethod.QR) {
            // QR scan placeholder area.
            Surface(
                onClick = {
                    Toast.makeText(context, context.getString(R.string.im_channel_bind_qr_placeholder), Toast.LENGTH_LONG).show()
                },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = "📷", style = MaterialTheme.typography.displaySmall)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.im_channel_bind_qr),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.im_channel_bind_qr_placeholder),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.im_channel_manual_config),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        // Credential fields.
        fields.forEach { field ->
            val value = values[field.key].orEmpty()
            val isSecret = field.kind == FieldKind.SECRET
            val placeholderText = field.placeholder
            OutlinedTextField(
                value = value,
                onValueChange = {
                    values[field.key] = it
                    validationError = false
                },
                label = { Text(stringResource(field.labelRes)) },
                placeholder = if (placeholderText != null) { { Text(placeholderText) } } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = when (field.kind) {
                        FieldKind.NUMBER -> KeyboardType.Number
                        FieldKind.URL -> KeyboardType.Uri
                        else -> KeyboardType.Text
                    },
                ),
                visualTransformation = if (isSecret && showSecret[field.key] != true) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                trailingIcon = if (isSecret) {
                    {
                        IconButton(onClick = { showSecret[field.key] = !(showSecret[field.key] ?: false) }) {
                            Icon(
                                imageVector = if (showSecret[field.key] == true) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                            )
                        }
                    }
                } else null,
                isError = validationError && field.required && value.isBlank(),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }

        if (validationError) {
            Text(
                text = stringResource(R.string.im_channel_validation_required),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.im_channel_cancel)) }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    val config = buildBotConfig(platform, values.toMap())
                    if (config == null) {
                        validationError = true
                    } else {
                        onConfirm(config)
                    }
                },
            ) { Text(stringResource(R.string.im_channel_bind)) }
        }
    }
}
