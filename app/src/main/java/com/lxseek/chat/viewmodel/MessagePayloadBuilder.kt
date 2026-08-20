package com.lxseek.chat.viewmodel

import android.app.Application
import android.net.Uri
import com.lxseek.chat.R
import com.lxseek.chat.model.AttachmentItem
import com.lxseek.chat.model.AttachmentMeta
import com.lxseek.chat.model.SelectedAttachment
import com.lxseek.chat.util.Constants
import com.lxseek.chat.util.DebugLog
import com.lxseek.chat.util.PdfPageRenderer
import com.lxseek.chat.util.AttachmentSourceReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves outgoing message attachments (images / video / file / pdf) into concrete
 * image paths + structured AttachmentMeta. Extracted verbatim from ChatViewModel.
 */
class MessagePayloadBuilder(
    private val generationManager: GenerationManager,
    // Suspend callback for the PDF-render-failure notice, so this class doesn't
    // depend on ChatViewModel's SharedFlow.
    private val onSnackbar: suspend (String) -> Unit,
) {
    data class MessagePayload(
        val allImages: List<String>,
        val attachmentMeta: AttachmentMeta?,
        /** Files created by payload preparation itself. Delete only if ownership never reaches
         * either a queued guidance entry or a durable MessageEntity. */
        val preparedOwnedPaths: List<String> = emptyList(),
    )

    suspend fun buildMessagePayload(
        app: Application,
        images: List<String>,
        attachments: List<SelectedAttachment>
    ): MessagePayload = withContext(Dispatchers.IO) {
        // mediaUris: URIs that need processImages (images, video content:// URIs)
        // directPaths: paths that skip processImages (pre-extracted frames, PDF copies, rendered pages)
        val mediaUris = mutableListOf<String>()
        val directPaths = mutableListOf<String>()
        val sliceConfigs = mutableMapOf<String, VideoSliceConfig>()
        val metaItems = mutableListOf<AttachmentItem>()
        val preparedOwnedPaths = linkedSetOf<String>()
        var nextImageIndex = 0

        // Process legacy images list (backward compatibility)
        for (uri in images) {
            mediaUris.add(uri)
        }

        // Process new SelectedAttachment list
        for (att in attachments) {
            when (att.type) {
                "image" -> {
                    mediaUris.add(att.localPath ?: att.uri)
                    metaItems.add(AttachmentItem(
                        originalUri = att.localPath?.let { "file://$it" } ?: att.uri,
                        type = "image", mimeType = att.mimeType,
                        imageIndex = nextImageIndex
                    ))
                    nextImageIndex++
                }
                "video" -> {
                    // Copy video to local storage for export/playback survival
                    val videoExt = when {
                        att.mimeType?.contains("mp4") == true -> "mp4"
                        att.mimeType?.contains("webm") == true -> "webm"
                        att.mimeType?.contains("quicktime") == true -> "mov"
                        else -> "mp4"
                    }
                    val videoFile = java.io.File(app.filesDir, "vid_original_${java.util.UUID.randomUUID()}.$videoExt")
                    val localVideoUri = try {
                        val copied = app.contentResolver.openInputStream(android.net.Uri.parse(att.uri))?.use { input ->
                            videoFile.outputStream().use { input.copyTo(it) }
                            true
                        } ?: false
                        if (copied && videoFile.isFile) {
                            preparedOwnedPaths += videoFile.absolutePath
                            "file://${videoFile.absolutePath}"
                        } else {
                            att.uri
                        }
                    } catch (_: Exception) {
                        runCatching { videoFile.delete() }
                        // Fallback: keep original content URI (may expire)
                        att.uri
                    }

                    if (att.processedFrames != null && att.processedFrames.isNotEmpty()) {
                        metaItems.add(AttachmentItem(
                            originalUri = localVideoUri, type = "video",
                            fileName = att.fileName, mimeType = att.mimeType,
                            imageIndex = nextImageIndex, pageCount = att.frameCount
                        ))
                        directPaths.addAll(att.processedFrames)
                        nextImageIndex += att.processedFrames.size
                    } else {
                        val frameCount = att.frameCount ?: 1
                        metaItems.add(AttachmentItem(
                            originalUri = localVideoUri, type = "video",
                            fileName = att.fileName, mimeType = att.mimeType,
                            imageIndex = nextImageIndex, pageCount = att.frameCount
                        ))
                        mediaUris.add(att.uri)
                        if (att.frameCount != null && att.frameCount > 1 && att.sliceIntervalMs != null) {
                            sliceConfigs[att.uri] = VideoSliceConfig(
                                intervalMicros = att.sliceIntervalMs * 1000L,
                                frameCount = att.frameCount
                            )
                        }
                        nextImageIndex += frameCount
                    }
                }
                "file" -> {
                    val source = att.localPath ?: att.uri
                    val textContent = AttachmentSourceReader.readText(
                        context = app,
                        source = source,
                        maxChars = Constants.MAX_FILE_CONTENT_READ_LENGTH,
                    )
                    if (textContent == null) {
                        DebugLog.e(
                            "ChatViewModel",
                            "Failed to read attachment content: ${att.fileName}",
                        )
                    }
                    metaItems.add(AttachmentItem(
                        originalUri = att.localPath?.let { "file://$it" } ?: att.uri,
                        type = "file",
                        fileName = att.fileName, mimeType = att.mimeType,
                        textContent = textContent
                    ))
                }
                "pdf" -> {
                    val preparedPages = att.preRenderedPaths.orEmpty()
                    val usesPreparedPages = preparedPages.isNotEmpty()
                    val pagePaths = if (usesPreparedPages) {
                        val sel = att.selectedPages ?: preparedPages.indices.toSet()
                        preparedPages.filterIndexed { i, _ -> i in sel }
                    } else {
                        PdfPageRenderer.renderAsImages(app, Uri.parse(att.uri), att.selectedPages)
                    }
                    if (pagePaths.isEmpty()) {
                        onSnackbar(app.getString(R.string.pdf_render_failed))
                        continue
                    }
                    if (!usesPreparedPages) preparedOwnedPaths += pagePaths
                    metaItems.add(AttachmentItem(
                        originalUri = att.uri, type = "pdf",
                        fileName = att.fileName, mimeType = "application/pdf",
                        imageIndex = nextImageIndex, pageCount = pagePaths.size
                    ))
                    directPaths.addAll(pagePaths)
                    nextImageIndex += pagePaths.size
                }
            }
        }

        val processedImages = if (mediaUris.isNotEmpty()) generationManager.processImages(mediaUris, sliceConfigs) else emptyList()
        preparedOwnedPaths += processedImages
        val allImages = processedImages + directPaths

        // Recalculate imageIndex for all meta items based on final allImages positions.
        // nextImageIndex tracked the expected order:
        //   First N items correspond to mediaUris entries (→ processedImages)
        //   Remaining items correspond to directPaths entries
        // After processing, processedImages may differ in size from mediaUris.
        // We build a position map: for each metaItem that has imageIndex < mediaUris.size,
        // it was tracking an offset within mediaUris. We need the actual offset within processedImages.
        val uriToResultMap = mutableListOf<IntRange>() // for each mediaUris entry, the range in processedImages
        var pos = 0
        for (uri in mediaUris) {
            val start = pos
            // Count consecutive results belonging to this URI by scanning forward until
            // we find files that don't correspond. Since we can't distinguish, use a simple
            // heuristic: each URI produces either 0 or 1+ results. The slice configs tell us
            // how many frames per video.
            val config = sliceConfigs[uri]
            val expectedCount = config?.frameCount ?: 1
            val end = minOf(pos + expectedCount, processedImages.size)
            uriToResultMap.add(start until end)
            pos = end
        }
        // Cap at processedImages size
        val adjustedMetaItems = metaItems.map { item ->
            val idx = item.imageIndex
            if (idx == null) {
                item
            } else if (idx < mediaUris.size && idx < uriToResultMap.size) {
                val range = uriToResultMap[idx]
                item.copy(imageIndex = range.first)
            } else if (idx in mediaUris.size until (mediaUris.size + directPaths.size)) {
                // This item's imageIndex is relative to directPaths start
                item.copy(imageIndex = processedImages.size + (idx - mediaUris.size))
            } else {
                // Fallback: keep original index (shouldn't happen for well-formed input)
                item
            }
        }
        val attachmentMeta = if (adjustedMetaItems.isNotEmpty()) {
            AttachmentMeta(items = adjustedMetaItems)
        } else null
        MessagePayload(allImages, attachmentMeta, preparedOwnedPaths.toList())
    }
}
