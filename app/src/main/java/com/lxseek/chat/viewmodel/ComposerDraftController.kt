package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.model.SelectedAttachment
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class LoadedComposerDraft(
    val text: String,
    val attachments: List<SelectedAttachment>,
    val revision: Long,
)

data class DraftPersistResult(
    val revision: Long,
    val succeeded: Boolean,
    val matchesRequested: Boolean,
)

private data class PersistedComposerDraft(
    val text: String,
    val attachments: List<SelectedAttachment>,
    val revision: Long,
)

/**
 * Owns the serialized, revision-checked composer-draft cache and its durable projection.
 *
 * This controller has no generation or UI lifecycle authority. Its accepted-clear result only
 * reports attachments whose draft ownership was durably removed; the caller decides whether the
 * accepted input has another owner before asking the repository to reclaim them.
 */
internal class ComposerDraftController(
    private val conversations: ConversationRepository,
) {
    private val persistenceMutex = Mutex()
    private val persistedDrafts = mutableMapOf<String, PersistedComposerDraft>()

    /**
     * Persists one revision-checked composer snapshot. Once a write starts it is atomic with
     * respect to cancellation; newer UI snapshots wait behind the mutex instead of overtaking it.
     */
    suspend fun persist(
        conversationId: String,
        expectedRevision: Long,
        text: String,
        attachments: List<SelectedAttachment>,
        explicitlyRemovedAttachments: List<SelectedAttachment> = emptyList(),
    ): DraftPersistResult = withContext(Dispatchers.IO + NonCancellable) {
        persistenceMutex.withLock {
            val current = try {
                persistedDrafts[conversationId]
                    ?: read(conversationId).also {
                        persistedDrafts[conversationId] = it
                    }
            } catch (e: Exception) {
                DebugLog.e("ChatViewModel", "Failed to read draft for $conversationId", e)
                return@withLock DraftPersistResult(
                    revision = persistedDrafts[conversationId]?.revision ?: expectedRevision,
                    succeeded = false,
                    matchesRequested = false,
                )
            }
            if (current.revision != expectedRevision) {
                reclaimAttachments(explicitlyRemovedAttachments)
                return@withLock DraftPersistResult(
                    revision = current.revision,
                    succeeded = true,
                    matchesRequested = current.text == text && current.attachments == attachments,
                )
            }

            if (current.text == text && current.attachments == attachments) {
                reclaimAttachments(explicitlyRemovedAttachments)
                return@withLock DraftPersistResult(
                    revision = current.revision,
                    succeeded = true,
                    matchesRequested = true,
                )
            }

            try {
                val json = if (attachments.isEmpty()) null else Json.encodeToString(attachments)
                conversations.updateDraft(conversationId, text, json)
                val next = PersistedComposerDraft(
                    text = text,
                    attachments = attachments,
                    revision = current.revision + 1L,
                )
                persistedDrafts[conversationId] = next
                reclaimAttachments(current.attachments + explicitlyRemovedAttachments)
                DraftPersistResult(
                    revision = next.revision,
                    succeeded = true,
                    matchesRequested = true,
                )
            } catch (e: Exception) {
                DebugLog.e("ChatViewModel", "Failed to persist draft for $conversationId", e)
                DraftPersistResult(
                    revision = current.revision,
                    succeeded = false,
                    matchesRequested = false,
                )
            }
        }
    }

    /**
     * A successfully accepted send invalidates every older UI tail-flush by advancing the cached
     * revision only after the draft reference is durably cleared.
     */
    suspend fun clearAccepted(
        conversationId: String,
        acceptedText: String,
        acceptedAttachments: List<SelectedAttachment>,
    ): List<SelectedAttachment> =
        withContext(Dispatchers.IO + NonCancellable) {
            persistenceMutex.withLock {
                try {
                    val current = persistedDrafts[conversationId] ?: read(conversationId)
                    // Clear only when the draft still holds exactly what was accepted; if the
                    // user typed new content while the send was in flight, that content stays
                    // (and the revision is untouched so it keeps persisting) — otherwise the
                    // accepted input would be silently wiped and later flushes fail the
                    // revision check (C2).
                    val draftStillMatches =
                        current.text == acceptedText && current.attachments == acceptedAttachments
                    if (draftStillMatches) {
                        conversations.updateDraft(conversationId, "", null)
                        persistedDrafts[conversationId] = PersistedComposerDraft(
                            text = "",
                            attachments = emptyList(),
                            revision = current.revision + 1L,
                        )
                    }
                    if (draftStillMatches) current.attachments else emptyList()
                } catch (e: Exception) {
                    DebugLog.e(
                        "ChatViewModel",
                        "Failed to clear accepted draft for $conversationId",
                        e,
                    )
                    emptyList()
                }
            }
        }

    /** Loads and revision-tags the stored draft under the same serialization boundary as writes. */
    suspend fun load(conversationId: String): LoadedComposerDraft = withContext(Dispatchers.IO) {
        persistenceMutex.withLock {
            val loaded = read(conversationId)
            persistedDrafts[conversationId] = loaded
            LoadedComposerDraft(
                text = loaded.text,
                attachments = loaded.attachments,
                revision = loaded.revision,
            )
        }
    }

    private suspend fun read(conversationId: String): PersistedComposerDraft {
        val priorRevision = persistedDrafts[conversationId]?.revision ?: 0L
        val entity = conversations.getConversation(conversationId)
        val attachments: List<SelectedAttachment> = try {
            entity?.draftAttachments
                ?.let { Json.decodeFromString<List<SelectedAttachment>>(it) }
                ?: emptyList()
        } catch (e: Exception) {
            DebugLog.w(
                "ChatViewModel",
                "Failed to deserialize draft attachments for $conversationId",
                e,
            )
            emptyList()
        }
        return PersistedComposerDraft(
            text = entity?.draftText.orEmpty(),
            attachments = attachments,
            revision = priorRevision,
        )
    }

    suspend fun reclaimAttachments(attachments: List<SelectedAttachment>) {
        if (attachments.isEmpty()) return
        try {
            conversations.deleteUnreferencedDraftAttachmentFiles(attachments)
        } catch (e: Exception) {
            // The durable reference update already succeeded. A cleanup failure may leak a private
            // file, but must never roll the draft back to a now-invalid attachment.
            DebugLog.w("ChatViewModel", "Failed to reclaim draft attachment files", e)
        }
    }
}
