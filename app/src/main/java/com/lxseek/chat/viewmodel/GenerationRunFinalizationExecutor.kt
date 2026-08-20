package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.RunEffect
import com.lxseek.chat.model.RunEffectIdentity
import com.lxseek.chat.model.RunEndReason
import com.lxseek.chat.model.RunStatus

internal data class GenerationRunFinalizationRequest(
    val identity: RunEffectIdentity,
    val message: ChatMessage,
    val status: RunStatus,
    val reason: RunEndReason,
    val markConversationUnread: Boolean,
)

internal data class GenerationRunFinalizationCallbacks(
    val requestEffect: suspend (
        RunEffectIdentity,
        RunStatus,
        RunEndReason,
        Boolean,
    ) -> RunEffect.FinalizeRun?,
    val returnResult: suspend (RunEffectIdentity, Boolean) -> Boolean,
)

internal fun GenerationCallbacks.runFinalizationCallbacks() =
    GenerationRunFinalizationCallbacks(
        requestEffect = onRunFinalizationRequested,
        returnResult = onRunFinalizationCompleted,
    )

internal sealed interface GenerationRunFinalizationOutcome {
    data object NotAuthorized : GenerationRunFinalizationOutcome

    data class Settled(
        val effect: RunEffect.FinalizeRun,
        val durableResult: RunFinalizationEffectCoordinator.Result,
        val accepted: Boolean,
    ) : GenerationRunFinalizationOutcome {
        val terminalPersisted: Boolean
            get() = accepted && durableResult is RunFinalizationEffectCoordinator.Result.Succeeded
    }
}

/** Executes one exact mailbox-authorized normal finalization effect and returns its identified result. */
internal class GenerationRunFinalizationExecutor(
    private val conversations: ConversationRepository,
    private val effects: RunFinalizationEffectCoordinator = RunFinalizationEffectCoordinator(),
) {
    suspend fun execute(
        request: GenerationRunFinalizationRequest,
        callbacks: GenerationRunFinalizationCallbacks,
    ): GenerationRunFinalizationOutcome {
        val effect = callbacks.requestEffect(
            request.identity,
            request.status,
            request.reason,
            request.markConversationUnread,
        )?.takeIf { candidate ->
            candidate.identity == request.identity &&
                candidate.status == request.status &&
                candidate.reason == request.reason &&
                candidate.markConversationUnread == request.markConversationUnread
        } ?: return GenerationRunFinalizationOutcome.NotAuthorized

        val result = effects.execute(effect) { authorized ->
            conversations.finishGeneration(
                message = request.message,
                conversationId = authorized.identity.conversationId,
                runId = authorized.identity.runId,
                status = authorized.status,
                reason = authorized.reason,
                markConversationUnread = authorized.markConversationUnread,
            )
        }
        val durableSuccess = result is RunFinalizationEffectCoordinator.Result.Succeeded
        return GenerationRunFinalizationOutcome.Settled(
            effect = effect,
            durableResult = result,
            accepted = callbacks.returnResult(effect.identity, durableSuccess),
        )
    }
}
