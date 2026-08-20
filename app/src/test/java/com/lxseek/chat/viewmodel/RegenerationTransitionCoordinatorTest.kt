package com.lxseek.chat.viewmodel

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegenerationTransitionCoordinatorTest {
    @Test
    fun generationStartWaitsForFadeButNotScroll() = runTest {
        val coordinator = RegenerationTransitionCoordinator()
        val request = coordinator.begin("conversation", "old-model", "user")!!
        val result = async { coordinator.awaitFade(request.id) }

        coordinator.acknowledgeScroll(request.id, success = true)
        runCurrent()
        assertFalse(result.isCompleted)

        coordinator.acknowledgeFade(request.id)
        assertTrue(result.await())
        assertTrue(coordinator.request.value?.fadeFinished == true)
        assertTrue(coordinator.request.value?.scrollFinished == true)
        assertTrue(coordinator.markCommitted(request.id))
        assertEquals(
            RegenerationTransitionStage.COMMITTED,
            coordinator.request.value?.stage,
        )

        assertTrue(coordinator.complete(request.id))
        assertNull(coordinator.request.value)
    }

    @Test
    fun failedScrollDoesNotBlockCommitAndAbortWakesFadeWaiter() = runTest {
        val coordinator = RegenerationTransitionCoordinator()
        val request = coordinator.begin("conversation", "old-model", "user")!!
        val fade = async { coordinator.awaitFade(request.id) }
        coordinator.acknowledgeFade(request.id)
        coordinator.acknowledgeScroll(request.id, success = false)
        assertTrue(fade.await())
        assertTrue(coordinator.markCommitted(request.id))
        assertTrue(coordinator.request.value?.scrollFinished == true)
        assertFalse(coordinator.request.value?.scrollSucceeded ?: true)

        coordinator.abort(request.id)
        assertNull(coordinator.request.value)

        val second = coordinator.begin("conversation", "old-model", "user")!!
        val aborted = async { coordinator.awaitFade(second.id) }
        coordinator.abort(second.id)
        assertFalse(aborted.await())
    }
}
