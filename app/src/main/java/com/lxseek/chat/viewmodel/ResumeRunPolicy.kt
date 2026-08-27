package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.util.Constants

/**
 * Decides how an interrupted (STOPPED) assistant generation can be continued.
 *
 * This is the langgraph-style "checkpoint at the last durable tool boundary": rather than
 * re-running a multi-step tool loop from its user input (which is what regeneration does and
 * would re-execute every completed tool round), a continuation resumes from the deepest already
 * persisted point of the interrupted run — the last tool-RESULT row, or the interrupted model row
 * itself when no tool round was committed. Side effects are therefore never replayed.
 */
internal object ResumeRunPolicy {
    /** Only a terminal STOPPED model output is eligible to be continued. */
    fun isResumable(status: MessageStatus): Boolean = status == MessageStatus.STOPPED

    /**
     * Finds the durable continuation anchor for [interruptedRunId].
     *
     * Returns the deepest persisted tool-RESULT of the run (preferred), otherwise the interrupted
     * model row ([stoppedModelMessageId]) when it itself was durably persisted. Returns null when
     * the run cannot be located.
     */
    fun selectResumeTail(
        messages: List<MessageEntity>,
        interruptedRunId: String,
        stoppedModelMessageId: String,
    ): MessageEntity? {
        val runMessages = messages.filter { it.runId == interruptedRunId }
        val lastResult = runMessages
            .asSequence()
            .filter { it.id.startsWith(Constants.RESULT_MSG_PREFIX) }
            .maxWithOrNull(messageOrder)
        if (lastResult != null) return lastResult
        return runMessages.firstOrNull { it.id == stoppedModelMessageId }
    }

    private val messageOrder =
        compareBy<MessageEntity> {
            it.runSequence.takeIf { sequence -> sequence >= 0L } ?: Long.MAX_VALUE
        }
            .thenBy { it.timestamp }
            .thenBy { it.id }
}