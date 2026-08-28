package com.lxseek.chat.ui.settings

import com.lxseek.chat.R
import com.lxseek.chat.im.ImGatewayConfig
import com.lxseek.chat.im.ImPlatform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

// ── Platform display metadata ──────────────────────────────────────────────

/** Emoji glyph used as a lightweight platform icon (per project icon-style preference). */
internal fun ImPlatform.emoji(): String = when (this) {
    ImPlatform.WECHAT -> "💬"
    ImPlatform.TELEGRAM -> "✈️"
    ImPlatform.LARK -> "🐦"
    ImPlatform.DINGTALK -> "📌"
    ImPlatform.WECOM -> "🏢"
    ImPlatform.QQ -> "🐧"
    ImPlatform.DISCORD -> "🎮"
    ImPlatform.SLACK -> "💼"
    ImPlatform.WHATSAPP -> "🟢"
    ImPlatform.SMS -> "📱"
}

/** Localized display name resource for a platform. */
internal fun ImPlatform.nameRes(): Int = when (this) {
    ImPlatform.WECHAT -> R.string.im_platform_wechat
    ImPlatform.TELEGRAM -> R.string.im_platform_telegram
    ImPlatform.LARK -> R.string.im_platform_lark
    ImPlatform.DINGTALK -> R.string.im_platform_dingtalk
    ImPlatform.WECOM -> R.string.im_platform_wecom
    ImPlatform.QQ -> R.string.im_platform_qq
    ImPlatform.DISCORD -> R.string.im_platform_discord
    ImPlatform.SLACK -> R.string.im_platform_slack
    ImPlatform.WHATSAPP -> R.string.im_platform_whatsapp
    ImPlatform.SMS -> R.string.im_platform_sms
}

/** Short bind-method hint shown under the platform name. */
internal fun ImPlatform.hintRes(): Int = when (this) {
    ImPlatform.WECHAT -> R.string.im_hint_wechat
    ImPlatform.TELEGRAM -> R.string.im_hint_telegram
    ImPlatform.LARK -> R.string.im_hint_lark
    ImPlatform.DINGTALK -> R.string.im_hint_dingtalk
    ImPlatform.WECOM -> R.string.im_hint_wecom
    ImPlatform.QQ -> R.string.im_hint_qq
    ImPlatform.DISCORD -> R.string.im_hint_discord
    ImPlatform.SLACK -> R.string.im_hint_slack
    ImPlatform.WHATSAPP -> R.string.im_hint_whatsapp
    ImPlatform.SMS -> R.string.im_hint_sms
}

/** Longer description shown in the card body. */
internal fun ImPlatform.descRes(): Int = when (this) {
    ImPlatform.WECHAT -> R.string.im_desc_wechat
    ImPlatform.TELEGRAM -> R.string.im_desc_telegram
    ImPlatform.LARK -> R.string.im_desc_lark
    ImPlatform.DINGTALK -> R.string.im_desc_dingtalk
    ImPlatform.WECOM -> R.string.im_desc_wecom
    ImPlatform.QQ -> R.string.im_desc_qq
    ImPlatform.DISCORD -> R.string.im_desc_discord
    ImPlatform.SLACK -> R.string.im_desc_slack
    ImPlatform.WHATSAPP -> R.string.im_desc_whatsapp
    ImPlatform.SMS -> R.string.im_desc_sms
}

/** Bind method drives which form section the platform card shows. */
internal enum class BindMethod { QR, TOKEN, SMS }

internal fun ImPlatform.bindMethod(): BindMethod = when (this) {
    ImPlatform.WECHAT, ImPlatform.WECOM, ImPlatform.QQ -> BindMethod.QR
    ImPlatform.TELEGRAM, ImPlatform.DISCORD, ImPlatform.SLACK,
    ImPlatform.DINGTALK, ImPlatform.LARK, ImPlatform.WHATSAPP -> BindMethod.TOKEN
    ImPlatform.SMS -> BindMethod.SMS
}

/**
 * Platforms whose native long-connection channel is not yet plugged into [ImChannelFactory]
 * All push channels are now implemented in ImChannelFactory, so this set is empty.
 */
internal val PUSH_PENDING_PLATFORMS: Set<ImPlatform> = emptySet()

// ── Credential field schema per platform ───────────────────────────────────

internal enum class FieldKind { TEXT, SECRET, URL, NUMBER }

internal data class CredField(
    val key: String,
    val labelRes: Int,
    val kind: FieldKind,
    val placeholder: String? = null,
    val required: Boolean = true,
)

/** Credential fields shown in the bind form for [platform]. */
internal fun ImPlatform.credentialFields(): List<CredField> = when (this) {
    // 微信走 iLink 扫码绑定（WeixinBindingFlow），无需手动配置 base_url/token。
    ImPlatform.WECHAT -> emptyList()
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
    ImPlatform.WHATSAPP -> listOf(
        CredField("phone_number_id", R.string.im_channel_field_phone_number_id, FieldKind.TEXT, placeholder = "123456789012345"),
        CredField("access_token", R.string.im_channel_field_access_token, FieldKind.SECRET),
        CredField("verify_token", R.string.im_channel_field_verify_token, FieldKind.TEXT, required = false),
    )
}

// ── Credential encode / decode (stored in ImGatewayConfig.token as JSON) ────

private val credJson = Json { ignoreUnknownKeys = true }

