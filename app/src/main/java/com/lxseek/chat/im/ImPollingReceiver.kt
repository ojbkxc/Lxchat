package com.lxseek.chat.im

import com.lxseek.chat.automation.TaskExecutionEngine
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    }

    // ── Polling path ──────────────────────────────────────────

    /**
     * Self-healing poll loop: re-reads the bridge's channel map each cycle, polls every
     * configured non-push channel, and pauses when none are active. Push channels are
     * handled by [startPushListeners] and deliberately skipped here.
     */
    private suspend fun pollLoop() {
        while (true) {
            coroutineContext.ensureActive()
            val pollingChannels = bridge.channels().entries
                .filter { it.value.isConfigured && it.value !is PushMessageChannel }
            val interval = currentPollInterval()
            if (pollingChannels.isEmpty()) {
                delay(interval)
                continue
            }
            for ((channelKey, channel) in pollingChannels) {
                coroutineContext.ensureActive()
                pollChannel(channelKey, channel)
            }
            delay(interval)
        }
    }

    private suspend fun pollChannel(channelKey: String, channel: MessageChannel) {
        val conversations = try {
            channel.listConversations()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e("ImPolling", "listConversations failed for $channelKey", e)
            emptyList()
        }
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
        } catch (e: Exception) {
            DebugLog.e("ImPolling", "fetchMessages failed for ${conversation.id}", e)
            emptyList()
        }
        val inbox = messages.filter { it.direction == ImMessageDirection.INCOMING }
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
        if (fresh.isEmpty()) return
        onMessageHandled?.invoke(conversation.id)

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
            } catch (e: Exception) {
                DebugLog.e("ImPolling", "command processing failed", e)
                null
            }
            if (cmdResult != null) {
                if (cmdResult.isSteer && !cmdResult.steerText.isNullOrBlank()) {
                    // /steer：将补充指令作为用户消息走正常 AI 回复流程。
                    val steerMessage = merged.copy(text = cmdResult.steerText)
                    val reply = runOnce(channelKey, lxchatConvId, steerMessage)
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
        val reply = runOnce(channelKey, lxchatConvId, merged)
        if (!reply.isNullOrBlank()) {
            // Long replies are split into several short messages for readability.
            segmentSender.send(channel, conversation.id, reply)
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
            ImRuntimeState(channelId = channelKey, platform = channelKey)
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
         * 仅含图片、无文本时附加的默认提示，引导模型分析图片。
         * 与 dsh-im `image-prompt.mjs` 的 `DEFAULT_IMAGE_PROMPT` 保持一致。
         */
        const val DEFAULT_IMAGE_PROMPT = "请分析这张图片。"
    }
}
