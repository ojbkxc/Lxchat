package com.lxseek.chat.api.util

import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.MessageSegment
import com.lxseek.chat.model.Participant
import com.lxseek.chat.util.Constants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/**
 * Full fail-closed message preparation pipeline shared by every provider.
 *
 * The result is a canonical history:
 *  - duplicate persisted rows are removed by id;
 *  - status rows are projected into model-visible user events;
 *  - tool calls/results are complete atomic rounds with globally unique call ids;
 *  - context truncation never splits a tool round;
 *  - the history starts with a normal user turn and has no empty normal turns.
 */

/**
 * Non-destructive compact projection. The nearest compact entity is the logical start of context.
 * Its position is the only boundary metadata: deleting it naturally reveals the previous compact.
 */
fun applyNearestContextCompact(messages: List<ChatMessage>): List<ChatMessage> {
    val index = messages.indexOfLast { it.id.startsWith(Constants.COMPACT_MSG_PREFIX) }
    if (index < 0) return messages
    val compact = messages[index]
    return buildList(messages.size - index) {
        add(compact.copy(id = "context_summary_${compact.id}", participant = Participant.USER))
        addAll(messages.drop(index + 1))
    }
}

/** Expands protocol side chains with the same ordering/deduplication rule as ApiPathAssembler. */
fun expandSelectedToolProtocolRows(
    selectedPath: List<ChatMessage>,
    allMessages: List<ChatMessage>,
): List<ChatMessage> {
    if (selectedPath.isEmpty()) return emptyList()
    val protocolChildren = allMessages
        .asSequence()
        .filter(ChatMessage::isToolProtocolMessage)
        .groupBy(ChatMessage::parentId)
    val emitted = mutableSetOf<String>()
    val result = mutableListOf<ChatMessage>()

    fun emitProtocolSubtree(root: ChatMessage, runId: String?) {
        if (root.runId != runId || !emitted.add(root.id)) return
        result += root
        protocolChildren[root.id]
            .orEmpty()
            .asSequence()
            .filter { it.runId == runId }
            .sortedWith(compareBy<ChatMessage> { it.runSequence ?: Long.MAX_VALUE }
                .thenBy { it.timestamp }
                .thenBy { it.id })
            .forEach { emitProtocolSubtree(it, runId) }
    }

    selectedPath.forEach { message ->
        if (message.isToolProtocolMessage()) {
            if (emitted.add(message.id)) result += message
            return@forEach
        }
        protocolChildren[message.id]
            .orEmpty()
            .asSequence()
            .filter {
                it.runId == message.runId && it.id.startsWith(Constants.TOOL_MSG_PREFIX)
            }
            .sortedWith(compareBy<ChatMessage> { it.runSequence ?: Long.MAX_VALUE }
                .thenBy { it.timestamp }
                .thenBy { it.id })
            .forEach { emitProtocolSubtree(it, message.runId) }
        if (emitted.add(message.id)) result += message
    }
    return result
}

data class LogicalContextSplit(
    val prefix: List<ChatMessage>,
    val suffix: List<ChatMessage>,
    val logicalMessageCount: Int,
)

/** Splits context using provider role semantics. Tool rows have zero weight and remain atomic. */
fun splitLogicalContext(messages: List<ChatMessage>, retainLogicalMessages: Int): LogicalContextSplit {
    require(retainLogicalMessages >= 0)
    if (messages.isEmpty()) return LogicalContextSplit(emptyList(), emptyList(), 0)
    val normal = messages.mapIndexedNotNull { index, message ->
        if (message.isToolProtocolMessage() || message.id.startsWith(Constants.COMPACT_MSG_PREFIX)) null
        else index to message.participant
    }
    val groups = mutableListOf<MutableList<Int>>()
    var previous: Participant? = null
    normal.forEach { (index, participant) ->
        if (groups.isEmpty() || participant != previous) groups.add(mutableListOf())
        groups.last() += index
        previous = participant
    }
    val count = groups.size
    if (retainLogicalMessages <= 0) return LogicalContextSplit(messages, emptyList(), count)
    if (retainLogicalMessages >= count) return LogicalContextSplit(emptyList(), messages, count)
    var cut = groups[count - retainLogicalMessages].first()
    var cursor = cut - 1
    while (cursor >= 0 && messages[cursor].id.startsWith(Constants.RESULT_MSG_PREFIX)) cursor--
    if (cursor >= 0 && messages[cursor].id.startsWith(Constants.TOOL_MSG_PREFIX)) cut = cursor
    return LogicalContextSplit(messages.take(cut), messages.drop(cut), count)
}

