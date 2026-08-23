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
        val merged = fresh.first().copy(text = fresh.joinToString("\n") { it.text })
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
    }
}
