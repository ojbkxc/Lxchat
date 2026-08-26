package com.lxseek.chat.im.weixin

import com.lxseek.chat.api.HttpClient
import com.lxseek.chat.im.ImConversation
import com.lxseek.chat.im.ImMessage
import com.lxseek.chat.im.ImMessageDirection
import com.lxseek.chat.im.ImSendResult
import com.lxseek.chat.im.MessageChannel
import com.lxseek.chat.util.DebugLog

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 微信 iLink 专属扩展能力，由 [com.lxseek.chat.im.ImPollingReceiver] 通过类型检查选择性调用
 * （移动端可用，全部走官方 iLink 纯 HTTP 协议）：
 *  - [sendTyping]：发送"正在输入"状态，参考 weixin-ClawBot-API / openclaw-weixin 的 getconfig+sendtyping。
 *  - [contextTokensSnapshot] / [seedContextTokens]：跨重启持久化 per-会话 context_token，避免重启后
 *    由于没有 context_token 而导致的回复被服务端静默丢弃。
 */
interface WeixinCompanionChannel {
    /** 发送"正在输入"状态：status=1 生成中，status=2 完成。尽力而为，失败不抛。 */
    suspend fun sendTyping(conversationId: String, status: Int)

    /** 当前持有的 context_token 快照（conversationId/userId → token），用于持久化。 */
    fun contextTokensSnapshot(): Map<String, String>

    /** 从持久化状态恢复 context_token（App 重启后仍能带上下文回复）。 */
    fun seedContextTokens(tokens: Map<String, String>)

    /** Current sync_buf cursor snapshot, for persistence across restarts. */
    fun syncBufSnapshot(): String

    /** Restore sync_buf cursor from persistence (App restart). */
    fun seedSyncBuf(buf: String)

    /** 下载 [url] 的图片并发送给 [conversationId]（命令 `/sendimage`）。 */
    suspend fun sendImageUrl(conversationId: String, url: String): ImSendResult

    /** 下载 [url] 的文件并发送给 [conversationId]（命令 `/sendfile`）。 */
    suspend fun sendFileUrl(conversationId: String, url: String): ImSendResult

    /** 已缓存入站媒体的名称列表（供 `/forward` 列出可选转发项）。 */
    fun cachedMediaNames(): List<String>

    /** 把之前收到的名为 [name] 的媒体转发给 [conversationId]（命令 `/forward`）。 */
    suspend fun forwardMedia(conversationId: String, name: String): ImSendResult
}

/**
 * 微信 iLink 渠道：把 [WeixinIlinkApi] 的长轮询协议适配到 [MessageChannel]。
 *
 * 直连 ilinkai.weixin.qq.com，无需外部网关。会话列表和消息历史在内存中维护：
 * [listConversations] 触发一次 [WeixinIlinkApi.getUpdates] 长轮询（~35s），把新消息
 * 归档到对应会话；[fetchMessages] 只读内存，返回 INCOMING 消息。发送走 [WeixinIlinkApi.sendText]。
 *
 * 消息去重 / 会话绑定由 [com.lxseek.chat.im.ImPollingReceiver] 负责，本类只实现协议层。
 */
