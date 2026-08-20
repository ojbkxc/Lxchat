package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.model.RunEffectIdentity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationFinalizerTest {
    @Test
    fun `completion echoes the exact effect identity`() = runTest {
        val repository = mockk<ConversationRepository>()
        coEvery { repository.requestRunStop("run", any()) } returns true
        coEvery { repository.finishStoppedGeneration(emptyList(), "run", any()) } returns true
        val identity = RunEffectIdentity(
            conversationId = "conversation",
            ownerToken = 7,
            runId = "run",
            pass = 3,
            effectId = "stop-7",
        )
        val completion = CompletableDeferred<com.lxseek.chat.model.ConversationCommand.PersistenceSettled>()
        val finalizer = GenerationFinalizer(repository) { _, _ -> }

        val job = finalizer.launchStopFinalization(
            scope = this,
            identity = identity,
            messages = emptyList(),
            onFinalized = { completion.complete(it) },
        )
        job.join()

        assertEquals(identity, completion.await().identity)
        assertTrue(completion.await().success)
        coVerify(exactly = 1) { repository.requestRunStop("run", any()) }
        coVerify(exactly = 1) { repository.finishStoppedGeneration(emptyList(), "run", any()) }
    }
}
