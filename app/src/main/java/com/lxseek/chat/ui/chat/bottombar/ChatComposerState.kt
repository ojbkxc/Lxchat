package com.lxseek.chat.ui.chat.bottombar

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.lxseek.chat.model.MessageReplyRef
import com.lxseek.chat.model.SelectedAttachment
import com.lxseek.chat.ui.chat.VideoSliceDialog
import com.lxseek.chat.util.DebugLog
import com.lxseek.chat.ui.common.LxChatHaptics
import com.lxseek.chat.ui.common.LocalLxChatHaptics
import com.lxseek.chat.util.FileValidator
import com.lxseek.chat.util.PdfPageRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

data class CameraCaptureTarget(
    val uri: Uri,
    val privatePath: String,
)

data class PendingAttachmentRemoval(
    val id: Long,
    val ownerConversationId: String,
    val attachment: SelectedAttachment,
)

/**
 * State holder for the chat composer's attachment subsystem (images / videos / PDFs /
 * generic files): the picked-attachment list, per-attachment processing progress, and
 * the PDF page-select + video-slice dialog state, plus the logic for picking, frame
 * extraction, page rendering, and removal.
 *
 * Hoisted out of the `ChatBottomBar` composable body (Phase E6) so the composable holds
 * UI and this holder owns attachment state/behaviour — the Compose "separate state from
 * UI" best practice. Obtain via [rememberChatComposerState]; the composable reads/writes
 * `composer.xxx` and wires the launchers/dialogs to these methods.
 */
