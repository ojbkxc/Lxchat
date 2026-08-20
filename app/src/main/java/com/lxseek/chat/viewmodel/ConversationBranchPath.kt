package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.data.local.RunEntity
import com.lxseek.chat.util.Constants

/**
 * A deterministic snapshot of the branch that is actually visible in the conversation UI.
 *
 * The message tree remains the visual source of truth. Run ancestry is then closed explicitly so
 * persistence operations can never detach a selected Run from one of its parents.
 */
internal data class ConversationBranchPath(
    /** The exact root-to-leaf traversal chosen from the persisted message graph. */
    val selectedPathMessages: List<MessageEntity>,
    /** [selectedPathMessages] plus protocol side-chain rows needed for provider-valid history. */
    val structuralMessages: List<MessageEntity>,
    val visibleMessages: List<MessageEntity>,
    val runIds: List<String>,
)

internal fun resolveConversationBranchPath(
    messages: List<MessageEntity>,
    runs: List<RunEntity>,
    selectedChildren: Map<String?, String>,
    throughMessageId: String? = null,
): ConversationBranchPath? {
    if (messages.isEmpty()) {
        return ConversationBranchPath(emptyList(), emptyList(), emptyList(), emptyList())
    }

    val structuralPath = resolveStructuralMessagePath(messages, selectedChildren) ?: return null
    val visiblePath = structuralPath.filterNot { it.isSynthetic() }
    val endMessage = throughMessageId?.let { messageId ->
        visiblePath.firstOrNull { it.id == messageId } ?: return null
    }
    val structuralEndIndex = endMessage?.let { message ->
        structuralPath.indexOfFirst { it.id == message.id }
    } ?: structuralPath.lastIndex
    val selectedStructuralPath = structuralPath.take(structuralEndIndex + 1)

    val runsById = runs.associateBy { it.id }
    val orderedRunIds = linkedSetOf<String>()
    val visiting = mutableSetOf<String>()

    fun includeRunWithAncestors(runId: String): Boolean {
        if (runId in orderedRunIds) return true
        if (!visiting.add(runId)) return false
        val run = runsById[runId] ?: return false
        val parentIncluded = run.parentRunId?.let(::includeRunWithAncestors) ?: true
        visiting.remove(runId)
        if (!parentIncluded) return false
        orderedRunIds += runId
        return true
    }

    for (message in selectedStructuralPath) {
        if (!includeRunWithAncestors(message.runId)) return null
    }

    val includedRunIds = orderedRunIds.toSet()
    val includedMessages = linkedMapOf<String, MessageEntity>()
    selectedStructuralPath.forEach { includedMessages[it.id] = it }

    // Tool calls are persisted as protocol side chains. A parallel call has one tool_ parent and
    // multiple result_ siblings, while the visible branch can select only one sibling to continue
    // traversal. Close the selected path over every synthetic descendant so fork/share never
    // silently drops another result from the same provider turn.
    val protocolChildren = messages
        .asSequence()
        .filter { it.isSynthetic() && it.runId in includedRunIds }
        .groupBy { it.parentId }
    fun includeProtocolDescendants(parentId: String) {
        protocolChildren[parentId]
            .orEmpty()
            .sortedWith(messageOrder)
            .forEach { child ->
                if (includedMessages.putIfAbsent(child.id, child) == null) {
                    includeProtocolDescendants(child.id)
                }
            }
    }
    selectedStructuralPath.forEach { includeProtocolDescendants(it.id) }
    val selectedStructuralIds = selectedStructuralPath.mapTo(mutableSetOf()) { it.id }

    return ConversationBranchPath(
        selectedPathMessages = selectedStructuralPath,
        // Keep the selected path first. Callers use that order to reproduce explicit branch
        // selections; protocol siblings are appended deterministically for persistence.
        structuralMessages = selectedStructuralPath +
            includedMessages.values
                .asSequence()
                .filter { it.id !in selectedStructuralIds }
                .sortedWith(
                    compareBy<MessageEntity> {
                        orderedRunIds.indexOf(it.runId).let { index ->
                            if (index >= 0) index else Int.MAX_VALUE
                        }
                    }.then(messageOrder)
                )
                .toList(),
        visibleMessages = selectedStructuralPath.filterNot { it.isSynthetic() },
        runIds = orderedRunIds.toList(),
    )
}

/**
 * Mirrors [ConversationUiState.resolvePath], but retains synthetic tool/result rows so a cloned
 * protocol graph can preserve the exact selected edge at every parent.
 */
private fun resolveStructuralMessagePath(
    messages: List<MessageEntity>,
    selectedChildren: Map<String?, String>,
): List<MessageEntity>? {
    val messagesByParent = messages
        .groupBy { it.parentId }
        .mapValues { (_, siblings) ->
            siblings.sortedWith(compareBy<MessageEntity> { it.timestamp }.thenBy { it.id })
        }
    val path = mutableListOf<MessageEntity>()
    val visited = mutableSetOf<String>()
    var cursor: String? = null

    while (true) {
        val siblings = messagesByParent[cursor].orEmpty()
        if (siblings.isEmpty()) break
        val selectedId = selectedChildren[cursor]
        val visibleSiblings = siblings.filterNot { it.isSynthetic() }
        val selected = if (visibleSiblings.isNotEmpty()) {
            visibleSiblings.firstOrNull { it.id == selectedId } ?: visibleSiblings.last()
        } else {
            siblings.firstOrNull { it.id == selectedId } ?: siblings.last()
        }
        if (!visited.add(selected.id)) return null
        path += selected
        cursor = selected.id
    }
    return path
}

internal fun MessageEntity.isSynthetic(): Boolean =
    id.startsWith(Constants.TOOL_MSG_PREFIX) ||
        id.startsWith(Constants.RESULT_MSG_PREFIX)

private val messageOrder =
    compareBy<MessageEntity> { it.runSequence }
        .thenBy { it.timestamp }
        .thenBy { it.id }
