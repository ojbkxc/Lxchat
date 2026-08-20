package com.lxseek.chat.ui.tasks

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

internal enum class TaskHistoryPreviewPhase {
    IDLE,
    VIEWING,
    RETURNING,
}

/**
 * Navigation state for a Task History conversation.
 *
 * A history conversation is a transient preview: opening another history entry must keep the
 * original chat destination, and returning to Tasks does not release the preview until the Tasks
 * overlay has completely covered the chat.
 */
internal data class TaskHistoryPreviewState(
    val phase: TaskHistoryPreviewPhase = TaskHistoryPreviewPhase.IDLE,
    val taskId: String? = null,
    val originConversationId: String? = null,
    val originWasNewChat: Boolean = true,
) {
    val active: Boolean
        get() = phase != TaskHistoryPreviewPhase.IDLE

    fun open(
        taskId: String,
        currentConversationId: String?,
        isNewChatMode: Boolean,
    ): TaskHistoryPreviewState =
        if (active) {
            copy(
                phase = TaskHistoryPreviewPhase.VIEWING,
                taskId = taskId,
            )
        } else {
            TaskHistoryPreviewState(
                phase = TaskHistoryPreviewPhase.VIEWING,
                taskId = taskId,
                originConversationId = currentConversationId,
                originWasNewChat = isNewChatMode || currentConversationId == null,
            )
        }

    fun requestReturn(): TaskHistoryPreviewState =
        if (active) copy(phase = TaskHistoryPreviewPhase.RETURNING) else this

    companion object {
        val Idle = TaskHistoryPreviewState()
    }
}

internal val TaskHistoryPreviewStateSaver: Saver<TaskHistoryPreviewState, Any> =
    listSaver(
        save = { state ->
            listOf(
                state.phase.name,
                state.taskId.orEmpty(),
                state.originConversationId.orEmpty(),
                state.originWasNewChat,
            )
        },
        restore = { values ->
            val phase = (values.getOrNull(0) as? String)
                ?.let { saved -> TaskHistoryPreviewPhase.entries.firstOrNull { it.name == saved } }
                ?: TaskHistoryPreviewPhase.IDLE
            val taskId = (values.getOrNull(1) as? String)?.takeIf(String::isNotBlank)
            val originConversationId =
                (values.getOrNull(2) as? String)?.takeIf(String::isNotBlank)
            val originWasNewChat = values.getOrNull(3) as? Boolean ?: true

            if (phase == TaskHistoryPreviewPhase.IDLE || taskId == null) {
                TaskHistoryPreviewState.Idle
            } else {
                TaskHistoryPreviewState(
                    phase = phase,
                    taskId = taskId,
                    originConversationId = originConversationId,
                    originWasNewChat = originWasNewChat || originConversationId == null,
                )
            }
        },
    )
