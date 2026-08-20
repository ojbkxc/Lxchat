package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.local.ChatEntity
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.model.SelectedAttachment
import com.lxseek.chat.util.DebugLog
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerDraftControllerTest {
    @Test
    fun `load and persist keep one revision checked durable projection`() = runTest {
        val repository = mockk<ConversationRepository>()
        val oldAttachment = attachment("old")
        val newAttachment = attachment("new")
        coEvery { repository.getConversation(CONVERSATION_ID) } returns chat(
            text = "old text",
            attachments = listOf(oldAttachment),
        )
        coEvery { repository.updateDraft(any(), any(), any()) } returns Unit
        coEvery { repository.deleteUnreferencedDraftAttachmentFiles(any()) } returns Unit
        val controller = ComposerDraftController(repository)

        val loaded = controller.load(CONVERSATION_ID)
        val persisted = controller.persist(
            conversationId = CONVERSATION_ID,
            expectedRevision = loaded.revision,
            text = "new text",
            attachments = listOf(newAttachment),
        )

        assertEquals(LoadedComposerDraft("old text", listOf(oldAttachment), 0L), loaded)
        assertEquals(DraftPersistResult(1L, succeeded = true, matchesRequested = true), persisted)
        coVerify(exactly = 1) {
            repository.updateDraft(
                CONVERSATION_ID,
                "new text",
                Json.encodeToString(listOf(newAttachment)),
            )
        }
        coVerify(exactly = 1) {
            repository.deleteUnreferencedDraftAttachmentFiles(listOf(oldAttachment))
        }
    }

    @Test
    fun `stale revision cannot overwrite newer snapshot and only reclaims explicit removals`() =
        runTest {
            val repository = mockk<ConversationRepository>()
            val explicitlyRemoved = attachment("removed")
            coEvery { repository.getConversation(CONVERSATION_ID) } returns chat()
            coEvery { repository.updateDraft(any(), any(), any()) } returns Unit
            coEvery { repository.deleteUnreferencedDraftAttachmentFiles(any()) } returns Unit
            val controller = ComposerDraftController(repository)
            val loaded = controller.load(CONVERSATION_ID)
            val accepted = controller.persist(
                conversationId = CONVERSATION_ID,
                expectedRevision = loaded.revision,
                text = "current",
                attachments = emptyList(),
            )

            val stale = controller.persist(
                conversationId = CONVERSATION_ID,
                expectedRevision = loaded.revision,
                text = "stale",
                attachments = emptyList(),
                explicitlyRemovedAttachments = listOf(explicitlyRemoved),
            )

            assertEquals(1L, accepted.revision)
            assertEquals(1L, stale.revision)
            assertTrue(stale.succeeded)
            assertFalse(stale.matchesRequested)
            coVerify(exactly = 1) { repository.updateDraft(CONVERSATION_ID, "current", null) }
            coVerify(exactly = 1) {
                repository.deleteUnreferencedDraftAttachmentFiles(listOf(explicitlyRemoved))
            }
        }

    @Test
    fun `accepted clear advances revision after durable write and returns prior attachments`() =
        runTest {
            val repository = mockk<ConversationRepository>()
            val attachment = attachment("accepted")
            coEvery { repository.getConversation(CONVERSATION_ID) } returns
                chat("pending", listOf(attachment))
            coEvery { repository.updateDraft(any(), any(), any()) } returns Unit
            coEvery { repository.deleteUnreferencedDraftAttachmentFiles(any()) } returns Unit
            val controller = ComposerDraftController(repository)
            controller.load(CONVERSATION_ID)

            val cleared = controller.clearAccepted(CONVERSATION_ID)
            val staleTailFlush = controller.persist(
                conversationId = CONVERSATION_ID,
                expectedRevision = 0L,
                text = "pending",
                attachments = listOf(attachment),
            )

            assertEquals(listOf(attachment), cleared)
            assertEquals(1L, staleTailFlush.revision)
            assertTrue(staleTailFlush.succeeded)
            assertFalse(staleTailFlush.matchesRequested)
            coVerify(exactly = 1) { repository.updateDraft(CONVERSATION_ID, "", null) }
            coVerify(exactly = 0) { repository.deleteUnreferencedDraftAttachmentFiles(any()) }
        }

    @Test
    fun `failed durable write preserves revision and does not reclaim attachments`() = runTest {
        val repository = mockk<ConversationRepository>()
        val attachment = attachment("candidate")
        coEvery { repository.getConversation(CONVERSATION_ID) } returns chat()
        coEvery { repository.updateDraft(any(), any(), any()) } throws IllegalStateException("db")
        val controller = ComposerDraftController(repository)
        mockkObject(DebugLog)
        every { DebugLog.e(any(), any(), any()) } just Runs

        try {
            val result = controller.persist(
                conversationId = CONVERSATION_ID,
                expectedRevision = 0L,
                text = "not durable",
                attachments = listOf(attachment),
            )

            assertEquals(
                DraftPersistResult(0L, succeeded = false, matchesRequested = false),
                result,
            )
            coVerify(exactly = 0) { repository.deleteUnreferencedDraftAttachmentFiles(any()) }
        } finally {
            unmockkObject(DebugLog)
        }
    }

    private fun chat(
        text: String = "",
        attachments: List<SelectedAttachment> = emptyList(),
    ) = ChatEntity(
        id = CONVERSATION_ID,
        title = "Conversation",
        draftText = text,
        draftAttachments = attachments.takeIf { it.isNotEmpty() }?.let {
            Json.encodeToString(it)
        },
    )

    private fun attachment(id: String) = SelectedAttachment(
        localId = id,
        uri = "content://draft/$id",
        type = "file",
        fileName = "$id.txt",
        localPath = "/private/$id.txt",
    )

    private companion object {
        const val CONVERSATION_ID = "conversation"
    }
}
