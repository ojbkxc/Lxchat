package com.lxseek.chat.model

import java.security.MessageDigest
import java.util.ArrayDeque

data class ConversationRuntimeTraceEntry(
    val sequence: Long,
    val conversationIdHash: String,
    val runId: String?,
    val pass: Int,
    val effectId: String?,
    val oldState: String,
    val commandType: String,
    val newState: String,
    val effectTypes: List<String>,
    val timestamp: Long,
)

/** Bounded, content-free diagnostic history for one conversation runtime. */
class ConversationRuntimeTrace(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val entries = ArrayDeque<ConversationRuntimeTraceEntry>(capacity)
    private var nextSequence = 1L

    init {
        require(capacity in 1..MAX_CAPACITY)
    }

    @Synchronized
    fun record(
        oldState: RunState,
        command: ConversationCommand,
        transition: Transition,
    ) {
        val identity = command.runIdentity()
        if (entries.size == capacity) entries.removeFirst()
        entries.addLast(
            ConversationRuntimeTraceEntry(
                sequence = nextSequence++,
                conversationIdHash = hashConversationId(command.conversationId),
                runId = identity.runId,
                pass = identity.pass,
                effectId = command.effectIdOrNull(),
                oldState = oldState.traceName(),
                commandType = command.traceName(),
                newState = transition.newState.traceName(),
                effectTypes = transition.effects.map { effect -> effect.traceName() },
                timestamp = clock(),
            ),
        )
    }

    @Synchronized
    fun snapshot(): List<ConversationRuntimeTraceEntry> = entries.toList()

    private fun ConversationCommand.runIdentity(): RuntimeRunIdentity = when (this) {
        is ConversationCommand.Recover -> RuntimeRunIdentity(
            conversationId = snapshot.conversationId,
            ownerToken = Long.MAX_VALUE,
            runId = snapshot.runId,
            pass = snapshot.pass,
        )
        is ConversationCommand.RecoveryCompleted -> identity.runIdentity()
        is ConversationCommand.AcquireSlot -> identity
        is ConversationCommand.SendRequested -> identity.runIdentity()
        is ConversationCommand.InputPersisted -> identity.runIdentity()
        is ConversationCommand.InputPersistenceFailed -> identity.runIdentity()
        is ConversationCommand.SendLaunchAbandoned -> identity.runIdentity()
        is ConversationCommand.BindRun -> identity
        is ConversationCommand.ProviderPassRequested -> identity.runIdentity()
        is ConversationCommand.ProviderPassCompleted -> identity.runIdentity()
        is ConversationCommand.ToolBatchRequested -> identity.runIdentity()
        is ConversationCommand.ToolBatchCompleted -> identity.runIdentity()
        is ConversationCommand.ToolRoundCommitted -> identity.runIdentity()
        is ConversationCommand.CompactRequested -> identity.runIdentity()
        is ConversationCommand.CompactCompleted -> identity.runIdentity()
        is ConversationCommand.FinalizationRequested -> identity.runIdentity()
        is ConversationCommand.FinalizationCompleted -> identity.runIdentity()
        is ConversationCommand.StopRequested -> identity
        is ConversationCommand.CoroutineSettled -> identity
        is ConversationCommand.PersistenceSettled -> identity.runIdentity()
    }

    private fun ConversationCommand.effectIdOrNull(): String? = when (this) {
        is ConversationCommand.Recover -> "recover-${snapshot.runId}-${snapshot.pass}"
        is ConversationCommand.RecoveryCompleted -> identity.effectId
        is ConversationCommand.SendRequested -> identity.effectId
        is ConversationCommand.InputPersisted -> identity.effectId
        is ConversationCommand.InputPersistenceFailed -> identity.effectId
        is ConversationCommand.SendLaunchAbandoned -> identity.effectId
        is ConversationCommand.ProviderPassRequested -> identity.effectId
        is ConversationCommand.ProviderPassCompleted -> identity.effectId
        is ConversationCommand.ToolBatchRequested -> identity.effectId
        is ConversationCommand.ToolBatchCompleted -> identity.effectId
        is ConversationCommand.ToolRoundCommitted -> identity.effectId
        is ConversationCommand.CompactRequested -> identity.effectId
        is ConversationCommand.CompactCompleted -> identity.effectId
        is ConversationCommand.FinalizationRequested -> identity.effectId
        is ConversationCommand.FinalizationCompleted -> identity.effectId
        is ConversationCommand.StopRequested -> effectId
        is ConversationCommand.PersistenceSettled -> identity.effectId
        is ConversationCommand.AcquireSlot,
        is ConversationCommand.BindRun,
        is ConversationCommand.CoroutineSettled,
        -> null
    }

    private fun RunState.traceName(): String = when (this) {
        is RunState.Idle -> "Idle"
        is RunState.Recovering -> "Recovering"
        is RunState.Preparing -> "Preparing"
        is RunState.Active -> when (toolPhase) {
            RunToolPhase.None -> when (providerPhase) {
                RunProviderPhase.None -> "Active"
                is RunProviderPhase.Running -> "Streaming"
            }
            is RunToolPhase.Executing -> "ExecutingTools"
            is RunToolPhase.Committing -> "CommittingToolRound"
        }
        is RunState.Compacting -> when (mode) {
            CompactMode.MANUAL -> "CompactingManual"
            CompactMode.AUTOMATIC -> "CompactingAutomatic"
        }
        is RunState.Finalizing -> "Finalizing"
        is RunState.Stopping -> "Stopping"
    }

    private fun ConversationCommand.traceName(): String = when (this) {
        is ConversationCommand.Recover -> "Recover"
        is ConversationCommand.RecoveryCompleted -> "RecoveryCompleted"
        is ConversationCommand.AcquireSlot -> "AcquireSlot"
        is ConversationCommand.SendRequested -> "SendRequested"
        is ConversationCommand.InputPersisted -> "InputPersisted"
        is ConversationCommand.InputPersistenceFailed -> "InputPersistenceFailed"
        is ConversationCommand.SendLaunchAbandoned -> "SendLaunchAbandoned"
        is ConversationCommand.BindRun -> "BindRun"
        is ConversationCommand.ProviderPassRequested -> "ProviderPassRequested"
        is ConversationCommand.ProviderPassCompleted -> "ProviderPassCompleted"
        is ConversationCommand.ToolBatchRequested -> "ToolBatchRequested"
        is ConversationCommand.ToolBatchCompleted -> "ToolBatchCompleted"
        is ConversationCommand.ToolRoundCommitted -> "ToolRoundCommitted"
        is ConversationCommand.CompactRequested -> "CompactRequested"
        is ConversationCommand.CompactCompleted -> "CompactCompleted"
        is ConversationCommand.FinalizationRequested -> "FinalizationRequested"
        is ConversationCommand.FinalizationCompleted -> "FinalizationCompleted"
        is ConversationCommand.StopRequested -> "StopRequested"
        is ConversationCommand.CoroutineSettled -> "CoroutineSettled"
        is ConversationCommand.PersistenceSettled -> "PersistenceSettled"
    }

    private fun RunEffect.traceName(): String = when (this) {
        is RunEffect.RecoverDurableRun -> "RecoverDurableRun"
        is RunEffect.RunRecoveryFailed -> "RunRecoveryFailed"
        is RunEffect.SlotActivated -> "SlotActivated"
        is RunEffect.PersistAcceptedInput -> "PersistAcceptedInput"
        is RunEffect.AcceptGuidance -> "AcceptGuidance"
        is RunEffect.DrainGuidanceFirst -> "DrainGuidanceFirst"
        is RunEffect.AwaitRunRelease -> "AwaitRunRelease"
        is RunEffect.AwaitCompactSettlement -> "AwaitCompactSettlement"
        is RunEffect.RejectSendBusy -> "RejectSendBusy"
        is RunEffect.StartProviderPass -> "StartProviderPass"
        is RunEffect.ProviderPassAccepted -> "ProviderPassAccepted"
        is RunEffect.CancelProviderPass -> "CancelProviderPass"
        is RunEffect.FinalizeStop -> "FinalizeStop"
        is RunEffect.StopPersistenceFailed -> "StopPersistenceFailed"
        is RunEffect.ExecuteToolBatch -> "ExecuteToolBatch"
        is RunEffect.CommitToolRound -> "CommitToolRound"
        is RunEffect.ContinueProviderPass -> "ContinueProviderPass"
        is RunEffect.ToolRoundCommitFailed -> "ToolRoundCommitFailed"
        is RunEffect.RunCompact -> "RunCompact"
        is RunEffect.ResumeAfterCompact -> "ResumeAfterCompact"
        is RunEffect.CompactFailed -> "CompactFailed"
        is RunEffect.FinalizeRun -> "FinalizeRun"
        is RunEffect.RunFinalizationFailed -> "RunFinalizationFailed"
        is RunEffect.ReleaseSlot -> "ReleaseSlot"
    }

    internal companion object {
        const val DEFAULT_CAPACITY = 256
        const val MAX_CAPACITY = 4_096

        fun hashConversationId(conversationId: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(conversationId.toByteArray(Charsets.UTF_8))
                .take(HASH_BYTES)
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

        private const val HASH_BYTES = 12
    }
}
