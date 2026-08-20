package com.lxseek.chat.viewmodel

import com.lxseek.chat.automation.ConversationExecutionCoordinator
import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.data.local.RunEntity
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.model.RunStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConversationCompactControllerTest {
    @Test
    fun disabledSettingShortCircuitsBeforeReadingDurableGraph() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        val settings = mockk<SettingsRepository>()
        every { settings.contextCompactEnabled } returns MutableStateFlow(false)
        val compactor = ContextCompactor(
            conversations = conversations,
            settings = settings,
            providers = mockk(),
            pauseLoop = {},
        )

        assertFalse(compactor.automaticNeeded("conversation", 4096))

        coVerify(exactly = 0) {
            conversations.getMessagesForConversationSnapshot(any())
            conversations.restoreBranchSelections(any())
        }
    }

    @Test
    fun automaticPreflightRejectionDoesNotClaimRuntimeOrReadProjection() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        val operation = FakeCompactOperation(automaticNeeded = false)
        val state = ConversationGenerationState("conversation")
        var projections = 0

        controller(conversations, operation) { _, _, _ -> projections += 1 }
            .automaticBeforeBoundary("conversation", "model", 4096, state)

        assertEquals(1, operation.automaticNeededCalls)
        assertEquals(0, operation.automaticCalls)
        assertEquals(0, projections)
        assertTrue(state.runtimeTraceSnapshot().isEmpty())
        coVerify(exactly = 0) {
            conversations.getMessagesForConversationSnapshot(any())
            conversations.restoreBranchSelections(any())
        }
        state.dispose()
        Unit
    }

    @Test
    fun automaticCreatedUsesIdentifiedEffectAndProjectsDurableGraph() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        coEvery { conversations.getMessagesForConversationSnapshot("conversation") } returns emptyList()
        coEvery { conversations.restoreBranchSelections("conversation") } returns
            mapOf(null to "compact-message")
        val operation = FakeCompactOperation(
            automaticResult = CompactResult.Created("compact-message"),
        )
        val state = ConversationGenerationState("conversation")
        val token = requireNotNull(state.acquireForSend())
        state.bindRun(token, "run", pass = 2)
        val projections = mutableListOf<Map<String?, String>>()

        controller(conversations, operation) { _, _, selected -> projections += selected }
            .automaticBeforeBoundary("conversation", "model", 4096, state)

        assertEquals("conversation", operation.automaticConversationId)
        assertEquals("model", operation.automaticFallbackModel)
        assertEquals(4096, operation.automaticContextLimit)
        assertEquals("compact_run_fixed", operation.automaticCompactRunId)
        assertEquals(listOf(mapOf(null to "compact-message")), projections)
        assertEquals("", state.compactPreview.value)
        assertFalse(state.compacting.value)
        assertTrue(state.generating.value)
        assertEquals(
            listOf("RunCompact", "ResumeAfterCompact"),
            state.runtimeTraceSnapshot().takeLast(2).flatMap { it.effectTypes },
        )
        state.dispose()
        Unit
    }

    @Test
    fun automaticFailureKeepsFormerExceptionContract() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        val operation = FakeCompactOperation(
            automaticResult = CompactResult.Failed("provider failed"),
        )
        val state = ConversationGenerationState("conversation")
        val token = requireNotNull(state.acquireForSend())
        state.bindRun(token, "run")

        try {
            controller(conversations, operation) { _, _, _ -> }
                .automaticBeforeBoundary("conversation", "model", 4096, state)
            fail("Expected automatic Compact failure")
        } catch (error: IllegalStateException) {
            assertEquals("Automatic context compact failed: provider failed", error.message)
        }

        assertEquals("", state.compactPreview.value)
        assertFalse(state.compacting.value)
        assertTrue(state.generating.value)
        state.dispose()
        Unit
    }

    @Test
    fun manualRejectsDurableLiveRunBeforeExecutingOperation() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        coEvery { conversations.getLiveRun("conversation") } returns liveRun()
        val operation = FakeCompactOperation()
        val state = ConversationGenerationState("conversation")

        val result = controller(conversations, operation) { _, _, _ -> }.manual(
            conversationId = "conversation",
            request = CompactRequest("model", "prompt", 4),
            state = state,
        )

        assertEquals(CompactResult.Failed("Conversation is busy"), result)
        assertEquals(0, operation.manualCalls)
        assertFalse(state.compacting.value)
        assertFalse(state.generating.value)
        state.dispose()
        Unit
    }

    @Test
    fun manualCreatedReturnsTypedResultAndProjectsDurableGraph() = runBlocking {
        val conversations = mockk<ConversationRepository>()
        coEvery { conversations.getLiveRun("conversation") } returns null
        coEvery { conversations.getMessagesForConversationSnapshot("conversation") } returns emptyList()
        coEvery { conversations.restoreBranchSelections("conversation") } returns
            mapOf(null to "compact-message")
        val operation = FakeCompactOperation(
            manualResult = CompactResult.Created("compact-message"),
        )
        val state = ConversationGenerationState("conversation")
        val projections = mutableListOf<Map<String?, String>>()
        val request = CompactRequest("model", "prompt", 4)

        val result = controller(conversations, operation) { _, _, selected ->
            projections += selected
        }.manual("conversation", request, state)

        assertEquals(CompactResult.Created("compact-message"), result)
        assertEquals("conversation", operation.manualConversationId)
        assertEquals(request, operation.manualRequest)
        assertEquals("compact_run_fixed", operation.manualCompactRunId)
        assertEquals(listOf(mapOf(null to "compact-message")), projections)
        assertEquals("", state.compactPreview.value)
        assertFalse(state.compacting.value)
        assertFalse(state.generating.value)
        state.dispose()
        Unit
    }

    private fun controller(
        conversations: ConversationRepository,
        operation: ContextCompactOperation,
        projectGraph: (
            String,
            List<MessageEntity>,
            Map<String?, String>,
        ) -> Unit,
    ) = ConversationCompactController(
        conversations = conversations,
        executionCoordinator = ConversationExecutionCoordinator(),
        operation = operation,
        effectCoordinator = ContextCompactEffectCoordinator { "fixed" },
        projectGraph = projectGraph,
    )

    private fun liveRun() = RunEntity(
        id = "live-run",
        conversationId = "conversation",
        parentRunId = null,
        status = RunStatus.ACTIVE,
        activeSlot = 1,
        startedAt = 1L,
        lastCheckpointAt = 1L,
    )
}

