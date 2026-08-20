package com.lxseek.chat.ui.chat

import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatScrollTargetResolverTest {
    @Test
    fun `assistant target resolves to its parent user`() {
        val user = message("user", Participant.USER)
        val assistant = message("assistant", Participant.MODEL, parentId = user.id)

        assertEquals(user, resolveScrollTargetMessage(listOf(user, assistant), assistant.id))
    }

    @Test
    fun `implicit target resolves to the latest user`() {
        val first = message("first", Participant.USER)
        val assistant = message("assistant", Participant.MODEL, parentId = first.id)
        val latest = message("latest", Participant.USER, parentId = assistant.id)

        assertEquals(latest, resolveScrollTargetMessage(listOf(first, assistant, latest), null))
    }

    @Test
    fun `missing explicit target remains unresolved`() {
        assertNull(resolveScrollTargetMessage(listOf(message("user", Participant.USER)), "missing"))
    }

    private fun message(
        id: String,
        participant: Participant,
        parentId: String? = null,
    ): ChatMessage = ChatMessage(
        id = id,
        participant = participant,
        text = id,
        parentId = parentId,
    )
}
