package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.local.ChatEntity
import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.data.local.RunEntity
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.RunEffect
import com.lxseek.chat.model.RunStatus

/**
 * Exact durable boundary shared by foreground and headless ordinary sends.
 *
 * The runtime has already emitted `PersistAcceptedInput` before this writer runs. This class owns
 * only the corresponding Room graph effect: resolve the selected leaf, create one Run with its
 * USER boundary and MODEL placeholder, and publish both selected edges in the same transaction.
 * It does not claim a runtime slot, start a Provider pass, Compact context, or finalize the Run.
 */
internal class AcceptedInputGraphWriter(
    private val conversations: ConversationRepository,
) {
    data class Request(
        val inputEffect: RunEffect.PersistAcceptedInput,
        val userMessageId: String,
        val modelMessageId: String,
        val userText: String,
        val images: List<String> = emptyList(),
        val attachmentMeta: String? = null,
        val modelId: String,
        val userTimestamp: Long,
        val newConversation: ChatEntity? = null,
    ) {
        val conversationId: String get() = inputEffect.identity.conversationId
        val runId: String get() = inputEffect.identity.runId

        init {
            require(inputEffect.identity.pass == 0)
            require(userMessageId.isNotBlank())
            require(modelMessageId.isNotBlank())
            require(modelId.isNotBlank())
            require(newConversation == null || newConversation.id == conversationId)
        }
    }

    data class Commit(
        val userMessage: MessageEntity,
        val modelMessage: MessageEntity,
        val messageSelections: Map<String?, String>,
    )

    suspend fun commit(
        request: Request,
        beforeRoomCommit: () -> Unit = {},
    ): Commit {
        val snapshot = if (request.newConversation == null) {
            conversations.getMessagesForConversationSnapshot(request.conversationId)
        } else {
            emptyList()
        }
        val selectedChildren = if (request.newConversation == null) {
            conversations.restoreBranchSelections(request.conversationId)
        } else {
            emptyMap()
        }
        val selectedPath = ConversationUiState.resolvePath(
            allMessages = snapshot.map { it.toBranchMessage() },
            streamingMsg = null,
            selectedChildren = selectedChildren,
        )
        val leaf = selectedPath.lastOrNull()
        val modelTimestamp = request.userTimestamp + 1
        val userMessage = MessageEntity(
            id = request.userMessageId,
            conversationId = request.conversationId,
            parentId = leaf?.id,
            text = request.userText,
            images = request.images,
            thoughts = null,
            status = MessageStatus.SUCCESS,
            participant = Participant.USER,
            timestamp = request.userTimestamp,
            attachmentMeta = request.attachmentMeta,
            runId = request.runId,
            runSequence = 0,
            consumedAtPass = 0,
        )
        val modelMessage = MessageEntity(
            id = request.modelMessageId,
            conversationId = request.conversationId,
            parentId = userMessage.id,
            text = "",
            thoughts = null,
            status = MessageStatus.SENDING,
            participant = Participant.MODEL,
            timestamp = modelTimestamp,
            modelName = request.modelId,
            runId = request.runId,
            runSequence = 1,
        )
        val run = RunEntity(
            id = request.runId,
            conversationId = request.conversationId,
            parentRunId = leaf?.runId,
            status = RunStatus.ACTIVE,
            activeSlot = 1,
            startedAt = request.userTimestamp,
            lastCheckpointAt = modelTimestamp,
        )
        val selectionUpdates = mapOf(
            userMessage.parentId to userMessage.id,
            userMessage.id to modelMessage.id,
        )

        beforeRoomCommit()
        val graph = request.newConversation?.let { conversation ->
            conversations.createConversationRunWithMessages(
                conversation = conversation,
                run = run,
                messages = listOf(userMessage, modelMessage),
                messageSelectionUpdates = selectionUpdates,
            )
        } ?: conversations.createRunWithMessages(
            run = run,
            messages = listOf(userMessage, modelMessage),
            messageSelectionUpdates = selectionUpdates,
        )
        check(graph.messages.size == 2) {
            "Accepted-input graph must contain exactly one USER and one MODEL row"
        }
        val committedUser = graph.messages.singleOrNull { it.id == request.userMessageId }
            ?: error("Accepted-input graph did not return its USER row")
        val committedModel = graph.messages.singleOrNull { it.id == request.modelMessageId }
            ?: error("Accepted-input graph did not return its MODEL row")
        return Commit(committedUser, committedModel, graph.messageSelections)
    }

    private fun MessageEntity.toBranchMessage() = ChatMessage(
        id = id,
        parentId = parentId,
        text = text,
        participant = participant,
        timestamp = timestamp,
        status = status,
        runId = runId,
        runSequence = runSequence,
        consumedAtPass = consumedAtPass,
    )
}
