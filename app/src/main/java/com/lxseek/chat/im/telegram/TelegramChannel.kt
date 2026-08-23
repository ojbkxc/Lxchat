package com.lxseek.chat.im.telegram

import com.lxseek.chat.im.ImConversation
import com.lxseek.chat.im.ImGatewayConfig
import com.lxseek.chat.im.ImMessage
import com.lxseek.chat.im.ImMessageDirection
import com.lxseek.chat.im.ImSendResult
import com.lxseek.chat.im.MessageChannel
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Telegram Bot API channel: turns a bot token (from @BotFather) into a [MessageChannel] that
 * Lxchat's [com.lxseek.chat.im.ImPollingReceiver] can poll.
 *
 * Polling model — Telegram has no per-chat fetch endpoint, so the Bot API's `getUpdates`
 * long-poll is the only inbound surface. [listConversations] runs one poll, fans every received
 * `message` out into per-chat buffers ([pendingByChat]) and refreshes the set of known chats
 * ([knownChats]); [fetchMessages] then drains one chat's buffer. The receiver's
 * `seenMessageIds` set handles cross-cycle de-duplication, so a message already handed to the
 * agent is never replayed even if a poll happens to re-deliver it.
 *
 * Group gate — in group/supergroup chats the bot only reacts when explicitly @-mentioned
 * (an `entities[type=mention]` whose text equals `@<botUsername>`); private chats always
 * respond. This mirrors the dsh-im telegram channel behavior and avoids the bot answering
 * every line in every group it sits in.
 *
 * Configuration is reused from [ImGatewayConfig]: `token` carries the bot token, `baseUrl`
 * may override the API host (blank = official host), `platform` must be `"telegram"`. Session
 * binding and de-duplication are left to [com.lxseek.chat.im.ImPollingReceiver].
 */
