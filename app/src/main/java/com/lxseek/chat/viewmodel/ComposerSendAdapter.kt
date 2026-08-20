package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.SelectedAttachment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal typealias ComposerSend = suspend (
    text: String,
    images: List<String>,
    attachments: List<SelectedAttachment>,
    onAccepted: suspend (SendAcceptance) -> Unit,
) -> SendAcceptance?

/** Adapts an authoritative Send acceptance to composer draft ownership and UI acknowledgement. */
internal class ComposerSendAdapter(
    private val send: ComposerSend,
    private val drafts: ComposerDraftController,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun sendMessage(
        text: String,
        images: List<String> = emptyList(),
        attachments: List<SelectedAttachment> = emptyList(),
        onAccepted: suspend () -> Unit = {},
    ): SendAcceptance? = send(text, images, attachments) { acceptance ->
        // Acceptance transfers ownership before the composer clears. Direct inputs are Room-owned;
        // queued guidance remains memory-owned until its later drain boundary.
        val attachmentsToReclaim = withContext(NonCancellable) {
            drafts.clearAccepted(acceptance.conversationId, text, attachments)
        }
        withContext(mainDispatcher + NonCancellable) {
            onAccepted()
        }
        if (attachmentsToReclaim.isNotEmpty() && acceptance.hasDurableAttachmentOwner()) {
            // The visible handshake no longer waits on deletion. Repository cleanup rechecks all
            // remaining message/draft references before removing any app-private path.
            scope.launch(ioDispatcher) {
                drafts.reclaimAttachments(attachmentsToReclaim)
            }
        }
    }
}
