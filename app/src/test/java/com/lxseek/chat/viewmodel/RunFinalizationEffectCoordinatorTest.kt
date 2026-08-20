package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.RunEffect
import com.lxseek.chat.model.RunEffectIdentity
import com.lxseek.chat.model.RunEndReason
import com.lxseek.chat.model.RunStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RunFinalizationEffectCoordinatorTest {
    @Test
    fun `retries false and exception results with the exact effect`() = runTest {
        val delays = mutableListOf<Long>()
        val seen = mutableListOf<RunEffect.FinalizeRun>()
        val coordinator = RunFinalizationEffectCoordinator(
            retryDelaysMs = listOf(0L, 5L, 10L),
            delayForRetry = { delays += it },
        )
        val effect = effect()
        var attempt = 0

        val result = coordinator.execute(effect) { received ->
            seen += received
            when (attempt++) {
                0 -> false
                1 -> throw IllegalStateException("transient")
                else -> true
            }
        }

        assertEquals(RunFinalizationEffectCoordinator.Result.Succeeded(3), result)
        assertEquals(listOf(5L, 10L), delays)
        assertEquals(listOf(effect, effect, effect), seen)
    }

    @Test
    fun `bounded failure returns the last exception`() = runTest {
        val expected = IllegalArgumentException("database unavailable")
        val coordinator = RunFinalizationEffectCoordinator(
            retryDelaysMs = listOf(0L, 1L),
            delayForRetry = {},
        )

        val result = coordinator.execute(effect()) { throw expected }

        assertTrue(result is RunFinalizationEffectCoordinator.Result.Failed)
        result as RunFinalizationEffectCoordinator.Result.Failed
        assertEquals(2, result.attempts)
        assertSame(expected, result.lastFailure)
    }

    private fun effect() = RunEffect.FinalizeRun(
        identity = RunEffectIdentity(
            conversationId = "conversation",
            ownerToken = 7,
            runId = "run",
            pass = 2,
            effectId = "finalize-run-2",
        ),
        status = RunStatus.COMPLETED,
        reason = RunEndReason.MODEL_COMPLETED,
        markConversationUnread = true,
    )
}