/**
 * Canonical provider-visible context before applying the configured window. Compact eligibility,
 * the composer usage indicator, and provider rollout all use this exact projection so their counts
 * cannot drift. Consecutive ordinary roles are merged and complete tool rounds remain protocol
 * rows with zero logical-message weight.
 */
fun canonicalContextMessages(messages: List<ChatMessage>): List<ChatMessage> {
    val compacted = applyNearestContextCompact(messages)
    val canonical = validateToolMessages(
        stripEmptyTurns(
            projectGenerationStatusesForApi(compacted.distinctBy(ChatMessage::id))
        )
    )
    return stripEmptyTurns(mergeConsecutiveSameRole(canonical))
}

data class ContextWindowUsage(
    val estimatedTokenCount: Int,
    val tokenBudget: Int,
    val logicalMessageCount: Int,
    val hasCompactBoundary: Boolean,
) {
    val progress: Float
        get() = if (tokenBudget <= 0) 0f else
            (estimatedTokenCount.toFloat() / tokenBudget).coerceIn(0f, 1f)
}

fun contextWindowUsage(messages: List<ChatMessage>, tokenBudget: Int): ContextWindowUsage {
    val safeBudget = tokenBudget.coerceAtLeast(1)
    val canonical = canonicalContextMessages(messages)
    return ContextWindowUsage(
        estimatedTokenCount = ContextTokenEstimator.estimate(canonical),
        tokenBudget = safeBudget,
        logicalMessageCount = splitLogicalContext(canonical, retainLogicalMessages = 0)
            .logicalMessageCount,
        hasCompactBoundary = messages.any { it.id.startsWith(Constants.COMPACT_MSG_PREFIX) },
    )
}

/** Original message ids retained by the provider's canonical context window. */
fun contextWindowRetainedMessageIds(messages: List<ChatMessage>, tokenBudget: Int): Set<String> {
    if (messages.isEmpty()) return emptySet()
    val compacted = applyNearestContextCompact(messages)
    val retained = limitContext(canonicalContextMessages(messages), tokenBudget.coerceAtLeast(1))
    val firstRetainedId = retained.firstOrNull()?.id ?: return emptySet()
    val sourceAnchorId = firstRetainedId.removePrefix("context_summary_")
    val originalSourceIndex = messages.indexOfFirst { it.id == sourceAnchorId }
    if (originalSourceIndex >= 0) {
        // A Compact is projected with a synthetic context_summary_ id and may then absorb the first
        // same-role suffix row during canonicalization. Recover the durable boundary in the original
        // graph so rollout visualization retains the Compact and every verbatim suffix message.
        return messages.drop(originalSourceIndex).mapTo(linkedSetOf(), ChatMessage::id)
    }
    val sourceIndex = compacted.indexOfFirst { it.id == sourceAnchorId }
    if (sourceIndex < 0) return retained.mapTo(linkedSetOf()) {
        it.id.removePrefix("context_summary_")
    }
    // The canonical anchor is the first row of any merged same-role group. Keeping the original
    // suffix from that anchor preserves every member and all complete tool rows represented by it.
    return compacted.drop(sourceIndex).mapTo(linkedSetOf(), ChatMessage::id)
}

fun prepareMessages(messages: List<ChatMessage>, contextTokenBudget: Int): List<ChatMessage> {
    val canonical = canonicalContextMessages(messages)
    return stripEmptyTurns(
        mergeConsecutiveSameRole(limitContext(canonical, contextTokenBudget))
    )
}

/**
 * 带防护链的消息准备结果。
 *
 * @param messages 规范化后的消息列表（供 Provider 使用）。
 * @param guardEvents 防护链触发的事件列表（可用于日志/UI 展示）。
 * @param guardDecision 第3层 token 预算决策。
 */
data class PreparedMessagesWithGuard(
    val messages: List<ChatMessage>,
    val guardEvents: List<com.lxseek.chat.api.context.GuardEvent>,
    val guardDecision: com.lxseek.chat.api.context.GuardDecision?,
)

/**
 * 应用 Context 4层防护链后规范化消息（同步，前3层）。
 *
 * 在 [prepareMessages] 之前应用 [com.lxseek.chat.api.context.ContextGuardChain] 的前3层防护：
 * 1. 历史轮数限制  2. 工具结果裁剪  3. Token 预算检查
 *
 * 第4层（自动摘要）需要异步调用 LLM，使用 [prepareMessagesWithGuardAsync]。
 *
 * @param messages 原始消息列表。
 * @param contextTokenBudget 上下文 token 预算。
 * @param guardConfig 防护配置；默认 [com.lxseek.chat.api.context.ContextGuardConfig.Off] 保持向后兼容。
 * @return 规范化后的消息 + 防护事件。
 */
