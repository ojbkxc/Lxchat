package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Test

class UiMessageCommitPolicyTest {
    @Test
    fun roomFirstCommit_replacesRowsByIdWithoutAppendingDuplicates() {
        val existing = listOf(
            message("before", "before"),
            message("user", "room user", Participant.USER),
            message("model", "room placeholder", Participant.MODEL),
        )
        val committed = listOf(
            message("user", "controller user", Participant.USER),
            message("model", "controller placeholder", Participant.MODEL),
        )

        val merged = UiMessageCommitPolicy.upsert(existing, committed)

        assertEquals(listOf("before", "user", "model"), merged.map { it.id })
        assertEquals("controller user", merged.single { it.id == "user" }.text)
        assertEquals("controller placeholder", merged.single { it.id == "model" }.text)
    }

    @Test
    fun preexistingDuplicateIds_areCollapsedEvenWithoutReplacement() {
        val duplicate = message("user", "same", Participant.USER)

        val merged = UiMessageCommitPolicy.upsert(
            existing = listOf(duplicate, duplicate, message("model", "answer", Participant.MODEL)),
            committed = emptyList(),
        )

        assertEquals(listOf("user", "model"), merged.map { it.id })
    }

    private fun message(
        id: String,
        text: String,
        participant: Participant = Participant.MODEL,
    ) = ChatMessage(
        id = id,
        text = text,
        participant = participant,
        timestamp = id.hashCode().toLong(),
        runId = "run",
    )
}
