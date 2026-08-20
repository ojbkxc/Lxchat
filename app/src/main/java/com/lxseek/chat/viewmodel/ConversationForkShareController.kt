package com.lxseek.chat.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Adapts current-conversation fork/share UI intents to typed service outcomes. */
internal class ConversationForkShareController(
    private val currentConversationId: StateFlow<String?>,
    private val service: ConversationForkShareService,
    private val scope: CoroutineScope,
    private val onConversationForked: (String) -> Unit,
    private val onShareReady: suspend (String) -> Unit,
    private val forkFailureText: (String) -> String,
    private val shareFailureText: (String) -> String,
    private val onFailure: suspend (String) -> Unit,
) {
    fun fork(messageId: String? = null) {
        val conversationId = currentConversationId.value ?: return
        scope.launch {
            when (val result = service.fork(conversationId, messageId)) {
                is ConversationForkShareService.ForkResult.Success ->
                    onConversationForked(result.conversationId)
                is ConversationForkShareService.ForkResult.Failure ->
                    onFailure(forkFailureText(result.reason))
            }
        }
    }

    fun shareConversation() {
        share { conversationId -> service.shareAll(conversationId) }
    }

    fun shareGeneration(assistantMessageId: String) {
        share { conversationId -> service.shareRun(conversationId, assistantMessageId) }
    }

    fun shareMessages(messageIds: Set<String>) {
        if (messageIds.isEmpty()) return
        share { conversationId -> service.shareMessages(conversationId, messageIds) }
    }

    private fun share(
        load: suspend (conversationId: String) -> ConversationForkShareService.ShareResult,
    ) {
        val conversationId = currentConversationId.value ?: return
        scope.launch {
            when (val result = load(conversationId)) {
                is ConversationForkShareService.ShareResult.Success -> onShareReady(result.text)
                is ConversationForkShareService.ShareResult.Failure ->
                    onFailure(shareFailureText(result.reason))
            }
        }
    }
}
