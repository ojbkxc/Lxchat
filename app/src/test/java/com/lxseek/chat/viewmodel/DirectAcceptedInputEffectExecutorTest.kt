package com.lxseek.chat.viewmodel

import com.lxseek.chat.automation.ConversationExecutionCoordinator
import com.lxseek.chat.data.ConversationSettings
import com.lxseek.chat.data.local.ChatEntity
import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.data.local.RunEntity
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.RunEffect
import com.lxseek.chat.model.RunStatus
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectAcceptedInputEffectExecutorTest {
    @Test
    fun payloadLeaseDeletesUnownedFilesOnceAndPreservesTransferredFiles() {
        val deleted = mutableListOf<String>()
        val unowned = PreparedMessagePayloadLease(PAYLOAD) { deleted += it }

        unowned.releaseIfUnowned()
        unowned.releaseIfUnowned()

        assertEquals(listOf("one", "two"), deleted)

        val transferred = PreparedMessagePayloadLease(PAYLOAD) { deleted += "owned:$it" }
        transferred.transferOwnership()
        transferred.releaseIfUnowned()
        assertTrue(deleted.none { it.startsWith("owned:") })

        val rejected = PreparedMessagePayloadLease(PAYLOAD) { deleted += "rejected:$it" }
        rejected.transferOwnership()
        rejected.reclaimAfterRejectedTransfer()
        rejected.releaseIfUnowned()
        assertEquals(listOf("rejected:one", "rejected:two"), deleted.takeLast(2))
    }

    @Test
    fun durableCommitPrecedesAcceptanceProjectionAndBoundLaunch() = runBlocking {
        val fixture = Fixture()
        val state = ConversationGenerationState(CONVERSATION_ID)
        val effect = claimDirectEffect(state)
        coEvery { fixture.graphWriter.commit(any(), any()) } coAnswers {
            fixture.events += "room-commit"
            fixture.commit
        }
        coEvery { fixture.boundLauncher.launch(any(), state) } coAnswers {
            fixture.events += "bound-launch"
        }

        val execution = fixture.executor.launch(fixture.request(effect), state)
        val accepted = execution.awaitAcceptance()
        assertNotNull(execution.job)
        execution.job?.join()

        assertEquals(SendAcceptance.Direct(USER_ID, CONVERSATION_ID), accepted)
        assertEquals(
            listOf(
                "apply-pending",
                "room-commit",
                "persist-user:$USER_ID",
                "accept-callback:$USER_ID",
                "accept-event:$USER_ID",
                "scroll:$USER_ID",
                "compact",
                "bound-launch",
            ),
            fixture.events,
        )
        assertEquals(MODEL_ID, state.streamingMessage.value?.id)
        coVerify(exactly = 1) {
            fixture.boundLauncher.launch(
                match {
                    it.conversationId == CONVERSATION_ID &&
                        it.modelMessageId == MODEL_ID &&
                        it.runId == RUN_ID &&
                        it.uiToken == effect.identity.ownerToken &&
                        it.persistId > 0 &&
                        it.pass == 0 &&
                        it.callerTag == "sendMessage"
                },
                state,
            )
        }
        state.dispose()
        Unit
    }

    @Test
    fun uncommittedFailureReturnsNullCleansPayloadAndDoesNotLaunchProvider() = runBlocking {
        val deleted = mutableListOf<String>()
        val fixture = Fixture(deletePath = { deleted += it })
        val state = ConversationGenerationState(CONVERSATION_ID)
        val effect = claimDirectEffect(state)
        coEvery { fixture.graphWriter.commit(any(), any()) } throws
            IllegalStateException("Room unavailable")
        coEvery { fixture.conversations.getRun(RUN_ID) } returns null
        coEvery {
            fixture.terminalSettlement.failGenerationSetup(
                CONVERSATION_ID,
                RUN_ID,
                MODEL_ID,
                effect.identity.ownerToken,
                state,
                any(),
            )
        } just Runs

        val execution = fixture.executor.launch(fixture.request(effect), state)
        assertNull(execution.awaitAcceptance())
        execution.job?.join()

        assertEquals(listOf("one", "two"), deleted)
        assertTrue(fixture.events.none { it.startsWith("accept") })
        coVerify(exactly = 0) { fixture.boundLauncher.launch(any(), any()) }
        state.dispose()
        Unit
    }

    @Test
    fun cancellationAfterDurableCommitReconcilesIdentityAndKeepsPayloadOwned() = runBlocking {
        val deleted = mutableListOf<String>()
        val fixture = Fixture(deletePath = { deleted += it })
        val state = ConversationGenerationState(CONVERSATION_ID)
        val effect = claimDirectEffect(state)
        coEvery { fixture.graphWriter.commit(any(), any()) } throws
            CancellationException("cancelled after Room commit")
        coEvery { fixture.conversations.getRun(RUN_ID) } returns ACTIVE_RUN
        coEvery {
            fixture.terminalSettlement.settleCancelledDurableRun(state, any())
        } returns true

        val execution = fixture.executor.launch(fixture.request(effect), state)
        val accepted = execution.awaitAcceptance()
        execution.job?.join()

        assertEquals(SendAcceptance.Direct(USER_ID, CONVERSATION_ID), accepted)
        assertTrue(deleted.isEmpty())
        coVerify(exactly = 1) {
            fixture.terminalSettlement.settleCancelledDurableRun(
                state,
                ConversationGenerationState.RunBindingOutcome.Active,
            )
        }
        coVerify(exactly = 0) {
            fixture.terminalSettlement.failGenerationSetup(any(), any(), any(), any(), any(), any())
        }
        state.dispose()
        Unit
    }

    @Test
    fun newConversationPublishesOnlyAfterDurableCommit() = runBlocking {
        val fixture = Fixture()
        val state = ConversationGenerationState(CONVERSATION_ID)
        val effect = claimDirectEffect(state)
        coEvery { fixture.graphWriter.commit(any(), any()) } coAnswers {
            fixture.events += "room-commit"
            fixture.commit
        }
        coEvery { fixture.boundLauncher.launch(any(), state) } just Runs

        val execution = fixture.executor.launch(
            fixture.request(
                effect = effect,
                wasNewChat = true,
                newConversation = ChatEntity(CONVERSATION_ID, "New chat"),
            ),
            state,
        )
        execution.awaitAcceptance()
        execution.job?.join()

        val commitIndex = fixture.events.indexOf("room-commit")
        val acceptanceIndex = fixture.events.indexOf("accept-callback:$USER_ID")
        val publicationIndex = fixture.events.indexOf("publish-new")
        assertTrue(commitIndex >= 0)
        assertTrue(acceptanceIndex > commitIndex)
        assertTrue(publicationIndex > acceptanceIndex)
        coVerify(exactly = 0) {
            fixture.compactController.automaticBeforeBoundary(any(), any(), any(), any())
        }
        state.dispose()
        Unit
    }

    private class Fixture(
        deletePath: (String) -> Unit = {},
    ) {
        val conversations = mockk<ConversationRepository>()
        val settings = mockk<SettingsRepository>()
        val graphWriter = mockk<AcceptedInputGraphWriter>()
        val requestBuilder = mockk<GenerationRequestBuilder>()
        val compactController = mockk<ConversationCompactController>()
        val terminalSettlement = mockk<GenerationTerminalSettlementController>()
        val boundLauncher = mockk<BoundRunGenerationLauncher>()
        val events = mutableListOf<String>()
        val commit = AcceptedInputGraphWriter.Commit(
            userMessage = USER_ENTITY,
            modelMessage = MODEL_ENTITY,
            messageSelections = mapOf(null to USER_ID, USER_ID to MODEL_ID),
        )
        private val ids = ArrayDeque(listOf(USER_ID, MODEL_ID))
        private val payloadLease = PreparedMessagePayloadLease(PAYLOAD, deletePath)
        val executor: DirectAcceptedInputEffectExecutor

        init {
            every { settings.maxContextWindow } returns MutableStateFlow(4096)
            every { settings.titleGenerationEnabled } returns MutableStateFlow(false)
            coEvery { settings.incrementMessagesSent() } just Runs
            every {
                requestBuilder.buildEffectiveConversationSettings(CONVERSATION_ID)
            } returns ConversationSettings(contextWindow = 4096)
            coEvery {
                compactController.automaticBeforeBoundary(
                    CONVERSATION_ID,
                    "provider:model",
                    4096,
                    any(),
                )
            } coAnswers { events += "compact" }
            coEvery {
                conversations.getMessagesForConversationSnapshot(CONVERSATION_ID)
            } returns listOf(MODEL_ENTITY.copy(status = MessageStatus.SUCCESS))

            executor = DirectAcceptedInputEffectExecutor(
                conversations = conversations,
                settings = settings,
                executionCoordinator = ConversationExecutionCoordinator(),
                graphWriter = graphWriter,
                renderStore = ConversationRenderStore(),
                requestBuilder = requestBuilder,
                compactController = compactController,
                terminalSettlement = terminalSettlement,
                boundRunGenerationLauncher = boundLauncher,
                acceptanceNotifier = SendAcceptanceNotifier { _, messageId ->
                    events += "accept-event:$messageId"
                },
                toUiMessage = ::toUiMessage,
                isConversationOpen = { true },
                applyPendingConversationSettings = { events += "apply-pending" },
                publishNewConversation = { events += "publish-new" },
                onUserMessagePersisted = { messageId, _ ->
                    events += "persist-user:$messageId"
                },
                onGenerateTitle = { events += "generate-title" },
                idFactory = ids::removeFirst,
                clock = { 100L },
            )
        }

        fun request(
            effect: RunEffect.PersistAcceptedInput,
            wasNewChat: Boolean = false,
            newConversation: ChatEntity? = null,
        ) = DirectAcceptedInputRequest(
            inputEffect = effect,
            wasNewChat = wasNewChat,
            newConversation = newConversation,
            userText = "hello",
            payloadLease = payloadLease,
            modelId = "provider:model",
            providerName = "provider",
            activeKey = "key",
            alreadyHoldsLock = false,
            requestScroll = { _, messageId -> events += "scroll:$messageId" },
            onAccepted = { events += "accept-callback:${it.messageId}" },
            onModelMessageCreated = null,
        )
    }

    private companion object {
        const val CONVERSATION_ID = "conversation"
        const val RUN_ID = "run"
        const val USER_ID = "user"
        const val MODEL_ID = "model"
        val PAYLOAD = MessagePayloadBuilder.MessagePayload(
            allImages = listOf("image"),
            attachmentMeta = null,
            preparedOwnedPaths = listOf("one", "two"),
        )
        val USER_ENTITY = MessageEntity(
            id = USER_ID,
            conversationId = CONVERSATION_ID,
            text = "hello",
            participant = Participant.USER,
            timestamp = 100L,
            runId = RUN_ID,
            runSequence = 0,
            consumedAtPass = 0,
        )
        val MODEL_ENTITY = MessageEntity(
            id = MODEL_ID,
            conversationId = CONVERSATION_ID,
            parentId = USER_ID,
            text = "",
            participant = Participant.MODEL,
            status = MessageStatus.SENDING,
            timestamp = 101L,
            modelName = "provider:model",
            runId = RUN_ID,
            runSequence = 1,
        )
        val ACTIVE_RUN = RunEntity(
            id = RUN_ID,
            conversationId = CONVERSATION_ID,
            parentRunId = null,
            status = RunStatus.ACTIVE,
            activeSlot = 1,
            startedAt = 100L,
            lastCheckpointAt = 101L,
        )
    }
}

private suspend fun claimDirectEffect(
    state: ConversationGenerationState,
): RunEffect.PersistAcceptedInput = state.commands.requestSend(
    proposedRunId = "run",
    effectId = "send-run",
    directOnly = false,
    hasPendingGuidance = false,
).effects.filterIsInstance<RunEffect.PersistAcceptedInput>().single()

private fun toUiMessage(entity: MessageEntity) = ChatMessage(
    id = entity.id,
    parentId = entity.parentId,
    text = entity.text,
    images = entity.images,
    thoughts = entity.thoughts,
    thoughtTitle = entity.thoughtTitle,
    status = entity.status,
    participant = entity.participant,
    timestamp = entity.timestamp,
    modelName = entity.modelName,
    runId = entity.runId,
    runSequence = entity.runSequence,
    consumedAtPass = entity.consumedAtPass,
)
