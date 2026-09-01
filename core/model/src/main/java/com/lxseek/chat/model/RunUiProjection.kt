package com.lxseek.chat.model

import com.lxseek.chat.util.Constants

data class RunMessagePresentation(
    val showActions: Boolean = false,
    val copyText: String? = null,
    val deleteTargetMessageId: String? = null,
    val showBranchSelector: Boolean = false,
    val branchIndex: Int = 0,
    val totalBranches: Int = 1,
    val branchAnchorParentId: String? = null,
    val branchAnchorMessageId: String? = null,
)

/**
 * Derives Run-boundary UI affordances from the selected message path.
 *
 * Edit and Regenerate are two independent structural branch dimensions:
 *
 *  - edited USER messages are siblings under the preceding message, so their selector belongs
 *    only to the Run's boundary input;
 *  - regenerated MODEL roots are siblings under one shared USER input, so their selector belongs
 *    only to the selected Run's terminal output.
 *
 * Intermediate Pass input/output and synthetic tool/result rows never expose their own bars.
 */
object RunUiProjection {
    fun project(
        visibleMessages: List<ChatMessage>,
        allMessages: List<ChatMessage>,
    ): Map<String, RunMessagePresentation> {
        if (visibleMessages.isEmpty()) return emptyMap()

        // ID is the structural identity. A transient Room/optimistic-commit race must never be
        // interpreted as two real branches even if a caller accidentally supplies duplicates.
        val uniqueAllMessages = allMessages.distinctBy { it.id }
        val uniqueVisibleMessages = visibleMessages.distinctBy { it.id }
        val boundaryInputs = uniqueAllMessages.filter(::isBoundaryUserInput)
        val boundaryInputIds = boundaryInputs.mapTo(mutableSetOf()) { it.id }
        val editSiblingsByParent = boundaryInputs
            .groupBy { it.parentId }
            .mapValues { (_, messages) -> messages.sortedWith(branchOrder) }
        // A legacy Run can contain several regenerated assistant siblings with the same shared
        // user parent. Structural parentage, not Run ownership, is therefore the canonical branch
        // discriminator.
        val rootOutputs = uniqueAllMessages.filter {
            isVisibleModelOutput(it) &&
                it.parentId?.let(boundaryInputIds::contains) == true
        }
        val regenerationSiblingsByParent = rootOutputs
            .groupBy { it.parentId }
            .mapValues { (_, messages) -> messages.sortedWith(branchOrder) }

        val result = uniqueVisibleMessages
            .associate { it.id to RunMessagePresentation() }
            .toMutableMap()
        uniqueVisibleMessages
            .filter(::isBoundaryUserInput)
            .forEach { userBoundary ->
                val siblings = editSiblingsByParent[userBoundary.parentId].orEmpty()
                result[userBoundary.id] = RunMessagePresentation(
                    showActions = true,
                    copyText = userBoundary.text.takeIf { it.isNotBlank() },
                    deleteTargetMessageId = userBoundary.id,
                    showBranchSelector = siblings.size > 1,
                    branchIndex = siblings.indexOfFirst { it.id == userBoundary.id }.coerceAtLeast(0),
                    totalBranches = siblings.size.coerceAtLeast(1),
                    branchAnchorParentId = userBoundary.parentId,
                    branchAnchorMessageId = userBoundary.id,
                )
            }

        val visibleOutputsByRun = uniqueVisibleMessages
            .filter(::isVisibleModelOutput)
            .filter { !it.runId.isNullOrBlank() }
            .groupBy { checkNotNull(it.runId) }

        for ((runId, runOutputs) in visibleOutputsByRun) {
            val outputBoundary = runOutputs.maxWithOrNull(messageOrder) ?: continue
            val structuralRootOutput = runOutputs
                .filter { it.parentId?.let(boundaryInputIds::contains) == true }
                .minWithOrNull(messageOrder)
            // Malformed legacy rows may have lost the explicit root-output -> boundary-user edge.
            // Their safest deletion boundary is still the Run's earliest ordinary MODEL row.
            val rootOutput = structuralRootOutput ?: runOutputs.minWithOrNull(messageOrder)
            val siblings = structuralRootOutput
                ?.let { regenerationSiblingsByParent[it.parentId] }
                .orEmpty()
            result[outputBoundary.id] = RunMessagePresentation(
                showActions = true,
                copyText = outputBoundary.text.takeIf { it.isNotBlank() },
                deleteTargetMessageId = rootOutput?.id ?: outputBoundary.id,
                showBranchSelector = siblings.size > 1,
                branchIndex = siblings.indexOfFirst { it.id == rootOutput?.id }.coerceAtLeast(0),
                totalBranches = siblings.size.coerceAtLeast(1),
                branchAnchorParentId = rootOutput?.parentId,
                branchAnchorMessageId = rootOutput?.id,
            )
        }
        return result
    }

    private val messageOrder =
        compareBy<ChatMessage> { it.runSequence ?: Long.MAX_VALUE }
            .thenBy { it.timestamp }
            .thenBy { it.id }

    private val branchOrder =
        compareBy<ChatMessage> { it.timestamp }
            .thenBy { it.id }

    private fun isBoundaryUserInput(message: ChatMessage): Boolean =
        message.participant == Participant.USER &&
            message.runSequence == 0L &&
            !isSynthetic(message)

    private fun isVisibleModelOutput(message: ChatMessage): Boolean =
        message.participant == Participant.MODEL && !isSynthetic(message)

    private fun isSynthetic(message: ChatMessage): Boolean =
        message.id.startsWith(Constants.TOOL_MSG_PREFIX) ||
            message.id.startsWith(Constants.RESULT_MSG_PREFIX)
}