fun prepareMessagesWithGuard(
    messages: List<ChatMessage>,
    contextTokenBudget: Int,
    guardConfig: com.lxseek.chat.api.context.ContextGuardConfig = com.lxseek.chat.api.context.ContextGuardConfig.Off,
): PreparedMessagesWithGuard {
    if (guardConfig.disabled) {
        return PreparedMessagesWithGuard(
            messages = prepareMessages(messages, contextTokenBudget),
            guardEvents = emptyList(),
            guardDecision = null,
        )
    }

    // 应用前3层防护
    val guardResult = com.lxseek.chat.api.context.ContextGuardChain.apply(messages, guardConfig)

    // 对防护后的消息做规范化
    val prepared = prepareMessages(guardResult.messages, contextTokenBudget)

    return PreparedMessagesWithGuard(
        messages = prepared,
        guardEvents = guardResult.events,
        guardDecision = guardResult.decision,
    )
}

/**
 * 应用 Context 4层防护链后规范化消息（异步，全部4层）。
 *
 * 在 [prepareMessages] 之前应用 [com.lxseek.chat.api.context.ContextGuardChain] 的全部4层防护：
 * 1. 历史轮数限制  2. 工具结果裁剪  3. Token 预算检查  4. 自动摘要
 *
 * @param messages 原始消息列表。
 * @param contextTokenBudget 上下文 token 预算。
 * @param guardConfig 防护配置。
 * @param summaryGenerator 摘要生成器（第4层使用）；为 null 时跳过第4层。
 * @return 规范化后的消息 + 防护事件。
 */
suspend fun prepareMessagesWithGuardAsync(
    messages: List<ChatMessage>,
    contextTokenBudget: Int,
    guardConfig: com.lxseek.chat.api.context.ContextGuardConfig = com.lxseek.chat.api.context.ContextGuardConfig.Off,
    summaryGenerator: com.lxseek.chat.api.context.SummaryGenerator? = null,
): PreparedMessagesWithGuard {
    if (guardConfig.disabled) {
        return PreparedMessagesWithGuard(
            messages = prepareMessages(messages, contextTokenBudget),
            guardEvents = emptyList(),
            guardDecision = null,
        )
    }

    // 应用全部4层防护
    val guardResult = com.lxseek.chat.api.context.ContextGuardChain.applyAsync(
        messages = messages,
        config = guardConfig,
        summaryGenerator = summaryGenerator,
    )

    // 对防护后的消息做规范化
    val prepared = prepareMessages(guardResult.messages, contextTokenBudget)

    return PreparedMessagesWithGuard(
        messages = prepared,
        guardEvents = guardResult.events,
        guardDecision = guardResult.decision,
    )
}

/**
 * Converts durable terminal generation rows into model-visible status events without presenting
 * client/provider failures as genuine assistant output.
 *
 * The database/UI message remains untouched. In the API-only path, ERROR and STOPPED rows become
 * user-role status text with all assistant/tool payload removed. When the next row is a normal user
 * message, the status is prepended to it so context-window truncation cannot retain the follow-up
 * while silently dropping the immediately preceding status.
 */
fun projectGenerationStatusesForApi(messages: List<ChatMessage>): List<ChatMessage> {
    if (messages.none(ChatMessage::isGenerationStatusMessage)) return messages

    val projected = mutableListOf<ChatMessage>()
    val pendingStatuses = mutableListOf<ChatMessage>()

    fun flushPending() {
        projected.addAll(pendingStatuses.map(ChatMessage::asGenerationStatusEvent))
        pendingStatuses.clear()
    }

    messages.forEach { message ->
        when {
            message.isGenerationStatusMessage() -> pendingStatuses += message
            pendingStatuses.isNotEmpty() &&
                message.participant == Participant.USER &&
                !message.isToolProtocolMessage() -> {
                val statusText = pendingStatuses.joinToString("\n\n") {
                    it.generationStatusEventText()
                }
                projected += message.copy(
                    text = listOf(statusText, message.text)
                        .filter(String::isNotBlank)
                        .joinToString("\n\n")
                )
                pendingStatuses.clear()
            }
            else -> {
                flushPending()
                projected += message
            }
        }
    }
    flushPending()
    return projected
}

private fun ChatMessage.isGenerationStatusMessage(): Boolean =
    !isToolProtocolMessage() &&
        (participant == Participant.ERROR ||
            status == MessageStatus.ERROR ||
            status == MessageStatus.STOPPED)

