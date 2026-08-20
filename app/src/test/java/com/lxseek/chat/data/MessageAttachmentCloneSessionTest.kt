package com.lxseek.chat.data

import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.model.AttachmentItem
import com.lxseek.chat.model.AttachmentMeta
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MessageAttachmentCloneSessionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun eachMessageOwnsItsCopyWhileDuplicateReferencesInsideOneMessageAreReused() {
        val source = temporaryFolder.newFile("source.jpg").apply { writeText("image") }
        val destination = temporaryFolder.newFolder("copies")
        val meta = Json.encodeToString(
            AttachmentMeta(
                listOf(AttachmentItem(originalUri = "file://${source.absolutePath}", type = "image"))
            )
        )
        val message = message(source, meta)
        val session = MessageAttachmentCloneSession(destination)

        val first = session.cloneMessage(message, ownerKey = "first")
        val second = session.cloneMessage(message.copy(id = "second"), ownerKey = "second")
        session.commit()

        val firstMetaPath = Json.decodeFromString<AttachmentMeta>(checkNotNull(first.attachmentMeta))
            .items.single().originalUri!!.removePrefix("file://")
        assertEquals(first.images.single(), firstMetaPath)
        assertNotEquals(first.images.single(), second.images.single())
        assertTrue(File(first.images.single()).isFile)
        assertTrue(File(second.images.single()).isFile)
    }

    @Test
    fun rollbackDeletesOnlyFilesCreatedByTheSession() {
        val source = temporaryFolder.newFile("source.txt").apply { writeText("payload") }
        val destination = temporaryFolder.newFolder("rollback")
        val session = MessageAttachmentCloneSession(destination)
        val cloned = session.cloneMessage(message(source, null))

        session.rollback()

        assertTrue(source.isFile)
        assertFalse(File(cloned.images.single()).exists())
    }

    private fun message(source: File, meta: String?) = MessageEntity(
        id = "message",
        conversationId = "conversation",
        text = "attachment",
        images = listOf(source.absolutePath),
        status = MessageStatus.SUCCESS,
        participant = Participant.USER,
        timestamp = 0,
        attachmentMeta = meta,
        runId = "run",
        runSequence = 0,
    )
}
