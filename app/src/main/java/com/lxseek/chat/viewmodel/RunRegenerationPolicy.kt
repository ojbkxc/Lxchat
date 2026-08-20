package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.model.Participant
import com.lxseek.chat.util.Constants

/**
 * Resolves the shared user anchor for regeneration.
 *
 * A normal/edit Run owns its boundary user at sequence 0. A regeneration Run intentionally owns
 * only its assistant branch (plus any later queued interventions), so its anchor is the parent of
 * its earliest ordinary model row. This keeps repeated regeneration under the same user message
 * instead of cloning or progressively nesting user inputs.
 */
internal object RunRegenerationPolicy {
    fun selectBoundaryInput(
        messages: List<MessageEntity>,
        runId: String,
    ): MessageEntity? {
        val runMessages = messages.filter { it.runId == runId }
        val ownedBoundary = runMessages
            .asSequence()
            .filter {
                it.participant == Participant.USER &&
                    !it.id.startsWith(Constants.RESULT_MSG_PREFIX) &&
                    !it.id.startsWith(Constants.TOOL_MSG_PREFIX)
            }
            .minWithOrNull(messageOrder)
            ?.takeIf { it.runSequence == 0L }
        if (ownedBoundary != null) return ownedBoundary

        val rootOutput = runMessages
            .asSequence()
            .filter {
                it.participant == Participant.MODEL &&
                    !it.id.startsWith(Constants.RESULT_MSG_PREFIX) &&
                    !it.id.startsWith(Constants.TOOL_MSG_PREFIX)
            }
            .minWithOrNull(messageOrder)
            ?: return null
        return messages.firstOrNull {
            it.id == rootOutput.parentId &&
                it.participant == Participant.USER &&
                !it.id.startsWith(Constants.RESULT_MSG_PREFIX) &&
                !it.id.startsWith(Constants.TOOL_MSG_PREFIX)
        }
    }

    private val messageOrder =
        compareBy<MessageEntity> {
            it.runSequence.takeIf { sequence -> sequence >= 0L } ?: Long.MAX_VALUE
        }
            .thenBy { it.timestamp }
            .thenBy { it.id }
}
