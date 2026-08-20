package com.lxseek.chat.ui.chat

import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.Participant
import com.lxseek.chat.util.Constants

internal enum class MessageListLayoutMode {
    STABLE,
    ACTIVE_SCROLL,
    COVERED_TRANSITION,
}

internal fun messageListLayoutMode(
    isSwitching: Boolean,
    isScrollInProgress: Boolean,
): MessageListLayoutMode = when {
    isSwitching -> MessageListLayoutMode.COVERED_TRANSITION
    isScrollInProgress -> MessageListLayoutMode.ACTIVE_SCROLL
    else -> MessageListLayoutMode.STABLE
}

internal fun calculateTailMinHeightPx(
    viewportHeightPx: Int,
    targetTopPx: Int,
    bottomObstructionPx: Int,
): Int = (viewportHeightPx - targetTopPx - bottomObstructionPx).coerceAtLeast(0)

internal fun calculateTailLayoutHeightPx(
    minimumHeightPx: Int,
    contentHeightPx: Int,
): Int = maxOf(minimumHeightPx, contentHeightPx)

/**
 * One stable LazyColumn item per conversation turn.
 *
 * A USER starts a turn and every following non-USER message remains in that turn until the next
 * USER. This identity must not change when a new turn is appended: otherwise the previous
 * assistant is disposed from the tail item and recreated as a standalone item, producing a
 * visible blank/reparse frame on Send.
 */
internal data class MessageListTurn(
    val key: String,
    val messages: List<ChatMessage>,
)

internal fun regenerationExitMessageIds(
    messages: List<ChatMessage>,
    oldMessageId: String,
): Set<String> = regenerationExitMessages(messages, oldMessageId)
    .mapTo(linkedSetOf()) { message -> message.id }

internal fun regenerationExitMessages(
    messages: List<ChatMessage>,
    oldMessageId: String,
): List<ChatMessage> {
    val firstExitIndex = messages.indexOfFirst { message -> message.id == oldMessageId }
    if (firstExitIndex < 0) return emptyList()
    return messages.subList(firstExitIndex, messages.size).toList()
}

/**
 * Keeps the faded branch composed after the selected graph path switches to the replacement.
 * Current-path messages are ordered first so SENDING appears directly below its USER anchor;
 * retained messages keep their original stable keys after it and contribute layout height only.
 */
internal fun mergeRegenerationPresentationMessages(
    activeMessages: List<ChatMessage>,
    retainedExitMessages: List<ChatMessage>,
): List<ChatMessage> {
    if (retainedExitMessages.isEmpty()) return activeMessages
    val activeIds = activeMessages.mapTo(hashSetOf()) { message -> message.id }
    val retainedOnly = retainedExitMessages.filterNot { message -> message.id in activeIds }
    if (retainedOnly.isEmpty()) return activeMessages
    return buildList(activeMessages.size + retainedOnly.size) {
        addAll(activeMessages)
        addAll(retainedOnly)
    }
}

internal data class PendingEditVisualReplacement(
    val sourceMessageId: String,
    val sourceParentId: String?,
    val submittedText: String,
    val stableVisualKey: String,
)

internal fun resolvePendingEditReplacement(
    messages: List<ChatMessage>,
    pending: PendingEditVisualReplacement?,
): ChatMessage? {
    pending ?: return null
    if (messages.any { message -> message.id == pending.sourceMessageId }) return null
    return messages.lastOrNull { message ->
        message.participant == Participant.USER &&
            message.id != pending.sourceMessageId &&
            message.parentId == pending.sourceParentId &&
            message.text == pending.submittedText
    }
}

/**
 * Reuses unchanged turn objects across immutable streaming snapshots. Only the active tail turn
 * receives a new identity, allowing Compose to skip every historical LazyColumn item.
 */
internal class MessageListTurnCache {
    private var previousByKey: Map<String, MessageListTurn> = emptyMap()

    fun update(messages: List<ChatMessage>): List<MessageListTurn> {
        val next = buildMessageListTurns(messages).map { candidate ->
            previousByKey[candidate.key]
                ?.takeIf { previous -> previous.messages == candidate.messages }
                ?: candidate
        }
        previousByKey = next.associateBy { it.key }
        return next
    }
}

/**
 * Session-scoped one-shot registry. LazyColumn disposal/recreation and conversation switches must
 * not replay an entrance for a message the user has already seen.
 */
internal class MessageLifecycleAppearanceRegistry {
    private val knownMessageIds = HashSet<String>()

    fun isKnown(messageId: String): Boolean = messageId in knownMessageIds

