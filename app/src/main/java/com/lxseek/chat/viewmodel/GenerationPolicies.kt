package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageSegment
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.RunEndReason
import com.lxseek.chat.model.RunStatus
import com.lxseek.chat.util.Constants

/** Never route a request through an arbitrary fallback provider. */
internal fun <T> requireRegisteredProvider(providers: Map<String, T>, name: String): T =
    requireNotNull(providers[name]) { "Provider is not registered: $name" }

internal data class GenerationTerminalDisposition(
    val runStatus: RunStatus,
    val endReason: RunEndReason,
    val markConversationUnread: Boolean,
)

/**
 * Every provider-generation exit closes its durable Run. Pending guidance only defers the
 * conversation-unread/completion presentation; it cannot keep the origin Run live because the
 * normal Send boundary that consumes that guidance must create a distinct Run.
 */
internal fun generationTerminalDisposition(
    messageStatus: MessageStatus,
    hasPendingGuidance: Boolean,
): GenerationTerminalDisposition = when (messageStatus) {
    MessageStatus.STOPPED -> GenerationTerminalDisposition(
        RunStatus.STOPPED,
        RunEndReason.USER_STOPPED,
        markConversationUnread = false,
    )
    MessageStatus.ERROR -> GenerationTerminalDisposition(
        RunStatus.FAILED,
        RunEndReason.PROVIDER_ERROR,
        markConversationUnread = false,
    )
    else -> GenerationTerminalDisposition(
        RunStatus.COMPLETED,
        RunEndReason.MODEL_COMPLETED,
        markConversationUnread = !hasPendingGuidance,
    )
}

/**
 * Throttles durable stream snapshots while allowing lifecycle boundaries to force a write.
 * The first snapshot is always accepted, including when the clock moves backwards.
 */
internal class StreamingCheckpointGate(
    private val intervalMs: Long = 1_000L,
) {
    private var lastCheckpointAt: Long? = null

    init {
        require(intervalMs > 0)
    }

    fun shouldCheckpoint(nowMs: Long, force: Boolean = false): Boolean {
        val previous = lastCheckpointAt
        if (!force && previous != null && nowMs >= previous && nowMs - previous < intervalMs) {
            return false
        }
        lastCheckpointAt = nowMs
        return true
    }
}

/**
 * Returns only reasoning produced since the previous tool-round boundary.
 *
 * A model message keeps the full segment timeline for display, while each synthetic tool row must
 * contain only the protocol blocks that belong to that one round. Reusing every historical thought
 * here makes signatures and reasoning grow quadratically across a long agent run.
 */
internal fun toolRoundThoughtSegments(
    segments: List<MessageSegment>,
    fromIndex: Int,
): List<MessageSegment> {
    val safeStart = fromIndex.coerceIn(0, segments.size)
    return segments.subList(safeStart, segments.size).filter { it.type == "thought" }
}

/**
 * Removes only the strict cumulative thought prefix written by older LxChat builds.
 *
 * Legacy tool rows for one run were shaped as `[thought 1, ..., thought N, tool N]`; replaying all
 * rows therefore sent the same signed reasoning over and over. Current rows contain only their own
 * round. This tracker accepts both layouts and strips a prefix only when a later row is a strict
 * extension of the exact thought history already observed. Equal or unrelated content is retained,
 * so this never guesses from text and cannot classify an ordinary answer as protocol data.
 */
internal class ToolRoundHistoryCompactor {
    private val thoughtHistoryByRun = mutableMapOf<String, List<MessageSegment>>()

    fun compact(runId: String, segments: List<MessageSegment>): List<MessageSegment> {
        val thoughts = segments.filter { it.type == "thought" }
        if (thoughts.isEmpty()) return segments

        val history = thoughtHistoryByRun[runId].orEmpty()
        val repeatedPrefixSize = history.size.takeIf { prefixSize ->
            prefixSize > 0 &&
                thoughts.size > prefixSize &&
                thoughts.subList(0, prefixSize) == history
        } ?: 0

        thoughtHistoryByRun[runId] = when {
            repeatedPrefixSize > 0 -> thoughts
            history.isEmpty() -> thoughts
            thoughts == history -> history
            else -> history + thoughts
        }
        if (repeatedPrefixSize == 0) return segments

        var thoughtsToDrop = repeatedPrefixSize
        return segments.filter { segment ->
            if (segment.type == "thought" && thoughtsToDrop > 0) {
                thoughtsToDrop--
                false
            } else {
                true
            }
        }
    }
}

internal fun applyUserTemplateToMessages(
    messages: List<ChatMessage>,
    prepend: String?,
    postpend: String?
): List<ChatMessage> {
    if (prepend == null && postpend == null) return messages
    val timeSdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    val dateSdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    return messages.map { msg ->
        val isToolMessage = msg.id.startsWith(Constants.TOOL_MSG_PREFIX) ||
            msg.id.startsWith(Constants.RESULT_MSG_PREFIX)
        if (!isToolMessage && msg.participant == Participant.USER && msg.text.isNotEmpty()) {
            val ts = java.util.Date(msg.timestamp)
            val rp = prepend?.replace("{sent_time}", timeSdf.format(ts))?.replace("{sent_date}", dateSdf.format(ts)) ?: ""
            val ra = postpend?.replace("{sent_time}", timeSdf.format(ts))?.replace("{sent_date}", dateSdf.format(ts)) ?: ""
            if (rp.isEmpty() && ra.isEmpty()) msg
            else msg.copy(text = rp + msg.text + ra)
        } else msg
    }
}
