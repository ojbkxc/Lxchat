package com.lxseek.chat.viewmodel

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CurrentConversationRuntimeFacadeTest {
    @Test
    fun nullConversationProjectsEmptyQueueAndNotStopping() = runTest {
        val currentConversationId = MutableStateFlow<String?>(null)
        val registry = mockk<ConversationStateRegistry>()
        val facade = CurrentConversationRuntimeFacade(
            currentConversationId,
            registry,
            backgroundScope,
            StandardTestDispatcher(testScheduler),
        )
        runCurrent()

        assertTrue(facade.queuedSends.value.isEmpty())
        assertFalse(facade.isStopping.value)
        facade.removeQueuedSend("missing")
        runCurrent()
        verify(exactly = 0) { registry.getOrCreate(any()) }
    }

    @Test
    fun conversationSwitchProjectsOnlyTheCurrentRuntime() = runTest {
        val currentConversationId = MutableStateFlow<String?>("first")
        val registry = mockk<ConversationStateRegistry>()
        val first = runtimeState(listOf(queued("first")), stopping = true)
        val second = runtimeState(listOf(queued("second")), stopping = false)
        every { registry.getOrCreate("first") } returns first
        every { registry.getOrCreate("second") } returns second
        val facade = CurrentConversationRuntimeFacade(
            currentConversationId,
            registry,
            backgroundScope,
            StandardTestDispatcher(testScheduler),
        )
        runCurrent()

        assertEquals(listOf("first"), facade.queuedSends.value.map { it.id })
        assertTrue(facade.isStopping.value)
        currentConversationId.value = "second"
        runCurrent()

        assertEquals(listOf("second"), facade.queuedSends.value.map { it.id })
        assertFalse(facade.isStopping.value)
    }

    @Test
    fun removalTargetsTheCurrentRuntimeAndExactQueueId() = runTest {
        val currentConversationId = MutableStateFlow<String?>("conversation")
        val registry = mockk<ConversationStateRegistry>()
        val state = runtimeState(emptyList(), stopping = false)
        every { state.queueMutationMutex } returns Mutex()
        every { state.removeQueuedSend("queued") } returns queued("queued")
        every { registry.getOrCreate("conversation") } returns state
        val facade = CurrentConversationRuntimeFacade(
            currentConversationId,
            registry,
            backgroundScope,
            StandardTestDispatcher(testScheduler),
        )
        runCurrent()

        facade.removeQueuedSend("queued")
        runCurrent()

        verify(exactly = 1) { state.removeQueuedSend("queued") }
    }

    private companion object {
        fun runtimeState(
            queuedSends: List<QueuedSend>,
            stopping: Boolean,
        ): ConversationGenerationState = mockk<ConversationGenerationState>().also { state ->
            every { state.queuedSends } returns MutableStateFlow(queuedSends)
            every { state.stopping } returns MutableStateFlow(stopping)
        }

        fun queued(id: String) = QueuedSend(
            id = id,
            text = "guidance",
            modelId = "model",
            attachments = emptyList(),
            runId = "run",
        )
    }
}