private fun ChatMessage.asGenerationStatusEvent(): ChatMessage = copy(
    text = generationStatusEventText(),
    images = emptyList(),
    thoughts = null,
    thoughtTitle = null,
    tokenCount = 0,
    tokenUsage = null,
    status = MessageStatus.SUCCESS,
    participant = Participant.USER,
    thoughtTimeMs = null,
    modelName = null,
    toolCall = null,
    segments = null,
    attachmentMeta = null,
    retryText = null,
)

private fun ChatMessage.generationStatusEventText(): String {
    val detail = text.trim()
    return when {
        participant == Participant.ERROR || status == MessageStatus.ERROR ->
            buildString {
                append("[Generation status: ERROR]\n")
                append("The previous assistant generation failed before completing.")
                if (detail.isNotEmpty()) {
                    append("\nDetails:\n")
                    append(detail)
                }
            }
        else ->
            buildString {
                append("[Generation status: STOPPED]\n")
                append("The previous assistant generation was stopped before completing.")
                if (detail.isNotEmpty()) {
                    append("\nPartial output:\n")
                    append(detail)
                }
            }
    }
}

/**
 * Drops turns that would serialize to an empty/whitespace-only content block.
 *
 * Anthropic hard-rejects those with `400 messages: text content blocks must contain
 * non-whitespace text`, and other providers silently degrade on them. Such turns are
 * routine in practice: a generation stopped before its first token, an interrupted turn
 * that emitted only a newline, or two blank messages merged with "\n".
 *
 * A turn survives if it carries anything else of substance — images, or tool protocol
 * payload (tool_/result_ rows, whose content lives in segments/toolCall, not text).
 * Runs AFTER the merge so a blank fragment absorbed into a non-blank neighbor is kept.
 */
fun stripEmptyTurns(messages: List<ChatMessage>): List<ChatMessage> =
    messages.filter { msg ->
        msg.text.isNotBlank() ||
            msg.images.isNotEmpty() ||
            msg.isToolProtocolMessage() ||
            msg.toolCall != null ||
            msg.segments?.any { it.type == "tool" } == true
    }

/**
 * Builds an API-only view where assistant-generated images remain available for
 * visual follow-ups without being serialized as assistant-side image content.
 *
 * Chat completion schemas treat images as user inputs. LxChat stores generated
 * images on model messages for display, so the latest generated image set is
 * projected onto the latest normal user message when images are being sent.
 */
fun projectAssistantImagesToLatestUserMessage(
    messages: List<ChatMessage>,
    includeImages: Boolean
): List<ChatMessage> {
    if (messages.isEmpty()) return messages

    val latestUserIndex = messages.indexOfLast { it.isNormalUserMessage() }
    val generatedImages = if (includeImages && latestUserIndex >= 0) {
        messages
            .asSequence()
            .take(latestUserIndex)
            .filter { it.isNormalAssistantMessage() && it.images.isNotEmpty() }
            .lastOrNull()
            ?.images
            ?.filter { it.isNotBlank() }
            .orEmpty()
    } else {
        emptyList()
    }

    var changed = false
    val projected = messages.mapIndexed { index, msg ->
        var next = msg
        if (msg.isNormalAssistantMessage() && msg.images.isNotEmpty()) {
            next = next.copy(images = emptyList())
            changed = true
        }
        if (index == latestUserIndex && generatedImages.isNotEmpty()) {
            next = next.copy(
                text = addGeneratedImageContextNote(next.text, generatedImages.size),
                images = (generatedImages + next.images).distinct()
            )
            changed = true
        }
        next
    }

    return if (changed) projected else messages
}

/**
 * Adds an API-only normal user turn after each complete tool-result batch that returned images.
 *
 * Tool-result blocks themselves remain text-only because provider wire formats disagree about
 * multimodal tool results. A following ordinary user turn is accepted by all supported
 * multimodal providers and preserves the required tool-call/result adjacency. The synthetic row
 * is never persisted or rendered.
 */
