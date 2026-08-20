package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.util.Constants

/**
 * Freezes the nearest real USER ancestor before a destructive subtree mutation.
 *
 * Tool/result protocol rows can also use Participant.USER, so they are traversed but never selected
 * as a visible scroll destination. The visited set makes corrupted legacy cycles fail closed.
 */
internal fun nearestUserAncestorId(
    messages: List<ChatMessage>,
    messageId: String,
): String? {
    val byId = messages.associateBy(ChatMessage::id)
    var parentId = byId[messageId]?.parentId
    val visited = hashSetOf<String>()
    while (parentId != null && visited.add(parentId)) {
        val parent = byId[parentId] ?: return null
        if (
            parent.participant == Participant.USER &&
            !parent.id.startsWith(Constants.TOOL_MSG_PREFIX) &&
            !parent.id.startsWith(Constants.RESULT_MSG_PREFIX)
        ) {
            return parent.id
        }
        parentId = parent.parentId
    }
    return null
}

/**
 * Chooses the covered jump-cut destination after deleting a structural message subtree.
 *
 * A real USER message is one edit branch among siblings. If another sibling survives, keep the
 * viewport at that branch level; only deleting the last sibling may fall back to the nearest real
 * USER ancestor. Other message types retain the historical nearest-USER-ancestor behavior.
 */
internal fun deleteSettlementTargetMessageId(
    messagesBeforeDelete: List<ChatMessage>,
    deletedRootMessageId: String,
    remainingPath: List<ChatMessage>,
): String? {
    val deletedRoot = messagesBeforeDelete.firstOrNull { it.id == deletedRootMessageId }
    if (deletedRoot?.isRealUserMessage() == true) {
        remainingPath.firstOrNull { message ->
            message.isRealUserMessage() && message.parentId == deletedRoot.parentId
        }?.let { survivingSibling ->
            return survivingSibling.id
        }
    }

    return nearestUserAncestorId(messagesBeforeDelete, deletedRootMessageId)
        ?.takeIf { ancestorId -> remainingPath.any { it.id == ancestorId } }
        ?: remainingPath.firstOrNull(ChatMessage::isRealUserMessage)?.id
}

private fun ChatMessage.isRealUserMessage(): Boolean =
    participant == Participant.USER &&
        !id.startsWith(Constants.TOOL_MSG_PREFIX) &&
        !id.startsWith(Constants.RESULT_MSG_PREFIX)

data class ConversationUiState(
    val path: List<ChatMessage> = emptyList(),
    val allMessages: List<ChatMessage> = emptyList(),
    val streamingMsg: ChatMessage? = null,
    val isLoading: Boolean = false,
    val selectedChildren: Map<String?, String> = emptyMap()
) {
    companion object {
        /** Walk the conversation tree to produce the visible path. */
        fun resolvePath(
            allMessages: List<ChatMessage>,
            streamingMsg: ChatMessage?,
            selectedChildren: Map<String?, String>
        ): List<ChatMessage> {
            val path = mutableListOf<ChatMessage>()
            // An intervention is durable as soon as Send succeeds, but while the current model
            // Pass is still on screen it belongs exclusively to the queue banner. Advancing the
            // visible message path here would make the live model cease to be the last message,
            // incorrectly rendering its in-flight status as a terminal warning. Once the Pass
            // releases (or Stop/recovery clears the overlay), the durable input becomes visible.
            val messagesForPath = if (streamingMsg != null) {
                allMessages.filterNot { message ->
                    isPendingVisibleIntervention(
                        message = message,
                        streamingRunId = streamingMsg.runId,
                    )
                }
            } else {
                allMessages
            }
            val messagesByParent = messagesForPath.groupBy { it.parentId }
                .mapValues { (_, list) -> list.sortedBy { it.timestamp } }
            var cursor: String? = null

            while (true) {
                val siblings = messagesByParent[cursor] ?: break
                if (siblings.isEmpty()) break

                val selectedId = selectedChildren[cursor]
                val visibleSiblings = siblings.filterNot(::isSynthetic)
                var selected = if (visibleSiblings.isNotEmpty()) {
                    visibleSiblings.find { it.id == selectedId } ?: visibleSiblings.last()
                } else {
                    siblings.find { it.id == selectedId } ?: siblings.last()
                }
                // Substitute streaming message if it matches
                if (streamingMsg != null && selected.id == streamingMsg.id) {
                    selected = streamingMsg
                }
                val isSynthetic = isSynthetic(selected)
                if (!isSynthetic || (streamingMsg != null && selected.id == streamingMsg.id)) {
                    path.add(selected)
                }
                cursor = selected.id
            }
            // Append streaming message if not yet in path
            if (streamingMsg != null && path.none { it.id == streamingMsg.id }) {
                val lastId = path.lastOrNull()?.id
                if (streamingMsg.parentId == lastId || (streamingMsg.parentId == null && path.isEmpty())) {
                    path.add(streamingMsg)
                }
            }
            return path
        }

        /**
         * Only a real user intervention can be queue-only while a Pass is streaming. Tool-result
         * rows also use Participant.USER and consumedAtPass=null, but they are durable protocol
         * edges: filtering one severs every visible descendant after that tool round. Pending
         * inputs from an older terminal Run must remain visible while a newer Run streams; hiding
         * them would sever the newer Run's complete parent path.
         */
        private fun isPendingVisibleIntervention(
            message: ChatMessage,
            streamingRunId: String?,
        ): Boolean =
            message.participant == Participant.USER &&
                !isSynthetic(message) &&
                !streamingRunId.isNullOrBlank() &&
                message.runId == streamingRunId &&
                message.consumedAtPass == null

        private fun isSynthetic(message: ChatMessage): Boolean =
            message.id.startsWith(Constants.TOOL_MSG_PREFIX) ||
                message.id.startsWith(Constants.RESULT_MSG_PREFIX)
    }
}
