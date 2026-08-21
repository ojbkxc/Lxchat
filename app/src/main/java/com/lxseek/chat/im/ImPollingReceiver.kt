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
 * Background receiver that closes the IM loop: it polls the active [MessageChannel] for inbound
 * messages, routes each one to a bound Lxchat conversation, runs a headless generation through
 * [TaskExecutionEngine.runOnce], and writes the assistant reply back to the remote thread.
 *
 * Mirrors the dsh-im architecture (`#poll` loop + `conversation-state-store` binding + agent
 * trigger + reply write-back), translated onto Lxchat's existing plumbing:
 *  - polling interval comes from [ImGatewayConfig.pollIntervalMs]
 *  - IM conversation <-> Lxchat conversation bindings and a seen-message set are persisted in
 *    [ImGatewayStore] so the process can resume exactly where it left off after a restart.
 */
class ImPollingReceiver(
    private val bridge: ImBridgeService,
    private val taskEngine: TaskExecutionEngine,
    private val conversationRepository: ConversationRepository,
    private val store: ImGatewayStore,
    private val scope: CoroutineScope,
    /** Optional callback fired after an inbound message is successfully replied to, e.g. to mark
     *  the conversation as active so proactive messaging doesn't greet a contact that just spoke. */
    private val onMessageHandled: ((conversationId: String) -> Unit)? = null,
) {
    @Volatile
    private var job: Job? = null

    /** Start (or restart) the background poll loop. The loop is self-healing: it re-reads the
     *  bridge's current channel each cycle, so it smoothly begins polling as soon as the gateway
     *  becomes configured and pauses when it is disabled. */
    fun start() {
        stop()
        job = scope.launch(Dispatchers.Default) {
            pollLoop()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun pollLoop() {
        while (true) {
            coroutineContext.ensureActive()
            val channel = bridge.currentChannel()
            val config = store.config.first()
            val interval = config.pollIntervalMs.coerceAtLeast(1_000L)
            if (channel == null || !channel.isConfigured || !config.enabled) {
                delay(interval)
                continue
            }
            try {
                val conversations = try {
                    channel.listConversations()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLog.e("ImPolling", "listConversations failed", e)
                    emptyList()
                }
                for (conversation in conversations) {
                    coroutineContext.ensureActive()
                    pollConversation(channel, conversation)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.e("ImPolling", "Poll cycle failed", e)
            }
            delay(interval)
        }
    }

    private suspend fun pollConversation(channel: MessageChannel, conversation: ImConversation) {
        val initial = store.runtimeState.first()
        // Only answer the conversation that this gateway was configured for.
        if (initial.platform.isNotBlank() && initial.platform != channel.channelId) {
            DebugLog.d("ImPolling", "Platform mismatch, skipping ${conversation.id}")
            return
        }
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

        val lxchatConvId = initial.conversationBindings[conversation.id]
            ?: bindConversation(channel, conversation)

        // Reserve every inbound message id up front so a concurrent poll cannot re-handle it,
        // then merge the batch of new messages into one agent turn (fewer, more coherent replies
        // when a contact sends several lines in quick succession).
        val fresh = inbox.filter { message ->
            var seen = false
            store.updateState { s ->
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
        val reply = runOnce(lxchatConvId, merged)
        if (!reply.isNullOrBlank()) {
            channel.sendMessage(conversation.id, reply)
        }
    }

    private suspend fun bindConversation(channel: MessageChannel, conversation: ImConversation): String {
        val created = conversationRepository.createConversation(
            title = "IM · ${conversation.title}",
            systemPromptId = null,
            modelId = null,
        )
        store.updateState { s ->
            s.copy(
                conversationBindings = s.conversationBindings + (conversation.id to created),
                platform = channel.channelId,
            )
        }
        return created
    }

    private suspend fun runOnce(lxchatConvId: String, message: ImMessage): String? {
        val config = store.config.first()
        val result = taskEngine.runOnce(
            conversationId = lxchatConvId,
            userText = message.text,
            modelId = config.autoReplyModel.ifBlank { null },
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

    private companion object {
        const val MAX_SEEN = 2_000
    }
}