class ChatComposerState(
    private val context: Context,
    private val haptics: LxChatHaptics,
    private val scope: CoroutineScope,
) {
    var selectedAttachments by mutableStateOf<List<SelectedAttachment>>(emptyList())
    var processingStates by mutableStateOf<Map<String, Float>>(emptyMap())
    var pendingSend by mutableStateOf(false)
    /** Message quoted by a pending reply; null when no reply is armed. */
    var replyTo by mutableStateOf<MessageReplyRef?>(null)
        private set
    private var draftOwnerConversationId: String? = null
    private var attachmentRemovalIds = 0L
    var pendingAttachmentRemovals by mutableStateOf<List<PendingAttachmentRemoval>>(emptyList())
        private set

    // PDF page selection dialog state
    var showPdfPageDialog by mutableStateOf(false)
    var pendingPdfUri by mutableStateOf<String?>(null)
    var pendingPdfPages by mutableIntStateOf(0)
    var pendingPdfFileName by mutableStateOf<String?>(null)
    var pendingPdfMimeType by mutableStateOf<String?>(null)
    var pendingPdfRenderedPaths by mutableStateOf<List<String>>(emptyList())
    var pendingPdfIsRendering by mutableStateOf(false)
    var pendingPdfRenderProgress by mutableStateOf(0 to 0)
    var pdfDialogHiddenForPreview by mutableStateOf(false)
    // Background render job for the page-select dialog, so a dismiss can cancel it and
    // let renderAllPages clean up its partially-written page files.
    var pdfRenderJob by mutableStateOf<Job?>(null)
    // In-flight video frame-extraction jobs, keyed by video uri, so removing a video while
    // it is still extracting can cancel the job (which deletes its partial frame files).
    val videoExtractionJobs = mutableMapOf<String, Job>()

    // Video slicing dialog state
    var showVideoSliceDialog by mutableStateOf(false)
    var pendingVideoUri by mutableStateOf<String?>(null)
    var pendingVideoDurationMs by mutableLongStateOf(0L)
    var pendingVideoQueue by mutableStateOf<List<String>>(emptyList())
    private var videoMetadataJob: Job? = null
    private val attachmentInspectionMutex = Mutex()

    // Generic file validation and camera failures share one dialog surface, but not one title.
    // Keeping the title alongside the message prevents camera launch errors from being
    // misreported as an unsupported MIME type.
    private var rejectionMessageState by mutableStateOf<String?>(null)
    private var rejectionTitleState by mutableIntStateOf(
        com.lxseek.chat.R.string.file_unsupported_title,
    )
    var rejectedMessage: String?
        get() = rejectionMessageState
        set(value) {
            rejectionMessageState = value
            rejectionTitleState = com.lxseek.chat.R.string.file_unsupported_title
        }
    val rejectedTitleRes: Int
        get() = rejectionTitleState

    private data class InspectedFile(
        val uri: Uri,
        val validation: FileValidator.Result,
        val fileName: String?,
        val pageCount: Int,
    )

    /** Clear the attachment list after a successful send. The extracted-frame / rendered-page
     *  files are now owned by the stored message (via images field in MessageEntity) — they
     *  must NOT be deleted here; message deletion handles that. */
    fun clearAttachments() {
        selectedAttachments = emptyList()
    }

    /** Arm a quote-reply for the message at the top of the composer. */
    fun armReply(reply: MessageReplyRef) {
        haptics.selection()
        replyTo = reply
    }

    /** Clear the armed reply (dismiss or after a successful send). */
    fun clearReply() {
        replyTo = null
    }

    fun bindDraftOwner(conversationId: String?) {
        draftOwnerConversationId = conversationId
    }

    fun isDraftOwner(conversationId: String): Boolean =
        draftOwnerConversationId == conversationId

    fun attachmentRemovalsFor(conversationId: String): List<PendingAttachmentRemoval> =
        pendingAttachmentRemovals.filter { removal ->
            removal.ownerConversationId == conversationId
        }

    fun acknowledgeAttachmentRemovals(ids: Set<Long>) {
        if (ids.isNotEmpty()) {
            pendingAttachmentRemovals =
                pendingAttachmentRemovals.filterNot { removal -> removal.id in ids }
        }
    }

    /**
     * Commits the current PDF selection without doing filesystem work in the click transaction.
     * Selected page files become message-owned attachments; unselected files are reclaimed on IO.
     */
    fun confirmPendingPdfSelection(selectedPages: Set<Int>) {
        val uri = pendingPdfUri ?: return
        val renderedPaths = pendingPdfRenderedPaths
        val keptPaths = renderedPaths.filterIndexed { index, _ -> index in selectedPages }
        val discardedPaths = renderedPaths.filterIndexed { index, _ -> index !in selectedPages }
        selectedAttachments = selectedAttachments + SelectedAttachment(
            uri = uri,
            type = "pdf",
            mimeType = pendingPdfMimeType,
            fileName = pendingPdfFileName,
            selectedPages = keptPaths.indices.toSet(),
            preRenderedPaths = keptPaths,
        )
        resetPendingPdfState()
        deleteFilesAsync(discardedPaths)
    }

    /**
     * Cancels and clears the pending PDF state synchronously, then reclaims completed page files
     * off Main. A cancelled renderer owns cleanup of any files it had not published yet.
     */
    fun dismissPendingPdf() {
        pdfRenderJob?.cancel()
        pdfRenderJob = null
        val discardedPaths = pendingPdfRenderedPaths
        resetPendingPdfState()
        deleteFilesAsync(discardedPaths)
    }

    private fun resetPendingPdfState() {
        showPdfPageDialog = false
        pendingPdfUri = null
        pendingPdfPages = 0
        pendingPdfFileName = null
        pendingPdfMimeType = null
        pendingPdfRenderedPaths = emptyList()
        pendingPdfIsRendering = false
        pendingPdfRenderProgress = 0 to 0
        pdfDialogHiddenForPreview = false
    }

    private fun deleteFilesAsync(paths: List<String>) {
        if (paths.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            paths.forEach { path -> runCatching { java.io.File(path).delete() } }
        }
    }

    /** Copy a content URI to app-private storage, returning the absolute path (or null). */
    private suspend fun copyToPrivate(uri: Uri, ext: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                com.lxseek.chat.util.FileImport.copyToPrivate(
                    context = context,
                    uri = uri,
                    prefix = "att",
                    extension = ext,
                )?.absolutePath
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Creates the camera's output file inside LxChat's private files directory and exposes only
     * this one path through FileProvider. The system camera writes the full-resolution image
     * directly; LxChat never needs CAMERA permission or a public gallery entry.
     */
    suspend fun createCameraCaptureTarget(): CameraCaptureTarget? =
        withContext(Dispatchers.IO) {
            var target: java.io.File? = null
            try {
                val directory = java.io.File(context.filesDir, "images")
                check(directory.exists() || directory.mkdirs()) {
                    "Unable to create private image directory"
                }
                target = java.io.File(directory, "camera_${UUID.randomUUID()}.jpg")
                check(target.createNewFile()) { "Unable to create camera target" }
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    target,
                )
                CameraCaptureTarget(uri = uri, privatePath = target.absolutePath)
            } catch (error: Exception) {
                target?.let { runCatching { it.delete() } }
                DebugLog.e("ChatComposer", "Unable to prepare camera capture", error)
                null
            }
        }

    /**
     * Commits a successful camera file as a normal image attachment. Cancellation and malformed
     * zero-byte camera results reclaim the private target asynchronously.
     */
    fun completeCameraCapture(privatePath: String, captured: Boolean) {
        scope.launch {
            val attachment = withContext(Dispatchers.IO) {
                val file = privateCameraFile(privatePath)
                if (file == null) {
                    null
                } else if (!captured || !file.isFile || file.length() <= 0L) {
                    runCatching { file.delete() }
                    null
                } else {
                    runCatching {
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file,
                        )
                        SelectedAttachment(
                            uri = uri.toString(),
                            type = "image",
                            fileName = file.name,
                            mimeType = "image/jpeg",
                            fileSize = file.length(),
                            localPath = file.absolutePath,
                        )
                    }.getOrElse { error ->
                        runCatching { file.delete() }
                        DebugLog.e("ChatComposer", "Unable to attach camera capture", error)
                        null
                    }
                }
            }
            if (attachment != null) {
                haptics.selection()
                selectedAttachments = selectedAttachments + attachment
            } else if (captured) {
                rejectedMessage = context.getString(
                    com.lxseek.chat.R.string.attachment_copy_failed_image,
                )
            }
        }
    }

    private fun privateCameraFile(path: String): java.io.File? = runCatching {
        val directory = java.io.File(context.filesDir, "images").canonicalFile
        java.io.File(path).canonicalFile.takeIf { file ->
            file.parentFile == directory && file.name.startsWith("camera_")
        }
    }.getOrNull()

    fun reportCameraPreparationFailure() {
        rejectionTitleState = com.lxseek.chat.R.string.camera
        rejectionMessageState = context.getString(
            com.lxseek.chat.R.string.attachment_copy_failed_image,
        )
    }

    /** Remove the attachment at [index]. Conversation-owned files are reclaimed only after the
     *  new draft is durable; new-chat files have no possible draft owner and can be deleted now. */
    fun removeAttachmentAt(index: Int) {
        val removed = selectedAttachments.getOrNull(index) ?: return
        haptics.selection()
        // Cancel in-flight video extraction + delete partial frames
        if (videoExtractionJobs.containsKey(removed.uri)) {
            videoExtractionJobs[removed.uri]?.cancel()
            videoExtractionJobs.remove(removed.uri)
        }
        val uriStr = removed.uri
        selectedAttachments = selectedAttachments.toMutableList().also { it.removeAt(index) }
        processingStates = processingStates - uriStr
        val ownerConversationId = draftOwnerConversationId
        if (ownerConversationId == null) {
            // A new-chat attachment has never entered a persisted draft. It is still unique to
            // this composer, so reclaim it off Main immediately.
            scope.launch(Dispatchers.IO) {
                com.lxseek.chat.util.AttachmentFiles.deleteBacking(removed)
            }
        } else {
            attachmentRemovalIds =
                if (attachmentRemovalIds == Long.MAX_VALUE) 1L else attachmentRemovalIds + 1L
            pendingAttachmentRemovals = pendingAttachmentRemovals + PendingAttachmentRemoval(
                id = attachmentRemovalIds,
                ownerConversationId = ownerConversationId,
                attachment = removed,
            )
        }
    }

    // Helper: process next video in queue, showing slice dialog
    fun processNextVideo() {
        if (
            pendingVideoQueue.isEmpty() ||
            showVideoSliceDialog ||
            videoMetadataJob?.isActive == true
        ) return

        val uri = pendingVideoQueue.first()
        pendingVideoQueue = pendingVideoQueue.drop(1)
        videoMetadataJob = scope.launch {
            try {
                val durationMs = withContext(Dispatchers.IO) {
                    try {
                        val retriever = android.media.MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(context, Uri.parse(uri))
                            retriever.extractMetadata(
                                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                            )?.toLongOrNull() ?: 0L
                        } finally {
                            retriever.release()
                        }
                    } catch (_: Exception) {
                        0L
                    }
                }
                pendingVideoUri = uri
                pendingVideoDurationMs = durationMs
                showVideoSliceDialog = true
            } finally {
                videoMetadataJob = null
            }
        }
    }

    // Start frame extraction for a video, return list of frame paths
    suspend fun extractVideoFrames(videoUri: String, frameCount: Int, intervalMs: Long): List<String> {
        return withContext(Dispatchers.IO) {
            val paths = mutableListOf<String>()
            try {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                retriever.setDataSource(context, android.net.Uri.parse(videoUri))
                var timeUs = 0L
                val intervalUs = intervalMs * 1000L
                for (i in 0 until frameCount) {
                    ensureActive()
                    val bitmap = retriever.getFrameAtTime(
                        timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST
                    )
                    if (bitmap != null) {
                        val file = java.io.File(context.filesDir, "vid_${java.util.UUID.randomUUID()}_$i.jpg")
                        file.outputStream().use { out ->
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                        }
                        bitmap.recycle()
                        paths.add(file.absolutePath)
                    }
                    timeUs += intervalUs
                    // Snapshot-map read-modify-write must stay main-confined (see onPickImages).
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        processingStates = processingStates + (videoUri to (i + 1).toFloat() / frameCount)
                    }
                }
                } finally { retriever.release() }
            } catch (c: CancellationException) {
                // Removed mid-extraction: drop the partial frame files instead of orphaning them.
                paths.forEach { runCatching { java.io.File(it).delete() } }
                throw c
            } catch (e: Exception) { DebugLog.e("ChatComposer", "Video frame extraction failed", e) }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                processingStates = processingStates - videoUri
            }
            paths
        }
    }

    /** Handle images picked from the photo picker. Copies each URI to app-private
     *  storage immediately so the path is stable regardless of URI permission expiry. */
    fun onPickImages(uris: List<Uri>) {
        if (uris.isNotEmpty()) haptics.selection()
        val newAttachments = uris.map {
            SelectedAttachment(
                uri = it.toString(), type = "image",
                mimeType = null,
            )
        }
        selectedAttachments = selectedAttachments + newAttachments
        for (uriObj in uris) {
            val uriStr = uriObj.toString()
            processingStates = processingStates + (uriStr to 0f)
            // Launch on the scope's Main dispatcher: copyToPrivate hops to IO internally, so every
            // read-modify-write of the snapshot lists below runs main-confined. Launching the whole
            // block on IO made N parallel completions race each other's list assignment (lost
            // update → a localPath silently reverted to null → attachment dropped at send).
            scope.launch {
                val mimeType = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.getType(uriObj)
                    } catch (_: Exception) {
                        null
                    }
                }
                val localPath = copyToPrivate(uriObj, "img")
                if (localPath != null) {
                    selectedAttachments = selectedAttachments.map { a ->
                        if (a.uri == uriStr) {
                            a.copy(localPath = localPath, mimeType = mimeType)
                        } else {
                            a
                        }
                    }
                } else {
                    // Copy failed -- remove the attachment and show rejection
                    val idx = selectedAttachments.indexOfFirst { it.uri == uriStr }
                    if (idx >= 0) {
                        selectedAttachments = selectedAttachments.toMutableList().also { it.removeAt(idx) }
                    }
                    rejectedMessage = context.getString(com.lxseek.chat.R.string.attachment_copy_failed_image)
                }
                processingStates = processingStates - uriStr
            }
        }
    }

    /** Handle videos picked from the video picker; queues them and kicks off the slice dialog. */
    fun onPickVideos(uris: List<Uri>) {
        if (uris.isNotEmpty()) haptics.selection()
        val urisToQueue = uris.map { it.toString() }
        pendingVideoQueue = pendingVideoQueue + urisToQueue
        if (!showVideoSliceDialog) processNextVideo()
    }

    /** Handle generic files picked from the document picker (validates, queues first PDF for
     *  page rendering, adds the rest as attachments). */
    fun onPickFiles(uris: List<Uri>, onInitPdfSelection: ((Set<Int>) -> Unit)?) {
        if (uris.isEmpty()) return
        scope.launch {
            // SAF providers can block on MIME, metadata and page-count queries. Serialize commits
            // on Main, but perform the complete inspection batch on IO.
            attachmentInspectionMutex.withLock {
                val inspected = withContext(Dispatchers.IO) {
                    uris.map { uri ->
                        val validation = FileValidator.validate(context, uri)
                        val fileName = if (validation.valid) {
                            FileValidator.resolveFileName(context, uri)
                        } else {
                            null
                        }
                        val pageCount = if (
                            validation.valid &&
                            validation.mimeType == "application/pdf"
                        ) {
                            PdfPageRenderer.getPageCount(context, uri)
                        } else {
                            0
                        }
                        InspectedFile(uri, validation, fileName, pageCount)
                    }
                }

                val validAttachments = mutableListOf<SelectedAttachment>()
                val fileCopySources = mutableListOf<Pair<Uri, SelectedAttachment>>()
                val rejectedMessages = mutableListOf<String>()
                for (item in inspected) {
                    val validation = item.validation
                    if (!validation.valid) {
                        rejectedMessages.add(
                            FileValidator.errorMessage(
                                context,
                                checkNotNull(validation.error),
                                validation.mimeType,
                            )
                        )
                        continue
                    }
                    val mimeType = validation.mimeType
                    val type = if (mimeType == "application/pdf") "pdf" else "file"
                    if (type == "pdf" && !showPdfPageDialog && item.pageCount > 0) {
                        pendingPdfUri = item.uri.toString()
                        pendingPdfPages = item.pageCount
                        pendingPdfFileName = item.fileName
                        pendingPdfMimeType = mimeType
                        pendingPdfRenderedPaths = emptyList()
                        pendingPdfIsRendering = true
                        pendingPdfRenderProgress = 0 to item.pageCount
                        showPdfPageDialog = true
                        onInitPdfSelection?.invoke(
                            (0 until minOf(item.pageCount, 5)).toSet()
                        )
                        pdfRenderJob = scope.launch {
                            val paths = withContext(Dispatchers.IO) {
                                PdfPageRenderer.renderAllPages(
                                    context,
                                    item.uri,
                                    maxPages = item.pageCount,
                                    onProgress = { cur, total ->
                                        scope.launch {
                                            pendingPdfRenderProgress = cur to total
                                        }
                                    },
                                )
                            }
                            pendingPdfRenderedPaths = paths
                            pendingPdfIsRendering = false
                        }
                        continue
                    }
                    val attachment = SelectedAttachment(
                        uri = item.uri.toString(),
                        type = type,
                        mimeType = mimeType,
                        fileName = item.fileName,
                    )
                    validAttachments.add(attachment)
                    if (type == "file") {
                        fileCopySources.add(item.uri to attachment)
                    }
                }
                if (rejectedMessages.isNotEmpty()) {
                    haptics.reject()
                    rejectedMessage = rejectedMessages.joinToString("\n")
                }
                if (validAttachments.isNotEmpty()) haptics.selection()
                selectedAttachments = selectedAttachments + validAttachments

                // Copy generic files to app-private storage immediately. Main-confined mutations
                // prevent parallel completions from losing another attachment's localPath.
                for ((uri, attachment) in fileCopySources) {
                    val uriStr = uri.toString()
                    val ext = attachment.fileName?.substringAfterLast('.', "bin") ?: "bin"
                    processingStates = processingStates + (uriStr to 0f)
                    scope.launch {
                        val localPath = copyToPrivate(uri, ext)
                        if (localPath != null) {
                            selectedAttachments = selectedAttachments.map { current ->
                                if (current.uri == uriStr) {
                                    current.copy(localPath = localPath)
                                } else {
                                    current
                                }
                            }
                        } else {
                            val idx = selectedAttachments.indexOfFirst { it.uri == uriStr }
                            if (idx >= 0) {
                                selectedAttachments = selectedAttachments
                                    .toMutableList()
                                    .also { it.removeAt(idx) }
                            }
                            rejectedMessage = context.getString(
                                com.lxseek.chat.R.string.attachment_copy_failed_file
                            )
                        }
                        processingStates = processingStates - uriStr
                    }
                }
            }
        }
    }

    /** Add a sliced video as an attachment and start background frame extraction. */
    fun addSlicedVideo(vidUri: String, frameCount: Int, intervalMs: Long) {
        val attachment = SelectedAttachment(
            uri = vidUri, type = "video",
            frameCount = frameCount,
            sliceIntervalMs = intervalMs,
            fileName = null,
            mimeType = "video/*"
        )
        selectedAttachments = selectedAttachments + attachment
        processingStates = processingStates + (vidUri to 0f)

        // Start frame extraction and store result paths; track job so an X-delete while
        // extracting can cancel it (extractVideoFrames cleans up partial files on cancel).
        // Main-launched: extraction hops to IO internally; list/map mutations stay main-confined.
        val job = scope.launch {
            val fileName = withContext(Dispatchers.IO) {
                FileValidator.resolveFileName(context, Uri.parse(vidUri))
            }
            val framePaths = extractVideoFrames(vidUri, frameCount, intervalMs)
            selectedAttachments = selectedAttachments.map { a ->
                if (a.uri == vidUri) {
                    a.copy(processedFrames = framePaths, fileName = fileName)
                } else {
                    a
                }
            }
            videoExtractionJobs.remove(vidUri)
        }
        videoExtractionJobs[vidUri] = job
    }
}

@Composable
fun rememberChatComposerState(): ChatComposerState {
    val context = LocalContext.current
    val haptics = LocalLxChatHaptics.current
    val scope = rememberCoroutineScope()
    return remember(context, haptics, scope) { ChatComposerState(context, haptics, scope) }
}
