package com.lxseek.chat.im

import com.lxseek.chat.automation.TaskExecutionEngine
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.im.weixin.WeixinChannel
import com.lxseek.chat.im.weixin.WeixinCompanionChannel
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Background receiver that closes the IM loop for every active [MessageChannel]:
 *
 *  - **Polling channels** (wechat, telegram, sms): a single [pollLoop] periodically calls
 *    [MessageChannel.listConversations] + [fetchMessages] and feeds new inbound messages into
 *    [feedInboundBatch].
 *  - **Push channels** (lark, dingtalk, wecom, qq, discord, slack): each [PushMessageChannel]
 *    gets a dedicated listening job that calls [PushMessageChannel.startListening]; its
 *    callback feeds inbound messages into the same [feedInboundBatch] path, so push and
 *    polling share one agent-trigger / reply-write-back pipeline.
 *
 * Inbound messages are routed to a bound Lxchat conversation, run through
 * [TaskExecutionEngine.runOnce], and the assistant reply is written back to the remote thread.
 * IM conversation <-> Lxchat conversation bindings and a per-channel seen-message set are
 * persisted in [ImGatewayStore] (multi-channel map) so the process resumes exactly where it
 * left off after a restart. Legacy single-config state is read as a fallback so pre-multi-bot
 * setups keep their seen-set without migration.
 *
 * When a [ImCommandProcessor] is supplied, inbound messages starting with `/` are intercepted
 * as bot commands (e.g. `/help`, `/new`, `/models`) and handled without triggering AI replies.
 * The `/steer` command is the exception: its instruction text is forwarded as a normal user
 * message to the current Lxchat conversation so the agent incorporates it mid-turn.
 */
