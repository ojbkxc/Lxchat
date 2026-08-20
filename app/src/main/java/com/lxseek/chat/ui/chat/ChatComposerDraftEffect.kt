package com.lxseek.chat.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import com.lxseek.chat.model.SelectedAttachment
import com.lxseek.chat.ui.chat.bottombar.ChatComposerState
import com.lxseek.chat.ui.chat.bottombar.PendingAttachmentRemoval
import com.lxseek.chat.util.DebugLog
import com.lxseek.chat.viewmodel.ChatViewModel
import com.lxseek.chat.viewmodel.LoadedComposerDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

private const val DRAFT_TEXT_DEBOUNCE_MS = 300L
private const val DRAFT_PERSIST_RETRY_COUNT = 2
private const val DRAFT_PERSIST_RETRY_DELAY_MS = 80L

private data class ComposerDraftUiSnapshot(
    val text: String,
    val attachments: List<SelectedAttachment>,
    val removals: List<PendingAttachmentRemoval>,
)

internal fun composerDraftWriteDelayMillis(
    previousAttachments: List<SelectedAttachment>,
    nextAttachments: List<SelectedAttachment>,
    hasPendingRemovals: Boolean,
): Long =
    if (previousAttachments != nextAttachments || hasPendingRemovals) {
        0L
    } else {
        DRAFT_TEXT_DEBOUNCE_MS
    }

@Composable
internal fun ComposerDraftLifecycleEffect(
    currentConversationId: String?,
    viewModel: ChatViewModel,
    composer: ChatComposerState,
    textFieldState: TextFieldState,
) {
    // One effect owns both loading and persistence for exactly one conversation. This prevents
    // the former pair of independent effects from cancelling a debounced tail write during a
    // fast switch. Attachment mutations bypass the text debounce; cancellation performs a final
    // non-cancellable flush before the next conversation is allowed to bind the shared composer.
    LaunchedEffect(currentConversationId) {
        // A send queued (pendingSend) belongs to the previous conversation; a switch
        // mid-processing must drop it, or the queued submit would fire into the new draft (C1).
        composer.pendingSend = false
        val draftId = currentConversationId
        if (draftId == null) {
            // New-chat screen: clear the composer so a draft from the previous conversation
            // doesn't carry over.
            viewModel.loadingDraft = true
            try {
                composer.bindDraftOwner(null)
                textFieldState.edit { replace(0, length, "") }
                composer.selectedAttachments = emptyList()
            } finally {
                viewModel.loadingDraft = false
            }
            return@LaunchedEffect
        }

        viewModel.loadingDraft = true
        val loadedDraft = try {
            viewModel.loadDraft(draftId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DebugLog.e("LxChatUI", "Failed to load composer draft for $draftId", error)
            LoadedComposerDraft(
                text = "",
                attachments = emptyList(),
                revision = 0L,
            )
        }
        try {
            composer.bindDraftOwner(draftId)
            textFieldState.edit {
                replace(0, length, loadedDraft.text)
            }
            composer.selectedAttachments = loadedDraft.attachments
        } finally {
            viewModel.loadingDraft = false
        }

        var revision = loadedDraft.revision
        var persistedAttachments = loadedDraft.attachments

        fun captureDraft(): ComposerDraftUiSnapshot = ComposerDraftUiSnapshot(
            text = textFieldState.text.toString(),
            attachments = composer.selectedAttachments,
            removals = composer.attachmentRemovalsFor(draftId),
        )
        var latestSnapshot = captureDraft()

        suspend fun persistSnapshot(snapshot: ComposerDraftUiSnapshot) {
            var failureCount = 0
            while (true) {
                val result = viewModel.persistDraft(
                    conversationId = draftId,
                    expectedRevision = revision,
                    text = snapshot.text,
                    attachments = snapshot.attachments,
                    explicitlyRemovedAttachments =
                        snapshot.removals.map(PendingAttachmentRemoval::attachment),
                )
                revision = result.revision
                if (result.succeeded) {
                    if (result.matchesRequested) {
                        persistedAttachments = snapshot.attachments
                        composer.acknowledgeAttachmentRemovals(
                            snapshot.removals
                                .mapTo(linkedSetOf(), PendingAttachmentRemoval::id),
                        )
                    }
                    // A revision mismatch means a newer owner (most commonly accepted Send)
                    // already committed state. Never retry the stale snapshot over that state.
                    return
                }
                if (failureCount >= DRAFT_PERSIST_RETRY_COUNT) return
                failureCount += 1
                delay(DRAFT_PERSIST_RETRY_DELAY_MS * failureCount)
            }
        }

        try {
            snapshotFlow { captureDraft() }
                .distinctUntilChanged()
                .collectLatest { snapshot ->
                    // Retain a conversation-owned copy before any debounce suspension. A new
                    // LaunchedEffect may bind the shared composer while this one is cancelling.
                    latestSnapshot = snapshot
                    val delayMillis = composerDraftWriteDelayMillis(
                        previousAttachments = persistedAttachments,
                        nextAttachments = snapshot.attachments,
                        hasPendingRemovals = snapshot.removals.isNotEmpty(),
                    )
                    if (delayMillis > 0L) delay(delayMillis)
                    persistSnapshot(snapshot)
                }
        } finally {
            // LaunchedEffect cancellation normally remains cancellable. The final snapshot must
            // outlive a navigation/recomposition cancellation so its conversation cannot retain
            // stale text or attachment references.
            val finalSnapshot = if (composer.isDraftOwner(draftId)) {
                captureDraft()
            } else {
                latestSnapshot
            }
            withContext(NonCancellable) {
                persistSnapshot(finalSnapshot)
            }
        }
    }
}