class WeixinChannel(
    private val config: com.lxseek.chat.im.ImGatewayConfig,
    private val api: WeixinIlinkApi = WeixinIlinkApi(),
) : MessageChannel, WeixinCompanionChannel {

    override val channelId: String get() = "wechat"
    override val displayName: String get() = "微信 · iLink"
    override val isConfigured: Boolean
        // P1-1: tokenStale 时返回 false，pollChannel 跳过，避免 -14 后无效循环
        get() = config.enabled && config.token.isNotBlank() && !tokenStale

    /**
     * P1-1: token 失效标志。-14 后置 true，isConfigured 返回 false 跳过轮询；
     * 重新绑定成功后由 [clearTokenStale] 清除，或 buildChannels 重建新 channel 自动清除
     * （参考 weixin-ClawBot-API bot.py:1511-1531 受控重登录）。
     */
    @Volatile
    private var tokenStale = false

    /** P1-1: 标记 token 失效，暂停该渠道轮询，等 UI 引导重新扫码绑定。 */
    fun markTokenStale() {
        if (!tokenStale) {
            tokenStale = true
            DebugLog.w("WeixinChannel", "tokenStale marked — polling paused until rebind")
        }
    }

    /** P1-1: 重新绑定成功后清除失效标志，恢复轮询。 */
    fun clearTokenStale() {
        tokenStale = false
        notified = false
        state.resetCursor()
        DebugLog.d("WeixinChannel", "tokenStale cleared — polling resumed")
    }

    /** iLink base URL：配置优先，否则用协议默认。 */
    private val baseUrl: String
        get() = config.baseUrl.trim().ifBlank { WeixinIlinkApi.WEIXIN_QR_BASE_URL }

    private val state = ChannelState()

    /**
     * 串行化所有 getupdates 长轮询访问（对齐 Zyn-iLink 的"每 token 单消费者"模型）。
     * pollLoop / testConnection / ProactiveMessagingService 都可能触发 listConversations →
     * pollUpdates；若并发，同一 token 上会存在多个重叠长轮询，互相推进共享游标导致
     * 消息被其中一个消费者抢走，另一个 getUpdates 永远返回 0（日志表现为两个游标交替）。
     * 用 Mutex 把所有进入 pollUpdates 的调用排队，保证同时只有一个 getupdates 在飞。
     */
    private val getUpdatesMutex = Mutex()

    /**
     * context_token store: userId → latest context_token from inbound messages.
     * The iLink protocol requires echoing the context_token verbatim in every reply;
     * without it the server may silently drop the reply (per weixin-bot-api.md and
     * easy-weixin-clawbot SDK which refuses to send without it).
     */
    private val contextTokenStore = ConcurrentHashMap<String, String>()

    /**
     * P1-8: 自回复防护——记录最近 [SELF_REPLY_DEDUP_WINDOW_MS] 内自己发出的消息
     * （key=to_user_id+content），applyUpdates 时若入站消息命中则跳过，
     * 防止 AI 回复自己消息死循环（参考 Akasha bridge_core.py:51-64 should_ignore）。
     */
    private val recentSent = ConcurrentHashMap<String, Long>()

    /**
     * 输入状态打点：当服务端返回 errcode/ret=-14（token 失效）时触发，供 UI 引导重新扫码。
     * 由调用方（如 IM 设置页）在创建渠道后按需注册；进程内默认为 null。
     */
    @Volatile
    var onTokenStale: (() -> Unit)? = null

    /** typing ticket 缓存（userId → ticket）+ 上次拉取时间，避免每次 getconfig。 */
    private val typingTicketCache = ConcurrentHashMap<String, String>()
    private val typingTicketFetchedAt = ConcurrentHashMap<String, Long>()

    /** Ensures notifyStart is called once before the first getUpdates poll. */
    @Volatile
    private var notified = false

    /** Dynamic long-poll timeout suggested by the server (0 = use default). */
    @Volatile
    private var longPollTimeoutMs: Long = 0L

    /** P0-1: Last send timestamp for rate limiting. */
    @Volatile
    private var lastSendTimeMs: Long = 0L

    /**
     * 入站媒体缓存（name → 已解密字节），供 `/forward` 转发已收到的图片/文件。
     * 容量与单条大小均受限，避免内存无限增长。
     */
    private val inboundMediaCache = InboundMediaCache(MEDIA_CACHE_MAX, MEDIA_CACHE_MAX_BYTES)

    override suspend fun sendMessage(conversationId: String, text: String): ImSendResult {
        if (!isConfigured) return ImSendResult.NotConfigured
        val recipient = conversationId.trim()
        if (recipient.isEmpty()) return ImSendResult.Failure("conversationId is empty")

        // P1-7: Strip Markdown syntax WeChat can't render
        val plainText = WeixinMarkdownFilter.strip(text)
        // P1-8: Cap at WeChat's max text length instead of splitting into multiple messages
        val cappedText = if (plainText.length > WECHAT_MAX_TEXT_LENGTH) {
            plainText.take(WECHAT_MAX_TEXT_LENGTH - 3) + "..."
        } else {
            plainText
        }

        // P0-1: Enforce minimum interval between sends to avoid WeChat anti-spam ban.
        enforceRateLimit()

        // P2-12: Retry transient failures (network-error, timeout) up to SEND_MAX_RETRIES times.
        val contextToken = contextTokenStore[recipient]
        DebugLog.d("WeixinChannel", "sendMessage: recipient=$recipient hasContextToken=${contextToken != null} textLen=${cappedText.length}")
        var lastError: Exception? = null
        for (attempt in 1..SEND_MAX_RETRIES) {
            try {
                val newContextToken = api.sendText(baseUrl, config.token, recipient, cappedText, contextToken)
                // 响应回写新 context_token（对齐 weixin_client.py _extract_response_context_token）。
                if (!newContextToken.isNullOrEmpty() && newContextToken != contextToken) {
                    contextTokenStore[recipient] = newContextToken
                    DebugLog.d("WeixinChannel", "sendMessage: refreshed context_token for $recipient")
                }
                // P1-8: 记录已发送消息，供 applyUpdates 自回复去重（防 AI 回复自己消息死循环）
                recordSent(recipient, cappedText)
                // 用 client_id 风格的本地 id 作为成功回执（iLink 不回 server id）
                return ImSendResult.Success("lxchat-weixin-sent-${System.currentTimeMillis()}")
            } catch (e: WeixinApiError) {
                lastError = e
                DebugLog.e("WeixinChannel", "sendMessage attempt $attempt failed: ${e.code}", e)
                // Non-transient errors (send-rejected, invalid-*) should not retry.
                if (e.code !in TRANSIENT_ERROR_CODES) {
                    return ImSendResult.Failure(e.message ?: e.code)
                }
            } catch (e: Exception) {
                lastError = e
                DebugLog.e("WeixinChannel", "sendMessage attempt $attempt failed", e)
                // Generic exceptions (e.g. IOException) are treated as transient.
            }
            // Transient failure: wait before retrying (unless this was the last attempt).
            if (attempt < SEND_MAX_RETRIES) {
                DebugLog.d("WeixinChannel", "retrying send in ${SEND_RETRY_DELAY_MS}ms (attempt $attempt)")
                delay(SEND_RETRY_DELAY_MS)
            }
        }
        val msg = lastError?.message ?: "send failed"
        return ImSendResult.Failure(msg)
    }

    /** P0-1: Enforce minimum interval between sends to avoid WeChat anti-spam ban. */
    private suspend fun enforceRateLimit() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastSendTimeMs
        val jitter = SecureRandom().nextLong(SEND_MAX_JITTER_MS + 1).coerceAtLeast(0)
        val required = SEND_MIN_INTERVAL_MS + jitter
        if (elapsed < required) {
            val wait = required - elapsed
            DebugLog.d("WeixinChannel", "rate limit: waiting ${wait}ms (elapsed=${elapsed}ms)")
            delay(wait)
        }
        lastSendTimeMs = System.currentTimeMillis()
    }

    // ── 媒体发送（无 UI 场景：协议能力 + 命令触发） ──────────────────────

    override suspend fun sendImageUrl(conversationId: String, url: String): ImSendResult {
        val trimmed = url.trim()
        if (!isConfigured) return ImSendResult.NotConfigured
        val bytes = withContext(Dispatchers.IO) { HttpClient.getBytes(trimmed) }
            ?: return ImSendResult.Failure("图片下载失败，请检查 URL。")
        val name = fileNameFromUrl(trimmed)
        return sendMedia(conversationId, WeixinMediaSpec(
            kind = WeixinIlinkApi.WeixinMediaKind.IMAGE,
            rawBytes = bytes,
            fileName = name,
            thumbBytes = imageThumb(bytes),
        ))
    }

    override suspend fun sendFileUrl(conversationId: String, url: String): ImSendResult {
        val trimmed = url.trim()
        if (!isConfigured) return ImSendResult.NotConfigured
        val bytes = withContext(Dispatchers.IO) { HttpClient.getBytes(trimmed) }
            ?: return ImSendResult.Failure("文件下载失败，请检查 URL。")
        val name = fileNameFromUrl(trimmed)
        return sendMedia(conversationId, WeixinMediaSpec(
            kind = WeixinIlinkApi.WeixinMediaKind.FILE,
            rawBytes = bytes,
            fileName = name,
        ))
    }

    override fun cachedMediaNames(): List<String> = inboundMediaCache.names()

    override suspend fun forwardMedia(conversationId: String, name: String): ImSendResult {
        val key = name.trim()
        val bytes = inboundMediaCache.get(key) ?: return ImSendResult.Failure(
            "找不到已缓存的媒体：$key。可先让好友发送图片/文件，或用 /sendimage /sendfile 发送直链；/forward 无参数可查看缓存列表。",
        )
        // 以名字前缀 image 判定为图片（入站图片缓存名为 image / image-N）。
        val kind = if (key.startsWith("image")) WeixinIlinkApi.WeixinMediaKind.IMAGE
                   else WeixinIlinkApi.WeixinMediaKind.FILE
        return sendMedia(conversationId, WeixinMediaSpec(
            kind = kind,
            rawBytes = bytes,
            fileName = key,
            thumbBytes = if (kind == WeixinIlinkApi.WeixinMediaKind.IMAGE) imageThumb(bytes) else null,
        ))
    }

    /** 媒体发送核心：限速 → sendmediaitem → 回写新 context_token。 */
    private suspend fun sendMedia(conversationId: String, spec: WeixinIlinkApi.WeixinMediaSpec): ImSendResult {
        if (!isConfigured) return ImSendResult.NotConfigured
        val recipient = conversationId.trim()
        if (recipient.isEmpty()) return ImSendResult.Failure("conversationId is empty")
        if (spec.rawBytes.size > MAX_MEDIA_BYTES) {
            return ImSendResult.Failure("媒体过大（>${MAX_MEDIA_BYTES / 1024 / 1024}MB），暂不支持发送。")
        }
        enforceRateLimit()
        val contextToken = contextTokenStore[recipient]
        DebugLog.d("WeixinChannel", "sendMedia: recipient=$recipient kind=${spec.kind} size=${spec.rawBytes.size} hasCtx=${contextToken != null}")
        return try {
            val newCtx = api.sendMediaItem(baseUrl, config.token, recipient, spec, contextToken)
            if (!newCtx.isNullOrEmpty() && newCtx != contextToken) contextTokenStore[recipient] = newCtx
            ImSendResult.Success("lxchat-weixin-media-${System.currentTimeMillis()}")
        } catch (e: WeixinApiError) {
            DebugLog.e("WeixinChannel", "sendMedia failed: ${e.code}", e)
            ImSendResult.Failure(e.message ?: e.code)
        } catch (e: Exception) {
            DebugLog.e("WeixinChannel", "sendMedia failed", e)
            ImSendResult.Failure(e.message ?: "发送失败")
        }
    }

    /** 从 URL 取文件名（URL decode 末尾路径段），失败回退 "file"。 */
    private fun fileNameFromUrl(url: String): String {
        val last = url.substringAfterLast('/')
        val decoded = runCatching { URLDecoder.decode(last.substringBefore('?'), "UTF-8") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return "file"
        return decoded.ifBlank { "file" }
    }

    /** 图片字节 → 240x240 JPEG 缩略图（供微信图片发送），失败返回 null。 */
    private fun imageThumb(bytes: ByteArray): ByteArray? = try {
        if (bytes.isEmpty()) return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scaled = downscaleToMax(bmp, MEDIA_THUMB_MAX_DIMENSION)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, MEDIA_THUMB_QUALITY, out)
        out.toByteArray()
    } catch (e: Exception) {
        DebugLog.w("WeixinChannel", "image thumb failed: ${e.message}")
        null
    }

    /** 缓存一条已解密的入站媒体（供 /forward 转发）。容量与大小受限，忽略超限项。 */
    private fun cacheInboundMedia(name: String, bytes: ByteArray) {
        if (name.isBlank() || bytes.isEmpty()) return
        if (bytes.size > MEDIA_CACHE_MAX_BYTES) return
        inboundMediaCache.put(name, bytes)
    }

    /**
     * P2-1: 停止该渠道——best-effort 通知微信服务停止推送（参考 weixin-ClawBot-API bot.py:1563-1579）。
     * 用独立短超时（10s），失败不抛，不被长轮询取消信号连带取消。
     */
    suspend fun stop() {
        if (!config.enabled || config.token.isBlank()) return
        try {
            withTimeoutOrNull(NOTIFY_STOP_TIMEOUT_MS) {
                api.notifyStop(baseUrl, config.token)
            }
            DebugLog.d("WeixinChannel", "notifyStop done")
        } catch (e: Exception) {
            DebugLog.w("WeixinChannel", "notifyStop failed: ${e.message}")
        }
    }

    // ── P1-8 自回复防护辅助 ──────────────────────────────────────────────

    private fun recordSent(toUserId: String, content: String) {
        val key = selfReplyKey(toUserId, content)
        recentSent[key] = System.currentTimeMillis()
    }

    private fun isSelfReplyEcho(toUserId: String, content: String): Boolean {
        if (toUserId.isEmpty() || content.isEmpty()) return false
        val key = selfReplyKey(toUserId, content)
        val ts = recentSent[key] ?: return false
        return System.currentTimeMillis() - ts < SELF_REPLY_DEDUP_WINDOW_MS
    }

    private fun selfReplyKey(toUserId: String, content: String): String {
        // 用 to_user_id+content 做 key，避免不同人相同内容误判；content 截断控制 key 体积
        return "$toUserId\u0000${content.take(200)}"
    }

    private fun cleanupRecentSent() {
        val now = System.currentTimeMillis()
        recentSent.entries.removeIf { now - it.value > SELF_REPLY_DEDUP_WINDOW_MS }
    }

    // ── WeixinCompanionChannel 实现（移动端可用）──────────────────────────

    override suspend fun sendTyping(conversationId: String, status: Int) {
        if (!isConfigured) return
        val recipient = conversationId.trim()
        if (recipient.isEmpty()) return
        try {
            // getconfig 需要 context_token 做上下文关联；拿不到就跳过输入状态。
            val contextToken = contextTokenStore[recipient] ?: return
            val ticket = typingTicketFor(recipient, contextToken) ?: return
            api.sendTyping(baseUrl, config.token, recipient, ticket, status)
        } catch (e: Exception) {
            DebugLog.e("WeixinChannel", "sendTyping failed", e)
        }
    }

    override fun contextTokensSnapshot(): Map<String, String> = contextTokenStore.toMap()

    override fun seedContextTokens(tokens: Map<String, String>) {
        if (tokens.isNotEmpty()) contextTokenStore.putAll(tokens)
    }

    override fun syncBufSnapshot(): String = state.getUpdatesBuf()

    override fun seedSyncBuf(buf: String) {
        if (buf.isNotEmpty()) state.seedSyncBuf(buf)
    }

    /** 取用户 typing ticket，带 TTL 缓存；拿不到返回 null 让输入状态静默跳过。 */
    private suspend fun typingTicketFor(userId: String, contextToken: String): String? {
        val now = System.currentTimeMillis()
        val cached = typingTicketCache[userId]
        val fetchedAt = typingTicketFetchedAt[userId] ?: 0L
        if (cached != null && now - fetchedAt < TYPING_TICKET_TTL_MS) return cached
        val fresh = api.getConfig(baseUrl, config.token, userId, contextToken) ?: cached
        if (!fresh.isNullOrEmpty()) {
            typingTicketCache[userId] = fresh
            typingTicketFetchedAt[userId] = now
        }
        return fresh
    }

    override suspend fun listConversations(): List<ImConversation> {
        if (!isConfigured) return emptyList()
        // 长轮询拉新消息，更新会话列表。失败不抛（返回当前快照）。
        pollUpdates()
        return state.conversations()
    }

    override suspend fun fetchMessages(conversationId: String, afterId: String?): List<ImMessage> {
        if (!isConfigured) return emptyList()
        // 只读内存（listConversations 已经 poll 过）；iLink 入站消息全部 INCOMING。
        return state.messagesFor(conversationId, afterId)
            .filter { it.direction == ImMessageDirection.INCOMING }
    }

    private suspend fun pollUpdates() {
        // 串行化 getupdates（见 getUpdatesMutex 注释）：排队而非重叠长轮询，避免共享游标被抢导致 0 消息。
        getUpdatesMutex.withLock {
            pollUpdatesLocked()
        }
    }

    private suspend fun pollUpdatesLocked() {
        // P1-8: 清理过期自回复记录，避免 recentSent 无限增长
        cleanupRecentSent()
        try {
            // dsh-im calls notifyStart before the monitor loop; without this the WeChat server
            // does not push messages to getupdates, so every poll returns an empty list.
            if (!notified) {
                DebugLog.d("WeixinChannel", "pollUpdates: calling notifyStart, baseUrl=$baseUrl, tokenLen=${config.token.length}")
                api.notifyStart(baseUrl, config.token)
                notified = true
                DebugLog.d("WeixinChannel", "pollUpdates: notifyStart succeeded")
            }
            val timeoutMs = if (longPollTimeoutMs > 0) longPollTimeoutMs else WeixinIlinkApi.DEFAULT_LONG_POLL_TIMEOUT_MS
            val updates = api.getUpdates(baseUrl, config.token, state.getUpdatesBuf(), timeoutMs)
            DebugLog.d("WeixinChannel", "pollUpdates: received ${updates.msgs.size} msgs, ret=${updates.ret}, bufLen=${updates.getUpdatesBuf.length}, serverTimeout=${updates.longpollingTimeoutMs}")
            // Use server-suggested long-poll timeout for the next request.
            if (updates.longpollingTimeoutMs > 0) {
                // A12: 限制长轮询超时在 [1, 120]s，防止服务端返回大值（如 600000=10分钟）
                // 导致长轮询挂起。参考 weixin-ClawBot-API bot.py:1499-1502。
                longPollTimeoutMs = updates.longpollingTimeoutMs.coerceIn(1_000L, 120_000L)
            }
            // Check for server-side rejection (dsh-im checks ret and errcode).
            val errcode = updates.raw["errcode"]?.let { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
            if ((updates.ret != 0) || (errcode != null && errcode != 0)) {
                // H3: 优先检查 ret，ret=-14 时 code=-14 触发 onTokenStale。
                // 原 `errcode ?: updates.ret` 用 Elvis：当 errcode=0（非 null）时 code=0 不触发 -14，
                // 但 bot.py 优先 ret：`self.code = self.ret if self.ret not in (None, 0) else self.errcode`，
                // ret=-14 且 errcode=0 时 bot.py code=-14（触发 stale），Lxchat 旧逻辑 code=0（不触发）。
                // 参考weixin-ClawBot-API bot.py:329-330。
                val code = if (updates.ret != 0) updates.ret else (errcode ?: 0)
                DebugLog.e("WeixinChannel", "pollUpdates rejected: ret=${updates.ret} errcode=$errcode errmsg=${updates.raw["errmsg"]?.strSafe()}")
                if (code == -14) {
                    // token 失效：重置协议状态，让重新绑定后的新 token 能干净接管；
                    // 并通过 onTokenStale 提醒 UI 引导重新扫码（参考 weixin-ClawBot-API 的受控重登录）。
                    DebugLog.e("WeixinChannel", "stale token (errcode -14) — re-scan required")
                    notified = false
                    state.resetCursor()
                    runCatching { onTokenStale?.invoke() }
                }
                return
            }
            state.applyUpdates(
                updates,
                contextTokenStore,
                config.botId.takeIf { it.isNotBlank() },
                ::isSelfReplyEcho,
                { msg -> loadWeixinImageDataUris(msg) },
                { msg -> loadWeixinFileText(msg) },
            )
        } catch (e: WeixinApiError) {
            DebugLog.e("WeixinChannel", "pollUpdates failed: ${e.code}", e)
        } catch (e: Exception) {
            DebugLog.e("WeixinChannel", "pollUpdates failed", e)
        }
    }

    /**
     * 把一条入站消息里的微信图片 下载 → AES-128-ECB 解密 → 压缩 → base64 data URI，
     * 供 [com.lxseek.chat.im.ImPollingReceiver.buildPromptText] 以 Markdown 图片链接喂给视觉模型。
     *
     * 只保留图片、无文本的消息此前会被静默丢弃，接通后模型能看到图片。单张失败不影响其余图片和文本。
     */
    private suspend fun loadWeixinImageDataUris(message: JsonObject): List<String> {
        val refs = api.inboundImages(message)
        if (refs.isEmpty()) return emptyList()
        val uris = ArrayList<String>(refs.size)
        for (ref in refs) {
            try {
                val bytes = ref.load(MAX_IMAGE_DOWNLOAD_BYTES)
                // 缓存原始解密字节，供 /forward 转发收到过的图片。
                cacheInboundMedia(ref.name, bytes)
                weixinImageDataUri(bytes)?.let { uris.add(it) }
            } catch (e: Exception) {
                DebugLog.w("WeixinChannel", "load image ${ref.name} failed: ${e.message}")
            }
        }
        return uris
    }

    /**
     * 提取文件消息的可读文本：文本类文件（txt/csv/md/json/log 等）下载解密后转为
     * UTF-8 文本并入提示，让 AI 能读到文件内容；非文本文件只报文件名，不拉取大文件。
     * 对齐 Zyn-iLink 的 _auto_ai_reply_with_file。无文件返回空串，零开销。
     */
    private suspend fun loadWeixinFileText(message: JsonObject): String {
        val refs = api.inboundFiles(message)
        if (refs.isEmpty()) return ""
        val parts = ArrayList<String>()
        for (ref in refs) {
            val fname = ref.name
            if (!isTextFile(fname)) { parts += "[文件 $fname]"; continue }
            try {
                val bytes = ref.load(MAX_IMAGE_DOWNLOAD_BYTES)
                // 缓存文本类文件解密字节，供 /forward 转发。
                cacheInboundMedia(ref.name, bytes)
                val text = utf8Text(bytes)
                if (text.isNotBlank()) parts += "[文件 $fname 内容]\n${text.take(MAX_FILE_TEXT_CHARS)}"
                else parts += "[文件 $fname]"
            } catch (e: Exception) {
                DebugLog.w("WeixinChannel", "load file ${ref.name} failed: ${e.message}")
                parts += "[文件 $fname]"
            }
        }
        return parts.joinToString("\n")
    }

    /** 是否为文本类文件扩展名（可安全解码为 UTF-8 提示给模型）。 */
    private fun isTextFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in TEXT_FILE_EXTS
    }

    /** 将 AES 解密后的字节尝试按 UTF-8 解码；含二进制控制字符则视为非文本，返回空串。 */
    private fun utf8Text(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return try {
            val s = String(bytes, Charsets.UTF_8)
            for (c in s) {
                val code = c.code
                // H67: 允许 tab(9)/换行(10)/回车(13) 和所有非控制字符（含中文等 Unicode > 127）。
                // 旧逻辑只允许 ASCII 32-126，中文文本文件会被判为非文本返回空串，不并入 AI 提示。
                if (code != 9 && code != 10 && code != 13 && (code < 32 || code == 127)) {
                    return ""
                }
            }
            s
        } catch (e: Exception) {
            ""
        }
    }

    /** 解密后的图片字节 → 缩放 + JPEG 压缩 → `data:image/jpeg;base64,xxx`；失败返回 null。 */
    private fun weixinImageDataUri(bytes: ByteArray): String? = try {
        if (bytes.isEmpty()) return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scaled = downscaleToMax(bitmap, MAX_IMAGE_DIMENSION)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, IMAGE_JPEG_QUALITY, out)
        val data = out.toByteArray()
        "data:image/jpeg;base64," + android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
    } catch (e: Exception) {
        DebugLog.w("WeixinChannel", "image → data uri failed: ${e.message}")
        null
    }

    private fun downscaleToMax(bmp: Bitmap, maxDim: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        if (maxDim <= 0 || (w <= maxDim && h <= maxDim)) return bmp
        val scale = maxDim.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(
            bmp,
            (w * scale).toInt().coerceAtLeast(1),
            (h * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    // ── 内存状态 ─────────────────────────────────────────────────────────

    private class ChannelState {

        @Volatile
        private var _getUpdatesBuf: String = ""

        private val conversations = ConcurrentHashMap<String, ImConversation>()
        private val messages = ConcurrentHashMap<String, CopyOnWriteArrayList<ImMessage>>()

        fun getUpdatesBuf(): String = _getUpdatesBuf

        /** token 失效（-14）或需要重新绑定时重置游标，保证重新绑定后不丢不重。 */
        fun resetCursor() {
            _getUpdatesBuf = ""
        }

        /** P0-5: Restore sync_buf cursor from persistence (App restart). */
        fun seedSyncBuf(buf: String) {
            if (buf.isNotEmpty()) _getUpdatesBuf = buf
        }

        suspend fun applyUpdates(
            updates: WeixinIlinkApi.Updates,
            contextTokenStore: ConcurrentHashMap<String, String>,
            selfBotId: String?,
            selfReplyChecker: (toUserId: String, content: String) -> Boolean,
            imageResolver: suspend (JsonObject) -> List<String>,
            fileResolver: suspend (JsonObject) -> String,
        ) {
            // Only update the cursor if the server returned a non-empty buf.
            // An empty buf would reset the cursor and potentially miss messages.
            // (Matches easy-weixin-clawbot monitor.ts behavior.)
            if (updates.getUpdatesBuf.isNotEmpty()) {
                _getUpdatesBuf = updates.getUpdatesBuf
            }
            DebugLog.d("WeixinChannel", "applyUpdates: processing ${updates.msgs.size} msgs, bufUpdated=${updates.getUpdatesBuf.isNotEmpty()}")
            for (msg in updates.msgs) {
                // dsh-im skips message_type === 2 (outgoing messages sent by the bot itself).
                val msgType = msg["message_type"]?.let { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
                if (msgType == 2) { DebugLog.d("WeixinChannel", "applyUpdates: skipped - outgoing msg (type=2)"); continue }
                val text = WeixinIlinkApi.extractWeixinText(msg)
                val msgId = WeixinIlinkApi.weixinMessageId(msg)
                val fromUserId = msg["from_user_id"]?.strSafe()
                // P1-8: from_user_id == 自己 botId 时跳过（自回复防护第一道）
                if (!selfBotId.isNullOrEmpty() && fromUserId == selfBotId) {
                    DebugLog.d("WeixinChannel", "applyUpdates: skip self-reply echo (from=botId)")
                    continue
                }
                // P1-8: 内容去重——to_user_id+content 命中 recentSent 时跳过（自回复防护第二道）
                val toUserId = msg["to_user_id"]?.strSafe()
                if (text != null && selfReplyChecker(toUserId ?: "", text)) {
                    DebugLog.d("WeixinChannel", "applyUpdates: skip self-reply echo (to=$toUserId)")
                    continue
                }
                // Extract and store context_token — required for replies (per weixin-bot-api.md).
                val contextToken = msg["context_token"]?.strSafe()
                if (fromUserId != null && fromUserId.isNotEmpty() && !contextToken.isNullOrEmpty()) {
                    contextTokenStore[fromUserId] = contextToken
                    DebugLog.d("WeixinChannel", "applyUpdates: stored context_token for user=$fromUserId")
                }
                // 下载+解密图片 → data URI，让只发图片的消息也能被模型识别（此前会被静默丢弃）。
                val images = try {
                    imageResolver(msg)
                } catch (e: Exception) {
                    DebugLog.w("WeixinChannel", "applyUpdates: resolve images failed: ${e.message}")
                    emptyList()
                }
                // 下载+解密文件 → 文本（文本类文件并入提示），对齐 Zyn-iLink 文件识别。
                val fileText = try {
                    fileResolver(msg)
                } catch (e: Exception) {
                    DebugLog.w("WeixinChannel", "applyUpdates: resolve files failed: ${e.message}")
                    ""
                }
                val hasFile = fileText.isNotBlank()
                DebugLog.d("WeixinChannel", "applyUpdates: msg text=${text?.take(50)} images=${images.size} files=$hasFile id=$msgId from=$fromUserId hasCtxToken=${!contextToken.isNullOrEmpty()} keys=${msg.keys}")
                // 只有文本、图片、文件内容都没有才跳过，保证纯图片/纯文件消息能进入管线。
                if (text.isNullOrBlank() && images.isEmpty() && !hasFile) { DebugLog.w("WeixinChannel", "applyUpdates: skipped - no text, images, or files"); continue }
                if (msgId == null) { DebugLog.w("WeixinChannel", "applyUpdates: skipped - msgId is null"); continue }
                if (fromUserId == null) { DebugLog.w("WeixinChannel", "applyUpdates: skipped - fromUserId is null"); continue }
                if (fromUserId.isEmpty()) { DebugLog.w("WeixinChannel", "applyUpdates: skipped - fromUserId is empty"); continue }
                val timestampMs = normalizeTimestamp(msg["create_time"]?.longSafe())
                val combinedText = buildString {
                    text?.trim()?.let { append(it) }
                    if (fileText.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append(fileText)
                    }
                }
                val imMsg = ImMessage(
                    id = msgId,
                    conversationId = fromUserId,
                    direction = ImMessageDirection.INCOMING,
                    text = combinedText.trim(),
                    sender = fromUserId,
                    timestampMs = timestampMs,
                    images = images,
                )
                val list = messages.computeIfAbsent(fromUserId) { CopyOnWriteArrayList() }
                if (list.none { it.id == msgId }) list.add(imMsg)
                conversations.compute(fromUserId) { _, existing ->
                    val lastMs = existing?.lastMessageAtMs ?: 0L
                    if (lastMs < timestampMs) {
                        ImConversation(
                            id = fromUserId,
                            title = existing?.title ?: fromUserId,
                            platform = "wechat",
                            lastMessageAtMs = maxOf(lastMs, timestampMs),
                            unreadCount = (existing?.unreadCount ?: 0) + 1,
                            isGroup = existing?.isGroup ?: false,
                        )
                    } else existing
                }
            }
        }

        fun conversations(): List<ImConversation> =
            conversations.values.sortedByDescending { it.lastMessageAtMs }

        fun messagesFor(conversationId: String, afterId: String?): List<ImMessage> {
            val list = messages[conversationId] ?: return emptyList()
            if (afterId.isNullOrBlank()) return list.toList()
            val idx = list.indexOfFirst { it.id == afterId }
            if (idx < 0) return list.toList()
            return list.subList(idx + 1, list.size).toList()
        }

        /** iLink create_time 可能是秒也可能是毫秒；>1e12 视作毫秒。 */
        private fun normalizeTimestamp(value: Long?): Long {
            if (value == null || value <= 0L) return System.currentTimeMillis()
            return if (value > 1_000_000_000_000L) value else value * 1_000L
        }
    }

    private companion object {
        /** typing ticket 缓存时长（毫秒）。低于此时长内复用已取的 ticket，避免频繁 getconfig。 */
        const val TYPING_TICKET_TTL_MS = 30L * 60 * 1000
        /** 图片下载上限：超过视为异常，但仍保留文本。 */
        const val MAX_IMAGE_DOWNLOAD_BYTES = 8L * 1024 * 1024
        /** 缩放后最大边长（像素），控制 base64 体积，兼顾模型可读。 */
        const val MAX_IMAGE_DIMENSION = 1024
        /** JPEG 压缩质量（0-100），越小体积越小。 */
        const val IMAGE_JPEG_QUALITY = 70
        /** P2-1: notifyStop 独立短超时（毫秒），不被长轮询取消信号连带取消。 */
        const val NOTIFY_STOP_TIMEOUT_MS = 10_000L
        /** P1-8: 自回复去重窗口（毫秒），120 秒内命中视为自己消息回流。 */
        const val SELF_REPLY_DEDUP_WINDOW_MS = 120_000L
        /** P0-1: Minimum interval between sends (ms). SpenserCai: 34s/18 msgs triggers ban. */
        const val SEND_MIN_INTERVAL_MS = 3_000L
        /** P0-1: Maximum random jitter added to interval (ms). */
        const val SEND_MAX_JITTER_MS = 2_000L
        /** P1-8: Maximum text length for a single WeChat message. */
        const val WECHAT_MAX_TEXT_LENGTH = 2_000
        /** P2-12: Maximum retry attempts for transient send failures. */
        const val SEND_MAX_RETRIES = 3
        /** P2-12: Retry delay between attempts excluding last (`SEND_RETRY_DELAY_MS`). */
        const val SEND_RETRY_DELAY_MS = 5_000L
        /** 媒体（图片/文件）单条发送上限（字节），超过不发送。 */
        const val MAX_MEDIA_BYTES = 20L * 1024 * 1024
        /** 图片缩略图最大边长（像素）。 */
        const val MEDIA_THUMB_MAX_DIMENSION = 240
        /** 缩略图 JPEG 质量（0-100）。 */
        const val MEDIA_THUMB_QUALITY = 85
        /** 入站媒体缓存最大条数（超出丢弃最旧）。 */
        const val MEDIA_CACHE_MAX = 64
        /** 入站媒体单条缓存上限（字节），过大不缓存。 */
        const val MEDIA_CACHE_MAX_BYTES = 15L * 1024 * 1024
        /** P2-12: Transient error codes that warrant a retry. */
        val TRANSIENT_ERROR_CODES = setOf("network-error", "timeout")
        /** 文本类文件扩展名，解密后可直接用 UTF-8 解码并喂给模型。 */
        val TEXT_FILE_EXTS = setOf("txt", "csv", "md", "markdown", "json", "log", "html", "htm", "xml", "yml", "yaml", "ini", "conf", "properties")
        /** 单个文件内容并入提示的最大字符数，避免超大文本占用上下文。 */
        const val MAX_FILE_TEXT_CHARS = 12_000
    }
}

// ── JsonElement 安全取值（避免类型不符抛 IllegalStateException） ──
private fun kotlinx.serialization.json.JsonElement?.strSafe(): String? =
    (this as? JsonPrimitive)?.contentOrNull

private fun kotlinx.serialization.json.JsonElement?.longSafe(): Long? =
    (this as? JsonPrimitive)?.longOrNull

// ── 入站媒体缓存：name → 已解密字节，供 /forward 转发 ──
/** 线程安全的容量受限媒体字节缓存。超容量丢弃最旧；单条超上限直接忽略。 */
private class InboundMediaCache(
    private val maxEntries: Int,
    private val maxBytesPerEntry: Long,
) {
    private val lock = Any()
    private val map = java.util.LinkedHashMap<String, ByteArray>(16, 0.75f, false)

    fun put(name: String, bytes: ByteArray) {
        if (name.isBlank() || bytes.isEmpty() || bytes.size.toLong() > maxBytesPerEntry) return
        synchronized(lock) {
            // 已存在则先移除，保证最近写入排在队尾（保留最新语义）。
            map.remove(name)
            if (map.size >= maxEntries) {
                map.entries.firstOrNull()?.let { e -> map.remove(e.key) }
            }
            map[name] = bytes
        }
    }

    fun get(name: String): ByteArray? = synchronized(lock) { map[name] }

    fun names(): List<String> = synchronized(lock) { map.keys.toList() }
}

