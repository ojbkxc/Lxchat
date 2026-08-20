package com.lxseek.chat.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RunRecoveryPolicyTest {
    @Test
    fun recoverMessageStatusStopsOnlyInFlightModelMessages() {
        val inFlight = listOf(
            MessageStatus.SENDING,
            MessageStatus.THINKING,
            MessageStatus.TOOL_CALLING,
            MessageStatus.TRANSCRIBING,
        )

        inFlight.forEach { status ->
            assertEquals(
                MessageStatus.STOPPED,
                RunRecoveryPolicy.recoverMessageStatus(Participant.MODEL, status),
            )
            assertEquals(
                status,
                RunRecoveryPolicy.recoverMessageStatus(Participant.USER, status),
            )
        }
        listOf(MessageStatus.SUCCESS, MessageStatus.ERROR, MessageStatus.STOPPED).forEach { status ->
            assertEquals(
                status,
                RunRecoveryPolicy.recoverMessageStatus(Participant.MODEL, status),
            )
        }
    }

    @Test
    fun stopIncompleteTools_onlyTerminalizesLiveToolStates() {
        val recovered = RunRecoveryPolicy.stopIncompleteTools(
            listOf(
                MessageSegment(type = "answer", content = "kept"),
                MessageSegment(
                    type = "tool",
                    toolName = "file_write",
                    toolState = ToolExecutionStates.CALLING,
                ),
                MessageSegment(
                    type = "tool",
                    toolName = "shell",
                    toolState = ToolExecutionStates.RUNNING,
                ),
                MessageSegment(
                    type = "tool",
                    toolName = "background",
                    toolState = ToolExecutionStates.BACKGROUND_RUNNING,
                ),
                MessageSegment(
                    type = "tool",
                    toolName = "done",
                    toolState = ToolExecutionStates.SUCCEEDED,
                ),
            )
        )

        assertEquals(null, recovered[0].toolState)
        assertEquals(ToolExecutionStates.STOPPED, recovered[1].toolState)
        assertEquals(ToolExecutionStates.STOPPED, recovered[2].toolState)
        assertEquals(ToolExecutionStates.BACKGROUND_RUNNING, recovered[3].toolState)
        assertEquals(ToolExecutionStates.SUCCEEDED, recovered[4].toolState)
    }
}
