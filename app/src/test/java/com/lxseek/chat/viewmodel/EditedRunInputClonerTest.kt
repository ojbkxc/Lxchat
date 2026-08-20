package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.model.AttachmentItem
import com.lxseek.chat.model.AttachmentMeta
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EditedRunInputClonerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun commitsIndependentBackingFileAndReusesItAcrossMessageMetadata() = runBlocking {
        val sourceFile = temporaryFolder.newFile("source.jpg").apply {
            writeText("image bytes")
        }
        val destination = temporaryFolder.newFolder("committed")
        val source = message(
            images = listOf(sourceFile.absolutePath),
            attachmentMeta = Json.encodeToString(
                AttachmentMeta(
                    listOf(
                        AttachmentItem(
                            originalUri = "file://${sourceFile.absolutePath}",
                            type = "image",
                            fileName = "source.jpg",
                            mimeType = "image/jpeg",
                        ),
                    ),
                ),
            ),
        )

        val cloned = cloner(destination).clone(
            sourceInputs = listOf(source),
            destinationRunId = "edited-run",
        ).single()

        val clonedPath = cloned.images.single()
        val clonedMeta = Json.decodeFromString<AttachmentMeta>(checkNotNull(cloned.attachmentMeta))
        assertNotEquals(sourceFile.absolutePath, clonedPath)
        assertTrue(java.io.File(clonedPath).isFile)
        assertEquals("image bytes", java.io.File(clonedPath).readText())
        assertEquals("file://$clonedPath", clonedMeta.items.single().originalUri)
        assertEquals("edited-run", cloned.runId)
        Unit
    }

    @Test
    fun rollsBackCopiedBackingFilesWhenMetadataCloneFails() = runBlocking {
        val sourceFile = temporaryFolder.newFile("rollback.jpg").apply {
            writeText("image bytes")
        }
        val destination = temporaryFolder.newFolder("rolled-back")
        val source = message(
            images = listOf(sourceFile.absolutePath),
            attachmentMeta = "not-json",
        )

        try {
            cloner(destination).clone(
                sourceInputs = listOf(source),
                destinationRunId = "edited-run",
            )
            fail("Malformed attachment metadata must fail the clone")
        } catch (_: Exception) {
            Unit
        }

        assertTrue(destination.listFiles().orEmpty().isEmpty())
        Unit
    }

    private fun cloner(destination: java.io.File) = EditedRunInputCloner(
        destinationDir = destination,
        ioDispatcher = Dispatchers.Unconfined,
        idFactory = { "edited-input" },
        clock = { 10L },
    )

    private fun message(
        images: List<String>,
        attachmentMeta: String,
    ) = MessageEntity(
        id = "source-input",
        conversationId = "conversation",
        parentId = "previous",
        text = "original",
        images = images,
        attachmentMeta = attachmentMeta,
        participant = Participant.USER,
        status = MessageStatus.SUCCESS,
        timestamp = 1L,
        runId = "source-run",
        runSequence = 0,
        consumedAtPass = 0,
    )
}