fun projectToolResultImagesToUserMessage(
    messages: List<ChatMessage>,
    includeImages: Boolean,
): List<ChatMessage> {
    if (messages.none { it.id.startsWith(Constants.RESULT_MSG_PREFIX) && it.images.isNotEmpty() }) {
        return messages
    }

    val projected = ArrayList<ChatMessage>(messages.size + 2)
    var index = 0
    while (index < messages.size) {
        val message = messages[index]
        projected += message
        if (!message.id.startsWith(Constants.RESULT_MSG_PREFIX)) {
            index++
            continue
        }

        val batch = mutableListOf(message)
        var next = index + 1
        while (
            next < messages.size &&
            messages[next].id.startsWith(Constants.RESULT_MSG_PREFIX)
        ) {
            projected += messages[next]
            batch += messages[next]
            next++
        }
        val images = batch.flatMap(ChatMessage::images).filter(String::isNotBlank).distinct()
        if (images.isNotEmpty()) {
            val first = batch.first()
            val seed = batch.joinToString(":") { it.id }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(seed.toByteArray())
                .take(8)
                .joinToString("") { "%02x".format(it) }
            projected += ChatMessage(
                id = "tool_image_context_$digest",
                parentId = batch.last().id,
                text = if (includeImages) {
                    "[Tool visual result: inspect the attached image${if (images.size == 1) "" else "s"} before continuing.]"
                } else {
                    "[Tool visual result unavailable: the current model does not support image input.]"
                },
                images = if (includeImages) images else emptyList(),
                participant = Participant.USER,
                status = MessageStatus.SUCCESS,
                timestamp = batch.last().timestamp,
                runId = first.runId,
                runSequence = first.runSequence,
            )
        }
        index = next
    }
    return projected
}

private fun ChatMessage.isNormalUserMessage(): Boolean =
    participant == Participant.USER && !isToolProtocolMessage()

private fun ChatMessage.isNormalAssistantMessage(): Boolean =
    participant == Participant.MODEL && !isToolProtocolMessage()

internal fun ChatMessage.isToolProtocolMessage(): Boolean =
    id.startsWith(Constants.TOOL_MSG_PREFIX) || id.startsWith(Constants.RESULT_MSG_PREFIX)

private fun addGeneratedImageContextNote(text: String, imageCount: Int): String {
    val note = if (imageCount == 1) {
        "[Visual context: the first attached image was generated by the assistant earlier in this conversation.]"
    } else {
        "[Visual context: the first $imageCount attached images were generated by the assistant earlier in this conversation.]"
    }
    return if (text.isBlank()) note else "$note\n\n$text"
}

/**
 * Merges consecutive non-tool messages that share the same participant.
 * This handles orphans left by message deletion (e.g. two user messages
 * in a row after removing an assistant reply) and keeps the message list
 * compliant with providers that require strict role alternation.
 *
 * Tool messages (tool_/result_) pass through unchanged — they are validated
 * separately by [validateToolMessages].
 */
fun mergeConsecutiveSameRole(messages: List<ChatMessage>): List<ChatMessage> {
    if (messages.isEmpty()) return messages
    val result = mutableListOf<ChatMessage>()
    var i = 0
    while (i < messages.size) {
        val current = messages[i]
        val isTool = current.id.startsWith(Constants.TOOL_MSG_PREFIX) ||
            current.id.startsWith(Constants.RESULT_MSG_PREFIX)
        if (isTool) {
            result.add(current)
            i++
            continue
        }
        // Find consecutive messages with the same participant
        var j = i + 1
        while (j < messages.size) {
            val next = messages[j]
            val nextIsTool = next.id.startsWith(Constants.TOOL_MSG_PREFIX) ||
                next.id.startsWith(Constants.RESULT_MSG_PREFIX)
            if (nextIsTool || next.participant != current.participant) break
            j++
        }
        if (j == i + 1) {
            // No merge needed
            result.add(current)
        } else {
            // Merge messages[i..j-1] into one
            val merged = messages.subList(i, j)
            val mergedText = merged.joinToString("\n") { it.text }
            val mergedImages = merged.flatMap { it.images }
            result.add(current.copy(text = mergedText, images = mergedImages))
        }
        i = j
    }
    return result
}

/**
 * Validates and canonicalizes complete tool_ / result_ protocol rounds.
 *
 * Rules enforced:
 *  - Every tool_ message must be immediately followed by one result per emitted tool call
 *  - Every result_ message must be immediately preceded by a tool_ message
 *  - Each result_ segment's toolCallId matches the corresponding tool_use segment
 *
 * Missing legacy IDs may be synthesized only when every result is unambiguously paired by
 * cardinality and position. Explicit conflicting IDs, duplicate IDs, malformed arguments, and
 * extra/missing results are never "repaired" by relabeling or truncation: the whole round is
 * replayed as ordinary context instead, so an invalid protocol sequence cannot reach a provider
 * and a result can never be attached to the wrong call.
 */
