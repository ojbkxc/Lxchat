package com.lxseek.chat.im.telegram

import com.lxseek.chat.api.HttpClient
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Minimal Kotlin client for the official Telegram Bot API (https://core.telegram.org/bots/api).
 *
 * Pure HTTP over [HttpClient]'s shared OkHttp instance — no SDK, no extra dependencies. The
 * token is the bot token issued by @BotFather; every call is POST JSON to
 * `https://api.telegram.org/bot<token>/<method>` and returns the `result` field on success.
 *
 * Mirrors `dsh-im/src/channels/telegram/telegram-api.mjs` (`TelegramApi` class) one-to-one in
 * behavior: token validation, getUpdates long-polling, sendMessage, editMessageText,
 * sendChatAction, getMe. Kept deliberately small — only the methods Lxchat needs today.
 */
class TelegramBotApi(
    /** Bot token from @BotFather, format `<digits>:<35+ url-safe chars>`. */
    val token: String,
    /** API base, overrideable for tests or a self-hosted Bot API server. */
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    init {
        require(isValidTelegramToken(token)) { "Invalid Telegram bot token" }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val base = baseUrl.trim().trimEnd('/')

    /** Resolve the bot's own id/username. Used to detect @mentions in group chats. */
    suspend fun getMe(): JsonObject = call("getMe", buildJsonObject {}) as JsonObject

    /**
     * Long-poll for updates. [offset] is the next update_id to fetch (last + 1); pass null on
     * the first call. [timeout] is the server-side long-poll seconds (Telegram caps at 50).
     * Returns the `result` array (possibly empty when the poll times out with no updates).
     */
    suspend fun getUpdates(offset: Long? = null, timeout: Int = DEFAULT_POLL_TIMEOUT): JsonArray =
        call("getUpdates", buildJsonObject {
            put("timeout", timeout)
            put("limit", 100)
            putJsonArray("allowed_updates") { add("message") }
            if (offset != null) put("offset", offset)
        }) as JsonArray

    /**
     * Send a text message. Returns the sent `Message` object (contains `message_id`).
     *
     * [parseMode] is optional: pass `"MarkdownV2"` (with [escapeMarkdownV2] applied to [text])
     * for formatted output, or null for plain text. Plain text is the safe default for
     * arbitrary AI replies — MarkdownV2 rejects unescaped special characters with a 400.
     */
    suspend fun sendMessage(
        chatId: Long,
        text: String,
        replyToMessageId: Long? = null,
        parseMode: String? = null,
    ): JsonObject = call("sendMessage", buildJsonObject {
        put("chat_id", chatId)
        put("text", text)
        putJsonObject("link_preview_options") { put("is_disabled", true) }
        if (parseMode != null) put("parse_mode", parseMode)
        if (replyToMessageId != null) {
            putJsonObject("reply_parameters") {
                put("message_id", replyToMessageId)
                put("allow_sending_without_reply", true)
            }
        }
    }) as JsonObject

    /**
     * Edit an existing message's text in place. Used to stream an AI reply into a single
     * Telegram message as it is generated (send once, then edit on each token batch).
     */
    suspend fun editMessageText(
        chatId: Long,
        messageId: Long,
        text: String,
        parseMode: String? = null,
    ): JsonObject = call("editMessageText", buildJsonObject {
        put("chat_id", chatId)
        put("message_id", messageId)
        put("text", text)
        putJsonObject("link_preview_options") { put("is_disabled", true) }
        if (parseMode != null) put("parse_mode", parseMode)
    }) as JsonObject

    /** Show a chat action indicator (default "typing") so the user sees the bot is working. */
    suspend fun sendChatAction(chatId: Long, action: String = "typing"): JsonObject =
        call("sendChatAction", buildJsonObject {
            put("chat_id", chatId)
            put("action", action)
        }) as JsonObject

    /**
     * POST to a Bot API method. Throws [TelegramApiException] on `{ok:false}` or transport
     * failure; returns the `result` JsonElement on success.
     */
    private suspend fun call(method: String, payload: JsonObject): JsonElement =
        withContext(Dispatchers.IO) {
            val url = "$base/bot${token.trim()}/$method"
            val response = try {
                HttpClient.postTextResponse(url, payload.toString(), emptyMap())
            } catch (e: Exception) {
                DebugLog.e("TelegramApi", "$method transport failed", e)
                throw TelegramApiException(
                    "Telegram $method transport failed: ${e.message ?: e.javaClass.simpleName}",
                    null,
                )
            }
            if (!response.isSuccessful) {
                val desc = parseDescription(response.body)
                throw TelegramApiException(
                    desc ?: "Telegram $method failed (HTTP ${response.code})",
                    response.code,
                )
            }
            val root = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
                ?: throw TelegramApiException(
                    "Telegram $method returned invalid JSON",
                    response.code,
                )
            val ok = root["ok"]?.jsonPrimitive?.contentOrNull == "true"
            if (!ok) {
                val desc = root["description"]?.jsonPrimitive?.contentOrNull
                val code = root["error_code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                throw TelegramApiException(desc ?: "Telegram $method failed", code)
            }
            root["result"] ?: throw TelegramApiException(
                "Telegram $method returned no result",
                null,
            )
        }

    private fun parseDescription(body: String): String? =
        runCatching {
            json.parseToJsonElement(body).jsonObject["description"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.telegram.org/"
        const val DEFAULT_POLL_TIMEOUT = 30

        /** Token shape: `<5-20 digits>:<20+ url-safe chars>`, per @BotFather. */
        fun isValidTelegramToken(value: String): Boolean =
            TOKEN_REGEX.matches(value.trim())

        private val TOKEN_REGEX = Regex("""^\d{5,20}:[A-Za-z0-9_-]{20,}$""")

        /**
         * Escape characters with special meaning in MarkdownV2 so arbitrary text can be sent
         * with `parse_mode=MarkdownV2` without a 400. Per Telegram docs the special set is:
         * `` _ * [ ] ( ) ~ ` > # + - = | { } . ! ``
         */
        fun escapeMarkdownV2(text: String): String =
            text.replace(MD_V2_SPECIAL) { "\\" + it.value }

        private val MD_V2_SPECIAL = Regex("""[_*\[\]()~`>#+\-=|{}.!\\]""")
    }
}

/** Raised when the Bot API returns `{ok:false}` or the call fails to reach the server. */
class TelegramApiException(message: String, val errorCode: Int?) : Exception(message)