    fun markKnown(messageId: String) {
        knownMessageIds += messageId
    }
}

internal fun shouldAnimateMessageLifecycleEntrance(
    message: ChatMessage,
    isKnown: Boolean,
    isLoading: Boolean,
    isStreaming: Boolean,
    lastUserMessageId: String?,
    requestedTargetMessageId: String?,
): Boolean {
    if (isKnown) return false
    if (
        message.id.startsWith(Constants.TOOL_MSG_PREFIX) ||
        message.id.startsWith(Constants.RESULT_MSG_PREFIX)
    ) {
        return false
    }
    return when (message.participant) {
        Participant.USER ->
            message.id == requestedTargetMessageId ||
                (isLoading && message.id == lastUserMessageId)
        Participant.MODEL ->
            isStreaming ||
                (
                    requestedTargetMessageId != null &&
                        message.parentId == requestedTargetMessageId
                )
        Participant.ERROR -> false
    }
}

internal fun buildMessageListTurns(messages: List<ChatMessage>): List<MessageListTurn> {
    if (messages.isEmpty()) return emptyList()

    val turns = mutableListOf<MessageListTurn>()
    var activeTurn = mutableListOf<ChatMessage>()

    fun flushActiveTurn() {
        if (activeTurn.isEmpty()) return
        turns += MessageListTurn(
            key = activeTurn.first().id,
            messages = activeTurn.toList(),
        )
        activeTurn = mutableListOf()
    }

    messages.forEach { message ->
        if (message.participant == Participant.USER) {
            flushActiveTurn()
            activeTurn += message
        } else if (activeTurn.firstOrNull()?.participant == Participant.USER) {
            activeTurn += message
        } else {
            // Preserve leading/error-only paths as their own stable items until a USER begins a
            // normal conversation turn.
            flushActiveTurn()
            turns += MessageListTurn(message.id, listOf(message))
        }
    }
    flushActiveTurn()
    return turns
}

internal fun messageListTurnIndex(
    turns: List<MessageListTurn>,
    messageId: String,
): Int = turns.indexOfFirst { turn -> turn.messages.any { it.id == messageId } }

internal fun estimateMessageListTurnHeightPx(
    turn: MessageListTurn,
    messageHeights: Map<String, Int>,
    fallbackHeightPx: Float,
): Float = turn.messages.sumOf { message ->
    (messageHeights[message.id]?.toDouble() ?: fallbackHeightPx.toDouble())
}.toFloat()

internal fun estimateSearchMatchCenterInTurnPx(
    turn: MessageListTurn,
    match: ConversationSearchMatch,
    messageHeights: Map<String, Int>,
    fallbackHeightPx: Float,
): Float {
    val targetIndex = turn.messages.indexOfFirst { it.id == match.messageId }
    if (targetIndex < 0) return fallbackHeightPx / 2f
    val precedingHeight = turn.messages
        .take(targetIndex)
        .sumOf { message ->
            (messageHeights[message.id]?.toDouble() ?: fallbackHeightPx.toDouble())
        }
        .toFloat()
    val target = turn.messages[targetIndex]
    val targetHeight = messageHeights[target.id]?.toFloat() ?: fallbackHeightPx
    val characterCenter = (match.start + match.endExclusive) / 2f
    val textFraction = if (target.text.isEmpty()) {
        0.5f
    } else {
        (characterCenter / target.text.length).coerceIn(0.08f, 0.92f)
    }
    return precedingHeight + targetHeight * textFraction
}

internal data class MessageListViewportAnchor(
    val messageId: String,
    val scrollOffsetPx: Int,
)

internal class MessageListMutationAnchorLock {
    private val activeMutationKeys = mutableSetOf<String>()

    var anchor: MessageListViewportAnchor? = null
        private set

    fun begin(
        key: String,
        candidate: MessageListViewportAnchor?,
    ): MessageListViewportAnchor? {
        activeMutationKeys += key
        if (anchor == null) anchor = candidate
        return anchor
    }

    /**
     * Returns the anchor exactly once, when the final overlapping mutation settles.
     * Repeated begin calls for the same reversing animation never replace the pre-change anchor.
     */
    fun finish(key: String): MessageListViewportAnchor? {
        if (!activeMutationKeys.remove(key) || activeMutationKeys.isNotEmpty()) return null
        return anchor.also { anchor = null }
    }

    fun cancel() {
        activeMutationKeys.clear()
        anchor = null
    }

    val activeMutationCount: Int
        get() = activeMutationKeys.size
}
