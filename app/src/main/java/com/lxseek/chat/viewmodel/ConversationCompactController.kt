package com.lxseek.chat.viewmodel

import com.lxseek.chat.automation.ConversationExecutionCoordinator
import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.data.repository.ConversationRepository
import kotlinx.coroutines.CancellationException

/**
 * Executes only mailbox-authorized Compact work and publishes the resulting durable graph.
 *
 * It owns no Run state or long-lived resource. The existing effect coordinator returns every
 * asynchronous result through the same conversation mailbox before this component returns.
 */
internal class ConversationCompactController(
    private val conversations: ConversationRepository,
    private val executionCoordinator: ConversationExecutionCoordinator,
    private val operation: ContextCompactOperation,
    private val effectCoordinator: ContextCompactEffectCoordinator =
        ContextCompactEffectCoordinator(),
    private val projectGraph: (
        conversationId: String,
        messages: List<MessageEntity>,
        selectedChildren: Map<String?, String>,
    ) -> Unit,
) {
    suspend fun automaticBeforeBoundary(
        conversationId: String,
        fallbackModel: String,
        contextLimit: Int,
        state: ConversationGenerationState,
    ) {
        if (!operation.automaticNeeded(conversationId, contextLimit)) return
        when (
            val execution = effectCoordinator.executeAutomatic(state) { effect ->
                operation.compactAutomatic(
                    conversationId = conversationId,
                    fallbackModel = fallbackModel,
                    contextLimit = contextLimit,
                    compactRunId = effect.compactRunId,
                    onSummaryChunk = { chunk ->
                        state.appendCompactPreview(effect.identity, chunk)
                    },
                ).also { result ->
                    projectCreatedGraph(conversationId, result)
                }
            }
        ) {
            is ContextCompactEffectCoordinator.Execution.Settled -> when (
                val result = execution.result
            ) {
                is CompactResult.Failed -> throw IllegalStateException(
                    "Automatic context compact failed: ${result.message}",
                )
                is CompactResult.Created,
                CompactResult.NotNeeded,
                -> Unit
            }
            ContextCompactEffectCoordinator.Execution.Busy -> {
                if (state.stopping.value) {
                    throw CancellationException("Automatic context compact was stopped")
                }
                error("Automatic context compact was not admitted for the active Run")
            }
            ContextCompactEffectCoordinator.Execution.Superseded -> {
                if (state.stopping.value) {
                    throw CancellationException("Automatic context compact was superseded by Stop")
                }
                error("Automatic context compact result was superseded")
            }
        }
    }

    suspend fun manual(
        conversationId: String,
        request: CompactRequest,
        state: ConversationGenerationState,
    ): CompactResult = when (
        val execution = effectCoordinator.executeManual(state) { effect ->
            executionCoordinator.withConversationLock(conversationId) {
                if (conversations.getLiveRun(conversationId) != null) {
                    return@withConversationLock CompactResult.Failed("Conversation is busy")
                }
                operation.compactManual(
                    conversationId = conversationId,
                    request = request,
                    compactRunId = effect.compactRunId,
                    onSummaryChunk = { chunk ->
                        state.appendCompactPreview(effect.identity, chunk)
                    },
                ).also { result ->
                    projectCreatedGraph(conversationId, result)
                }
            }
        }
    ) {
        is ContextCompactEffectCoordinator.Execution.Settled -> execution.result
        ContextCompactEffectCoordinator.Execution.Busy ->
            CompactResult.Failed("Wait for the current generation or context compact to finish")
        ContextCompactEffectCoordinator.Execution.Superseded ->
            CompactResult.Failed("Context compact was interrupted")
    }

    private suspend fun projectCreatedGraph(
        conversationId: String,
        result: CompactResult,
    ) {
        if (result !is CompactResult.Created) return
        projectGraph(
            conversationId,
            conversations.getMessagesForConversationSnapshot(conversationId),
            conversations.restoreBranchSelections(conversationId),
        )
    }
}
