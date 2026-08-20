package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.model.Participant
import com.lxseek.chat.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Test

class RunRegenerationPolicyTest {

    @Test
    fun selectBoundaryInput_returnsOwnedBoundaryAndIgnoresLaterInterventions() {
        val messages = listOf(
            message("${Constants.RESULT_MSG_PREFIX}tool", sequence = 1),
            message("later", sequence = 4, timestamp = 1),
            message("first", sequence = 0, timestamp = 3),
            message("middle", sequence = 2, timestamp = 2),
            message("other-run", sequence = 0, runId = "other"),
        )

        val boundary = RunRegenerationPolicy.selectBoundaryInput(messages, "run")

        assertEquals("first", boundary?.id)
    }

    @Test
    fun selectBoundaryInput_forRegenerationRun_resolvesSharedParentUser() {
        val messages = listOf(
            message("anchor", sequence = 0, runId = "source"),
            message(
                id = "regenerated-output",
                sequence = 0,
                runId = "regeneration",
                participant = Participant.MODEL,
                parentId = "anchor",
            ),
            message("later-intervention", sequence = 1, runId = "regeneration"),
        )

        val boundary = RunRegenerationPolicy.selectBoundaryInput(messages, "regeneration")

        assertEquals("anchor", boundary?.id)
    }

    private fun message(
        id: String,
        sequence: Long,
        timestamp: Long = sequence,
        runId: String = "run",
        participant: Participant = Participant.USER,
        parentId: String? = null,
    ) = MessageEntity(
        id = id,
        conversationId = "conversation",
        parentId = parentId,
        text = id,
        participant = participant,
        timestamp = timestamp,
        runId = runId,
        runSequence = sequence,
    )
}
