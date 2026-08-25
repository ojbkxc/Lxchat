package com.lxseek.chat.im

import com.lxseek.chat.automation.TaskExecutionEngine
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.util.DebugLog
import java.time.LocalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Proactive IM messaging (default OFF). While [ImGatewayConfig.proactiveEnabled] is true, a
 * lightweight loop periodically inspects the bound IM conversations and greets a contact that has
 * been idle longer than [ImGatewayConfig.proactiveIdleMinutes], mirroring the reference python
 * bot's "inactive user" checker but with the same safety rails it requires:
 *
 * - default OFF (explicit opt-in),
 * - a quiet window suppresses greetings,
 * - group chats are skipped by default.
 *
 * The greeting is generated headlessly through [TaskExecutionEngine.runOnce] on the contact's bound
 * Lxchat conversation (the same path used for automatic replies) and written back via the channel,
 * so proactive and reactive messages share one generation pipeline and session trust state.
 */
class ProactiveMessagingService(
    private val bridge: ImBridgeService,
    private val store: ImGatewayStore,
    private val conversationRepository: ConversationRepository,
    private val taskEngine: TaskExecutionEngine,
    private val scope: CoroutineScope,
    /** Splits long proactive greetings into several short messages before writing them back. */
    private val segmentSender: MultiSegmentMessageSender = MultiSegmentMessageSender(),
    private val clock: () -> LocalTime = { LocalTime.now() },
) {
    private var job: Job? = null

    /** conversationId ("wechat:<id>") -> epoch millis of last proactive greeting / activity seen. */
    private val lastSeenActive = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Start the proactive loop once; it self-heals on config changes (idles when disabled). */
    fun start() {
        if (job != null) return
        job = scope.launch(Dispatchers.Default) {
            while (currentCoroutineContext().isActive) {
                try {
                    // P1-7: 遍历所有渠道，对每个 proactiveEnabled 的渠道独立扫描，
                    // 不再用 store.config.first() / bridge.currentChannel() 只处理第一个渠道。
                    // （参考 Lxchat 多渠道架构 ImBridgeService.buildChannels）
                    val multi = store.multiConfig.first()
                    val legacy = store.config.first()
                    val configs = if (multi.all.isEmpty()) {
                        if (legacy.isConfigured) listOf(legacy) else emptyList()
                    } else {
                        multi.all.filter { it.isConfigured && it.proactiveEnabled }
                    }
                    if (configs.isEmpty()) {
                        lastSeenActive.clear()
                    } else {
                        for (config in configs) {
                            currentCoroutineContext().ensureActive()
                            scan(config)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLog.e("ProactiveMsg", "proactive scan failed", e)
                }
                delay(SCAN_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Notify that real activity happened on [conversationId], postponing any proactive greeting. */
    fun markActive(conversationId: String) {
        lastSeenActive[conversationId] = System.currentTimeMillis()
    }

    private suspend fun scan(config: ImGatewayConfig) {
        // P1-7: 按 config.effectiveChannelId 找对应渠道，不再用 bridge.currentChannel()。
        // 没有 sendMessage 能力的渠道（PushMessageChannel 等不支持 proactive 的）跳过。
        val channel = bridge.channelFor(config.effectiveChannelId) ?: run {
            DebugLog.d("ProactiveMsg", "scan: no channel for ${config.effectiveChannelId}, skip")
            return
        }
        if (!channel.isConfigured) return
        val now = System.currentTimeMillis()
        val thresholdMs = config.proactiveIdleMinutes.coerceAtLeast(5) * 60_000L
        // P1-7: 按渠道 id 取该渠道的运行时绑定，避免多渠道串台。
        val state = store.multiRuntimeState.first()[config.effectiveChannelId]
            ?: store.runtimeState.first().takeIf { it.channelId.isBlank() }
            ?: ImRuntimeState()
        val bindings = state.conversationBindings

        channel.listConversations().forEach { conversation ->
            currentCoroutineContext().ensureActive()
            // Skip group chats when configured to.
            if (config.proactiveIgnoreGroups && conversation.isGroup) return@forEach
            bindings[conversation.id] ?: return@forEach

            val lastActive = lastSeenActive[conversation.id]
            if (lastActive == null) {
                // First observation: record baseline but do not greet immediately.
                lastSeenActive[conversation.id] = now
                return@forEach
            }
            if (now - lastActive < thresholdMs) return@forEach
            if (inQuietWindow(config.proactiveSilentStart, config.proactiveSilentEnd)) return@forEach

            greet(channel, conversation, bindings.getValue(conversation.id), config)
            lastSeenActive[conversation.id] = now
        }
    }

    private suspend fun greet(
        channel: MessageChannel,
        conversation: ImConversation,
        lxchatConvId: String,
        config: ImGatewayConfig,
    ) {
        DebugLog.d("ProactiveMsg", "Greeting idle contact ${conversation.title} on ${config.effectiveChannelId}")
        val result = taskEngine.runOnce(
            conversationId = lxchatConvId,
            userText = PROACTIVE_TRIGGER,
            modelId = config.autoReplyModel.ifBlank { null },
        )
        when (result) {
            is TaskExecutionEngine.Result.Success -> {
                val reply = result.text.trim()
                if (reply.isNotEmpty()) {
                    // P1-7: 用传入的 channel 发送，不再 bridge.currentChannel()。
                    // Long greetings are split into several short messages for readability.
                    segmentSender.send(channel, conversation.id, reply)
                }
            }
            is TaskExecutionEngine.Result.Busy ->
                DebugLog.d("ProactiveMsg", "Conversation busy; deferring greeting")
            is TaskExecutionEngine.Result.Failure ->
                DebugLog.e("ProactiveMsg", "greeting failed: ${result.reason}")
        }
    }

    /** True when [now] falls inside the configured HH:MM quiet window (if any is set). */
    private fun inQuietWindow(start: String, end: String): Boolean {
        val s = parseHm(start) ?: return false
        val e = parseHm(end) ?: return false
        val now = clock()
        return if (s <= e) now in s..e else now >= s || now <= e
    }

    private fun parseHm(v: String): LocalTime? = runCatching {
        val (h, m) = v.trim().split(":")
        LocalTime.of(h.toInt(), m.toInt())
    }.getOrNull()

    private companion object {
        const val SCAN_INTERVAL_MS = 60_000L
        const val PROACTIVE_TRIGGER = "[proactive] The user has been idle; send a short, natural greeting " +
            "or check-in unprompted. Keep it brief and appropriate for the relationship."
    }
}