fun validateToolMessages(messages: List<ChatMessage>): List<ChatMessage> {
    val result = mutableListOf<ChatMessage>()
    val seenToolCallIds = mutableSetOf<String>()
    var i = 0
    while (i < messages.size) {
        val msg = messages[i]
        when {
            msg.id.startsWith(Constants.TOOL_MSG_PREFIX) -> {
                val resultMessages = mutableListOf<ChatMessage>()
                var j = i + 1
                while (j < messages.size && messages[j].id.startsWith(Constants.RESULT_MSG_PREFIX)) {
                    resultMessages.add(messages[j])
                    j++
                }
                val normalizedCalls = normalizeToolCalls(msg, seenToolCallIds)
                val normalizedResults = normalizedCalls?.let { calls ->
                    normalizeToolResults(calls, resultMessages)
                }
                if (normalizedCalls != null && normalizedResults != null) {
                    result.add(normalizedCalls.message)
                    result.addAll(normalizedResults)
                    seenToolCallIds.addAll(normalizedCalls.callIds)
                    i = j
                } else {
                    result += toolRoundAsPlainContext(
                        toolMessage = msg,
                        resultMessages = resultMessages,
                        reason = "the stored tool round was incomplete or damaged",
                    )
                    i = j
                }
            }
            msg.id.startsWith(Constants.RESULT_MSG_PREFIX) -> {
                result += toolRoundAsPlainContext(
                    toolMessage = null,
                    resultMessages = listOf(msg),
                    reason = "the stored tool result had no matching tool call",
                )
                i++
            }
            else -> {
                result.add(msg)
                i++
            }
        }
    }
    return result
}

private data class NormalizedToolCalls(
    val message: ChatMessage,
    val segments: List<MessageSegment>,
    val callIds: List<String>,
)

private val toolJson = Json { ignoreUnknownKeys = true }
private val safeToolCallId = safeWireToolCallId

private fun normalizeToolCalls(
    toolMsg: ChatMessage,
    alreadySeenIds: Set<String>,
): NormalizedToolCalls? {
    val sourceSegments = toolMsg.segments
        ?.filter { it.type == "tool" }
        .orEmpty()
        .ifEmpty {
            val toolCall = toolMsg.toolCall ?: return null
            listOf(
                MessageSegment(
                    type = "tool",
                    toolName = toolCall.toolName,
                    toolArgs = toolCall.arguments,
                    toolResult = toolCall.result,
                    toolCallId = toolCall.toolCallId,
                    signature = toolCall.signature,
                )
            )
        }
    if (sourceSegments.isEmpty()) return null

    val explicitIds = sourceSegments.map { it.toolCallId?.takeIf(String::isNotBlank) }
    if (
        explicitIds.filterNotNull().any { !it.matches(safeToolCallId) } ||
        explicitIds.filterNotNull().distinct().size != explicitIds.filterNotNull().size ||
        explicitIds.filterNotNull().any(alreadySeenIds::contains)
    ) {
        return null
    }

    val reserved = alreadySeenIds.toMutableSet()
    val normalized = sourceSegments.mapIndexed { index, segment ->
        val name = segment.toolName
            ?.takeIf { it.matches(safeWireToolName) }
            ?: return null
        val arguments = normalizeArgumentsOrNull(segment.toolArgs) ?: return null
        val callId = segment.toolCallId?.takeIf(String::isNotBlank)
            ?: buildToolCallId("$name:${toolMsg.id}:$index", arguments)
        if (!reserved.add(callId)) return null
        segment.copy(
            toolName = name,
            toolArgs = arguments,
            toolCallId = callId,
            // Results belong only to the following result_ row in the API representation.
            toolResult = null,
        )
    }
    val normalizedToolCall = toolMsg.toolCall?.takeIf { normalized.size == 1 }?.copy(
        toolName = normalized.single().toolName.orEmpty(),
        arguments = normalized.single().toolArgs.orEmpty(),
        toolCallId = normalized.single().toolCallId,
    )
    var normalizedIndex = 0
    val rebuiltSegments = toolMsg.segments
        ?.map { segment ->
            if (segment.type == "tool") normalized[normalizedIndex++] else segment
        }
        ?: normalized
    return NormalizedToolCalls(
        message = toolMsg.copy(
            // Signed thought blocks are protocol state for Anthropic/Gemini. Replace only tool
            // segments and preserve every non-tool segment in its original order.
            segments = rebuiltSegments,
            toolCall = normalizedToolCall,
        ),
        segments = normalized,
        callIds = normalized.map { checkNotNull(it.toolCallId) },
    )
}

/**
 * Replaces provider-specific tool rounds that cannot safely be replayed with ordinary user
 * context. This is the last-resort compatibility path for opaque thought signatures: the model
 * still sees what ran and what it returned, while no foreign signature reaches the target API.
 */
