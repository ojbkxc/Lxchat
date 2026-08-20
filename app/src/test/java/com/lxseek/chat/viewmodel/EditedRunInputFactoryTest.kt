package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.model.AttachmentItem
import com.lxseek.chat.model.AttachmentMeta
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class EditedRunInputFactoryTest {
    @Test
    fun editedInput_replacesTextAndIdentityWhileRetainingEveryAttachmentField() {
        val originalMeta = AttachmentMeta(
            items = listOf(
                AttachmentItem(
                    originalUri = "file:///private/video.mp4",
                    type = "video",
                    fileName = "video.mp4",
                    mimeType = "video/mp4",
                    imageIndex = 2,
                    warning = "warning",
                    transcription = "spoken words",
                ),
                AttachmentItem(
                    originalUri = "content://document/42",
                    type = "pdf",
                    fileName = "paper.pdf",
                    mimeType = "application/pdf",
                    pageCount = 12,
                    textContent = "document text",
                ),
            )
        )
        val source = MessageEntity(
            id = "source",
            conversationId = "conversation",
            parentId = "previous",
            text = "old text",
            images = listOf("/private/frame-1.jpg", "/private/frame-2.jpg"),
            status = MessageStatus.SUCCESS,
            participant = Participant.USER,
            timestamp = 1,
            attachmentMeta = Json.encodeToString(originalMeta),
            runId = "source-run",
            runSequence = 0,
            consumedAtPass = 0,
        )

        val edited = EditedRunInputFactory.create(
            source = source,
            id = "edited",
            parentId = "previous",
            text = "new text",
            timestamp = 10,
            destinationRunId = "edited-run",
            runSequence = 0,
            cloneBackingPath = { "$it.copy" },
        )

        assertEquals("new text", edited.text)
        assertEquals(
            listOf("/private/frame-1.jpg.copy", "/private/frame-2.jpg.copy"),
            edited.images,
        )
        assertEquals(
            originalMeta.copy(
                items = listOf(
                    originalMeta.items[0].copy(
                        originalUri = "file:///private/video.mp4.copy"
                    ),
                    originalMeta.items[1],
                )
            ),
            Json.decodeFromString<AttachmentMeta>(checkNotNull(edited.attachmentMeta)),
        )
        assertEquals("edited-run", edited.runId)
        assertEquals(MessageStatus.SUCCESS, edited.status)
        assertEquals(0, edited.consumedAtPass)
    }
}
