package com.lxseek.chat.util

import com.lxseek.chat.model.SelectedAttachment
import java.io.File

/**
 * Single home for deleting the app-private files that back a [SelectedAttachment], so the composer
 * (removing a picked attachment) and the generation layer (dropping a queued send that was never
 * sent) don't duplicate — and drift on — the same delete logic.
 *
 * Ownership rule: these files are private copies the composer made at pick time. They stay alive
 * only while a [SelectedAttachment] still references them. Once an attachment's message is stored
 * the files belong to the [com.lxseek.chat.data.local.MessageEntity] (message deletion cleans
 * them up) — so callers must NOT call this on an attachment whose send actually went through; only
 * on abandoned ones (X-removed picks, X-removed / cleared queued sends).
 *
 * Never touches the original content:// [SelectedAttachment.uri] — that isn't ours to delete.
 */
object AttachmentFiles {

    /** Delete every private file backing [att]: extracted video frames, rendered PDF pages, and the
     *  copied-to-private image/file. Best-effort and exception-safe per file. */
    fun deleteBacking(att: SelectedAttachment) {
        att.processedFrames?.forEach { deleteQuietly(it) }
        att.preRenderedPaths?.forEach { deleteQuietly(it) }
        att.localPath?.let { deleteQuietly(it) }
    }

    /** Delete the backing files for every attachment in [attachments]. */
    fun deleteBacking(attachments: List<SelectedAttachment>) {
        attachments.forEach { deleteBacking(it) }
    }

    private fun deleteQuietly(path: String) {
        runCatching { File(path).delete() }
    }
}
