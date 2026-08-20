package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.local.ConversationDraftAttachmentReference
import com.lxseek.chat.data.local.MessageAttachmentReference
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.model.AttachmentItem
import com.lxseek.chat.model.AttachmentMeta
import com.lxseek.chat.model.SelectedAttachment
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AttachmentOrphanSweeperTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `sweep preserves every durable and fresh reference while deleting only eligible orphans`() =
        runTest {
            val root = temporaryFolder.root
            val messageImage = oldFile(root, "att_message")
            val messageMetadataImage = oldFile(File(root, "images"), "camera_message")
            val draftLocal = oldFile(File(root, "run-inputs"), "draft_local")
            val draftFrame = oldFile(File(root, "fork-attachments"), "draft_frame")
            val draftRendered = oldFile(root, "pdf_draft")

            val orphanRoot = oldFile(root, "img_orphan")
            val orphanCamera = oldFile(File(root, "images"), "camera_orphan")
            val orphanRunInput = oldFile(File(root, "run-inputs"), "run_orphan")
            val orphanFork = oldFile(File(root, "fork-attachments"), "fork_orphan")
            val freshEligible = file(root, "vid_fresh", NOW - 30 * 60 * 1000L)
            val unrelatedRoot = oldFile(root, "notes.txt")
            val unrelatedImage = oldFile(File(root, "images"), "thumbnail_other")

            val conversations = mockk<ConversationRepository>()
            coEvery {
                conversations.getMessageAttachmentReferencesPage(null, 64)
            } returns listOf(
                MessageAttachmentReference(
                    id = "message",
                    images = listOf(fileUri(messageImage)),
                    attachmentMeta = Json.encodeToString(
                        AttachmentMeta(
                            listOf(
                                AttachmentItem(
                                    originalUri = fileUri(messageMetadataImage),
                                    type = "image",
                                )
                            )
                        )
                    ),
                ),
                MessageAttachmentReference(
                    id = "malformed-message",
                    images = emptyList(),
                    attachmentMeta = "not-json",
                ),
            )
            coEvery {
                conversations.getConversationDraftAttachmentReferencesPage(null, 64)
            } returns listOf(
                ConversationDraftAttachmentReference(
                    id = "conversation",
                    draftAttachments = Json.encodeToString(
                        listOf(
                            SelectedAttachment(
                                uri = "content://draft",
                                type = "file",
                                localPath = draftLocal.absolutePath,
                                processedFrames = listOf(draftFrame.absolutePath),
                                preRenderedPaths = listOf(draftRendered.absolutePath),
                            )
                        )
                    ),
                ),
                ConversationDraftAttachmentReference(
                    id = "malformed-draft",
                    draftAttachments = "not-json",
                ),
            )

            AttachmentOrphanSweeper(
                conversations = conversations,
                filesDirectory = root,
                now = { NOW },
            ).sweep()

            listOf(
                messageImage,
                messageMetadataImage,
                draftLocal,
                draftFrame,
                draftRendered,
                freshEligible,
                unrelatedRoot,
                unrelatedImage,
            ).forEach { retained -> assertTrue(retained.absolutePath, retained.exists()) }
            listOf(orphanRoot, orphanCamera, orphanRunInput, orphanFork).forEach { deleted ->
                assertFalse(deleted.absolutePath, deleted.exists())
            }
            coVerify(exactly = 1) {
                conversations.getMessageAttachmentReferencesPage(null, 64)
            }
            coVerify(exactly = 1) {
                conversations.getConversationDraftAttachmentReferencesPage(null, 64)
            }
        }

    private fun oldFile(parent: File, name: String): File =
        file(parent, name, NOW - 2 * 60 * 60 * 1000L)

    private fun file(parent: File, name: String, lastModified: Long): File {
        parent.mkdirs()
        return File(parent, name).also { file ->
            file.writeText("test")
            check(file.setLastModified(lastModified))
        }
    }

    private fun fileUri(file: File): String = "file://${file.absolutePath}"

    private companion object {
        const val NOW = 10_000_000L
    }
}