/** Encode a credential map as a JSON object string for storage in [ImGatewayConfig.token]. */
internal fun encodeCredentials(map: Map<String, String>): String {
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
internal fun decodeCredentials(raw: String): Map<String, String> {
    val trimmed = raw.trimStart()
    if (trimmed.isEmpty() || !trimmed.startsWith("{")) return emptyMap()
    return runCatching {
        credJson.parseToJsonElement(raw).jsonObject.entries
            .associate { (k, v) -> k to v.jsonPrimitive.content }
    }.getOrDefault(emptyMap())
}

/** Mask a secret for display: keep first 4 and last 4 chars, hide the middle. */
internal fun maskSecret(value: String): String {
    if (value.length <= 8) return "••••"
    return value.take(4) + "••••" + value.takeLast(4)
}

// ── Build / summarize an ImGatewayConfig from form values ───────────────────

/**
 * Build an [ImGatewayConfig] from the [values] entered for [platform], or null when required
 * fields are missing. A fresh [channelId] is generated so each bind creates a distinct bot.
 *
 * @param agentPreset 选中的 Agent Preset ID；空串表示跟随默认。
 */
internal fun buildBotConfig(
    platform: ImPlatform,
    values: Map<String, String>,
    agentPreset: String = "",
): ImGatewayConfig? {
    val fields = platform.credentialFields()
    // Validate required fields.
    if (fields.any { it.required && values[it.key].isNullOrBlank() }) return null

    val channelId = "${platform.id}:${UUID.randomUUID()}"
    val preset = agentPreset.trim()

    return when (platform) {
        ImPlatform.WECHAT -> ImGatewayConfig(
            enabled = true,
            platform = platform.id,
            baseUrl = values["base_url"].orEmpty().trim(),
            token = values["token"].orEmpty(), // wechat auth token stays as a bare string (legacy compat)
            channelId = channelId,
            pollIntervalMs = 5_000L,
            agentPreset = preset,
        )
        ImPlatform.SMS -> ImGatewayConfig(
            enabled = true,
            platform = platform.id,
            baseUrl = values["base_url"].orEmpty().trim(),
            token = values["token"].orEmpty(),
            channelId = channelId,
            pollIntervalMs = values["poll_interval"]?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: 5_000L,
            agentPreset = preset,
        )
        // Each platform maps its credential fields to the flat config.token / config.baseUrl /
        // config.botId columns that its Channel implementation expects (NOT JSON-encoded).
        ImPlatform.TELEGRAM -> ImGatewayConfig(
            enabled = true, platform = platform.id, channelId = channelId, pollIntervalMs = 5_000L,
            baseUrl = "",
            token = values["bot_token"].orEmpty(),
            agentPreset = preset,
        )
        ImPlatform.DISCORD -> ImGatewayConfig(
            enabled = true, platform = platform.id, channelId = channelId, pollIntervalMs = 5_000L,
            baseUrl = "",
            token = values["bot_token"].orEmpty(),
            agentPreset = preset,
        )
        ImPlatform.SLACK -> ImGatewayConfig(
            enabled = true, platform = platform.id, channelId = channelId, pollIntervalMs = 5_000L,
            baseUrl = values["app_token"].orEmpty(),   // app token (xapp-)
            token = values["bot_token"].orEmpty(),      // bot token (xoxb-)
            agentPreset = preset,
        )
        ImPlatform.DINGTALK -> ImGatewayConfig(
            enabled = true, platform = platform.id, channelId = channelId, pollIntervalMs = 5_000L,
            baseUrl = values["client_secret"].orEmpty(),
            token = values["client_id"].orEmpty(),
            agentPreset = preset,
        )
        ImPlatform.LARK -> ImGatewayConfig(
            enabled = true, platform = platform.id, channelId = channelId, pollIntervalMs = 5_000L,
            baseUrl = values["app_secret"].orEmpty(),
            token = values["app_id"].orEmpty(),
            agentPreset = preset,
        )
        ImPlatform.WECOM -> ImGatewayConfig(
            enabled = true, platform = platform.id, channelId = channelId, pollIntervalMs = 5_000L,
            baseUrl = values["corp_secret"].orEmpty(),
            token = values["corp_id"].orEmpty(),
            botId = values["agent_id"].orEmpty(),
            agentPreset = preset,
        )
        ImPlatform.QQ -> ImGatewayConfig(
            enabled = true, platform = platform.id, channelId = channelId, pollIntervalMs = 5_000L,
            baseUrl = values["app_secret"].orEmpty(),
            token = values["app_id"].orEmpty(),
            agentPreset = preset,
        )
        ImPlatform.WHATSAPP -> ImGatewayConfig(
            enabled = true, platform = platform.id, channelId = channelId, pollIntervalMs = 5_000L,
            baseUrl = values["verify_token"].orEmpty(),   // verify token (webhook)
            token = values["access_token"].orEmpty(),       // access token
            botId = values["phone_number_id"].orEmpty(),    // phone number id
            agentPreset = preset,
        )
    }
}

/** A short, human-readable summary (title + subtitle) of a bound bot for list display. */
internal fun botSummary(bot: ImGatewayConfig, platform: ImPlatform): Pair<String, String> {
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
        ImPlatform.WHATSAPP -> bot.botId.takeIf { it.isNotBlank() }.orEmpty()  // phone_number_id
    }
    return title to subtitle
}