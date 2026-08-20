package com.lxseek.chat.viewmodel

import com.lxseek.chat.automation.TaskExecutionEngine.BridgeOutcome
import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.model.MessageStatus
import kotlinx.coroutines.flow.StateFlow

internal typealias ForegroundSendBridge = suspend (
    conversationId: String,
    userText: String,
    modelId: String,
) -> BridgeOutcome

/** Owns one ViewModel-scoped foreground automation bridge registration. */
internal class ForegroundAutomationBridgeController(
    private val currentConversationId: StateFlow<String?>,
    private val send: suspend (
        conversationId: String,
        userText: String,
        modelId: String,
    ) -> AutomationSendOutcome,
    private val loadMessages: suspend (String) -> List<MessageEntity>,
    private val attach: (owner: Any, bridge: ForegroundSendBridge) -> Unit,
    private val detach: (owner: Any) -> Unit,
) : AutoCloseable {
    private val owner = Any()
    private var attached = false

    @Synchronized
    fun start() {
        if (attached) return
        attach(owner, ::sendIfVisible)
        attached = true
    }

    @Synchronized
    override fun close() {
        if (!attached) return
        detach(owner)
        attached = false
    }

    private suspend fun sendIfVisible(
        conversationId: String,
        userText: String,
        modelId: String,
    ): BridgeOutcome {
        if (currentConversationId.value != conversationId) {
            return BridgeOutcome.NotDelegated
        }
        val delivered = when (
            val outcome = send(conversationId, userText, modelId)
        ) {
            AutomationSendOutcome.SlotBusy -> return BridgeOutcome.Busy()
            is AutomationSendOutcome.Delivered -> outcome
        }
        // Resolve the exact row created by this Send. A tail lookup can race branch changes or
        // guidance draining and incorrectly attribute an older assistant row to this Loop cycle.
        val modelMessage = loadMessages(conversationId)
            .find { it.id == delivered.modelMessageId }
            ?: return BridgeOutcome.Failed("Generation row disappeared")
        return if (modelMessage.status == MessageStatus.SUCCESS) {
            BridgeOutcome.Completed(modelMessage.id, modelMessage.text)
        } else {
            BridgeOutcome.Failed(
                modelMessage.text.takeIf { it.isNotBlank() } ?: "Generation failed",
            )
        }
    }
}
