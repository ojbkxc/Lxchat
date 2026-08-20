package com.lxseek.chat.data

import com.lxseek.chat.model.AttachmentItem
import com.lxseek.chat.model.AttachmentMeta
import com.lxseek.chat.model.MessageSegment
import com.lxseek.chat.model.ToolImageAttachment
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeBackupMediaPolicyTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun attachmentRoundTrip_reindexesPagesAndRestoresEachVideoIndependently() {
        val raw = json.encodeToString(
            AttachmentMeta(
                items = listOf(
                    AttachmentItem(
                        originalUri = "content://device/document.pdf",
                        type = "pdf",
                        imageIndex = 0,
                        pageCount = 3,
                    ),
                    AttachmentItem(
                        originalUri = "content://device/video-a.mp4",
                        type = "video",
                        imageIndex = 3,
                    ),
                    AttachmentItem(
                        originalUri = "content://device/video-b.mp4",
                        type = "video",
                        imageIndex = 4,
                    ),
                ),
            ),
        )
        val archiveEntries = mapOf(
            "content://device/video-a.mp4" to "media/videos/a.mp4",
            "content://device/video-b.mp4" to "media/videos/b.mp4",
        )

        val exported = requireNotNull(
            NativeBackupMediaPolicy.rewriteAttachmentMetaForExport(
                raw = raw,
                oldToNewImageIndex = mapOf(0 to 0, 2 to 1, 3 to 2, 4 to 3),
                archiveEntryForSource = archiveEntries::get,
            ),
        )
        val exportedItems = json.decodeFromString<AttachmentMeta>(exported).items
        assertNull(exportedItems[0].originalUri)
        assertEquals(0, exportedItems[0].imageIndex)
        assertEquals(2, exportedItems[0].pageCount)
        assertEquals("media/videos/a.mp4", exportedItems[1].originalUri)
        assertEquals("media/videos/b.mp4", exportedItems[2].originalUri)

        val restored = requireNotNull(
            NativeBackupMediaPolicy.restoreAttachmentMeta(
                raw = exported,
                archiveVersion = 4,
                legacyVideoUris = emptyMap(),
                restoredUriForArchiveEntry = {
                    when (it) {
                        "media/videos/a.mp4" -> "content://restored/video-a"
                        "media/videos/b.mp4" -> "content://restored/video-b"
                        else -> null
                    }
                },
            ),
        )
        val restoredItems = json.decodeFromString<AttachmentMeta>(restored).items
        assertEquals("content://restored/video-a", restoredItems[1].originalUri)
        assertEquals("content://restored/video-b", restoredItems[2].originalUri)
    }

    @Test
    fun legacyAttachmentRestore_keepsMultipleVideoSlotsDistinct() {
        val raw = json.encodeToString(
            AttachmentMeta(
                items = listOf(
                    AttachmentItem(type = "video", imageIndex = 0),
                    AttachmentItem(type = "video", imageIndex = 2),
                ),
            ),
        )

        val restored = requireNotNull(
            NativeBackupMediaPolicy.restoreAttachmentMeta(
                raw = raw,
                archiveVersion = 3,
                legacyVideoUris = mapOf(
                    0 to "content://restored/legacy-a",
                    2 to "content://restored/legacy-b",
                ),
                restoredUriForArchiveEntry = { null },
            ),
        )
        val items = json.decodeFromString<AttachmentMeta>(restored).items
        assertEquals("content://restored/legacy-a", items[0].originalUri)
        assertEquals("content://restored/legacy-b", items[1].originalUri)
    }

    @Test
    fun toolImages_roundTripOnlyCopiedFilesAndDropLegacyDevicePaths() {
        val imageA = ToolImageAttachment(
            path = "/data/user/0/app/tool-a.png",
            mimeType = "image/png",
            sizeBytes = 10,
            sha256 = "a",
        )
        val imageB = imageA.copy(
            path = "/data/user/0/app/tool-b.png",
            sha256 = "b",
        )
        val raw = json.encodeToString(
            listOf(MessageSegment(type = "tool", toolImages = listOf(imageA, imageB))),
        )
        assertEquals(
            listOf(imageA.path, imageB.path),
            NativeBackupMediaPolicy.toolImagePaths(raw),
        )

        val exported = requireNotNull(
            NativeBackupMediaPolicy.rewriteToolImagePathsForExport(raw) { source ->
                "media/images/tool-a.png".takeIf { source == imageA.path }
            },
        )
        val exportedImages = json.decodeFromString<List<MessageSegment>>(exported)
            .single()
            .toolImages
        assertEquals(listOf("media/images/tool-a.png"), exportedImages.map { it.path })

        val restored = requireNotNull(
            NativeBackupMediaPolicy.restoreToolImagePaths(
                raw = exported,
                archiveVersion = 4,
                restoredPathForArchiveEntry = { "C:/private/restored-tool-a.png" },
            ),
        )
        assertEquals(
            listOf("C:/private/restored-tool-a.png"),
            json.decodeFromString<List<MessageSegment>>(restored)
                .single()
                .toolImages
                .map { it.path },
        )

        val legacy = requireNotNull(
            NativeBackupMediaPolicy.restoreToolImagePaths(
                raw = raw,
                archiveVersion = 3,
                restoredPathForArchiveEntry = { error("legacy paths must never be resolved") },
            ),
        )
        assertEquals(
            emptyList<ToolImageAttachment>(),
            json.decodeFromString<List<MessageSegment>>(legacy).single().toolImages,
        )
    }
}
