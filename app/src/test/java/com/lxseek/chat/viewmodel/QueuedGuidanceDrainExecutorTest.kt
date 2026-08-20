package com.lxseek.chat.viewmodel

import com.lxseek.chat.automation.ConversationExecutionCoordinator
import com.lxseek.chat.data.ConversationSettings
import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.data.local.RunEntity
import com.lxseek.chat.data.local.RunGraphCommit
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuedGuidanceDrainExecutorTest {
    @Test
    fun claimUsesFreshRunIdentityAndFailedLeaseReturnsToFifoFront() = runBlocking {
        val fixture = Fixture()
        val state = ConversationGenerationState("conversation")
        QUEUED.forEach(state::enqueueSend)
        val lease = checkNotNull(state.claimQueuedSends())

        val claim = fixture.executor.claimUnderLock(state, lease)

        assertNotNull(claim)
        assertEquals("fresh-run", claim?.inputEffect?.identity?.runId)
        assertEquals("guidance-fresh-run", claim?.inputEffect?.identity?.effectId)
        assertTrue(state.queuedSends.value.isEmpty())
        assertTrue(state.settleGuidanceClaim(lease.id, durable = false))
        assertEquals(listOf("guidance-1", "guidance-2"), state.queuedSends.value.map { it.id })
        state.dispose()
        Unit
    }

    @Test
    fun launchCommitsWholeFifoAsOneBubbleBeforeCompactAndBoundGeneration() = runBlocking {
        val fixture = Fixture()
        val state = ConversationGenerationState("conversation")
        QUEUED.forEach(state::enqueueSend)
        val lease = checkNotNull(state.claimQueuedSends())
        val claim = checkNotNull(fixture.executor.claimUnderLock(state, lease))
        every {
            fixture.requestBuilder.resolveProviderKey("provider:model-2")
        } returns GenerationRequestBuilder.ProviderKey("provider", "active-key")
        every {
            fixture.requestBuilder.buildEffectiveConversationSettings("conversation")
        } returns ConversationSettings(contextWindow = 4096)
        coEvery {
            fixture.conversations.getMessagesForConversationSnapshot("conversation")
        } returns emptyList()
        coEvery {
            fixture.conversations.restoreBranchSelections("conversation")
        } returns emptyMap()
        val createdRun = slot<RunEntity>()
        val createdMessages = slot<List<MessageEntity>>()
        coEvery {
            fixture.conversations.createRunWithMessages(
                run = capture(createdRun),
                messages = capture(createdMessages),
                messageSelectionUpdates = any(),
                at = any(),
            )
        } answers {
            val messages = secondArg<List<MessageEntity>>()
            val selections = thirdArg<Map<String?, String>>()
            RunGraphCommit(messages, selections, emptyMap())
        }
        coEvery { fixture.settings.incrementMessagesSent() } just Runs
        coEvery {
            fixture.compactController.automaticBeforeBoundary(any(), any(), any(), state)
        } just Runs
        coEvery { fixture.boundLauncher.launch(any(), state) } just Runs

        fixture.executor.launchClaim(state, claim)

        coVerify(timeout = 5_000, exactly = 1) {
            fixture.boundLauncher.launch(
                match {
                    it.conversationId == "conversation" &&
                        it.modelMessageId == "model-message" &&
                        it.modelId == "provider:model-2" &&
                        it.runId == "fresh-run" &&
                        it.pass == 0 &&
                        it.callerTag == "guidanceBoundary"
                },
                state,
            )
        }
        assertEquals("fresh-run", createdRun.captured.id)
        assertEquals(null, createdRun.captured.parentRunId)
        assertEquals(2, createdMessages.captured.size)
        assertEquals("guidance-1", createdMessages.captured[0].id)
        assertEquals("one\n\ntwo", createdMessages.captured[0].text)
        assertEquals("model-message", createdMessages.captured[1].id)
        assertEquals(listOf("indexed:guidance-1:one\n\ntwo", "scroll:guidance-1"), fixture.events)
        assertFalse(state.settleGuidanceClaim(lease.id, durable = false))
        coVerify(exactly = 1) {
            fixture.compactController.automaticBeforeBoundary(
                "conversation",
                "provider:model-2",
                4096,
                state,
            )
        }
        state.dispose()
        Unit
    }

    private class Fixture {
        val conversations = mockk<ConversationRepository>()
        val settings = mockk<SettingsRepository>()
        val requestBuilder = mockk<GenerationRequestBuilder>()
        val compactController = mockk<ConversationCompactController>()
        val terminalSettlement = mockk<GenerationTerminalSettlementController>()
        val boundLauncher = mockk<BoundRunGenerationLauncher>()
        val events = mutableListOf<String>()
        private val ids = ArrayDeque(listOf("fresh-run", "model-message"))
        val executor = QueuedGuidanceDrainExecutor(
            conversations = conversations,
            settings = settings,
            requestBuilder = requestBuilder,
            executionCoordinator = ConversationExecutionCoordinator(),
            compactController = compactController,
            terminalSettlement = terminalSettlement,
            boundRunGenerationLauncher = boundLauncher,
            toUiMessage = ::toUiMessage,
            isConversationOpen = { true },
            projectGraph = { _, _, _, _ -> Unit },
            onScrollToAbsoluteBottomAfter = { _, messageId ->
                events += "scroll:$messageId"
            },
            onUserMessagePersisted = { messageId, text ->
                events += "indexed:$messageId:$text"
            },
            idFactory = ids::removeFirst,
            clock = { 100L },
        )

        init {
            every { settings.maxContextWindow } returns MutableStateFlow(8192)
        }
    }

    private companion object {
        val QUEUED = listOf(
            QueuedSend(
                id = "guidance-1",
                text = "one",
                modelId = "provider:model-1",
                attachments = emptyList(),
                runId = "old-run",
            ),
            QueuedSend(
                id = "guidance-2",
                text = "two",
                modelId = "provider:model-2",
                attachments = emptyList(),
                runId = "old-run",
            ),
        )

        fun toUiMessage(entity: MessageEntity) = ChatMessage(
            id = entity.id,
            parentId = entity.parentId,
            text = entity.text,
            participant = entity.participant,
            status = entity.status,
            timestamp = entity.timestamp,
            modelName = entity.modelName,
            runId = entity.runId,
            runSequence = entity.runSequence,
        )
    }
}
