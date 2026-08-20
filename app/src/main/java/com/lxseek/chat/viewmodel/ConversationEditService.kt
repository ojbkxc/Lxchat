package com.lxseek.chat.viewmodel

import com.lxseek.chat.automation.ConversationExecutionCoordinator
import com.lxseek.chat.data.MessageAttachmentCloneSession
import com.lxseek.chat.data.cloneAttachmentMeta
import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.data.local.RunEntity
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.RunStatus
import com.lxseek.chat.util.Constants
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Immutable UI snapshot and Provider selection for one edit intent. */
internal data class ConversationEditRequest(
    val conversationId: String,
    val messageId: String,
    val newText: String,
    val modelId: String,
    val providerName: String,
    val activeKey: String,
    val visiblePath: List<ChatMessage>,
)

/** Owns attachment-safe cloning for the user-input side of an edited Run. */
internal class EditedRunInputCloner(
    private val destinationDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun clone(
        sourceInputs: List<MessageEntity>,
        destinationRunId: String,
        textOverrides: Map<String, String> = emptyMap(),
    ): List<MessageEntity> = withContext(ioDispatcher) {
        require(sourceInputs.isNotEmpty())
        val attachmentClones = MessageAttachmentCloneSession(destinationDir)
        try {
            val now = clock()
            var parentId = sourceInputs.first().parentId
            sourceInputs.mapIndexed { index, source ->
                val cloned = EditedRunInputFactory.create(
                    source = source,
                    id = idFactory(),
                    parentId = parentId,
                    text = textOverrides[source.id] ?: source.text,
                    timestamp = now + index,
                    destinationRunId = destinationRunId,
                    runSequence = index.toLong(),
                    cloneBackingPath = { path ->
                        attachmentClones.cloneBackingPath("edited-run-inputs", path)
                    },
                )
                parentId = cloned.id
                cloned
            }.also { attachmentClones.commit() }
        } catch (error: Exception) {
            attachmentClones.rollback()
            throw error
        }
    }
}

/** Pure entity transformation used within an attachment clone session. */
internal object EditedRunInputFactory {
    fun create(
        source: MessageEntity,
        id: String,
        parentId: String?,
        text: String,
        timestamp: Long,
        destinationRunId: String,
        runSequence: Long,
        cloneBackingPath: (String) -> String,
    ): MessageEntity {
        val clonedMeta = source.attachmentMeta?.let { raw ->
            cloneAttachmentMeta(raw, cloneBackingPath)
        }
        return source.copy(
            id = id,
            parentId = parentId,
            text = text,
            images = source.images.map(cloneBackingPath),
            status = MessageStatus.SUCCESS,
            timestamp = timestamp,
            attachmentMeta = clonedMeta,
            runId = destinationRunId,
            runSequence = runSequence,
            consumedAtPass = 0,
        )
    }
}

/**
 * Performs one idle-only edit branch mutation and launches its already-bound generation tail.
 *
 * The call-scoped [ConversationGenerationState] remains the only Run-state owner. This service
 * owns no scope, Job, mailbox, or mutable lifecycle state.
 */
