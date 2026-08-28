package com.lxseek.chat.ui.chat

import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.Participant

/**
 * Stable structural key for a message inside the run-projection pipeline.
 *
 * Two messages that share the same key are considered the same logical slot
 * across projection updates, so the LazyColumn can reuse their composition
 * even when the surrounding run metadata changes. Extracted from
 * [MessageList] so the list container stays focused on scrolling and layout
 * while the projection-keying concern lives next to the dispatch logic.
 */
internal data class RunProjectionMessageKey(
    val id: String,
    val parentId: String?,
    val participant: Participant,
    val timestamp: Long,
    val runId: String?,
    val runSequence: Long?,
)

/**
 * Maps a [ChatMessage] to its [RunProjectionMessageKey].
 *
 * Kept as an extension on the model so call sites read naturally
 * (`message.toRunProjectionKey()`) without importing a free function.
 */
internal fun ChatMessage.toRunProjectionKey(): RunProjectionMessageKey =
    RunProjectionMessageKey(
        id = id,
        parentId = parentId,
        participant = participant,
        timestamp = timestamp,
        runId = runId,
        runSequence = runSequence,
    )