package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.AttachmentMeta
import com.lxseek.chat.model.SelectedAttachment
import com.lxseek.chat.util.AttachmentFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * A message queued behind an in-progress generation. It deliberately remains outside Room until
 * the next durable boundary accepts it into a fresh Run.
 */
data class QueuedSend(
    val id: String,
    val text: String,
    /** Model selected in the originating conversation when Send was tapped. */
    val modelId: String,
    val attachments: List<SelectedAttachment>,
    /** Provenance only; drain always creates a fresh Run and never reuses this id. */
    val runId: String,
    /** Legacy bare-image paths retained for queue display and cleanup. */
    val images: List<String> = emptyList(),
    /** Prepared payload owned by this in-memory guidance until its boundary commit. */
    val preparedImages: List<String> = emptyList(),
    val preparedAttachmentMetaJson: String? = null,
    val preparedOwnedPaths: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

data class GuidanceBatchLease(
    val id: String,
    val batch: List<QueuedSend>,
) {
    init {
        require(id.isNotBlank())
        require(batch.isNotEmpty())
    }
}

/** One queue drain becomes one durable user bubble while preserving FIFO content and ownership. */
internal fun mergeQueuedGuidance(batch: List<QueuedSend>): QueuedSend {
    require(batch.isNotEmpty())
    val first = batch.first()
    val attachmentItems = batch.flatMap { queued ->
        queued.preparedAttachmentMetaJson
            ?.let { raw -> Json.decodeFromString<AttachmentMeta>(raw).items }
            .orEmpty()
    }
    return first.copy(
        text = batch.joinToString(separator = "\n\n", transform = QueuedSend::text),
        modelId = batch.last().modelId,
        attachments = batch.flatMap(QueuedSend::attachments),
        images = batch.flatMap(QueuedSend::images),
        preparedImages = batch.flatMap(QueuedSend::preparedImages),
        preparedAttachmentMetaJson = attachmentItems
            .takeIf(List<*>::isNotEmpty)
            ?.let { Json.encodeToString(AttachmentMeta(it)) },
        preparedOwnedPaths = batch.flatMap(QueuedSend::preparedOwnedPaths),
    )
}

/**
 * Sole owner of pending and claimed in-memory guidance for one conversation.
 *
 * A claim transfers the complete FIFO batch to one lease. Failed claims return to the front;
 * durable claims transfer attachment ownership to Room; disposal owns only still-pending cleanup.
 */
internal class GuidanceLeaseStore(
    private val newLeaseId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()
    private val _queuedSends = MutableStateFlow<List<QueuedSend>>(emptyList())
    val queuedSends: StateFlow<List<QueuedSend>> = _queuedSends.asStateFlow()

    private val claimedGuidance = mutableMapOf<String, List<QueuedSend>>()
    private var disposed = false

    fun enqueue(send: QueuedSend) {
        synchronized(lock) {
            check(!disposed) { "Conversation guidance store was disposed" }
            _queuedSends.value = _queuedSends.value + send
        }
    }

    fun remove(id: String): QueuedSend? = synchronized(lock) {
        val removed = _queuedSends.value.firstOrNull { it.id == id } ?: return null
        _queuedSends.value = _queuedSends.value.filterNot { it.id == id }
        removed
    }

    /** Transfer the pending batch to one explicit in-flight owner. */
    fun claim(): GuidanceBatchLease? = synchronized(lock) {
        if (disposed || _queuedSends.value.isEmpty()) return null
        val lease = GuidanceBatchLease(newLeaseId(), _queuedSends.value)
        _queuedSends.value = emptyList()
        check(claimedGuidance.put(lease.id, lease.batch) == null)
        lease
    }

    /** Settle one exact lease without allowing duplicate or unknown results to mutate the queue. */
    fun settle(leaseId: String, durable: Boolean): Boolean {
        var orphaned = emptyList<QueuedSend>()
        synchronized(lock) {
            val batch = claimedGuidance.remove(leaseId) ?: return false
            when {
                durable -> Unit
                disposed -> orphaned = batch
                else -> _queuedSends.value = batch + _queuedSends.value
            }
        }
        orphaned.forEach(QueuedSend::deleteOwnedFiles)
        return true
    }

    /** Mark the owner closed and transfer its still-pending batch to the disposal caller. */
    fun disposePending(): List<QueuedSend> = synchronized(lock) {
        disposed = true
        _queuedSends.value.also { _queuedSends.value = emptyList() }
    }
}

internal fun QueuedSend.deleteOwnedFiles() {
    AttachmentFiles.deleteBacking(attachments)
    preparedOwnedPaths.forEach { path -> runCatching { File(path).delete() } }
}
