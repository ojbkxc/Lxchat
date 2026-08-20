package com.lxseek.chat.service

import androidx.work.NetworkType
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationWorkerPolicyTest {
    @Test
    fun workerAllowsExactlyTwoInfrastructureRetries() {
        assertTrue(LoopWorker.shouldRetry(0))
        assertTrue(LoopWorker.shouldRetry(1))
        assertFalse(LoopWorker.shouldRetry(2))
        assertFalse(LoopWorker.shouldRetry(3))
    }

    @Test
    fun foregroundNotificationIdsAreStablePerConversation() {
        val first = AutomationForegroundInfo.notificationId("conversation-a")
        assertEquals(first, AutomationForegroundInfo.notificationId("conversation-a"))
        assertNotEquals(first, AutomationForegroundInfo.notificationId("conversation-b"))
        assertTrue(first > 0)
    }

    @Test
    fun automationWaitsForNetworkConnectivity() {
        assertEquals(
            NetworkType.CONNECTED,
            AutomationForegroundInfo.executionConstraints.requiredNetworkType,
        )
    }

    @Test
    fun foregroundNotificationIdsAreUniquePerWorkSpecOccurrence() {
        val firstWork = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val secondWork = UUID.fromString("00000000-0000-0000-0000-000000000002")

        assertNotEquals(
            AutomationForegroundInfo.taskNotificationId("task", firstWork),
            AutomationForegroundInfo.taskNotificationId("task", secondWork),
        )
        assertNotEquals(
            AutomationForegroundInfo.loopNotificationId("conversation", firstWork),
            AutomationForegroundInfo.loopNotificationId("conversation", secondWork),
        )
    }
}