private class FakeCompactOperation(
    private val automaticNeeded: Boolean = true,
    private val automaticResult: CompactResult = CompactResult.NotNeeded,
    private val manualResult: CompactResult = CompactResult.NotNeeded,
) : ContextCompactOperation {
    var automaticNeededCalls = 0
        private set
    var automaticCalls = 0
        private set
    var manualCalls = 0
        private set
    var automaticConversationId: String? = null
        private set
    var automaticFallbackModel: String? = null
        private set
    var automaticContextLimit: Int? = null
        private set
    var automaticCompactRunId: String? = null
        private set
    var manualConversationId: String? = null
        private set
    var manualRequest: CompactRequest? = null
        private set
    var manualCompactRunId: String? = null
        private set

    override suspend fun automaticNeeded(conversationId: String, contextLimit: Int): Boolean {
        automaticNeededCalls += 1
        return automaticNeeded
    }

    override suspend fun compactAutomatic(
        conversationId: String,
        fallbackModel: String,
        contextLimit: Int,
        compactRunId: String,
        onSummaryChunk: (String) -> Unit,
    ): CompactResult {
        automaticCalls += 1
        automaticConversationId = conversationId
        automaticFallbackModel = fallbackModel
        automaticContextLimit = contextLimit
        automaticCompactRunId = compactRunId
        onSummaryChunk("partial summary")
        return automaticResult
    }

    override suspend fun compactManual(
        conversationId: String,
        request: CompactRequest,
        compactRunId: String,
        onSummaryChunk: (String) -> Unit,
    ): CompactResult {
        manualCalls += 1
        manualConversationId = conversationId
        manualRequest = request
        manualCompactRunId = compactRunId
        onSummaryChunk("partial summary")
        return manualResult
    }
}
