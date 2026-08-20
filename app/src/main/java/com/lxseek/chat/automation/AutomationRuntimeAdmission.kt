package com.lxseek.chat.automation

import com.lxseek.chat.model.RunEffect
import com.lxseek.chat.viewmodel.ConversationGenerationState
import kotlinx.coroutines.sync.withLock

/** Direct-only Task/Loop adapter for the authoritative conversation Send mailbox. */
internal object AutomationRuntimeAdmission {
    sealed interface Decision {
        data class Accepted(
            val state: ConversationGenerationState,
            val inputEffect: RunEffect.PersistAcceptedInput,
        ) : Decision

        /** No input, Run, attachment, or selected-edge side effect has occurred. */
        data object Busy : Decision
    }

    suspend fun request(
        state: ConversationGenerationState,
        proposedRunId: String,
        effectId: String,
    ): Decision = state.queueMutationMutex.withLock {
        val transition = state.commands.requestSend(
            proposedRunId = proposedRunId,
            effectId = effectId,
            directOnly = true,
            hasPendingGuidance = state.queuedSends.value.isNotEmpty(),
        )
        transition.effects.filterIsInstance<RunEffect.PersistAcceptedInput>()
            .singleOrNull()
            ?.let { Decision.Accepted(state, it) }
            ?: run {
                check(transition.effects.singleOrNull() is RunEffect.RejectSendBusy) {
                    "Direct-only automation admission produced an illegal effect"
                }
                Decision.Busy
            }
    }
}
