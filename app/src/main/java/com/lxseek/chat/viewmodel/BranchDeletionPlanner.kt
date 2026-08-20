package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.data.local.RunEntity
import com.lxseek.chat.util.Constants

internal data class BranchDeletionPlan(
    val deletedMessageIds: Set<String>,
    val deletedRunIds: Set<String>,
    val rootRunIdsToDelete: Set<String>,
    val messageSelections: Map<String?, String>,
    val runSelections: Map<String?, String>,
)

/**
 * Plans deletion from the structural message tree.
 *
 * A boundary USER owns an edit branch. Each ordinary MODEL child of that USER owns one
 * regeneration branch, even when the original MODEL happens to share its Run with the USER.
 * Run ownership is therefore only a persistence/lifecycle concern and must never expand the
 * requested message subtree.
 */
internal object BranchDeletionPlanner {
    fun plan(
        rootMessageId: String,
        messages: List<MessageEntity>,
        runs: List<RunEntity>,
        messageSelections: Map<String?, String>,
        runSelections: Map<String?, String>,
    ): BranchDeletionPlan {
        require(messages.any { it.id == rootMessageId }) {
            "Message $rootMessageId does not exist"
        }

        val deletedMessageIds = descendantMessageIds(messages, rootMessageId)
        val runDeletion = planRunDeletion(messages, runs, deletedMessageIds)
        return BranchDeletionPlan(
            deletedMessageIds = deletedMessageIds,
            deletedRunIds = runDeletion.deletedRunIds,
            rootRunIdsToDelete = runDeletion.rootRunIds,
            messageSelections = repairMessageSelections(
                selections = messageSelections,
                messages = messages,
                deletedMessageIds = deletedMessageIds,
            ),
            runSelections = repairRunSelections(
                selections = runSelections,
                runs = runs,
                deletedRunIds = runDeletion.deletedRunIds,
            ),
        )
    }

    fun descendantMessageIds(
        messages: List<MessageEntity>,
        rootMessageId: String,
    ): Set<String> {
        if (messages.none { it.id == rootMessageId }) return emptySet()
        val childrenByParent = messages.groupBy { it.parentId }
        val descendants = linkedSetOf(rootMessageId)
        val pending = ArrayDeque<String>().apply { add(rootMessageId) }
        while (pending.isNotEmpty()) {
            for (child in childrenByParent[pending.removeFirst()].orEmpty()) {
                if (descendants.add(child.id)) pending.add(child.id)
            }
        }
        return descendants
    }

    private data class RunDeletion(
        val deletedRunIds: Set<String>,
        val rootRunIds: Set<String>,
    )

    /**
     * A Run may be deleted only when every message it owns is in the structural subtree and every
     * Run that its CASCADE would reach is equally removable. A partially-deleted original Run is
     * deliberately retained because it still owns the shared boundary USER.
     */
    private fun planRunDeletion(
        messages: List<MessageEntity>,
        runs: List<RunEntity>,
        deletedMessageIds: Set<String>,
    ): RunDeletion {
        if (runs.isEmpty()) return RunDeletion(emptySet(), emptySet())
        val messagesByRun = messages.groupBy { it.runId }
        val childrenByRun = runs.groupBy { it.parentRunId }
        val candidateRunIds = runs
            .filter { run ->
                messagesByRun[run.id].orEmpty().all { it.id in deletedMessageIds }
            }
            .mapTo(mutableSetOf()) { it.id }

        fun descendantsOf(runId: String): Set<String> {
            val descendants = linkedSetOf(runId)
            val pending = ArrayDeque<String>().apply { add(runId) }
            while (pending.isNotEmpty()) {
                for (child in childrenByRun[pending.removeFirst()].orEmpty()) {
                    if (descendants.add(child.id)) pending.add(child.id)
                }
            }
            return descendants
        }

        val descendantsByRun = runs.associate { it.id to descendantsOf(it.id) }
        val cascadeSafeRunIds = candidateRunIds.filterTo(mutableSetOf()) { runId ->
            descendantsByRun.getValue(runId).all { it in candidateRunIds }
        }
        val relevantSafeRunIds = cascadeSafeRunIds.filterTo(mutableSetOf()) { runId ->
            descendantsByRun.getValue(runId).any { descendantRunId ->
                messagesByRun[descendantRunId].orEmpty().any { it.id in deletedMessageIds }
            }
        }
        val runsById = runs.associateBy { it.id }
        val rootRunIds = relevantSafeRunIds.filterTo(linkedSetOf()) { runId ->
            runsById.getValue(runId).parentRunId !in relevantSafeRunIds
        }
        val deletedRunIds = rootRunIds
            .flatMapTo(linkedSetOf()) { descendantsByRun.getValue(it) }
        return RunDeletion(deletedRunIds, rootRunIds)
    }

    private fun repairMessageSelections(
        selections: Map<String?, String>,
        messages: List<MessageEntity>,
        deletedMessageIds: Set<String>,
    ): Map<String?, String> {
        val messagesById = messages.associateBy { it.id }
        val repaired = selections.toMutableMap()
        for ((parentId, selectedId) in selections) {
            if (parentId != null && parentId in deletedMessageIds) {
                repaired.remove(parentId)
                continue
            }
            if (selectedId !in deletedMessageIds) continue

            val selected = messagesById[selectedId]
            val siblings = messages
                .asSequence()
                .filter {
                    it.parentId == parentId &&
                        (selected == null || it.participant == selected.participant) &&
                        !isSynthetic(it.id)
                }
                .sortedWith(compareBy<MessageEntity> { it.timestamp }.thenBy { it.id })
                .toList()
            val replacement = previousThenNext(
                orderedIds = siblings.map { it.id },
                removedId = selectedId,
                deletedIds = deletedMessageIds,
            )
            if (replacement != null) repaired[parentId] = replacement
            else repaired.remove(parentId)
        }
        return repaired
    }

    private fun repairRunSelections(
        selections: Map<String?, String>,
        runs: List<RunEntity>,
        deletedRunIds: Set<String>,
    ): Map<String?, String> {
        val repaired = selections.toMutableMap()
        for ((parentRunId, selectedRunId) in selections) {
            if (parentRunId != null && parentRunId in deletedRunIds) {
                repaired.remove(parentRunId)
                continue
            }
            if (selectedRunId !in deletedRunIds) continue

            val siblings = runs
                .asSequence()
                .filter { it.parentRunId == parentRunId }
                .sortedWith(compareBy<RunEntity> { it.startedAt }.thenBy { it.id })
                .map { it.id }
                .toList()
            val replacement = previousThenNext(
                orderedIds = siblings,
                removedId = selectedRunId,
                deletedIds = deletedRunIds,
            )
            if (replacement != null) repaired[parentRunId] = replacement
            else repaired.remove(parentRunId)
        }
        return repaired
    }

    private fun previousThenNext(
        orderedIds: List<String>,
        removedId: String,
        deletedIds: Set<String>,
    ): String? {
        val removedIndex = orderedIds.indexOf(removedId)
        if (removedIndex < 0) return null
        return orderedIds
            .subList(0, removedIndex)
            .lastOrNull { it !in deletedIds }
            ?: orderedIds
                .subList(removedIndex + 1, orderedIds.size)
                .firstOrNull { it !in deletedIds }
    }

    private fun isSynthetic(messageId: String): Boolean =
        messageId.startsWith(Constants.TOOL_MSG_PREFIX) ||
            messageId.startsWith(Constants.RESULT_MSG_PREFIX)
}
