package com.lxseek.chat.model

/**
 * Pure process-death recovery rules for content stored inside a model message.
 *
 * A tool that was only CALLING/RUNNING cannot still be executing after the Android process that
 * owned it has died. Terminal tool states remain authoritative, including BACKGROUND_RUNNING
 * where the external operation intentionally outlives the request coroutine.
 */
object RunRecoveryPolicy {
    fun recoverMessageStatus(
        participant: Participant,
        status: MessageStatus,
    ): MessageStatus = if (
        participant == Participant.MODEL &&
        status in setOf(
            MessageStatus.SENDING,
            MessageStatus.THINKING,
            MessageStatus.TOOL_CALLING,
            MessageStatus.TRANSCRIBING,
        )
    ) {
        MessageStatus.STOPPED
    } else {
        status
    }

    fun stopIncompleteTools(segments: List<MessageSegment>): List<MessageSegment> =
        segments.map { segment ->
            if (
                segment.type == "tool" &&
                segment.toolState !in ToolExecutionStates.TERMINAL
            ) {
                segment.copy(toolState = ToolExecutionStates.STOPPED)
            } else {
                segment
            }
        }
}
