package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.CompactMode
import com.lxseek.chat.model.CompactOutcome
import com.lxseek.chat.model.ConversationCommand
import com.lxseek.chat.model.ProviderPassResult
import com.lxseek.chat.model.RunEffect
import com.lxseek.chat.model.RunEffectIdentity
import com.lxseek.chat.model.RunEndReason
import com.lxseek.chat.model.RunStatus
import com.lxseek.chat.model.RuntimeRunIdentity
import com.lxseek.chat.model.Transition
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Typed application port for commands that do not manipulate process resources directly.
 *
 * The identity suppliers are evaluated only inside a mailbox command factory, where the runtime
 * host already holds its generation lock. This port cannot reduce/apply state, cancel resources,
 * release a slot, or authorize a next lifecycle stage outside the effects returned by the mailbox.
 */
internal class ConversationRuntimeCommandPort(
    private val conversationId: String,
    private val mailbox: ConversationCommandMailbox,
    private val nextOwnerToken: () -> Long,
    private val currentRunIdentity: () -> RuntimeRunIdentity?,
) {
    /** Submit one ordinary foreground/headless Send placement decision. */
    suspend fun requestSend(
        proposedRunId: String,
        effectId: String,
        directOnly: Boolean,
        hasPendingGuidance: Boolean,
    ): Transition {
        require(proposedRunId.isNotBlank())
        require(effectId.isNotBlank())
        return mailbox.submit(
            commandFactory = ConversationCommandFactory {
                ConversationCommand.SendRequested(
                    identity = RunEffectIdentity(
                        conversationId = conversationId,
                        ownerToken = nextOwnerToken(),
                        runId = proposedRunId,
                        pass = 0,
                        effectId = effectId,
                    ),
                    directOnly = directOnly,
                    hasPendingGuidance = hasPendingGuidance,
                )
            },
            cancellationCommand = { transition ->
                transition.effects
                    .filterIsInstance<RunEffect.PersistAcceptedInput>()
                    .singleOrNull()
                    ?.let { effect -> ConversationCommand.SendLaunchAbandoned(effect.identity) }
            },
        )
    }

    /** Echo the exact Room acceptance effect back through the mailbox. */
    suspend fun finishInputPersistence(
        identity: RunEffectIdentity,
    ): Transition = mailbox.submit(
        ConversationCommandFactory { ConversationCommand.InputPersisted(identity) },
    )

    suspend fun inputPersistenceFailed(identity: RunEffectIdentity): Boolean = mailbox.submit(
        ConversationCommandFactory { ConversationCommand.InputPersistenceFailed(identity) },
    ).accepted

    suspend fun abandonSendLaunch(identity: RunEffectIdentity): Boolean = mailbox.submit(
        ConversationCommandFactory { ConversationCommand.SendLaunchAbandoned(identity) },
    ).accepted

    /** Authorize one exact validated Provider tool batch. */
    suspend fun requestToolBatch(
        providerOutcomeIdentity: RunEffectIdentity,
    ): RunEffect.ExecuteToolBatch? = withContext(NonCancellable) {
        mailbox.submit(
            ConversationCommandFactory {
                ConversationCommand.ToolBatchRequested(providerOutcomeIdentity)
            },
        ).effects.filterIsInstance<RunEffect.ExecuteToolBatch>().singleOrNull()
    }

    suspend fun completeToolBatch(
        batchIdentity: RunEffectIdentity,
    ): RunEffect.CommitToolRound? = withContext(NonCancellable) {
        mailbox.submit(
            ConversationCommandFactory {
                ConversationCommand.ToolBatchCompleted(batchIdentity)
            },
        ).effects.filterIsInstance<RunEffect.CommitToolRound>().singleOrNull()
    }

    suspend fun finishToolRoundCommit(
        commitIdentity: RunEffectIdentity,
        success: Boolean,
    ): RunEffect? = withContext(NonCancellable) {
        mailbox.submit(
            ConversationCommandFactory {
                ConversationCommand.ToolRoundCommitted(commitIdentity, success)
            },
        ).effects.singleOrNull()
    }

    /** Authorize exactly one Provider pass for the current Run/pass. */
    suspend fun requestProviderPass(
        identity: RunEffectIdentity,
    ): RunEffect.StartProviderPass? = mailbox.submit(
        commandFactory = ConversationCommandFactory {
            ConversationCommand.ProviderPassRequested(identity)
        },
        cancellationCommand = { transition ->
            transition.effects.filterIsInstance<RunEffect.StartProviderPass>()
                .singleOrNull()
                ?.let { effect ->
                    ConversationCommand.ProviderPassCompleted(
                        effect.identity,
                        ProviderPassResult.CANCELLED,
                    )
                }
        },
    ).effects.filterIsInstance<RunEffect.StartProviderPass>().singleOrNull()

    suspend fun finishProviderPass(
        identity: RunEffectIdentity,
        result: ProviderPassResult,
    ): RunEffect.ProviderPassAccepted? = withContext(NonCancellable) {
        mailbox.submit(
            ConversationCommandFactory {
                ConversationCommand.ProviderPassCompleted(identity, result)
            },
        ).effects.filterIsInstance<RunEffect.ProviderPassAccepted>().singleOrNull()
    }

    suspend fun requestRunFinalization(
        identity: RunEffectIdentity,
        status: RunStatus,
        reason: RunEndReason,
        markConversationUnread: Boolean,
    ): RunEffect.FinalizeRun? = withContext(NonCancellable) {
        mailbox.submit(
            ConversationCommandFactory {
                ConversationCommand.FinalizationRequested(
                    identity = identity,
                    status = status,
                    reason = reason,
                    markConversationUnread = markConversationUnread,
                )
            },
        ).effects.filterIsInstance<RunEffect.FinalizeRun>().singleOrNull()
    }

    suspend fun finishRunFinalization(
        identity: RunEffectIdentity,
        success: Boolean,
    ): Transition = withContext(NonCancellable) {
        mailbox.submit(
            ConversationCommandFactory {
                ConversationCommand.FinalizationCompleted(identity, success)
            },
        )
    }

    /** Claim an idle-only manual Compact without presenting it as a generation Run. */
    suspend fun requestManualCompact(
        compactRunId: String,
        effectId: String,
    ): RunEffect.RunCompact? {
        require(compactRunId.isNotBlank())
        require(effectId.isNotBlank())
        return mailbox.submit(
            commandFactory = ConversationCommandFactory {
                ConversationCommand.CompactRequested(
                    identity = RunEffectIdentity(
                        conversationId = conversationId,
                        ownerToken = nextOwnerToken().coerceAtLeast(1),
                        runId = compactRunId,
                        pass = 0,
                        effectId = effectId,
                    ),
                    compactRunId = compactRunId,
                    mode = CompactMode.MANUAL,
                )
            },
            cancellationCommand = { transition -> transition.failedCompactCommand() },
        ).effects.filterIsInstance<RunEffect.RunCompact>().singleOrNull()
    }

    /** Claim an automatic Compact for the exact currently-active Run/pass. */
    suspend fun requestAutomaticCompact(
        compactRunId: String,
        effectId: String,
    ): RunEffect.RunCompact? {
        require(compactRunId.isNotBlank())
        require(effectId.isNotBlank())
        return mailbox.submit(
            commandFactory = ConversationCommandFactory {
                val currentIdentity = currentRunIdentity()
                val identity = if (currentIdentity?.runId != null) {
                    currentIdentity.effectIdentity(effectId)
                } else {
                    RunEffectIdentity(
                        conversationId = conversationId,
                        ownerToken = nextOwnerToken().coerceAtLeast(1),
                        runId = "unbound_$compactRunId",
                        pass = 0,
                        effectId = effectId,
                    )
                }
                ConversationCommand.CompactRequested(
                    identity = identity,
                    compactRunId = compactRunId,
                    mode = CompactMode.AUTOMATIC,
                )
            },
            cancellationCommand = { transition -> transition.failedCompactCommand() },
        ).effects.filterIsInstance<RunEffect.RunCompact>().singleOrNull()
    }

    suspend fun finishCompact(
        identity: RunEffectIdentity,
        outcome: CompactOutcome,
    ): Transition = mailbox.submit(
        ConversationCommandFactory {
            ConversationCommand.CompactCompleted(identity, outcome)
        },
    )

    private fun Transition.failedCompactCommand(): ConversationCommand.CompactCompleted? =
        effects.filterIsInstance<RunEffect.RunCompact>()
            .singleOrNull()
            ?.let { effect ->
                ConversationCommand.CompactCompleted(effect.identity, CompactOutcome.FAILED)
            }

    private fun RuntimeRunIdentity.effectIdentity(effectId: String): RunEffectIdentity =
        RunEffectIdentity(
            conversationId = conversationId,
            ownerToken = ownerToken,
            runId = requireNotNull(runId),
            pass = pass,
            effectId = effectId,
        )

}