class TelegramChannel(
    private val config: ImGatewayConfig,
) : MessageChannel {

    override val channelId: String get() = CHANNEL_ID
    override val displayName: String
        get() {
            // Snapshot the @Volatile field once so the null-check and the interpolation agree.
            val username = botUsername
            return if (username != null) "Telegram · @$username" else "Telegram"
        }
    override val isConfigured: Boolean
        get() = config.enabled && TelegramBotApi.isValidTelegramToken(config.token)

    /** Lazily built; null when the token is malformed so [isConfigured] stays false. */
    private val client: TelegramBotApi? =
        if (TelegramBotApi.isValidTelegramToken(config.token)) {
            TelegramBotApi(
                token = config.token,
                baseUrl = config.baseUrl.takeIf { it.isNotBlank() } ?: TelegramBotApi.DEFAULT_BASE_URL,
            )
        } else null

    // ── Poll state ────────────────────────────────────────────────────────────
    // Touched only by the receiver's single polling coroutine (listConversations →
    // fetchMessages), so plain non-thread-safe collections are sufficient. sendMessage runs on
    // unrelated tool-call coroutines but never mutates this state.
    @Volatile private var updateOffset: Long? = null
    @Volatile private var botUsername: String? = null
    @Volatile private var botId: Long? = null
    private val knownChats = LinkedHashMap<Long, ImConversation>()
    private val pendingByChat = HashMap<Long, MutableList<ImMessage>>()

    override suspend fun sendMessage(conversationId: String, text: String): ImSendResult {
        val api = client ?: return ImSendResult.NotConfigured
        if (!isConfigured) return ImSendResult.NotConfigured
        val chatId = conversationId.toLongOrNull()
            ?: return ImSendResult.Failure("Telegram chat id must be numeric: $conversationId")
        return withContext(Dispatchers.IO) {
            try {
                val result = api.sendMessage(chatId, text)
                val messageId = result["message_id"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                ImSendResult.Success(messageId)
            } catch (e: TelegramApiException) {
                DebugLog.e("TelegramChannel", "sendMessage failed: ${e.message} (code=${e.errorCode})")
                ImSendResult.Failure(e.message ?: "telegram send failed")
            } catch (e: Exception) {
                DebugLog.e("TelegramChannel", "sendMessage failed", e)
                ImSendResult.Failure(e.message ?: "telegram send failed")
            }
        }
    }

    override suspend fun listConversations(): List<ImConversation> {
        if (!isConfigured) return emptyList()
        val api = client ?: return emptyList()
        try {
            pollUpdates(api)
        } catch (e: Exception) {
            DebugLog.e("TelegramChannel", "listConversations poll failed", e)
        }
        return knownChats.values.sortedByDescending { it.lastMessageAtMs }
    }

    override suspend fun fetchMessages(conversationId: String, afterId: String?): List<ImMessage> {
        if (!isConfigured) return emptyList()
        val chatId = conversationId.toLongOrNull() ?: return emptyList()
        // afterId is honored by ImPollingReceiver's seen set; we drain the buffer once per
        // poll cycle so the next getUpdates re-fills it with only brand-new updates.
        return pendingByChat.remove(chatId) ?: emptyList()
    }

    // ── Polling helpers ───────────────────────────────────────────────────────

    /**
     * Run one `getUpdates` long-poll, advance [updateOffset], refresh the bot identity on the
     * first call, and route every inbound text message into [pendingByChat] / [knownChats].
     */
    private suspend fun pollUpdates(api: TelegramBotApi) {
        ensureBotIdentity(api)
        val updates = api.getUpdates(offset = updateOffset, timeout = POLL_TIMEOUT_SECONDS)
        var maxUpdateId: Long? = null
        for (update in updates) {
            val obj = runCatching { update.jsonObject }.getOrNull() ?: continue
            val updateId = obj["update_id"]?.jsonPrimitive?.longOrNull ?: continue
            if (maxUpdateId == null || updateId > maxUpdateId) maxUpdateId = updateId
            val message = obj["message"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: continue
            handleMessage(updateId, message)
        }
        // Telegram expects offset = last_update_id + 1 to confirm we've processed them; without
        // this advance the same updates would be redelivered on every poll.
        if (maxUpdateId != null) updateOffset = maxUpdateId + 1
    }

    /** Fetch the bot's own id/username once, for the @-mention group gate. */
    private suspend fun ensureBotIdentity(api: TelegramBotApi) {
        if (botUsername != null) return
        try {
            val me = api.getMe()
            botId = me["id"]?.jsonPrimitive?.longOrNull
            botUsername = me["username"]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            DebugLog.e("TelegramChannel", "getMe failed", e)
        }
    }

    /** Convert one Telegram `message` into an [ImMessage] and file it under its chat. */
    private fun handleMessage(updateId: Long, message: JsonObject) {
        val chat = message["chat"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: return
        val chatId = chat["id"]?.jsonPrimitive?.longOrNull ?: return
        val chatType = chat["type"]?.jsonPrimitive?.contentOrNull ?: "private"
        val isGroup = chatType != "private"

        // Text-only for now; voice/photo/etc. are future work.
        val text = message["text"]?.jsonPrimitive?.contentOrNull ?: return
        val messageId = message["message_id"]?.jsonPrimitive?.contentOrNull ?: return
        val date = message["date"]?.jsonPrimitive?.longOrNull ?: 0L
        val from = message["from"]?.let { runCatching { it.jsonObject }.getOrNull() }
        val sender = from?.let {
            it["username"]?.jsonPrimitive?.contentOrNull
                ?: it["first_name"]?.jsonPrimitive?.contentOrNull
        } ?: ""

        // Group gate: only react when explicitly @-mentioned. Private chats always pass.
        if (isGroup && !isMentionedForUs(message, text)) return

        knownChats[chatId] = ImConversation(
            id = chatId.toString(),
            title = chatTitle(chat, chatId),
            platform = CHANNEL_ID,
            lastMessageAtMs = date * MILLIS_PER_SEC,
            isGroup = isGroup,
        )
        pendingByChat.getOrPut(chatId) { mutableListOf() }.add(
            ImMessage(
                id = updateId.toString(),
                conversationId = chatId.toString(),
                direction = ImMessageDirection.INCOMING,
                text = text,
                sender = sender,
                timestampMs = date * MILLIS_PER_SEC,
            ),
        )
    }

    /** True when [message] carries an `@<botUsername>` mention entity. */
    private fun isMentionedForUs(message: JsonObject, text: String): Boolean {
        val username = botUsername ?: return false
        val entities = message["entities"]?.let { runCatching { it.jsonArray }.getOrNull() }
            ?: return false
        for (entity in entities) {
            val e = runCatching { entity.jsonObject }.getOrNull() ?: continue
            if (e["type"]?.jsonPrimitive?.contentOrNull != "mention") continue
            val offset = e["offset"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: continue
            val length = e["length"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: continue
            val mention = runCatching { text.substring(offset, offset + length) }.getOrNull()
                ?: continue
            if (mention.equals("@$username", ignoreCase = true)) return true
        }
        return false
    }

    private fun chatTitle(chat: JsonObject, chatId: Long): String =
        chat["title"]?.jsonPrimitive?.contentOrNull
            ?: chat["first_name"]?.jsonPrimitive?.contentOrNull
            ?: chat["username"]?.jsonPrimitive?.contentOrNull?.let { "@$it" }
            ?: chatId.toString()

    companion object {
        /** Platform identifier stored in [ImGatewayConfig.platform] for this channel. */
        const val PLATFORM = "telegram"

        private const val CHANNEL_ID = "telegram"
        private const val POLL_TIMEOUT_SECONDS = 30
        private const val MILLIS_PER_SEC = 1000L
    }
}