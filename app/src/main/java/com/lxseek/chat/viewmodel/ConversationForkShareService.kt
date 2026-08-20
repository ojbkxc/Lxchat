package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.local.ChatEntity
import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.data.local.RunEntity
import com.lxseek.chat.data.MessageAttachmentCloneSession
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.model.AttachmentMeta
import com.lxseek.chat.model.MessageSegment
import com.lxseek.chat.model.Participant
import com.lxseek.chat.util.Constants
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * One source of truth for the two conversation-scope operations:
 *
 * - Fork copies the selected branch, optionally stopping after one assistant output.
 * - Share renders either that same selected branch or one complete generation Run.
 *
 * Synthetic tool/result rows stay in the forked Run graph even though the visible UI path omits
 * them. This preserves provider-valid history if the user continues chatting in the fork.
 */
class ConversationForkShareService(
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val attachmentRoot: File,
) {
    sealed interface ForkResult {
        data class Success(val conversationId: String) : ForkResult
        data class Failure(val reason: String) : ForkResult
    }

    sealed interface ShareResult {
        data class Success(val text: String) : ShareResult
        data class Failure(val reason: String) : ShareResult
    }

    suspend fun fork(
        conversationId: String,
        throughMessageId: String?,
    ): ForkResult = withContext(Dispatchers.Default) {
        forkOffMain(conversationId, throughMessageId)
    }

    private suspend fun forkOffMain(
        conversationId: String,
        throughMessageId: String?,
    ): ForkResult {
        val source = conversations.getConversation(conversationId)
            ?: return ForkResult.Failure("Conversation not found")
        val sourceMessages = conversations.getMessagesForConversationSnapshot(conversationId)
        val allRuns = conversations.getRunsForConversationSnapshot(conversationId)
        val selectedChildren = conversations.restoreBranchSelections(conversationId)
        val selection = resolveConversationBranchPath(
            messages = sourceMessages,
            runs = allRuns,
            selectedChildren = selectedChildren,
            throughMessageId = throughMessageId,
        ) ?: return ForkResult.Failure(
            "The selected branch is incomplete or contains a broken parent link"
        )
        if (selection.runIds.isEmpty()) {
            return ForkResult.Failure("Conversation has no completed run")
        }

        val sourceRuns = allRuns.associateBy { it.id }
        val selectedRuns = selection.runIds.map { runId ->
            sourceRuns[runId] ?: return ForkResult.Failure("Run not found: $runId")
        }
        if (selectedRuns.any { !it.status.isTerminal }) {
            return ForkResult.Failure("Wait for generation to finish before forking")
        }

        val selectedMessages = selection.structuralMessages
            .filter { it.conversationId == conversationId }
        if (selectedMessages.isEmpty()) return ForkResult.Failure("Conversation has no messages")
        val selectedMessageIds = selectedMessages.mapTo(mutableSetOf()) { it.id }
        val selectedRunIds = selection.runIds.toSet()
        if (selectedRuns.any { it.parentRunId != null && it.parentRunId !in selectedRunIds }) {
            return ForkResult.Failure("Selected Run ancestry is incomplete")
        }
        if (selectedMessages.any { it.parentId != null && it.parentId !in selectedMessageIds }) {
            return ForkResult.Failure("Selected message ancestry is incomplete")
        }

        val newConversationId = UUID.randomUUID().toString()
        val runIds = selection.runIds.associateWith { UUID.randomUUID().toString() }
        val messageIds = selectedMessages.associate { message ->
            message.id to remapForkMessageId(message.id)
        }
        val clonedRuns = selectedRuns.map { run ->
            run.copy(
                id = checkNotNull(runIds[run.id]),
                conversationId = newConversationId,
                parentRunId = run.parentRunId?.let { checkNotNull(runIds[it]) },
                activeSlot = null,
            )
        }
        val attachmentClones = MessageAttachmentCloneSession(attachmentRoot)
        var graphCommitted = false
        val clonedMessages = try {
            withContext(Dispatchers.IO) {
                selectedMessages
            .sortedWith(
                compareBy<MessageEntity> { selection.runIds.indexOf(it.runId) }
                    .thenBy { it.runSequence }
                    .thenBy { it.timestamp }
                    .thenBy { it.id }
            )
                    .map { message ->
                        attachmentClones.cloneMessage(
                            message.copy(
                    id = checkNotNull(messageIds[message.id]),
                    conversationId = newConversationId,
                    parentId = message.parentId?.let { checkNotNull(messageIds[it]) },
                    runId = checkNotNull(runIds[message.runId]),
                            ),
                            ownerKey = checkNotNull(messageIds[message.id]),
                        )
                    }
            }
        } catch (cancelled: CancellationException) {
            attachmentClones.rollback()
            throw cancelled
        } catch (e: Exception) {
            attachmentClones.rollback()
            return ForkResult.Failure(e.localizedMessage ?: "Unable to copy fork attachments")
        }
        val explicitMessageSelections = buildMap<String?, String> {
            selectedChildren.forEach { (parentId, childId) ->
                if ((parentId == null || parentId in selectedMessageIds) &&
                    childId in selectedMessageIds
                ) {
                    put(parentId?.let { checkNotNull(messageIds[it]) }, checkNotNull(messageIds[childId]))
                }
            }
            // Reproduce the exact persisted traversal, not structuralMessages: that list also
            // contains parallel protocol side-chain rows appended for history completeness.
            // Treating closure rows as traversal edges can select a dead-end result sibling and
            // make an otherwise-complete fork appear truncated.
            selection.selectedPathMessages.zipWithNext { parent, child ->
                if (child.parentId == parent.id) {
                    put(checkNotNull(messageIds[parent.id]), checkNotNull(messageIds[child.id]))
                }
            }
            selection.selectedPathMessages.firstOrNull()
                ?.let { first -> put(null, checkNotNull(messageIds[first.id])) }
        }
        val explicitRunSelections = buildMap<String?, String> {
            clonedRuns.forEach { run -> put(run.parentRunId, run.id) }
        }

        val forkConversation = ChatEntity(
            id = newConversationId,
            title = source.title,
            selectedBranchesJson = explicitMessageSelections.encodeSelections(),
            systemPromptId = source.systemPromptId,
            modelId = source.modelId,
            taskId = null,
            origin = "user",
            graduated = false,
            selectedRunBranchesJson = explicitRunSelections.encodeSelections(),
        )
        val clonedSelection = resolveConversationBranchPath(
            messages = clonedMessages,
            runs = clonedRuns,
            selectedChildren = explicitMessageSelections,
        ) ?: run {
            attachmentClones.rollback()
            return ForkResult.Failure("Fork validation failed")
        }
        val expectedVisibleIds = selection.visibleMessages
            .filter { it.id in selectedMessageIds }
            .map { checkNotNull(messageIds[it.id]) }
        if (clonedSelection.visibleMessages.map { it.id } != expectedVisibleIds) {
            attachmentClones.rollback()
            return ForkResult.Failure("Fork validation found a truncated visible path")
        }
        return try {
            conversations.createForkGraph(
                conversation = forkConversation,
                runs = clonedRuns,
                messages = clonedMessages,
                sourceToForkMessageIds = messageIds,
            )
            graphCommitted = true
            attachmentClones.commit()
            try {
                settings.conversationSettings.value[conversationId]?.let { sourceSettings ->
                    settings.setConversationSettings(newConversationId, sourceSettings)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                // The Room graph already committed atomically. Do not report a false fork failure
                // that would encourage the user to retry and create a duplicate conversation.
                DebugLog.e("ForkShare", "Forked graph but could not copy conversation settings", e)
            }
            ForkResult.Success(newConversationId)
        } catch (cancelled: CancellationException) {
            if (!graphCommitted) attachmentClones.rollback()
            throw cancelled
        } catch (e: Exception) {
            if (!graphCommitted) attachmentClones.rollback()
            ForkResult.Failure(e.localizedMessage ?: "Unable to fork conversation")
        }
    }

    suspend fun shareAll(conversationId: String): ShareResult =
        withContext(Dispatchers.Default) {
            shareAllOffMain(conversationId)
        }

    private suspend fun shareAllOffMain(conversationId: String): ShareResult {
        val snapshot = shareSnapshot(conversationId)
            ?: return ShareResult.Failure("Conversation not found")
        return renderShare(snapshot, snapshot.branch.visibleMessages.mapTo(linkedSetOf()) { it.id })
    }

    suspend fun shareRun(
        conversationId: String,
        assistantMessageId: String,
    ): ShareResult = withContext(Dispatchers.Default) {
        shareRunOffMain(conversationId, assistantMessageId)
    }

    private suspend fun shareRunOffMain(
        conversationId: String,
        assistantMessageId: String,
    ): ShareResult {
        val snapshot = shareSnapshot(conversationId)
            ?: return ShareResult.Failure("Conversation not found")
        val message = snapshot.branch.visibleMessages.singleOrNull { it.id == assistantMessageId }
            ?.takeIf { it.participant == Participant.MODEL }
            ?: return ShareResult.Failure("Assistant message not found")
        return renderShare(snapshot, linkedSetOf(message.id))
    }

    suspend fun shareMessages(
        conversationId: String,
        messageIds: Set<String>,
    ): ShareResult = withContext(Dispatchers.Default) {
        shareMessagesOffMain(conversationId, messageIds)
    }

    suspend fun buildPlainText(
        conversationId: String,
        messageIds: Set<String>,
        userLabel: String,
        aiLabel: String,
    ): ShareResult = withContext(Dispatchers.Default) {
        if (messageIds.isEmpty()) return@withContext ShareResult.Failure("Select at least one message")
        val snapshot = shareSnapshot(conversationId)
            ?: return@withContext ShareResult.Failure("Conversation not found")
        renderShare(snapshot, messageIds, asMarkdown = false, userLabel = userLabel, aiLabel = aiLabel)
    }

    private suspend fun shareMessagesOffMain(
        conversationId: String,
        messageIds: Set<String>,
    ): ShareResult {
        if (messageIds.isEmpty()) return ShareResult.Failure("Select at least one message")
        val snapshot = shareSnapshot(conversationId)
            ?: return ShareResult.Failure("Conversation not found")
        return renderShare(snapshot, messageIds)
    }

    private suspend fun shareSnapshot(conversationId: String): ShareSnapshot? {
        val conversation = conversations.getConversation(conversationId) ?: return null
        val messages = conversations.getMessagesForConversationSnapshot(conversationId)
        val runs = conversations.getRunsForConversationSnapshot(conversationId)
        val branch = resolveConversationBranchPath(
            messages = messages,
            runs = runs,
            selectedChildren = conversations.restoreBranchSelections(conversationId),
        ) ?: return null
        return ShareSnapshot(
            title = conversation.title,
            runsById = runs.associateBy { it.id },
            branch = branch,
        )
    }

    private fun renderShare(
        snapshot: ShareSnapshot,
        selectedMessageIds: Set<String>,
        asMarkdown: Boolean = true,
        userLabel: String = "User",
        aiLabel: String = "AI",
    ): ShareResult {
        val visibleById = snapshot.branch.visibleMessages.associateBy { it.id }
        if (selectedMessageIds.any { it !in visibleById }) {
            return ShareResult.Failure("A selected message is no longer on the visible branch")
        }
        val completeRunIds = selectedMessageIds
            .mapNotNull { visibleById[it] }
            .filter { it.participant == Participant.MODEL }
            .mapTo(linkedSetOf()) { it.runId }
        if (completeRunIds.any { snapshot.runsById[it]?.status?.isTerminal != true }) {
            return ShareResult.Failure("Wait for generation to finish before sharing")
        }
        val selected = snapshot.branch.structuralMessages
            .filter { message ->
                message.id in selectedMessageIds || message.runId in completeRunIds
            }
            .sortedWith(
                compareBy<MessageEntity> {
                    snapshot.branch.runIds.indexOf(it.runId).let { index ->
                        if (index >= 0) index else Int.MAX_VALUE
                    }
                }
                    .thenBy { it.runSequence }
                    .thenBy { it.timestamp }
                    .thenBy { it.id }
            )
        val includeThinking = settings.shareIncludeThinking.value
        val includeTools = settings.shareIncludeTools.value
        val text = if (asMarkdown) formatShareText(snapshot.title, selected, includeThinking, includeTools) else formatPlainText(selected, userLabel, aiLabel)
        return if (text.isBlank()) ShareResult.Failure("Selection has no shareable content")
        else ShareResult.Success(text)
    }

    private data class ShareSnapshot(
        val title: String,
        val runsById: Map<String, RunEntity>,
        val branch: ConversationBranchPath,
    )
}

private fun Map<String?, String>.encodeSelections(): String =
    Json.encodeToString(mapKeys { (key, _) -> key ?: "null" })

internal fun formatShareText(
    title: String,
    messages: List<MessageEntity>,
    includeThinking: Boolean = true,
    includeTools: Boolean = true,
): String {
    val blocks = mutableListOf<String>()
    title.trim().takeIf { it.isNotBlank() }?.let { blocks += "# $it" }

    messages.forEach { message ->
        if (message.isSynthetic()) {
            return@forEach
        }
        when (message.participant) {
            Participant.USER -> {
                val body = buildString {
                    append(message.text.trim())
                    attachmentSummary(message)?.let { summary ->
                        if (isNotEmpty()) append("\n\n")
                        append(summary)
                    }
                }.trim()
                if (body.isNotBlank()) blocks += "## User\n\n$body"
            }
            Participant.MODEL -> {
                val segments = message.toolCallJson
                    ?.let { raw -> runCatching { Json.decodeFromString<List<MessageSegment>>(raw) }.getOrNull() }
                    .orEmpty()
                if (segments.isNotEmpty()) {
                    var includedAnswer = false
                    segments.forEach { segment ->
                        when (segment.type) {
                            "thought" -> if (includeThinking) segment.content.trim().takeIf { it.isNotBlank() }?.let {
                                blocks += "## Thinking\n\n$it"
                            }
                            "tool" -> if (includeTools) {
                                val name = segment.toolName?.takeIf { it.isNotBlank() } ?: "Tool"
                                val toolBody = buildString {
                                    segment.toolArgs?.takeIf { it.isNotBlank() }?.let {
                                        append("Arguments\n\n```json\n")
                                        append(it)
                                        append("\n```")
                                    }
                                    segment.toolResult?.takeIf { it.isNotBlank() }?.let {
                                        if (isNotEmpty()) append("\n\n")
                                        append("Result\n\n```\n")
                                        append(it)
                                        append("\n```")
                                    }
                                }
                                blocks += "## Tool: $name" +
                                    toolBody.takeIf { it.isNotBlank() }?.let { "\n\n$it" }.orEmpty()
                            }
                            "answer" -> segment.content.trim().takeIf { it.isNotBlank() }?.let {
                                includedAnswer = true
                                blocks += "## Assistant\n\n$it"
                            }
                            "transcription" -> segment.content.trim().takeIf { it.isNotBlank() }?.let {
                                blocks += "## Transcription\n\n$it"
                            }
                        }
                    }
                    if (!includedAnswer) {
                        message.text.trim().takeIf { it.isNotBlank() }?.let {
                            blocks += "## Assistant\n\n$it"
                        }
                    }
                } else {
                    message.thoughts?.trim()?.takeIf { it.isNotBlank() }?.let {
                        blocks += "## Thinking\n\n$it"
                    }
                    message.text.trim().takeIf { it.isNotBlank() }?.let {
                        blocks += "## Assistant\n\n$it"
                    }
                }
            }
            Participant.ERROR -> message.text.trim().takeIf { it.isNotBlank() }?.let {
                blocks += "## Error\n\n$it"
            }
        }
    }
    return blocks.joinToString("\n\n")
}

private fun attachmentSummary(message: MessageEntity): String? {
    val names = message.attachmentMeta
        ?.let { raw -> runCatching { Json.decodeFromString<AttachmentMeta>(raw) }.getOrNull() }
        ?.items
        ?.mapNotNull { it.fileName?.takeIf(String::isNotBlank) }
        .orEmpty()
    val imageCount = message.images.size
    if (names.isEmpty() && imageCount == 0) return null
    return buildString {
        if (names.isNotEmpty()) append("Attachments: ${names.joinToString(", ")}")
        if (imageCount > 0) {
            if (isNotEmpty()) append("\n")
            append("Images: $imageCount")
        }
    }
}

private fun formatPlainText(
    messages: List<MessageEntity>,
    userLabel: String,
    aiLabel: String,
): String {
    val blocks = mutableListOf<String>()
    messages.forEach { message ->
        if (message.isSynthetic()) return@forEach
        when (message.participant) {
            Participant.USER -> {
                val body = message.text.trim()
                if (body.isNotBlank()) blocks += "[$userLabel] $body"
            }
            Participant.MODEL -> {
                val segments = message.toolCallJson
                    ?.let { raw -> runCatching { Json.decodeFromString<List<MessageSegment>>(raw) }.getOrNull() }
                    .orEmpty()
                val body = if (segments.isNotEmpty()) {
                    segments.filter { it.type == "answer" }
                        .joinToString("\n") { it.content.trim() }
                        .trim()
                } else {
                    message.text.trim()
                }
                if (body.isNotBlank()) blocks += "[$aiLabel] $body"
            }
            else -> {}
        }
    }
    return blocks.joinToString("\n\n")
}

/**
 * Protocol row kind is encoded in the persisted message ID and consumed throughout provider,
 * projection, deletion and fork code. A fork must generate a fresh identity without erasing that
 * discriminator; otherwise tool/result rows become ordinary visible messages in the cloned graph.
 */
internal fun remapForkMessageId(
    sourceMessageId: String,
    generatedId: String = UUID.randomUUID().toString(),
): String = when {
    sourceMessageId.startsWith(Constants.TOOL_MSG_PREFIX) ->
        "${Constants.TOOL_MSG_PREFIX}$generatedId"
    sourceMessageId.startsWith(Constants.RESULT_MSG_PREFIX) ->
        "${Constants.RESULT_MSG_PREFIX}$generatedId"
    else -> generatedId
}
