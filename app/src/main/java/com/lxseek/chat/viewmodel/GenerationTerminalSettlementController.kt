package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.RunEffectIdentity
import com.lxseek.chat.model.RunEndReason
import com.lxseek.chat.model.RunStatus
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Settles terminal outcomes for a durable Run that could not enter normal Provider execution. */
internal class GenerationTerminalSettlementController(
    private val conversations: ConversationRepository,
    private val stopFinalizer: GenerationFinalizer,
    private val runFinalizationEffects: RunFinalizationEffectCoordinator,
    private val failureText: () -> String,
    private val toUiMessage: (MessageEntity) -> ChatMessage,
    private val onSnackbar: (String) -> Unit,
) {
    /** Execute the exact Stop effect emitted when Room wins the commit race after Stop. */
    suspend fun settleLateBoundStop(
        state: ConversationGenerationState,
        outcome: ConversationGenerationState.RunBindingOutcome.Stopping,
    ) = withContext(NonCancellable) {
        stopFinalizer.launchStopFinalization(
            scope = state.scope,
            identity = outcome.finalizationEffect.identity,
            messages = emptyList(),
        ) { completion ->
            val result = state.finishStopFinalization(completion)
            if (result.accepted && completion.success) state.clearStoppedOverlay()
        }.join()
    }

    /** Convert an external cancellation after a Room commit into the same mailbox Stop path. */
    suspend fun settleCancelledDurableRun(
        state: ConversationGenerationState,
        binding: ConversationGenerationState.RunBindingOutcome,
    ): Boolean = withContext(NonCancellable) {
        when (binding) {
            ConversationGenerationState.RunBindingOutcome.Active -> {
                val stopped = state.stop()
                val effect = stopped.finalizationEffect
                if (effect == null) {
                    // A concurrent user Stop already owns the finalization effect.
                    return@withContext state.stopping.value
                }
                stopFinalizer.launchStopFinalization(
                    scope = state.scope,
                    identity = effect.identity,
                    messages = stopped.stoppedMessage?.let(::listOf).orEmpty(),
                ) { completion ->
                    val result = state.finishStopFinalization(completion)
                    if (result.accepted && completion.success) state.clearStoppedOverlay()
                }.join()
                true
            }
            is ConversationGenerationState.RunBindingOutcome.Stopping -> {
                settleLateBoundStop(state, binding)
                true
            }
            ConversationGenerationState.RunBindingOutcome.Rejected -> false
        }
    }

    /** Terminalize a Run whose durable graph exists but whose generation could not start. */
    suspend fun failGenerationSetup(
        conversationId: String,
        runId: String,
        modelMessageId: String?,
        uiToken: Long,
        state: ConversationGenerationState,
        error: Exception,
    ) {
        DebugLog.e(
            "LxChatVM",
            "Failed to start Run $runId errorType=${error.javaClass.simpleName}",
        )
        val errorText = failureText()
        val failedMessage = modelMessageId?.let { id ->
            runCatching {
                conversations.getMessagesForConversationSnapshot(conversationId)
                    .firstOrNull { it.id == id }
                    ?.let(toUiMessage)
                    ?.copy(text = errorText, status = MessageStatus.ERROR)
            }.getOrNull()
        }
        if (failedMessage != null && state.currentRunId() == runId) {
            finalizeBoundFailure(
                conversationId = conversationId,
                runId = runId,
                pass = 0,
                uiToken = uiToken,
                state = state,
                failedMessage = failedMessage,
                effectId = "setup-finalize-$runId",
            )
        } else if (failedMessage != null && !state.generating.value) {
            // Runtime disposal is the only no-writer edge. Repair message + Run atomically without
            // accepting a stale result into a newer process state.
            runCatching {
                conversations.finishGeneration(
                    message = failedMessage,
                    conversationId = conversationId,
                    runId = runId,
                    status = RunStatus.FAILED,
                    reason = RunEndReason.PROVIDER_ERROR,
                    markConversationUnread = false,
                )
            }
        } else if (failedMessage == null) {
            // No Run graph was committed. The installed Job's coroutine barrier releases the
            // unbound process slot; there is no durable terminal state to write.
            state.loadingChange(uiToken, false)
        }
        onSnackbar(errorText)
    }

    suspend fun finalizeBoundFailure(
        conversationId: String,
        runId: String,
        pass: Int,
        uiToken: Long,
        state: ConversationGenerationState,
        failedMessage: ChatMessage,
        effectId: String,
    ): Boolean {
        val requested = state.commands.requestRunFinalization(
            identity = RunEffectIdentity(
                conversationId = conversationId,
                ownerToken = uiToken,
                runId = runId,
                pass = pass,
                effectId = effectId,
            ),
            status = RunStatus.FAILED,
            reason = RunEndReason.PROVIDER_ERROR,
            markConversationUnread = false,
        ) ?: return false
        val result = runFinalizationEffects.execute(requested) { effect ->
            conversations.finishGeneration(
                message = failedMessage,
                conversationId = effect.identity.conversationId,
                runId = effect.identity.runId,
                status = effect.status,
                reason = effect.reason,
                markConversationUnread = effect.markConversationUnread,
            )
        }
        val success = result is RunFinalizationEffectCoordinator.Result.Succeeded
        state.finishRunFinalization(requested.identity, success)
        if (success) {
            state.streamUpdate(uiToken, failedMessage)
            state.streamClear(uiToken)
            state.loadingChange(uiToken, false)
        }
        return success
    }
}
