package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.util.Constants

/**
 * Builds the API-facing history from the selected message ancestry.
 *
 * Tool protocol rows are stored as side chains below the visible model row, while a queued
 * intervention can itself have a tool/result row in its ancestry. Consequently, blindly walking
 * both the ancestry and every side chain can replay the same tool round twice. This assembler has
 * one ownership rule: every persisted message id may enter the API path exactly once.
 */
internal object ApiPathAssembler {
    fun assemble(
        ancestorPath: List<MessageEntity>,
        allMessages: List<MessageEntity>,
    ): List<MessageEntity> {
        if (ancestorPath.isEmpty()) return emptyList()

        val protocolChildren = allMessages
            .asSequence()
            .filter { it.isToolProtocolRow() }
            .groupBy(MessageEntity::parentId)
        val emittedIds = mutableSetOf<String>()
        val result = mutableListOf<MessageEntity>()

        fun emitProtocolSubtree(root: MessageEntity, runId: String, sourceModelName: String?) {
            if (root.runId != runId || !emittedIds.add(root.id)) return
            val enriched = if (root.modelName == null && sourceModelName != null) {
                root.copy(modelName = sourceModelName)
            } else {
                root
            }
            result += enriched
            protocolChildren[root.id]
                .orEmpty()
                .asSequence()
                .filter { it.runId == runId }
                .sortedWith(messageOrder)
                .forEach { emitProtocolSubtree(it, runId, sourceModelName) }
        }

        for (entity in ancestorPath) {
            if (entity.isToolProtocolRow()) {
                if (emittedIds.add(entity.id)) result += entity
                continue
            }

            val toolRoots = protocolChildren[entity.id]
                .orEmpty()
                .asSequence()
                .filter {
                    it.runId == entity.runId &&
                        it.id.startsWith(Constants.TOOL_MSG_PREFIX)
                }
                .sortedWith(messageOrder)
                .toList()
            toolRoots.forEach { emitProtocolSubtree(it, entity.runId, entity.modelName) }

            if (emittedIds.add(entity.id)) {
                // The visible model row aggregates every tool round for UI rendering. During the
                // live tool loop that row is still the in-progress output placeholder; replaying
                // it after the just-persisted result would make the next request end in assistant
                // output instead of tool input. Terminal rows, however, contain the completed
                // answer after those tool rounds and remain part of later historical requests.
                val isInProgressToolAggregate =
                    toolRoots.isNotEmpty() && entity.status.isGenerationInProgress()
                if (!isInProgressToolAggregate) {
                    result += if (toolRoots.isEmpty()) entity else entity.copy(toolCallJson = null)
                }
            }
        }
        return result
    }

    private val messageOrder =
        compareBy<MessageEntity> { it.runSequence }
            .thenBy { it.timestamp }
            .thenBy { it.id }

    private fun MessageEntity.isToolProtocolRow(): Boolean =
        id.startsWith(Constants.TOOL_MSG_PREFIX) ||
            id.startsWith(Constants.RESULT_MSG_PREFIX)

    private fun MessageStatus.isGenerationInProgress(): Boolean = when (this) {
        MessageStatus.TRANSCRIBING,
        MessageStatus.SENDING,
        MessageStatus.THINKING,
        MessageStatus.TOOL_CALLING -> true
        MessageStatus.SUCCESS,
        MessageStatus.STOPPED,
        MessageStatus.ERROR -> false
    }
}