class ImPollingReceiver(
    private val bridge: ImBridgeService,
    private val taskEngine: TaskExecutionEngine,
    private val conversationRepository: ConversationRepository,
    private val store: ImGatewayStore,
    private val scope: CoroutineScope,
    /** Splits long auto-replies into several short messages before writing them back. */
    private val segmentSender: MultiSegmentMessageSender = MultiSegmentMessageSender(),
    /** Optional callback fired after an inbound message is successfully replied to, e.g. to mark
     *  the conversation as active so proactive messaging doesn't greet a contact that just spoke. */
    private val onMessageHandled: ((conversationId: String) -> Unit)? = null,
    /** 机器人命令处理器。非 null 时，以 `/` 开头的消息会交给它处理而不触发 AI 回复
     *  （`/steer` 例外，它会将补充指令作为用户消息发送）。 */
    private val commandProcessor: ImCommandProcessor? = null,
) {
    /** The single poll-loop job (covers all polling channels). */
    @Volatile
    private var pollJob: Job? = null

    /** One listening job per push channel key, so we can stop/restart individual channels. */
    private val pushJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /**
     * 按好友 AI 回复冷却打点（key = channelKey + conversationId）。
     * 对齐 Zyn-iLink 的 ai_cooldown：防止同一好友短时间内连发触发一串 AI 请求互相打断，
     * 冷却期内对该好友的消息本轮不回复（消息已被 seen 标记，不会重试）。
     */
    private val lastAiReplyAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Start (or restart) the background receiver. Polling and push channels are both wired. */
    fun start() {
        stop()
        pollJob = scope.launch(Dispatchers.Default) {
            pollLoop()
        }
        startPushListeners()
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        stopPushListeners()
        // P2-1: best-effort 通知微信服务停止推送，用独立协程避免被长轮询取消连带
        for ((_, channel) in bridge.channels()) {
            if (channel is WeixinChannel) {
                scope.launch(Dispatchers.Default) {
                    runCatching { channel.stop() }
                }
            }
        }
    }

    // ── Polling path ──────────────────────────────────────────

    /**
     * Self-healing poll loop: re-reads the bridge's channel map each cycle, polls every
     * configured non-push channel, and pauses when none are active. Push channels are
     * handled by [startPushListeners] and deliberately skipped here.
     */
    private suspend fun pollLoop() {
        DebugLog.d("ImPolling", "pollLoop started")
        while (true) {
            coroutineContext.ensureActive()
            val pollingChannels = bridge.channels().entries
                .filter { it.value.isConfigured && it.value !is PushMessageChannel }
            val interval = currentPollInterval()
            if (pollingChannels.isEmpty()) {
                DebugLog.d("ImPolling", "pollLoop: no polling channels, waiting ${interval}ms")
                delay(interval)
                continue
            }
            for ((channelKey, channel) in pollingChannels) {
                coroutineContext.ensureActive()
                try {
                    pollChannel(channelKey, channel)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // 防止单个渠道异常导致整个 pollLoop 崩溃（否则入站消息永久停止）
                    DebugLog.e("ImPolling", "pollChannel failed for $channelKey", e)
                }
            }
            delay(interval)
        }
    }

    private suspend fun pollChannel(channelKey: String, channel: MessageChannel) {
        val conversations = try {
            channel.listConversations()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            DebugLog.e("ImPolling", "listConversations failed for $channelKey", e)
            emptyList()
        }
        DebugLog.d("ImPolling", "pollChannel $channelKey: ${conversations.size} conversations")
        for (conversation in conversations) {
            coroutineContext.ensureActive()
            pollConversation(channelKey, channel, conversation)
        }
    }

    private suspend fun pollConversation(
        channelKey: String,
        channel: MessageChannel,
        conversation: ImConversation,
    ) {
        val messages = try {
            channel.fetchMessages(conversation.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            DebugLog.e("ImPolling", "fetchMessages failed for ${conversation.id}", e)
            emptyList()
        }
        val inbox = messages.filter { it.direction == ImMessageDirection.INCOMING }
        DebugLog.d("ImPolling", "pollConversation ${conversation.id}: ${inbox.size} inbox msgs")
        if (inbox.isEmpty()) return
        feedInboundBatch(channelKey, channel, conversation, inbox)
    }

    // ── Push path ─────────────────────────────────────────────

    /** Start a listening job for every active push channel not already listening. */
    private fun startPushListeners() {
        val pushChannels = bridge.channels().entries
            .filter { it.value is PushMessageChannel && it.value.isConfigured }
        for ((channelKey, channel) in pushChannels) {
            if (pushJobs.containsKey(channelKey)) continue
            val pushChannel = channel as PushMessageChannel
            val job = scope.launch(Dispatchers.Default) {
                try {
                    pushChannel.startListening(
                        onMessage = { message ->
                            // The callback is non-suspending; launch the heavy agent work on the
                            // receiver scope so the channel's internal dispatcher is not blocked.
                            if (message.direction == ImMessageDirection.INCOMING) {
                                scope.launch(Dispatchers.Default) {
                                    handlePushInbound(channelKey, pushChannel, message)
                                }
                            }
                        },
                        scope = scope,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLog.e("ImPolling", "push listener failed for $channelKey", e)
                }
            }
            pushJobs[channelKey] = job
        }
    }

    private fun stopPushListeners() {
        for ((channelKey, job) in pushJobs) {
            runCatching {
                (bridge.channelFor(channelKey) as? PushMessageChannel)?.stopListening()
            }
            job.cancel()
        }
        pushJobs.clear()
    }

    /** Convert one pushed [message] into a synthetic conversation and feed it to the shared path. */
    private suspend fun handlePushInbound(
        channelKey: String,
        channel: MessageChannel,
        message: ImMessage,
    ) {
        val conversation = ImConversation(
            id = message.conversationId,
            title = message.sender.ifBlank { message.conversationId },
            platform = channel.channelId,
        )
        feedInboundBatch(channelKey, channel, conversation, listOf(message))
    }

    // ── Shared inbound → agent → reply pipeline ───────────────

    /**
     * Deduplicate [inbox] against the seen-set, bind the IM conversation to a Lxchat
     * conversation, run the agent once on the merged batch, and write the reply back.
     * Used by both the polling and push paths so every inbound message follows the same
     * agent-trigger / reply-write-back pipeline.
     *
     * [channelKey] is the [ImGatewayConfig.effectiveChannelId] used to key per-channel state.
     */
    private suspend fun feedInboundBatch(
        channelKey: String,
        channel: MessageChannel,
        conversation: ImConversation,
        inbox: List<ImMessage>,
    ) {
        val state = channelState(channelKey)

        // Only answer the conversation that this channel was configured for (multi-channel guard).
        if (state.platform.isNotBlank() && state.platform != channel.channelId) {
            DebugLog.d("ImPolling", "Platform mismatch, skipping ${conversation.id}")
            return
        }

        // 微信 iLink 专属扩展：跨重启恢复 + 发送输入状态。
        val weixin = channel as? WeixinCompanionChannel
        if (weixin != null && state.contextTokens.isNotEmpty()) {
            // H44: 只在首次启动（contextTokenStore 为空）时才 seed 持久化 token，
            // 不在每次 feedInboundBatch 时 seed——否则会用旧 token 覆盖 applyUpdates 刚写入的新 token，
            // 导致回复用旧 token 被服务端拒绝。参考 weixin-ClawBot-API。
            val current = weixin.contextTokensSnapshot()
            if (current.isEmpty()) {
                DebugLog.d("ImPolling", "seed ${state.contextTokens.size} context tokens into $channelKey")
                weixin.seedContextTokens(state.contextTokens)
            }
        }
        // 注意：不持久化 sync_buf 游标。游标仅在内存（WeixinChannel.ChannelState）中维护，
        // 重启/重新绑定后重置为空，从当前时刻开始拉取。若把旧游标持久化并在重绑后 seed，
        // 会导致 get_updates 使用失效游标一直返回空，bot 收不到任何消息（v1.0.27 回归根因，
        // 与 v1.0.23 的正常行为一致）。

        val lxchatConvId = state.conversationBindings[conversation.id]
            ?: bindConversation(channelKey, channel, conversation)

        // Reserve every inbound message id up front so a concurrent poll cannot re-handle it,
        // then merge the batch of new messages into one agent turn (fewer, more coherent replies
        // when a contact sends several lines in quick succession).
        val fresh = inbox.filter { message ->
            var seen = false
            store.updateChannelState(channelKey) { s ->
                seen = s.seenMessageIds.contains(message.id)
                s.copy(seenMessageIds = (s.seenMessageIds + message.id).takeLast(MAX_SEEN))
            }
            !seen
        }
        // 诊断：若上一条 "feedInboundBatch" 日志缺失，说明卡在 bindConversation/seenMessageIds（DB）。
        DebugLog.d("ImPolling", "feedInboundBatch: ${fresh.size} fresh msgs for conv=${conversation.id}, lxchatConv=$lxchatConvId")
        if (fresh.isEmpty()) return
        onMessageHandled?.invoke(conversation.id)

        // 持久化最新 context_token 快照到运行时状态，供 App 重启后恢复
        // （入站消息在 poll 阶段就已写入 channel 的 contextTokenStore）。
        if (weixin != null) {
            val tokens = weixin.contextTokensSnapshot()
            if (tokens.isNotEmpty() && tokens != state.contextTokens) {
                store.updateChannelState(channelKey) { s -> s.copy(contextTokens = tokens) }
            }
        }

        // Merge the batch of new messages into one synthetic inbound message so the private
        // runOnce overload (which expects an ImMessage for id/timestamp tracking) can be reused.
        // 每条消息的图片 URL 以 Markdown 链接形式附加到文本前，让支持视觉的模型识别图片，
        // 不支持视觉的模型也能读到 URL 知道有图（见 buildPromptText）。
        val merged = fresh.first().copy(text = fresh.joinToString("\n") { buildPromptText(it) })

        // ── 命令处理 ──────────────────────────────────────────
        // 以 '/' 开头的消息交给 ImCommandProcessor 处理；非命令走正常 AI 回复流程。
        // /steer 是例外：它返回补充指令文本，需要作为用户消息发送给当前会话。
        if (commandProcessor != null) {
            val cmdResult = try {
                commandProcessor.process(merged.text, channelKey, conversation.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                DebugLog.e("ImPolling", "command processing failed", e)
                null
            }
            if (cmdResult != null) {
                if (cmdResult.mediaAction != null) {
                    // 媒体发送命令（/sendimage /sendfile /forward）：用微信渠道执行，结果回文本。
                    val mediaReply = executeMediaAction(weixin, conversation.id, cmdResult.mediaAction)
                    if (mediaReply.isNotBlank()) {
                        segmentSender.send(channel, conversation.id, mediaReply)
                    }
                } else if (cmdResult.isSteer && !cmdResult.steerText.isNullOrBlank()) {
                    // /steer：将补充指令作为用户消息走正常 AI 回复流程。
                    val steerMessage = merged.copy(text = cmdResult.steerText)
                    val reply = replyFromAgent(weixin, channelKey, conversation.id, lxchatConvId, steerMessage)
                    if (!reply.isNullOrBlank()) {
                        segmentSender.send(channel, conversation.id, reply)
                    }
                } else if (cmdResult.replyText.isNotBlank()) {
                    // 普通命令：直接回复命令结果，不触发 AI。
                    segmentSender.send(channel, conversation.id, cmdResult.replyText)
                }
                return
            }
        }

        // ── 普通 AI 回复流程 ──────────────────────────────────
        // 按好友 AI 开关（对齐 Zyn is_ai_enabled_for_user）：被 /ai off 关闭的好友
        // 不再自动回复，但命令（如 /ai on）已在上方处理，可随时恢复。
        if (state.aiDisabledContacts.contains(conversation.id)) {
            DebugLog.d("ImPolling", "Auto-reply disabled for ${conversation.id}, skip")
            return
        }
        // 对齐 Zyn-iLink 的 ai_cooldown：同一好友短时间连发时，冷却期内本轮不回复，
        // 避免触发一串 AI 请求互相打断。消息已在 seen 集合标记，冷却后不会重试。
        val coolKey = "$channelKey\u0000${conversation.id}"
        val now = System.currentTimeMillis()
        val lastReply = lastAiReplyAt[coolKey] ?: 0L
        if (now - lastReply < AI_COOLDOWN_MS) {
            DebugLog.d("ImPolling", "AI cooldown for ${conversation.id}, skip reply")
            return
        }
        lastAiReplyAt[coolKey] = now
        try {
            val reply = replyFromAgent(weixin, channelKey, conversation.id, lxchatConvId, merged)
            if (!reply.isNullOrBlank()) {
                // Long replies are split into several short messages for readability.
                segmentSender.send(channel, conversation.id, reply)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // AI 回复或发送失败不应导致 pollLoop 崩溃
            DebugLog.e("ImPolling", "reply/send failed for conv=${conversation.id}", e)
        }
    }

    /**
     * 跑一轮 agent 生成回复；对微信渠道在生成前后发送"正在输入"状态
     * （status=1 生成中 / status=2 完成），提升对方体验。失败也以 status=2 收尾。
     */
    private suspend fun replyFromAgent(
        weixin: WeixinCompanionChannel?,
        channelKey: String,
        convRemoteId: String,
        lxchatConvId: String,
        message: ImMessage,
    ): String? {
        // 非微信渠道不涉及输入状态，直接跑 agent（带硬超时，防止挂起阻塞轮询）。
        if (weixin == null) return runWithAgentTimeout(channelKey, lxchatConvId, message)
        weixin.sendTyping(convRemoteId, 1)
        // 长回复（看图 / 多轮思考）可能超过微信"正在输入"提示的存活时间，故在生成期间
        // 周期性重发 status=1 保活（参考 AstrBot weixin_oc 的 typing 心跳），
        // 生成结束（含失败）一律以 status=2 收尾。每 10s 一次轻量 POST，且 typing_ticket
        // 已有 30min TTL 缓存，性能开销可忽略。
        return coroutineScope {
            val keepalive = launch { typingKeepalive(weixin, convRemoteId) }
            try {
                runWithAgentTimeout(channelKey, lxchatConvId, message)
            } finally {
                keepalive.cancel()
                weixin.sendTyping(convRemoteId, 2)
            }
        }
    }

    /** 带硬超时跑一轮 agent 回复；挂起/超时返回 null，下周期自动重试，不卡死轮询。 */
    private suspend fun runWithAgentTimeout(
        channelKey: String,
        lxchatConvId: String,
        message: ImMessage,
    ): String? = try {
        withTimeout(AGENT_REPLY_TIMEOUT_MS) {
            runOnce(channelKey, lxchatConvId, message)
        }
    } catch (e: TimeoutCancellationException) {
        DebugLog.w("ImPolling", "agent reply timed out for conv=$lxchatConvId after ${AGENT_REPLY_TIMEOUT_MS}ms")
        null
    }

    /** 生成期间周期性下发"正在输入"状态，直到被 [replyFromAgent] 取消。 */
    private suspend fun typingKeepalive(weixin: WeixinCompanionChannel, convRemoteId: String) {
        while (true) {
            try {
                delay(TYPING_KEEPALIVE_INTERVAL_MS)
            } catch (e: CancellationException) {
                return
            }
            // sendTyping 内部已 try/catch 兜底，这里失败不影响主流程。
            weixin.sendTyping(convRemoteId, 1)
        }
    }

    private suspend fun bindConversation(
        channelKey: String,
        channel: MessageChannel,
        conversation: ImConversation,
    ): String {
        val created = conversationRepository.createConversation(
            title = "IM · ${conversation.title}",
            systemPromptId = null,
            modelId = null,
        )
        store.updateChannelState(channelKey) { s ->
            s.copy(
                conversationBindings = s.conversationBindings + (conversation.id to created),
                platform = channel.channelId,
                channelId = channelKey,
            )
        }
        return created
    }

    private suspend fun runOnce(channelKey: String, lxchatConvId: String, message: ImMessage): String? {
        val config = configForChannel(channelKey)
        val result = taskEngine.runOnce(
            conversationId = lxchatConvId,
            userText = message.text,
            modelId = config?.autoReplyModel?.ifBlank { null },
        )
        return when (result) {
            is TaskExecutionEngine.Result.Success -> result.text
            is TaskExecutionEngine.Result.Busy -> {
                DebugLog.d("ImPolling", "Conversation busy; retry on next cycle (seen=${message.id})")
                null
            }
            is TaskExecutionEngine.Result.Failure -> {
                DebugLog.e("ImPolling", "runOnce failed: ${result.reason}")
                null
            }
        }
    }

    /**
     * 把 [message] 的图片 URL 和文本拼装成发给 AI 的提示文本。
     *
     * 图片以 Markdown 链接形式 `[图片N](url)` 列在文本前，便于支持视觉的模型识别；
     * 不支持视觉的模型也会把 URL 当作普通文本读到，至少能告知用户图片存在。
     * 没有图片时直接返回原始文本，零开销。空 URL 会被跳过。
     *
     * 格式示例（两张图 + 文本"看下这张"）：
     * ```
     * [图片1](https://.../a.jpg) [图片2](https://.../b.png) 看下这张
     * ```
     * 仅有图片无文本时，附加默认提示 [DEFAULT_IMAGE_PROMPT] 引导模型分析图片。
     */
    private fun buildPromptText(message: ImMessage): String {
        if (message.images.isEmpty()) return message.text
        val imageMarkdown = buildString {
            message.images.forEachIndexed { index, url ->
                if (url.isBlank()) return@forEachIndexed
                if (isNotEmpty()) append(' ')
                append("[图片").append(index + 1).append("](").append(url).append(')')
            }
        }
        if (imageMarkdown.isEmpty()) return message.text
        return when {
            message.text.isBlank() -> "$imageMarkdown $DEFAULT_IMAGE_PROMPT"
            else -> "$imageMarkdown ${message.text}"
        }
    }

    // ── 媒体发送命令执行 ──────────────────────────────────────────────────

    /**
     * 执行媒体发送动作（`/sendimage` `/sendfile` `/forward`）并返回给用户的提示文本。
     * 仅微信 iLink 渠道支持；其余渠道回"不支持"提示。
     */
    private suspend fun executeMediaAction(
        weixin: WeixinCompanionChannel?,
        conversationId: String,
        action: CommandResult.MediaAction,
    ): String {
        if (weixin == null) {
            return "当前渠道不支持发送图片/文件/转发。（仅微信 iLink 支持）"
        }
        return try {
            when (action) {
                is CommandResult.MediaAction.SendImage -> {
                    if (action.url.isBlank()) "用法：/sendimage <图片URL>"
                    else mediaResultText("图片", weixin.sendImageUrl(conversationId, action.url))
                }
                is CommandResult.MediaAction.SendFile -> {
                    if (action.url.isBlank()) "用法：/sendfile <文件URL>"
                    else mediaResultText("文件", weixin.sendFileUrl(conversationId, action.url))
                }
                is CommandResult.MediaAction.Forward -> {
                    if (action.name.isBlank()) {
                        val names = weixin.cachedMediaNames()
                        if (names.isEmpty()) {
                            "暂无已缓存媒体。先让好友发送图片/文件，或用 /sendimage /sendfile 发送直链。"
                        } else {
                            "可转发媒体（/forward 名称）：\n" + names.joinToString("\n")
                        }
                    } else {
                        mediaResultText("媒体", weixin.forwardMedia(conversationId, action.name))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            DebugLog.e("ImPolling", "media action failed", e)
            "媒体操作失败：${e.message ?: "未知错误"}"
        }
    }

    /** 把媒体发送结果转为给用户的提示文本。 */
    private fun mediaResultText(kind: String, result: ImSendResult): String = when (result) {
        is ImSendResult.Success -> "${kind}已发送。"
        is ImSendResult.Failure -> "${kind}发送失败：${result.reason}"
        ImSendResult.NotConfigured -> "微信渠道未配置，无法发送${kind}。"
    }

    // ── State / config resolution (multi-channel with legacy fallback) ──

    /**
     * Read the runtime state for [channelKey]. Prefers the multi-channel map; falls back to
     * the legacy single-config state when this is the primary channel, so pre-multi-bot
     * setups keep their seen-set and bindings without migration.
     */
    private suspend fun channelState(channelKey: String): ImRuntimeState {
        val multi = store.multiRuntimeState.first()
        multi[channelKey]?.let { return it }
        val legacy = store.runtimeState.first()
        return if (legacy.channelId.isBlank() && (legacy.platform == channelKey || legacy.platform.isBlank())) {
            legacy
        } else {
            // platform 留空：channelKey 是渠道实例 ID（如 "wechat:uuid"），不是平台 ID（"wechat"）。
            // 留空让 feedInboundBatch 的 platform mismatch 检查通过，bindConversation 后会被正确设为 channel.channelId。
            ImRuntimeState(channelId = channelKey, platform = "")
        }
    }

    /** Resolve the [ImGatewayConfig] backing [channelKey], or null to use app-default model. */
    private suspend fun configForChannel(channelKey: String): ImGatewayConfig? {
        val multi = store.multiConfig.first()
        multi.all.firstOrNull { it.effectiveChannelId == channelKey }?.let { return it }
        val legacy = store.config.first()
        return if (legacy.effectiveChannelId == channelKey) legacy else null
    }

    /** Polling interval: first polling bot's interval, else legacy single-config interval. */
    private suspend fun currentPollInterval(): Long {
        val multi = store.multiConfig.first()
        val polling = multi.all.firstOrNull { !ImPlatform.isPush(it.platform) && it.enabled }
        if (polling != null) return polling.pollIntervalMs.coerceAtLeast(1_000L)
        return store.config.first().pollIntervalMs.coerceAtLeast(1_000L)
    }

    private companion object {
        const val MAX_SEEN = 2_000
        /**
         * 按好友 AI 回复冷却（毫秒），对齐 Zyn-iLink 的 ai_cooldown：
         * 同一好友在此窗口内只回复一次，防止连发触发一串 AI 请求互相打断。
         */
        const val AI_COOLDOWN_MS = 5_000L
        /** "正在输入"保活重发间隔（毫秒）：长回复超过微信提示存活时间时维持状态显示。
         *  仅微信渠道在 agent 生成期间生效，之后立即取消。 */
        const val TYPING_KEEPALIVE_INTERVAL_MS = 10_000L
        /**
         * IM 触发单轮 agent 回复的硬超时（毫秒）。轮询与回复在同一顺序协程里跑，
         * 若模型请求挂起而无超时，会把整个轮询循环卡死（其余渠道不再 getUpdates）。
         * 超时后本轮不回复、下一轮重新拉取时自动重试。比单次提示的生成期望时长略宽。
         */
        const val AGENT_REPLY_TIMEOUT_MS = 300_000L

        /**
         * 仅含图片、无文本时附加的默认提示，引导模型分析图片。
         * 与 dsh-im `image-prompt.mjs` 的 `DEFAULT_IMAGE_PROMPT` 保持一致。
         */
        const val DEFAULT_IMAGE_PROMPT = "请分析这张图片。"
    }
}
