package com.lxseek.chat.im.whatsapp

import com.lxseek.chat.api.HttpClient
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// ── JSON 导航辅助（与 WecomBotApi / FeishuLarkApi 一致的安全转型风格） ──
private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

/** WhatsApp Cloud API 错误。HTTP 失败或 Meta 返回非 200 时抛出，[errorCode] 携带 Graph API 错误码。 */
class WhatsappApiException(
    message: String,
    val errorCode: Int? = null,
    val httpStatus: Int? = null,
) : Exception(message)

/**
 * Meta WhatsApp Business Cloud API 客户端：纯 HTTP over [HttpClient] 的共享 OkHttp 实例，
 * 无 SDK、无额外依赖。
 *
 * 协议参考（Meta 官方文档）：
 *  - 发送消息: POST `https://graph.facebook.com/v18.0/{phone-number-id}/messages`
 *    鉴权: `Authorization: Bearer {access_token}`
 *    Body: `{ "messaging_product": "whatsapp", "to": "<phone>", "type": "text"|"template"|"image",
 *             "text" | "template" | "image": { ... } }`
 *    成功响应: `{ "messaging_product": "whatsapp", "contacts": [...], "messages": [ { "id": "wamid..." } ] }`
 *  - Webhook 验证 (订阅设置阶段，手机端不使用): `hub.mode=subscribe` & `hub.verify_token=<verify-token>`
 *    → 服务器回传 `hub.challenge`。
 *
 * **接收模型说明** — WhatsApp Cloud API 是 webhook-only：Meta 服务器把入站消息 POST 到
 * 一个公开可达的 HTTPS 端点，无法轮询拉取。手机 App 无法暴露公网 webhook，因此本渠道
 * 在手机端仅支持发送（[WhatsappChannel.listConversations] / [WhatsappChannel.fetchMessages]
 * 返回空）。入站消息需要由外部 webhook 转发器把 Meta 推送转交到 Lxchat 的本地接口
 * （后续任务实现），或在桌面/服务器部署时直接接收。
 *
 * 配置映射（复用 [com.lxseek.chat.im.ImGatewayConfig] 字段，与任务约束对齐）：
 *  - [phoneNumberId] ← config.botId   （WhatsApp Business 电话号码 ID，形如 123456789012）
 *  - [accessToken]   ← config.token   （Meta 系统用户访问令牌，永久或长有效期）
 *  - [verifyToken]   ← config.baseUrl （Webhook 订阅验证 token；仅 webhook 部署时使用）
 *
 * 与 dsh-im 的 WhatsApp 渠道（基于 @whiskeysockets/baileys 的 WhatsApp Web 多设备协议）
 * 不同：dsh-im 走的是非官方 Web 协议，可双向收发但需扫码绑定；本实现走官方 Cloud API，
 * 仅发送但无需扫码、合规稳定，适合手机端 Lxchat 主动通知场景。
 */
