package com.lxseek.chat.automation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationExecutionCoordinatorTest {
    @Test
    fun sameConversation_isStrictlySerialized() = runTest {
        val coordinator = ConversationExecutionCoordinator()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var secondEntered = false

        val first = launch {
            coordinator.withConversationLock("conversation") {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = launch {
            coordinator.withConversationLock("conversation") { secondEntered = true }
        }

        runCurrent()
        assertFalse(secondEntered)
        assertTrue(coordinator.isExecuting("conversation"))

        releaseFirst.complete(Unit)
        first.join()
        second.join()
        assertTrue(secondEntered)
        assertFalse(coordinator.isExecuting("conversation"))
        assertEquals(0, coordinator.trackedConversationCount())
    }

    @Test
    fun differentConversations_canProceedIndependently() = runTest {
        val coordinator = ConversationExecutionCoordinator()
        val releaseFirst = CompletableDeferred<Unit>()
        val firstEntered = CompletableDeferred<Unit>()
        var secondEntered = false

        val first = launch {
            coordinator.withConversationLock("one") {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = launch {
            coordinator.withConversationLock("two") { secondEntered = true }
        }
        second.join()

        assertTrue(secondEntered)
        assertTrue(coordinator.isExecuting("one"))
        releaseFirst.complete(Unit)
        first.join()
        assertEquals(0, coordinator.trackedConversationCount())
    }

    @Test
    fun cancelledWaiter_releasesItsReference() = runTest {
        val coordinator = ConversationExecutionCoordinator()
        val releaseFirst = CompletableDeferred<Unit>()
        val firstEntered = CompletableDeferred<Unit>()

        val first = launch {
            coordinator.withConversationLock("conversation") {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val waiter = launch {
            coordinator.withConversationLock("conversation") { error("must not enter") }
        }
        runCurrent()
        waiter.cancelAndJoin()
        assertEquals(1, coordinator.trackedConversationCount())

        releaseFirst.complete(Unit)
        first.join()
        assertEquals(0, coordinator.trackedConversationCount())
    }

    @Test
    fun automationWaiters_runBeforeAlreadyQueuedForegroundWaiters() = runTest {
        val coordinator = ConversationExecutionCoordinator()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        val first = launch {
            coordinator.withConversationLock("conversation") {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val foreground = launch {
            coordinator.withConversationLock("conversation") { order += "foreground" }
        }
        runCurrent()
        val automationOne = launch {
            coordinator.withAutomationConversationLock("conversation") {
                order += "automation-1"
            }
        }
        val automationTwo = launch {
            coordinator.withAutomationConversationLock("conversation") {
                order += "automation-2"
            }
        }
        runCurrent()

        releaseFirst.complete(Unit)
        joinAll(first, foreground, automationOne, automationTwo)

        assertEquals(listOf("automation-1", "automation-2", "foreground"), order)
        assertEquals(0, coordinator.trackedConversationCount())
    }

    @Test
    fun automationOwnership_isExposedSeparatelyFromForegroundWork() = runTest {
        val coordinator = ConversationExecutionCoordinator()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = launch {
            coordinator.withAutomationConversationLock("conversation") {
                entered.complete(Unit)
                release.await()
            }
        }

        entered.await()
        assertTrue("conversation" in coordinator.activeConversationIds.value)
        assertTrue("conversation" in coordinator.activeAutomationConversationIds.value)

        release.complete(Unit)
        job.join()
        assertFalse("conversation" in coordinator.activeConversationIds.value)
        assertFalse("conversation" in coordinator.activeAutomationConversationIds.value)
    }
}
