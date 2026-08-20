package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.CompactMode
import com.lxseek.chat.model.CompactOutcome
import com.lxseek.chat.model.RunEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** Executes only the exact Context Compact effect granted by one conversation mailbox. */
internal class ContextCompactEffectCoordinator(
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    sealed interface Execution {
        data class Settled(val result: CompactResult) : Execution
        data object Busy : Execution
        data object Superseded : Execution
    }

    suspend fun executeManual(
        state: ConversationGenerationState,
        block: suspend (RunEffect.RunCompact) -> CompactResult,
    ): Execution = execute(state, CompactMode.MANUAL, block)

    suspend fun executeAutomatic(
        state: ConversationGenerationState,
        block: suspend (RunEffect.RunCompact) -> CompactResult,
    ): Execution = execute(state, CompactMode.AUTOMATIC, block)

    private suspend fun execute(
        state: ConversationGenerationState,
        mode: CompactMode,
        block: suspend (RunEffect.RunCompact) -> CompactResult,
    ): Execution {
        val operationId = idFactory()
        val compactRunId = "compact_run_$operationId"
        val effectId = "compact-$operationId"
        val effect = when (mode) {
            CompactMode.MANUAL -> state.queueMutationMutex.withLock {
                if (state.queuedSends.value.isNotEmpty()) null
                else state.commands.requestManualCompact(compactRunId, effectId)
            }
            CompactMode.AUTOMATIC -> state.commands.requestAutomaticCompact(compactRunId, effectId)
        } ?: return Execution.Busy

        val result = try {
            block(effect)
        } catch (cancelled: CancellationException) {
            settleFailure(state, effect)
            throw cancelled
        } catch (error: Exception) {
            settleFailure(state, effect)
            throw error
        }

        val outcome = result.toRuntimeOutcome()
        val transition = withContext(NonCancellable) {
            state.commands.finishCompact(effect.identity, outcome)
        }
        if (!transition.accepted) {
            // Stop cancels an automatic Compact's installed generation Job. If cancellation has
            // already reached this caller, preserve it; otherwise report the stale result without
            // granting continuation.
            currentCoroutineContext().ensureActive()
            return Execution.Superseded
        }
        when {
            outcome == CompactOutcome.FAILED -> check(
                transition.effects.singleOrNull() ==
                    RunEffect.CompactFailed(effect.identity, mode),
            )
            mode == CompactMode.AUTOMATIC -> check(
                transition.effects.singleOrNull() ==
                    RunEffect.ResumeAfterCompact(effect.identity, outcome),
            )
            else -> check(transition.effects.isEmpty())
        }
        return Execution.Settled(result)
    }

    private suspend fun settleFailure(
        state: ConversationGenerationState,
        effect: RunEffect.RunCompact,
    ) {
        withContext(NonCancellable) {
            try {
                state.commands.finishCompact(effect.identity, CompactOutcome.FAILED)
            } catch (_: Exception) {
                // Preserve the originating cancellation/effect failure when runtime disposal has
                // already closed the mailbox. No later continuation can be authorized there.
            }
        }
    }
}

private fun CompactResult.toRuntimeOutcome(): CompactOutcome = when (this) {
    is CompactResult.Created -> CompactOutcome.CREATED
    CompactResult.NotNeeded -> CompactOutcome.NOT_NEEDED
    is CompactResult.Failed -> CompactOutcome.FAILED
}