class WhatsappCloudApi(
    /** WhatsApp Business 电话号码 ID（Phone Number ID），由 Meta Business Manager 分配。 */
    val phoneNumberId: String,
    /** Meta 系统用户访问令牌（System User Access Token），需 `whatsapp_business_messaging` 权限。 */
    val accessToken: String,
    /** Webhook 订阅验证 token；仅用于 webhook 部署时的 `hub.verify_token` 校验，可为空。 */
    val verifyToken: String = "",
    /** Graph API 版本，默认 `v18.0`；可覆盖用于测试或升级。 */
    private val apiVersion: String = DEFAULT_API_VERSION,
    /** Graph API 主机，默认 `https://graph.facebook.com`；可覆盖用于测试或代理。 */
    private val graphHost: String = DEFAULT_GRAPH_HOST,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    init {
        require(phoneNumberId.isNotBlank()) { "phoneNumberId is required" }
        require(accessToken.isNotBlank()) { "accessToken is required" }
    }

    /** Graph API messages 端点：`{host}/{version}/{phone-number-id}/messages`。 */
    private val messagesUrl: String = "$graphHost/$apiVersion/${phoneNumberId.trim()}/messages"

    /** 鉴权头：`Authorization: Bearer <access_token>`。 */
    private val authHeaders: Map<String, String> = mapOf(
        "Authorization" to "Bearer ${accessToken.trim()}",
        "Content-Type" to "application/json",
    )

    // ── 发送消息 ──────────────────────────────────────────────────────────

    /**
     * 发送文本消息给 [to]（E.164 格式电话号码，如 `8613800138000`，不带 `+`）。
     * 返回 Meta 分配的消息 ID（`wamid.*`）。
     *
     * Body:
     * ```json
     * { "messaging_product": "whatsapp", "recipient_type": "individual",
     *   "to": "<to>", "type": "text", "text": { "body": "<text>" } }
     * ```
     *
     * WhatsApp 单条文本上限 4096 字符；超长由调用方（[WhatsappChannel]）分段，本方法不截断。
     */
    suspend fun sendText(to: String, text: String): String {
        val recipient = normalizePhone(to)
        val body = buildJsonObject {
            put("messaging_product", "whatsapp")
            put("recipient_type", "individual")
            put("to", recipient)
            put("type", "text")
            putJsonObject("text") { put("body", text) }
        }.toString()
        return postMessages(body, "sendText")
    }

    /**
     * 通过 URL 发送图片消息给 [to]。[imageUrl] 必须是 HTTPS 公网可达的图片 URL；
     * [caption] 可选，作为图片下方的文字说明（上限 1024 字符）。
     *
     * Body:
     * ```json
     * { "messaging_product": "whatsapp", "recipient_type": "individual",
     *   "to": "<to>", "type": "image",
     *   "image": { "link": "<imageUrl>", "caption": "<caption>" } }
     * ```
     *
     * WhatsApp 限制：图片格式 JPEG/PNG/WebP，单张 ≤ 5MB；URL 必须公网可达（Meta 服务器
     * 下载）。本方法不预检大小/格式，由调用方保证。
     */
    suspend fun sendImage(to: String, imageUrl: String, caption: String? = null): String {
        val recipient = normalizePhone(to)
        require(imageUrl.isNotBlank()) { "imageUrl is required" }
        require(imageUrl.startsWith("http://", true) || imageUrl.startsWith("https://", true)) {
            "imageUrl must be an absolute http(s) URL"
        }
        val body = buildJsonObject {
            put("messaging_product", "whatsapp")
            put("recipient_type", "individual")
            put("to", recipient)
            put("type", "image")
            putJsonObject("image") {
                put("link", imageUrl)
                if (!caption.isNullOrBlank()) put("caption", caption)
            }
        }.toString()
        return postMessages(body, "sendImage")
    }

    /**
     * 发送模板消息给 [to]。模板必须已在 Meta WhatsApp Manager 中预审批通过。
     *
     * Body:
     * ```json
     * { "messaging_product": "whatsapp", "to": "<to>", "type": "template",
     *   "template": { "name": "<name>", "language": { "code": "<languageCode>" },
     *                 "components": [ ... ] } }
     * ```
     *
     * [components] 是模板变量/按钮参数的 JSON 数组（结构由模板定义决定）；为空时省略
     * `components` 字段，发送无变量模板。本方法不校验 [components] 结构，由调用方按
     * Meta 文档构造。
     *
     * 模板消息是 24 小时窗口外唯一允许的主动消息类型——当用户超过 24 小时未与商家互动，
     * `sendText` 会被 Meta 拒绝（错误码 470），此时改用 `sendTemplate` 即可。
     */
    suspend fun sendTemplate(
        to: String,
        name: String,
        languageCode: String = DEFAULT_TEMPLATE_LANGUAGE,
        components: JsonArray? = null,
    ): String {
        val recipient = normalizePhone(to)
        require(name.isNotBlank()) { "template name is required" }
        val body = buildJsonObject {
            put("messaging_product", "whatsapp")
            put("to", recipient)
            put("type", "template")
            putJsonObject("template") {
                put("name", name)
                putJsonObject("language") { put("code", languageCode) }
                if (components != null && components.isNotEmpty()) {
                    put("components", components)
                }
            }
        }.toString()
        return postMessages(body, "sendTemplate")
    }

    // ── Webhook 验证（仅文档/外部转发器使用） ─────────────────────────────

    /**
     * 校验来自 Meta 的 Webhook 订阅验证请求参数。
     *
     * Meta 在配置 webhook 订阅时会 GET `{webhook_url}?hub.mode=subscribe&hub.verify_token=...&hub.challenge=...`，
     * 服务端需校验 `hub.verify_token` 与配置的 [verifyToken] 一致后回传 `hub.challenge`。
     *
     * @param mode      `hub.mode` 参数值，期望 `"subscribe"`。
     * @param token     `hub.verify_token` 参数值。
     * @param challenge `hub.challenge` 参数值，校验通过后应原样回传。
     * @return 校验通过时返回 [challenge]（应作为 200 响应体回传给 Meta）；失败时返回 null。
     */
    fun verifyWebhook(mode: String?, token: String?, challenge: String?): String? {
        if (mode != "subscribe") {
            DebugLog.w("WhatsappCloudApi", "webhook verify: hub.mode=$mode (expected subscribe)")
            return null
        }
        if (verifyToken.isBlank() || token != verifyToken) {
            DebugLog.w("WhatsappCloudApi", "webhook verify: token mismatch")
            return null
        }
        return challenge
    }

    // ── HTTP 调用 ──────────────────────────────────────────────────────────

    /**
     * POST 消息到 Graph API `/messages` 端点，返回 Meta 分配的 `wamid`。
     *
     * 成功响应形如：
     * ```json
     * { "messaging_product": "whatsapp",
     *   "contacts": [ { "wa_id": "8613800138000", "input": "8613800138000" } ],
     *   "messages": [ { "id": "wamid.HBgL..." } ] }
     * ```
     * 失败响应形如：
     * ```json
     * { "error": { "message": "...", "type": "OAuthException", "code": 190, "fbtrace_id": "..." } }
     * ```
     */
    private suspend fun postMessages(body: String, action: String): String = withContext(Dispatchers.IO) {
        val response = try {
            HttpClient.postTextResponse(messagesUrl, body, authHeaders)
        } catch (e: Exception) {
            DebugLog.e("WhatsappCloudApi", "$action transport failed", e)
            throw WhatsappApiException(
                "$action transport failed: ${e.message ?: e.javaClass.simpleName}",
                httpStatus = null,
            )
        }
        if (!response.isSuccessful) {
            val (msg, code) = parseGraphError(response.body)
            DebugLog.e("WhatsappCloudApi", "$action failed: HTTP ${response.code} code=$code msg=$msg")
            throw WhatsappApiException(
                msg ?: "$action failed (HTTP ${response.code})",
                errorCode = code,
                httpStatus = response.code,
            )
        }
        val root = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
            ?: throw WhatsappApiException(
                "$action: response is not valid JSON: ${response.body.take(200)}",
                httpStatus = response.code,
            )
        // 成功响应的 messages[0].id 即消息 ID
        val messageId = root["messages"]?.arr()?.firstOrNull()?.obj()?.get("id")?.str()
            ?: throw WhatsappApiException(
                "$action: response missing messages[0].id: ${response.body.take(200)}",
                httpStatus = response.code,
            )
        messageId
    }

    /** 从 Graph API 错误响应中提取 `error.message` 和 `error.code`。 */
    private fun parseGraphError(body: String): Pair<String?, Int?> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null to null
        val error = root["error"]?.obj() ?: return null to null
        val msg = error["message"]?.str()
        val code = error["code"]?.str()?.toIntOrNull()
        return msg to code
    }

    /** 规范化电话号码：去 `+`、空格、`-`，仅保留数字。WhatsApp 期望 E.164 不带 `+`。 */
    private fun normalizePhone(phone: String): String {
        val trimmed = phone.trim()
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length < 6 || digits.length > 15) {
            throw IllegalArgumentException("invalid phone number (E.164 without '+'): $phone")
        }
        return digits
    }

    companion object {
        /** Graph API 默认版本。 */
        const val DEFAULT_API_VERSION = "v18.0"

        /** Graph API 默认主机。 */
        const val DEFAULT_GRAPH_HOST = "https://graph.facebook.com"

        /** 模板默认语言代码（美式英语）；中文模板用 `zh_CN`。 */
        const val DEFAULT_TEMPLATE_LANGUAGE = "en_US"

        /** WhatsApp 单条文本消息字符上限。 */
        const val TEXT_MAX_CHARS = 4096

        /** WhatsApp 图片单张字节上限（5 MB）。 */
        const val IMAGE_MAX_BYTES = 5L * 1024L * 1024L

        /** WhatsApp 图片总计字节上限（20 MB）。 */
        const val IMAGES_TOTAL_MAX_BYTES = 20L * 1024L * 1024L

        /** 支持的图片 MIME 类型（与 dsh-im whatsapp-runtime.mjs IMAGE_MEDIA_TYPES 一致）。 */
        val IMAGE_MIME_TYPES: Set<String> = setOf("image/jpeg", "image/png", "image/webp", "image/gif")

        /**
         * 简易 token 形状校验：Meta 系统用户访问令牌通常是 100+ 字符的字母数字串。
         * 仅用于在 UI 层提示配置是否完整，不阻塞构造。
         */
        fun looksLikeAccessToken(value: String): Boolean {
            val v = value.trim()
            return v.length >= 50 && v.all { it.isLetterOrDigit() || it == '_' || it == '-' }
        }

        /** Phone Number ID 形状校验：纯数字，通常 12-16 位。 */
        fun looksLikePhoneNumberId(value: String): Boolean {
            val v = value.trim()
            return v.length in 12..20 && v.all { it.isDigit() }
        }
    }
}