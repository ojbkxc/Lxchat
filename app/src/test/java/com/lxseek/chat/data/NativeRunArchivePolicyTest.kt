package com.lxseek.chat.data

import com.lxseek.chat.data.local.RunEntity
import com.lxseek.chat.model.RunEndReason
import com.lxseek.chat.model.RunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRunArchivePolicyTest {
    @Test
    fun activeRun_isRecoveredAsTerminalStoppedRun() {
        val run = NativeRunArchivePolicy.terminalize(
            snapshot(
                status = "ACTIVE",
                lastCheckpointAt = 150,
                endedAt = null,
                endReason = null,
            )
        )

        assertEquals(RunStatus.STOPPED, run.status)
        assertEquals(RunEndReason.PROCESS_RECOVERED, run.endReason)
        assertNull(run.activeSlot)
        assertEquals(150L, run.endedAt)
    }

    @Test
    fun terminalRun_preservesStatusAndRepairsMissingReason() {
        val run = NativeRunArchivePolicy.terminalize(
            snapshot(
                status = "COMPLETED",
                lastCheckpointAt = 150,
                endedAt = 140,
                endReason = null,
            )
        )

        assertEquals(RunStatus.COMPLETED, run.status)
        assertEquals(RunEndReason.MODEL_COMPLETED, run.endReason)
        assertEquals(150L, run.endedAt)
    }

    @Test
    fun completeOwnership_requiresMatchingRunsAndUniqueSequences() {
        val runs = listOf(terminalRun("run", "conversation"))
        val valid = listOf(
            ownership("m1", "conversation", "run", 0),
            ownership("m2", "conversation", "run", 3),
        )

        assertTrue(NativeRunArchivePolicy.hasCompleteOwnership(runs, valid, true))
        assertFalse(
            NativeRunArchivePolicy.hasCompleteOwnership(
                runs,
                valid + ownership("m3", "conversation", "run", 3),
                true,
            )
        )
        assertFalse(
            NativeRunArchivePolicy.hasCompleteOwnership(
                runs,
                listOf(ownership("m1", "other", "run", 0)),
                true,
            )
        )
        assertFalse(NativeRunArchivePolicy.hasCompleteOwnership(runs, valid, false))
    }

    @Test
    fun incompleteOwnership_forcesLegacyFallback() {
        val runs = listOf(terminalRun("run", "conversation"))

        assertFalse(
            NativeRunArchivePolicy.hasCompleteOwnership(
                runs,
                listOf(ownership("m1", "conversation", null, null)),
                true,
            )
        )
        assertFalse(
            NativeRunArchivePolicy.hasCompleteOwnership(
                emptyList(),
                emptyList(),
                true,
            )
        )
    }

    @Test
    fun missingParent_isSeveredAndMarkedAmbiguous() {
        val repaired = NativeRunArchivePolicy.orderByParent(
            listOf(terminalRun("child", "conversation", parentRunId = "missing"))
        ).single()

        assertNull(repaired.parentRunId)
        assertTrue(repaired.legacyAmbiguous)
    }

    @Test
    fun parentCycle_isPreservedInDeterministicInsertionOrder() {
        val ordered = NativeRunArchivePolicy.orderByParent(
            listOf(
                terminalRun("b", "conversation", parentRunId = "a", startedAt = 20),
                terminalRun("a", "conversation", parentRunId = "b", startedAt = 10),
            )
        )

        assertEquals(listOf("a", "b"), ordered.map { it.id })
        assertNull(ordered.first().parentRunId)
        assertTrue(ordered.first().legacyAmbiguous)
        assertEquals("a", ordered.last().parentRunId)
    }

    private fun snapshot(
        status: String,
        lastCheckpointAt: Long,
        endedAt: Long?,
        endReason: String?,
    ) = ArchivedRunSnapshot(
        id = "run",
        conversationId = "conversation",
        parentRunId = null,
        status = status,
        startedAt = 100,
        lastCheckpointAt = lastCheckpointAt,
        stopRequestedAt = null,
        endedAt = endedAt,
        endReason = endReason,
        currentPass = 0,
        legacyAmbiguous = false,
    )

    private fun terminalRun(
        id: String,
        conversationId: String,
        parentRunId: String? = null,
        startedAt: Long = 0,
    ) = RunEntity(
        id = id,
        conversationId = conversationId,
        parentRunId = parentRunId,
        status = RunStatus.COMPLETED,
        activeSlot = null,
        startedAt = startedAt,
        lastCheckpointAt = startedAt,
        endedAt = startedAt,
        endReason = RunEndReason.MODEL_COMPLETED,
    )

    private fun ownership(
        messageId: String,
        conversationId: String,
        runId: String?,
        runSequence: Long?,
    ) = ArchivedMessageRunOwnership(
        messageId = messageId,
        conversationId = conversationId,
        runId = runId,
        runSequence = runSequence,
        consumedAtPass = null,
    )
}
