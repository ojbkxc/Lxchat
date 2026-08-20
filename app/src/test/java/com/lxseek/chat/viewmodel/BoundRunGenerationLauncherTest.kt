package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.ConversationSettings
import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.util.DebugLog
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class BoundRunGenerationLauncherTest {
    @Test
    fun launchesOnePassWithLoadedKeyAndIdentifiedRuntimeCallbacks() = runBlocking {
        val fixture = Fixture(activeKey = "loaded-key")
        var generationJob: Job? = null
        val callbacks = slot<GenerationCallbacks>()
        coEvery {
            fixture.manager.generate(
                conversationId = "conversation",
                modelMessageId = "model-message",
                startTime = 100L,
                isRegenerate = false,
                replaceMessageId = null,
                modelName = "provider:model",
                runId = "run",
                pass = 3,
                ownerToken = fixture.uiToken,
                config = fixture.config,
                ctx = fixture.generationContext,
                generationJob = any(),
                callbacks = capture(callbacks),
                streamScope = fixture.state.streamScope,
                requestTrace = any(),
            )
        } coAnswers {
            generationJob = arg(11)
            Unit
        }
        mockDebugLog()
        try {
            fixture.launcher.launch(fixture.request, fixture.state)
            callbacks.captured.onToolRoundPersisted()
        } finally {
            unmockkObject(DebugLog)
        }

        assertSame(currentCoroutineContext()[Job], generationJob)
        coVerify(exactly = 0) { fixture.settings.awaitActiveKey(any()) }
        coVerify(exactly = 1) {
            fixture.compactController.automaticBeforeBoundary(
                "conversation",
                "provider:model",
                4096,
                fixture.state,
            )
        }
        fixture.state.dispose()
        Unit
    }

    @Test
    fun blankLoadedKeyAwaitsFreshDataStoreValueBeforeBuildingConfig() = runBlocking {
        val fixture = Fixture(activeKey = "")
        coEvery { fixture.settings.awaitActiveKey("provider") } returns "fresh-key"
        coEvery {
            fixture.builder.buildGenerationPair(
                "provider",
                "provider:model",
                "fresh-key",
                "system",
                "prepend",
                "postpend",
                fixture.effectiveSettings,
                "conversation",
            )
        } returns (fixture.config to fixture.generationContext)
        coEvery { fixture.manager.generate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } just Runs
        mockDebugLog()
        try {
            fixture.launcher.launch(fixture.request, fixture.state)
        } finally {
            unmockkObject(DebugLog)
        }

        coVerify(exactly = 1) { fixture.settings.awaitActiveKey("provider") }
        coVerify(exactly = 1) {
            fixture.builder.buildGenerationPair(
                "provider",
                "provider:model",
                "fresh-key",
                "system",
                "prepend",
                "postpend",
                fixture.effectiveSettings,
                "conversation",
            )
        }
        fixture.state.dispose()
        Unit
    }

    @Test
    fun requestBuildFailureTerminalizesOnlyTheBoundSendingPlaceholder() = runBlocking {
        val fixture = Fixture(activeKey = "loaded-key", stubGenerationPair = false)
        coEvery { fixture.builder.buildGenerationPair(any(), any(), any(), any(), any(), any(), any(), any()) } throws
            IllegalStateException("configuration failed")
        coEvery {
            fixture.conversations.getMessagesForConversationSnapshot("conversation")
        } returns listOf(MESSAGE_ENTITY)
        val failedMessage = slot<ChatMessage>()
        coEvery {
            fixture.terminalSettlement.finalizeBoundFailure(
                conversationId = "conversation",
                runId = "run",
                pass = 3,
                uiToken = fixture.uiToken,
                state = fixture.state,
                failedMessage = capture(failedMessage),
                effectId = "request-finalize-run-3",
            )
        } returns true
        mockDebugLog()
        try {
            fixture.launcher.launch(fixture.request, fixture.state)
        } finally {
            unmockkObject(DebugLog)
        }

        assertEquals(MessageStatus.ERROR, failedMessage.captured.status)
        assertEquals("Error: configuration failed", failedMessage.captured.text)
        coVerify(exactly = 0) { fixture.manager.generate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        fixture.state.dispose()
        Unit
    }

    @Test
    fun cancellationFromGenerationIsPropagatedWithoutFailureFinalization() = runBlocking {
        val fixture = Fixture(activeKey = "loaded-key")
        coEvery { fixture.manager.generate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
            CancellationException("stop")
        mockDebugLog()
        try {
            try {
                fixture.launcher.launch(fixture.request, fixture.state)
                fail("CancellationException should propagate")
            } catch (_: CancellationException) {
                Unit
            }
        } finally {
            unmockkObject(DebugLog)
        }

        coVerify(exactly = 0) {
            fixture.terminalSettlement.finalizeBoundFailure(
                any(), any(), any(), any(), any(), any(), any()
            )
        }
        fixture.state.dispose()
        Unit
    }

    private class Fixture(
        activeKey: String,
        stubGenerationPair: Boolean = true,
    ) {
        val builder = mockk<GenerationRequestBuilder>()
        val settings = mockk<SettingsRepository>()
        val conversations = mockk<ConversationRepository>()
        val manager = mockk<GenerationManager>()
        val compactController = mockk<ConversationCompactController>()
        val terminalSettlement = mockk<GenerationTerminalSettlementController>()
        val state = ConversationGenerationState("conversation")
        val uiToken = requireNotNull(state.acquireForSend())
        val effectiveSettings = ConversationSettings(contextWindow = 4096)
        val config = GenerationConfig(
            providerName = "provider",
            modelId = "model",
            apiKey = "loaded-key",
            effectiveSystemPrompt = "system",
            codeExecutionEnabled = false,
            googleSearchEnabled = false,
            thinkingEnabled = false,
            baseUrl = null,
        )
        val generationContext = GenerationContext(conversationId = "conversation")
        val request = BoundRunGenerationRequest(
            conversationId = "conversation",
            modelMessageId = "model-message",
            startTime = 100L,
            isRegenerate = false,
            replaceMessageId = null,
            providerName = "provider",
            modelId = "provider:model",
            activeKey = activeKey,
            uiToken = uiToken,
            persistId = 7L,
            runId = "run",
            pass = 3,
            callerTag = "test",
        )
        val launcher: BoundRunGenerationLauncher

        init {
            state.bindRun(uiToken, "run", pass = 3)
            coEvery {
                builder.buildEffectiveSystemPrompt("conversation", "provider:model")
            } returns GenerationRequestBuilder.ResolvedPrompt("system", "prepend", "postpend")
            every {
                builder.buildEffectiveConversationSettings("conversation")
            } returns effectiveSettings
            every { settings.maxContextWindow } returns MutableStateFlow(8192)
            if (activeKey.isNotBlank()) {
                coEvery { settings.awaitActiveKey(any()) } returns null
            }
            if (stubGenerationPair) {
                coEvery {
                    builder.buildGenerationPair(
                        "provider",
                        "provider:model",
                        activeKey,
                        "system",
                        "prepend",
                        "postpend",
                        effectiveSettings,
                        "conversation",
                    )
                } returns (config to generationContext)
            }
            coEvery {
                compactController.automaticBeforeBoundary(any(), any(), any(), any())
            } just Runs
            launcher = BoundRunGenerationLauncher(
                requestBuilder = builder,
                settings = settings,
                conversations = conversations,
                generationManagerProvider = { manager },
                compactController = compactController,
                terminalSettlement = terminalSettlement,
                toUiMessage = ::toUiMessage,
                clock = { 150L },
            )
        }
    }

    private companion object {
        val MESSAGE_ENTITY = MessageEntity(
            id = "model-message",
            conversationId = "conversation",
            text = "",
            participant = Participant.MODEL,
            status = MessageStatus.SENDING,
            timestamp = 100L,
            runId = "run",
            runSequence = 0,
        )

        fun toUiMessage(entity: MessageEntity) = ChatMessage(
            id = entity.id,
            parentId = entity.parentId,
            text = entity.text,
            participant = entity.participant,
            status = entity.status,
            runId = entity.runId,
            runSequence = entity.runSequence,
        )

        fun mockDebugLog() {
            mockkObject(DebugLog)
            every { DebugLog.d(any(), any()) } just Runs
            every { DebugLog.e(any(), any()) } just Runs
        }
    }
}
