package com.lxseek.chat.data

import com.lxseek.chat.data.local.RunEntity
import com.lxseek.chat.model.RunEndReason
import com.lxseek.chat.model.RunStatus

internal data class ArchivedRunSnapshot(
    val id: String,
    val conversationId: String,
    val parentRunId: String?,
    val status: String,
    val startedAt: Long,
    val lastCheckpointAt: Long,
    val stopRequestedAt: Long?,
    val endedAt: Long?,
    val endReason: String?,
    val currentPass: Int,
    val legacyAmbiguous: Boolean,
)

internal data class ArchivedMessageRunOwnership(
    val messageId: String,
    val conversationId: String,
    val runId: String?,
    val runSequence: Long?,
    val consumedAtPass: Int?,
)

/**
 * Pure validation and recovery policy for native v3 Run archives.
 *
 * An archive is either accepted as one complete Run graph or rebuilt entirely by the legacy
 * planner. Mixing trusted Run rows with synthesized message ownership would leave branch
 * selections and parent links pointing across two incompatible graphs.
 */
internal object NativeRunArchivePolicy {
    fun terminalize(snapshot: ArchivedRunSnapshot): RunEntity {
        val importedStatus = runCatching { RunStatus.valueOf(snapshot.status) }
            .getOrDefault(RunStatus.FAILED)
        val terminalStatus = if (importedStatus.isTerminal) importedStatus else RunStatus.STOPPED
        val terminalReason = if (!importedStatus.isTerminal) {
            RunEndReason.PROCESS_RECOVERED
        } else {
            runCatching { snapshot.endReason?.let(RunEndReason::valueOf) }.getOrNull()
                ?: when (terminalStatus) {
                    RunStatus.COMPLETED -> RunEndReason.MODEL_COMPLETED
                    RunStatus.STOPPED -> RunEndReason.USER_STOPPED
                    RunStatus.FAILED -> RunEndReason.PROVIDER_ERROR
                    else -> error("Terminal status expected")
                }
        }
        val checkpoint = maxOf(snapshot.startedAt, snapshot.lastCheckpointAt)
        return RunEntity(
            id = snapshot.id,
            conversationId = snapshot.conversationId,
            parentRunId = snapshot.parentRunId,
            status = terminalStatus,
            activeSlot = null,
            startedAt = snapshot.startedAt,
            lastCheckpointAt = checkpoint,
            stopRequestedAt = snapshot.stopRequestedAt,
            endedAt = maxOf(snapshot.endedAt ?: checkpoint, checkpoint),
            endReason = terminalReason,
            currentPass = snapshot.currentPass.coerceAtLeast(0),
            legacyAmbiguous = snapshot.legacyAmbiguous,
        )
    }

    fun hasCompleteOwnership(
        runs: List<RunEntity>,
        ownership: List<ArchivedMessageRunOwnership>,
        sourceRunIdsWereUnique: Boolean,
    ): Boolean {
        if (runs.isEmpty() || !sourceRunIdsWereUnique) return false
        if (ownership.map { it.messageId }.distinct().size != ownership.size) return false

        val runsById = runs.associateBy { it.id }
        val sequenceKeys = mutableSetOf<Pair<String, Long>>()
        return ownership.all { item ->
            val run = item.runId?.let(runsById::get)
            val sequence = item.runSequence
            run != null &&
                run.conversationId == item.conversationId &&
                sequence != null &&
                sequence >= 0 &&
                sequenceKeys.add(run.id to sequence)
        }
    }

    /**
     * Produces a parent-before-child insertion order. Missing parents and cycles are preserved as
     * roots marked ambiguous instead of aborting the entire import or silently dropping Runs.
     */
    fun orderByParent(rawRuns: List<RunEntity>): List<RunEntity> {
        if (rawRuns.isEmpty()) return emptyList()
        val ids = rawRuns.mapTo(mutableSetOf()) { it.id }
        val remaining = rawRuns
            .map {
                if (it.parentRunId != null && it.parentRunId !in ids) {
                    it.copy(parentRunId = null, legacyAmbiguous = true)
                } else {
                    it
                }
            }
            .associateByTo(linkedMapOf()) { it.id }
        val ordered = mutableListOf<RunEntity>()
        val inserted = mutableSetOf<String>()
        while (remaining.isNotEmpty()) {
            val ready = remaining.values
                .filter { it.parentRunId == null || it.parentRunId in inserted }
                .sortedWith(compareBy<RunEntity> { it.startedAt }.thenBy { it.id })
            if (ready.isEmpty()) {
                val first = remaining.values.minWith(
                    compareBy<RunEntity> { it.startedAt }.thenBy { it.id }
                )
                remaining[first.id] = first.copy(parentRunId = null, legacyAmbiguous = true)
                continue
            }
            for (run in ready) {
                ordered += run
                inserted += run.id
                remaining.remove(run.id)
            }
        }
        return ordered
    }
}
