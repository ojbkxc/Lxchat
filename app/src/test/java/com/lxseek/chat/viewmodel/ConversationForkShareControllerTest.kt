package com.lxseek.chat.viewmodel

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConversationForkShareControllerTest {
    @Test
    fun missingConversationMakesEveryIntentANoOp() = runTest {
        val fixture = Fixture(currentConversationId = null, scope = this)

        fixture.controller.fork("message")
        fixture.controller.shareConversation()
        fixture.controller.shareGeneration("assistant")
        fixture.controller.shareMessages(setOf("message"))
        runCurrent()

        coVerify(exactly = 0) { fixture.service.fork(any(), any()) }
        coVerify(exactly = 0) { fixture.service.shareAll(any()) }
        coVerify(exactly = 0) { fixture.service.shareRun(any(), any()) }
        coVerify(exactly = 0) { fixture.service.shareMessages(any(), any()) }
        fixture.assertNoOutputs()
    }

    @Test
    fun emptyMessageSelectionIsRejectedBeforeLaunchingServiceWork() = runTest {
        val fixture = Fixture(scope = this)

        fixture.controller.shareMessages(emptySet())
        runCurrent()

        coVerify(exactly = 0) { fixture.service.shareMessages(any(), any()) }
        fixture.assertNoOutputs()
    }

    @Test
    fun forkSuccessSelectsTheCreatedConversation() = runTest {
        val fixture = Fixture(scope = this)
        coEvery { fixture.service.fork("conversation", "through") } returns
            ConversationForkShareService.ForkResult.Success("fork")

        fixture.controller.fork("through")
        runCurrent()

        assertEquals(listOf("fork"), fixture.forkedConversationIds)
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun forkFailureIsLocalizedAndReported() = runTest {
        val fixture = Fixture(scope = this)
        coEvery { fixture.service.fork("conversation", null) } returns
            ConversationForkShareService.ForkResult.Failure("broken")

        fixture.controller.fork()
        runCurrent()

        assertEquals(listOf("fork: broken"), fixture.failures)
        assertTrue(fixture.forkedConversationIds.isEmpty())
    }

    @Test
    fun shareIntentsPreserveTheirExactServiceArguments() = runTest {
        val fixture = Fixture(scope = this)
        coEvery { fixture.service.shareAll("conversation") } returns
            ConversationForkShareService.ShareResult.Success("all")
        coEvery { fixture.service.shareRun("conversation", "assistant") } returns
            ConversationForkShareService.ShareResult.Success("run")
        coEvery { fixture.service.shareMessages("conversation", setOf("one", "two")) } returns
            ConversationForkShareService.ShareResult.Success("selection")

        fixture.controller.shareConversation()
        fixture.controller.shareGeneration("assistant")
        fixture.controller.shareMessages(setOf("one", "two"))
        runCurrent()

        assertEquals(listOf("all", "run", "selection"), fixture.shareTexts)
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun shareFailureIsLocalizedAndReported() = runTest {
        val fixture = Fixture(scope = this)
        coEvery { fixture.service.shareRun("conversation", "assistant") } returns
            ConversationForkShareService.ShareResult.Failure("unfinished")

        fixture.controller.shareGeneration("assistant")
        runCurrent()

        assertEquals(listOf("share: unfinished"), fixture.failures)
        assertTrue(fixture.shareTexts.isEmpty())
    }

    private class Fixture(
        currentConversationId: String? = "conversation",
        scope: kotlinx.coroutines.CoroutineScope,
    ) {
        val service = mockk<ConversationForkShareService>()
        val forkedConversationIds = mutableListOf<String>()
        val shareTexts = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val controller = ConversationForkShareController(
            currentConversationId = MutableStateFlow(currentConversationId),
            service = service,
            scope = scope,
            onConversationForked = forkedConversationIds::add,
            onShareReady = shareTexts::add,
            forkFailureText = { "fork: $it" },
            shareFailureText = { "share: $it" },
            onFailure = failures::add,
        )

        fun assertNoOutputs() {
            assertTrue(forkedConversationIds.isEmpty())
            assertTrue(shareTexts.isEmpty())
            assertTrue(failures.isEmpty())
        }
    }
}
