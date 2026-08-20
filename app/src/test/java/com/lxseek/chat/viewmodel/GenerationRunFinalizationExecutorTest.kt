package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.RunEffect
import com.lxseek.chat.model.RunEffectIdentity
import com.lxseek.chat.model.RunEndReason
import com.lxseek.chat.model.RunStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationRunFinalizationExecutorTest {
    @Test
    fun `persists and returns only the exact authorized terminal effect`() = runTest {
        val repository = mockk<ConversationRepository>()
        coEvery {
            repository.finishGeneration(
                MESSAGE,
                IDENTITY.conversationId,
                IDENTITY.runId,
                RunStatus.COMPLETED,
                RunEndReason.MODEL_COMPLETED,
                true,
                any(),
            )
        } returns true
        var returnedIdentity: RunEffectIdentity? = null
        var returnedSuccess: Boolean? = null

        val outcome = GenerationRunFinalizationExecutor(repository).execute(
            request(),
            GenerationRunFinalizationCallbacks(
                requestEffect = { identity, status, reason, unread ->
                    RunEffect.FinalizeRun(identity, status, reason, unread)
                },
                returnResult = { identity, success ->
                    returnedIdentity = identity
                    returnedSuccess = success
                    true
                },
            ),
        )

        assertTrue(outcome is GenerationRunFinalizationOutcome.Settled)
        assertTrue((outcome as GenerationRunFinalizationOutcome.Settled).terminalPersisted)
        assertSame(IDENTITY, returnedIdentity)
        assertTrue(returnedSuccess == true)
        coVerify(exactly = 1) {
            repository.finishGeneration(
                MESSAGE,
                IDENTITY.conversationId,
                IDENTITY.runId,
                RunStatus.COMPLETED,
                RunEndReason.MODEL_COMPLETED,
                true,
                any(),
            )
        }
    }

    @Test
    fun `mismatched authorization is rejected without durable mutation or result`() = runTest {
        val repository = mockk<ConversationRepository>()
        var resultReturned = false

        val outcome = GenerationRunFinalizationExecutor(repository).execute(
            request(),
            GenerationRunFinalizationCallbacks(
                requestEffect = { identity, status, reason, unread ->
                    RunEffect.FinalizeRun(identity.copy(effectId = "stale"), status, reason, unread)
                },
                returnResult = { _, _ ->
                    resultReturned = true
                    true
                },
            ),
        )

        assertSame(GenerationRunFinalizationOutcome.NotAuthorized, outcome)
        assertFalse(resultReturned)
        coVerify(exactly = 0) { repository.finishGeneration(any(), any(), any(), any(), any(), any(), any()) }
    }

    private fun request() = GenerationRunFinalizationRequest(
        identity = IDENTITY,
        message = MESSAGE,
        status = RunStatus.COMPLETED,
        reason = RunEndReason.MODEL_COMPLETED,
        markConversationUnread = true,
    )

    private companion object {
        val IDENTITY = RunEffectIdentity(
            conversationId = "conversation",
            ownerToken = 7,
            runId = "run",
            pass = 2,
            effectId = "finalize-run-2",
        )
        val MESSAGE = ChatMessage(
            id = "model",
            text = "answer",
            status = MessageStatus.SUCCESS,
            participant = Participant.MODEL,
        )
    }
}