internal class ConversationEditService(
    private val conversations: ConversationRepository,
    private val executionCoordinator: ConversationExecutionCoordinator,
    private val inputCloner: EditedRunInputCloner,
    private val terminalSettlement: GenerationTerminalSettlementController,
    private val boundRunGenerationLauncher: BoundRunGenerationLauncher,
    private val toUiMessage: (MessageEntity) -> ChatMessage,
    private val isConversationOpen: (String) -> Boolean,
    private val projectGraph: (
        conversationId: String,
        messages: List<ChatMessage>,
        selectedChildren: Map<String?, String>,
        streamingMessage: ChatMessage,
    ) -> Unit,
    private val awaitProjectedPath: suspend (conversationId: String, messageId: String) -> Unit,
    private val onUserMessagePersisted: (messageId: String, text: String) -> Unit,
    private val onScrollToMessage: (String) -> Unit,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun edit(
        request: ConversationEditRequest,
        state: ConversationGenerationState,
    ): Boolean {
        if (request.newText.isBlank()) return false
        val messageToEdit = request.visiblePath.find { it.id == request.messageId } ?: return false
        if (messageToEdit.participant != Participant.USER) return false
        val sourceRunId = messageToEdit.runId ?: return false
        val inputBoundary = request.visiblePath
            .filter {
                it.runId == sourceRunId &&
                    it.participant == Participant.USER &&
                    !it.id.startsWith(Constants.TOOL_MSG_PREFIX) &&
                    !it.id.startsWith(Constants.RESULT_MSG_PREFIX)
            }
            .minWithOrNull(
                compareBy<ChatMessage> { it.runSequence ?: Long.MAX_VALUE }
                    .thenBy { it.timestamp }
                    .thenBy { it.id },
            )
        if (inputBoundary?.id != request.messageId) return false

        val uiToken = state.tryAcquireForReplacement() ?: return false
        val runId = idFactory()
        val committed = CompletableDeferred<Boolean>()
        val job = state.launchGenerationJob(uiToken) {
            val persistId = state.nextPersistId()
            var setupModelMessageId: String? = null
            var graphCommitted = false
            var runBound = false
            var stopFinalizationClaimed = false
            try {
                executionCoordinator.withConversationLock(request.conversationId) lock@{
                    val persistedMessages = conversations.getMessagesForConversationSnapshot(
                        request.conversationId,
                    )
                    val persistedSource = persistedMessages
                        .find { it.id == request.messageId } ?: return@lock
                    if (persistedSource.runId != sourceRunId) return@lock
                    val sourceRun = conversations.getRun(sourceRunId) ?: return@lock
                    val newUser = inputCloner.clone(
                        sourceInputs = listOf(persistedSource),
                        destinationRunId = runId,
                        textOverrides = mapOf(persistedSource.id to request.newText),
                    ).single()
                    val modelMessageId = idFactory()
                    setupModelMessageId = modelMessageId
                    val startTime = newUser.timestamp + 1
                    val modelEntity = MessageEntity(
                        id = modelMessageId,
                        conversationId = request.conversationId,
                        parentId = newUser.id,
                        text = "",
                        thoughts = null,
                        status = MessageStatus.SENDING,
                        participant = Participant.MODEL,
                        timestamp = startTime,
                        modelName = request.modelId,
                        runId = runId,
                        runSequence = 1,
                    )
                    val graphCommit = conversations.createRunWithMessages(
                        RunEntity(
                            id = runId,
                            conversationId = request.conversationId,
                            parentRunId = sourceRun.parentRunId,
                            status = RunStatus.ACTIVE,
                            activeSlot = 1,
                            startedAt = newUser.timestamp,
                            lastCheckpointAt = startTime,
                        ),
                        listOf(newUser, modelEntity),
                        messageSelectionUpdates = mapOf(
                            newUser.parentId to newUser.id,
                            newUser.id to modelEntity.id,
                        ),
                    )
                    graphCommitted = true
                    val binding = state.bindPersistedRun(uiToken, runId)
                    runBound = binding is ConversationGenerationState.RunBindingOutcome.Active
                    if (!runBound) {
                        if (binding is ConversationGenerationState.RunBindingOutcome.Stopping) {
                            stopFinalizationClaimed = true
                            terminalSettlement.settleLateBoundStop(state, binding)
                        } else {
                            withContext(kotlinx.coroutines.NonCancellable) {
                                conversations.finishStoppedGeneration(emptyList(), runId)
                            }
                        }
                        committed.complete(true)
                        return@lock
                    }
                    runCatching {
                        onUserMessagePersisted(newUser.id, request.newText)
                    }.onFailure { error ->
                        DebugLog.w(
                            "MessageGenerationController",
                            "Failed to enqueue edited-message indexing for ${newUser.id}",
                            error,
                        )
                    }
                    val placeholder = toUiMessage(modelEntity)
                    state.streamUpdate(uiToken, placeholder)
                    if (isConversationOpen(request.conversationId)) {
                        projectGraph(
                            request.conversationId,
                            listOf(toUiMessage(newUser), placeholder),
                            graphCommit.messageSelections,
                            placeholder,
                        )
                        onScrollToMessage(newUser.id)
                    }
                    if (isConversationOpen(request.conversationId)) {
                        awaitProjectedPath(request.conversationId, newUser.id)
                    }
                    committed.complete(true)
                    boundRunGenerationLauncher.launch(
                        BoundRunGenerationRequest(
                            conversationId = request.conversationId,
                            modelMessageId = modelMessageId,
                            startTime = startTime,
                            isRegenerate = false,
                            replaceMessageId = null,
                            providerName = request.providerName,
                            modelId = request.modelId,
                            activeKey = request.activeKey,
                            uiToken = uiToken,
                            persistId = persistId,
                            runId = runId,
                            pass = 0,
                            callerTag = "editMessage",
                        ),
                        state,
                    )
                }
            } catch (error: CancellationException) {
                if (!runBound && !stopFinalizationClaimed) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        if (conversations.getRun(runId) != null) {
                            graphCommitted = true
                            val binding = state.bindPersistedRun(uiToken, runId)
                            stopFinalizationClaimed =
                                terminalSettlement.settleCancelledDurableRun(state, binding)
                            if (!stopFinalizationClaimed) {
                                conversations.finishStoppedGeneration(emptyList(), runId)
                            }
                        }
                    }
                }
                throw error
            } catch (error: Exception) {
                if (!runBound && !stopFinalizationClaimed) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        if (conversations.getRun(runId) != null) {
                            graphCommitted = true
                            val binding = state.bindPersistedRun(uiToken, runId)
                            runBound = binding is
                                ConversationGenerationState.RunBindingOutcome.Active
                            if (binding is ConversationGenerationState.RunBindingOutcome.Stopping) {
                                stopFinalizationClaimed = true
                                terminalSettlement.settleLateBoundStop(state, binding)
                            }
                        }
                    }
                }
                terminalSettlement.failGenerationSetup(
                    conversationId = request.conversationId,
                    runId = runId,
                    modelMessageId = setupModelMessageId,
                    uiToken = uiToken,
                    state = state,
                    error = error,
                )
            } finally {
                if (!committed.isCompleted) committed.complete(graphCommitted)
            }
        }
        if (job == null) return false
        return committed.await()
    }
}
