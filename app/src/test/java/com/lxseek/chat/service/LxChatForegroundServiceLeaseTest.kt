package com.lxseek.chat.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LxChatForegroundServiceLeaseTest {
    @Test
    fun distinctOwners_startOnceAndOnlyLastReleaseStopsRunningService() {
        val leases = ForegroundOwnerLeases()

        val firstAcquire = leases.acquire("message-a")
        assertTrue(firstAcquire.accepted)
        assertEquals(ForegroundServiceLeaseAction.Start, firstAcquire.action)
        assertEquals(
            ForegroundServiceLeaseAction.None,
            leases.serviceCommandReceived(startId = 11),
        )
        assertEquals(ForegroundServiceLifecycleState.RUNNING, leases.lifecycleState())

        val secondAcquire = leases.acquire("message-b")
        assertTrue(secondAcquire.accepted)
        assertEquals(ForegroundServiceLeaseAction.None, secondAcquire.action)
        assertEquals(2, leases.size())

        val firstRelease = leases.release("message-a")
        assertTrue(firstRelease.accepted)
        assertEquals(ForegroundServiceLeaseAction.None, firstRelease.action)
        assertEquals(1, leases.size())

        val lastRelease = leases.release("message-b")
        assertTrue(lastRelease.accepted)
        assertEquals(ForegroundServiceLeaseAction.Stop(startId = 11), lastRelease.action)
        assertEquals(ForegroundServiceLifecycleState.STOPPING, leases.lifecycleState())
        assertEquals(0, leases.size())
    }

    @Test
    fun duplicateAcquireAndRelease_areIdempotent() {
        val leases = ForegroundOwnerLeases()

        assertEquals(ForegroundServiceLeaseAction.Start, leases.acquire("message").action)
        assertEquals(
            ForegroundServiceLeaseAction.None,
            leases.serviceCommandReceived(startId = 4),
        )
        assertFalse(leases.acquire("message").accepted)

        assertEquals(
            ForegroundServiceLeaseAction.Stop(startId = 4),
            leases.release("message").action,
        )
        assertFalse(leases.release("message").accepted)
    }

    @Test
    fun failedFirstStart_rollsBackOwnerSoAcquireCanRetry() {
        val leases = ForegroundOwnerLeases()

        assertEquals(ForegroundServiceLeaseAction.Start, leases.acquire("message").action)
        leases.startRequestFailed(ownerToRollback = "message")

        assertEquals(0, leases.size())
        assertEquals(ForegroundServiceLifecycleState.STOPPED, leases.lifecycleState())
        assertEquals(ForegroundServiceLeaseAction.Start, leases.acquire("message").action)
        assertEquals(1, leases.size())
    }

    @Test
    fun lastReleaseWhileStarting_waitsForPromotionThenStopsWithStartId() {
        val leases = ForegroundOwnerLeases()

        assertEquals(ForegroundServiceLeaseAction.Start, leases.acquire("message").action)
        val release = leases.release("message")

        assertTrue(release.accepted)
        assertEquals(ForegroundServiceLeaseAction.None, release.action)
        assertEquals(ForegroundServiceLifecycleState.STARTING, leases.lifecycleState())
        assertEquals(
            ForegroundServiceLeaseAction.Stop(startId = 27),
            leases.serviceCommandReceived(startId = 27),
        )
        assertEquals(ForegroundServiceLifecycleState.STOPPING, leases.lifecycleState())
    }

    @Test
    fun acquireWhileStopping_waitsForDestroyThenStartsReplacement() {
        val leases = ForegroundOwnerLeases()

        assertEquals(ForegroundServiceLeaseAction.Start, leases.acquire("message-a").action)
        assertEquals(
            ForegroundServiceLeaseAction.None,
            leases.serviceCommandReceived(startId = 31),
        )
        assertEquals(
            ForegroundServiceLeaseAction.Stop(startId = 31),
            leases.release("message-a").action,
        )

        val acquireDuringStop = leases.acquire("message-b")
        assertTrue(acquireDuringStop.accepted)
        assertEquals(ForegroundServiceLeaseAction.None, acquireDuringStop.action)
        assertEquals(ForegroundServiceLifecycleState.STOPPING, leases.lifecycleState())

        leases.serviceDestroyed()
        assertEquals(ForegroundServiceLifecycleState.DESTROYING, leases.lifecycleState())
        assertEquals(
            ForegroundServiceLeaseAction.Start,
            leases.completeServiceDestroyed(),
        )
        assertEquals(ForegroundServiceLifecycleState.STARTING, leases.lifecycleState())
    }

    @Test
    fun ownerReleasedDuringDestroy_preventsReplacementStart() {
        val leases = ForegroundOwnerLeases()

        assertEquals(ForegroundServiceLeaseAction.Start, leases.acquire("message-a").action)
        leases.serviceCommandReceived(startId = 42)
        assertEquals(
            ForegroundServiceLeaseAction.Stop(startId = 42),
            leases.release("message-a").action,
        )
        assertTrue(leases.acquire("message-b").accepted)

        leases.serviceDestroyed()
        assertTrue(leases.release("message-b").accepted)
        assertEquals(
            ForegroundServiceLeaseAction.None,
            leases.completeServiceDestroyed(),
        )
        assertEquals(ForegroundServiceLifecycleState.STOPPED, leases.lifecycleState())
        assertEquals(0, leases.size())
    }

    @Test
    fun completionId_isStableAndHandlesIntMinHash() {
        val ordinary = "conversation-with-a-wide-hash"

        assertEquals(ordinary.hashCode() and Int.MAX_VALUE, stableCompletionNotificationId(ordinary))
        assertEquals(
            stableCompletionNotificationId(ordinary),
            stableCompletionNotificationId(ordinary),
        )
        // This Java/Kotlin string is a known hashCode() == Int.MIN_VALUE edge case.
        assertEquals(Int.MIN_VALUE, "polygenelubricants".hashCode())
        assertEquals(0, stableCompletionNotificationId("polygenelubricants"))
    }
}
