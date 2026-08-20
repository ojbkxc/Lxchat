package com.lxseek.chat.ui.chat

import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.Participant
import com.lxseek.chat.ui.chat.message.escapeForMarkdown
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

internal data class ConversationSearchMatch(
    val messageId: String,
    val start: Int,
    val endExclusive: Int,
    val occurrenceInMessage: Int,
) {
    val key: String get() = "$messageId:$start:$endExclusive"
}

internal fun caseInsensitiveMatchRanges(text: String, query: String): List<IntRange> {
    if (query.isBlank()) return emptyList()
    return buildList {
        var from = 0
        while (from <= text.length - query.length) {
            val index = text.indexOf(query, startIndex = from, ignoreCase = true)
            if (index < 0) break
            add(index until index + query.length)
            from = index + query.length.coerceAtLeast(1)
        }
    }
}

internal fun findConversationSearchMatches(
    messages: List<ChatMessage>,
    query: String,
): List<ConversationSearchMatch> {
    if (query.isBlank()) return emptyList()
    return buildList {
        messages.forEach { message ->
            conversationSearchMatchRanges(message, query).forEachIndexed {
                    occurrence, range ->
                add(
                    ConversationSearchMatch(
                        messageId = message.id,
                        start = range.first,
                        endExclusive = range.last + 1,
                        occurrenceInMessage = occurrence,
                    )
                )
            }
        }
    }
}

internal fun conversationSearchMatchRanges(
    message: ChatMessage,
    query: String,
): List<IntRange> {
    val sourceMatches = caseInsensitiveMatchRanges(message.text, query)
    if (message.participant == Participant.USER || sourceMatches.isEmpty()) return sourceMatches

    // Markdown rendering inserts a few protective characters without changing occurrence order.
    // Pair the visible prepared-source occurrences back to persisted-source ranges so match keys
    // remain stable and hidden URL/image syntax never inflates the visible result count.
    val prepared = message.text.escapeForMarkdown()
    val preparedMatches = caseInsensitiveMatchRanges(prepared, query)
    val visiblePrepared = visibleMarkdownMatchRanges(prepared, query).toSet()
    return preparedMatches.indices.mapNotNull { index ->
        preparedMatches[index]
            .takeIf(visiblePrepared::contains)
            ?.let { sourceMatches.getOrNull(index) }
    }
}

internal fun visibleMarkdownMatchRanges(
    content: String,
    query: String,
): List<IntRange> {
    val matches = caseInsensitiveMatchRanges(content, query)
    if (matches.isEmpty()) return emptyList()
    val hiddenRanges = runCatching {
        val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content)
        buildList { root.collectHiddenMarkdownRanges(this) }
    }.getOrDefault(emptyList())
    return matches.filter { match ->
        hiddenRanges.none { hidden ->
            match.first >= hidden.first && match.last <= hidden.last
        }
    }
}

private fun ASTNode.collectHiddenMarkdownRanges(target: MutableList<IntRange>) {
    val hidden = type == MarkdownElementTypes.LINK_DESTINATION ||
        type == MarkdownElementTypes.LINK_DEFINITION ||
        type == MarkdownElementTypes.IMAGE ||
        type == MarkdownTokenTypes.FENCE_LANG
    if (hidden) {
        if (endOffset > startOffset) target += startOffset until endOffset
        return
    }
    children.forEach { child -> child.collectHiddenMarkdownRanges(target) }
}

internal fun nearestConversationSearchMatchIndex(
    matches: List<ConversationSearchMatch>,
    turnIndexByMessageId: Map<String, Int>,
    anchorTurnIndex: Int,
): Int = matches.indices.minByOrNull { index ->
    kotlin.math.abs(
        (turnIndexByMessageId[matches[index].messageId] ?: Int.MAX_VALUE / 2) -
            anchorTurnIndex
    )
} ?: -1

internal fun nearestVisibleConversationSearchMatchIndex(
    matches: List<ConversationSearchMatch>,
    distanceByMatchKey: Map<String, Float>,
): Int? = matches.indices
    .filter { index -> matches[index].key in distanceByMatchKey }
    .minByOrNull { index -> distanceByMatchKey.getValue(matches[index].key) }