fun adaptToolRoundsForProvider(
    messages: List<ChatMessage>,
    providerName: String,
    isCompatible: (ChatMessage) -> Boolean,
): List<ChatMessage> {
    if (messages.none(ChatMessage::isToolProtocolMessage)) return messages
    val result = mutableListOf<ChatMessage>()
    var index = 0
    while (index < messages.size) {
        val message = messages[index]
        if (!message.id.startsWith(Constants.TOOL_MSG_PREFIX)) {
            if (!message.id.startsWith(Constants.RESULT_MSG_PREFIX)) result += message
            index++
            continue
        }

        val resultMessages = mutableListOf<ChatMessage>()
        var end = index + 1
        while (
            end < messages.size &&
            messages[end].id.startsWith(Constants.RESULT_MSG_PREFIX)
        ) {
            resultMessages += messages[end++]
        }
        if (isCompatible(message)) {
            result += message
            result += resultMessages
        } else {
            result += toolRoundAsPlainContext(
                toolMessage = message,
                resultMessages = resultMessages,
                reason = "its opaque protocol metadata is not compatible with $providerName",
            )
        }
        index = end
    }
    return stripEmptyTurns(mergeConsecutiveSameRole(result))
}

/**
 * Normalizes every result payload against the assistant's call IDs by position. A synthetic result
 * row normally carries one payload, but legacy imports can carry several segments in one row; both
 * forms are counted correctly. Returns null unless every tool call has exactly one usable result.
 * Extra result rows/segments reject the whole round rather than being dropped.
 */
private fun normalizeToolResults(
    calls: NormalizedToolCalls,
    resultMessages: List<ChatMessage>
): List<ChatMessage>? {
    data class ResultRef(
        val message: ChatMessage,
        val segment: MessageSegment?,
        val explicitId: String?,
    )

    val resultRefs = resultMessages.flatMap { message ->
        val segments = message.segments
            ?.filter { it.type == "tool" }
            .orEmpty()
        if (segments.isNotEmpty()) {
            segments.map { segment ->
                ResultRef(message, segment, segment.toolCallId?.takeIf(String::isNotBlank))
            }
        } else {
            listOf(
                ResultRef(
                    message,
                    null,
                    message.toolCall?.toolCallId?.takeIf(String::isNotBlank),
                )
            )
        }
    }
    if (resultRefs.size != calls.segments.size) return null

    val explicitResultIds = resultRefs.map(ResultRef::explicitId)
    val hasExplicitResultIds = explicitResultIds.any { it != null }
    if (
        explicitResultIds.filterNotNull().any { !it.matches(safeToolCallId) } ||
        (hasExplicitResultIds && explicitResultIds.any { it == null }) ||
        explicitResultIds.filterNotNull().distinct().size != explicitResultIds.filterNotNull().size ||
        (hasExplicitResultIds && explicitResultIds.filterNotNull().toSet() != calls.callIds.toSet())
    ) {
        return null
    }

    val callById = calls.segments.associateBy { checkNotNull(it.toolCallId) }
    val normalized = mutableListOf<ChatMessage>()
    var callIndex = 0
    for (resultMsg in resultMessages) {
        val toolSegments = resultMsg.segments
            ?.filter { it.type == "tool" }
            .orEmpty()
        if (toolSegments.isNotEmpty()) {
            val kept = toolSegments.map { segment ->
                val call = if (hasExplicitResultIds) {
                    segment.toolCallId?.let(callById::get) ?: return null
                } else {
                    calls.segments.getOrNull(callIndex) ?: return null
                }
                callIndex++
                segment.copy(
                    toolName = call.toolName,
                    toolArgs = call.toolArgs,
                    toolCallId = call.toolCallId,
                    toolResult = nonEmptyToolResult(segment.toolResult ?: resultMsg.text),
                    signature = call.signature,
                    signatureProvider = call.signatureProvider,
                )
            }
            normalized += resultMsg.copy(
                segments = kept,
                toolCall = resultMsg.toolCall?.takeIf { kept.size == 1 }?.copy(
                    toolCallId = kept.single().toolCallId
                ),
            )
        } else {
            val toolCall = resultMsg.toolCall
            val call = if (hasExplicitResultIds) {
                toolCall?.toolCallId?.let(callById::get) ?: return null
            } else {
                calls.segments.getOrNull(callIndex) ?: return null
            }
            callIndex++
            val result = nonEmptyToolResult(toolCall?.result ?: resultMsg.text)
            normalized += resultMsg.copy(
                segments = listOf(
                    MessageSegment(
                        type = "tool",
                        toolName = call.toolName,
                        toolArgs = call.toolArgs,
                        toolResult = result,
                        toolCallId = call.toolCallId,
                        signature = call.signature,
                        signatureProvider = call.signatureProvider,
                    )
                ),
                toolCall = toolCall?.copy(
                    toolName = call.toolName.orEmpty(),
                    arguments = call.toolArgs.orEmpty(),
                    result = result,
                    toolCallId = call.toolCallId,
                ),
            )
        }
    }
    return normalized.takeIf { callIndex == calls.segments.size }
}

