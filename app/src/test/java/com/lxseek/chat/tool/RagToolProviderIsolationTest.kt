package com.lxseek.chat.tool

import com.lxseek.chat.data.local.ChatEntity
import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.viewmodel.GenerationContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagToolProviderIsolationTest {
    private val conversations = mockk<ConversationRepository>()
    private val provider = RagToolProvider(conversations)
    private val context = GenerationContext(accessPastConversations = true)

    @Test
    fun listConversations_readsOnlySearchableConversationSource() = runTest {
        coEvery { conversations.getSearchableConversationsList() } returns listOf(
            ChatEntity(id = "visible", title = "Visible", lastUpdated = 123L)
        )

        val result = Json.parseToJsonElement(
            provider.execute("list_conversations", "{}", context)
        ).jsonObject

        assertEquals(1, result.getValue("total").jsonPrimitive.content.toInt())
        assertEquals(
            "visible",
            result.getValue("conversations").jsonArray.single().jsonObject
                .getValue("id").jsonPrimitive.content,
        )
        coVerify(exactly = 1) { conversations.getSearchableConversationsList() }
        coVerify(exactly = 0) { conversations.getAllConversationsList() }
    }

    @Test
    fun readConversation_rejectsHiddenConversationBeforeReadingMessages() = runTest {
        coEvery { conversations.getSearchableConversation("hidden") } returns null

        val result = Json.parseToJsonElement(
            provider.execute(
                "read_conversation",
                "{\"conversation_id\":\"hidden\"}",
                context,
            )
        ).jsonObject

        assertEquals("not_found", result.getValue("error").jsonPrimitive.content)
        verify(exactly = 0) { conversations.getMessagesForConversation(any()) }
    }

    @Test
    fun keywordSearch_dropsStaleHiddenMatchBeforeWindowExpansion() = runTest {
        val hiddenMatch = MessageEntity(
            id = "message-hidden",
            conversationId = "hidden",
            text = "private task result",
            status = MessageStatus.SUCCESS,
            participant = Participant.MODEL,
            timestamp = 123L,
            runId = "run-hidden",
            runSequence = 0,
        )
        coEvery { conversations.searchMessages("private", any()) } returns listOf(hiddenMatch)
        coEvery { conversations.getSearchableConversation("hidden") } returns null

        val result = Json.parseToJsonElement(
            provider.execute(
                "search_conversations",
                "{\"query\":\"private\"}",
                context,
            )
        ).jsonObject

        assertTrue(result.getValue("results").jsonArray.isEmpty())
        verify(exactly = 0) { conversations.getMessagesForConversation(any()) }
    }
}
