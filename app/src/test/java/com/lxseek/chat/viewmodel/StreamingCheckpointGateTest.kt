package com.lxseek.chat.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingCheckpointGateTest {

    @Test
    fun firstSnapshotIsImmediate_thenPeriodic() {
        val gate = StreamingCheckpointGate(intervalMs = 1_000)

        assertTrue(gate.shouldCheckpoint(nowMs = 5_000))
        assertFalse(gate.shouldCheckpoint(nowMs = 5_999))
        assertTrue(gate.shouldCheckpoint(nowMs = 6_000))
    }

    @Test
    fun forcedBoundaryBypassesThrottleAndStartsNewWindow() {
        val gate = StreamingCheckpointGate(intervalMs = 1_000)

        assertTrue(gate.shouldCheckpoint(nowMs = 1_000))
        assertTrue(gate.shouldCheckpoint(nowMs = 1_100, force = true))
        assertFalse(gate.shouldCheckpoint(nowMs = 2_099))
        assertTrue(gate.shouldCheckpoint(nowMs = 2_100))
    }

    @Test
    fun clockRollbackDoesNotSuppressCheckpoint() {
        val gate = StreamingCheckpointGate(intervalMs = 1_000)

        assertTrue(gate.shouldCheckpoint(nowMs = 10_000))
        assertTrue(gate.shouldCheckpoint(nowMs = 9_000))
    }
}