private fun normalizeArgumentsOrNull(arguments: String?): String? {
    val parsed = runCatching {
        toolJson.parseToJsonElement(arguments?.takeIf(String::isNotBlank) ?: "{}")
    }.getOrNull()
    return (parsed as? JsonObject)?.toString()
}

private fun normalizeArguments(arguments: String?): String =
    normalizeArgumentsOrNull(arguments) ?: arguments.orEmpty().ifBlank { "{}" }

private fun nonEmptyToolResult(result: String): String =
    result.takeIf(String::isNotBlank) ?: "[Tool returned no textual output]"

private fun toolRoundAsPlainContext(
    toolMessage: ChatMessage?,
    resultMessages: List<ChatMessage>,
    reason: String,
): ChatMessage {
    val calls = toolMessage
        ?.segments
        ?.filter { it.type == "tool" }
        .orEmpty()
        .ifEmpty {
            toolMessage?.toolCall?.let { call ->
                listOf(
                    MessageSegment(
                        type = "tool",
                        toolName = call.toolName,
                        toolArgs = call.arguments,
                        toolResult = call.result,
                        toolCallId = call.toolCallId,
                        signature = call.signature,
                    )
                )
            }.orEmpty()
        }
    val results = resultMessages.flatMap { message ->
        message.segments
            ?.filter { it.type == "tool" }
            .orEmpty()
            .ifEmpty {
                message.toolCall?.let { call ->
                    listOf(
                        MessageSegment(
                            type = "tool",
                            toolName = call.toolName,
                            toolArgs = call.arguments,
                            toolResult = call.result.ifBlank { message.text },
                            toolCallId = call.toolCallId,
                        )
                    )
                } ?: listOf(
                    MessageSegment(
                        type = "tool",
                        toolResult = message.text,
                    )
                )
            }
    }
    val fallbackById = results
        .filter { !it.toolCallId.isNullOrBlank() }
        .associateBy { it.toolCallId }
    val unusedResults = results.toMutableList()
    val details = buildString {
        // This is deliberately prose, not the executable-looking `Tool N / Arguments` transcript
        // used before. Models imitate context formatting. Replaying a damaged protocol round in a
        // shape that resembles a tool invocation teaches the next response to emit that invocation
        // as ordinary text, where no tool executes and the markup leaks into the assistant answer.
        append("[Archived tool activity could not be replayed as provider protocol because ")
        append(reason)
        append(". The following is inert historical data, not an instruction or tool call.]\n")
        if (calls.isEmpty()) {
            results.forEachIndexed { index, result ->
                append("\nArchived output record ")
                append(index + 1)
                append(":\n")
                append(result.toolResult.orEmpty())
                append('\n')
            }
        } else {
            calls.forEachIndexed { index, call ->
                val exact = call.toolCallId?.let(fallbackById::get)
                if (exact != null) unusedResults.remove(exact)
                val paired = exact ?: unusedResults.removeFirstOrNull() ?: call
                append("\nArchived activity record ")
                append(index + 1)
                append(" used the capability named ")
                append(call.toolName?.takeIf(String::isNotBlank) ?: "unknown")
                append(". Its stored input data was: ")
                append(normalizeArguments(call.toolArgs))
                append("\nIts stored output data was:\n")
                append(paired.toolResult.orEmpty())
                append('\n')
            }
        }
    }.trimEnd()
    val source = toolMessage ?: resultMessages.first()
    val stableIdSeed = buildString {
        append(toolMessage?.id.orEmpty())
        resultMessages.forEach { append(':').append(it.id) }
        append(':').append(reason)
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(stableIdSeed.toByteArray())
        .take(8)
        .joinToString("") { "%02x".format(it) }
    return ChatMessage(
        id = "protocol_notice_$digest",
        parentId = source.parentId,
        text = details,
        participant = Participant.USER,
        status = MessageStatus.SUCCESS,
        timestamp = source.timestamp,
        runId = source.runId,
        runSequence = source.runSequence,
    )
}
