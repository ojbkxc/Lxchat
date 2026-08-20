package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.data.local.RunEntity
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.RunEndReason
import com.lxseek.chat.model.RunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchDeletionPlannerTest {
    @Test
    fun deletingOriginalAnswer_preservesSharedUserAndRegenerationSibling() {
        val messages = listOf(
            message("u0", "run-0", Participant.USER, null, 0, 1),
            message("m0", "run-0", Participant.MODEL, "u0", 1, 2),
            message("queued", "run-0", Participant.USER, "m0", 2, 3),
            message("m-final", "run-0", Participant.MODEL, "queued", 3, 4),
            message("u-next", "run-next", Participant.USER, "m-final", 0, 5),
            message("m-next", "run-next", Participant.MODEL, "u-next", 1, 6),
            message("m-regenerated", "run-regen", Participant.MODEL, "u0", 0, 7),
        )
        val runs = listOf(
            run("run-0", null, 1),
            run("run-next", "run-0", 5),
            run("run-regen", "run-0", 7),
        )

        val plan = BranchDeletionPlanner.plan(
            rootMessageId = "m0",
            messages = messages,
            runs = runs,
            messageSelections = mapOf("u0" to "m0", "m0" to "queued"),
            runSelections = mapOf("run-0" to "run-regen"),
        )

        assertEquals(
            setOf("m0", "queued", "m-final", "u-next", "m-next"),
            plan.deletedMessageIds,
        )
        assertFalse("u0" in plan.deletedMessageIds)
        assertFalse("m-regenerated" in plan.deletedMessageIds)
        assertEquals(setOf("run-next"), plan.deletedRunIds)
        assertFalse("run-0" in plan.deletedRunIds)
        assertFalse("run-regen" in plan.deletedRunIds)
        assertEquals("m-regenerated", plan.messageSelections["u0"])
    }

    @Test
    fun deletingRegeneration_selectsImmediatePreviousBranch_notLastBranch() {
        val messages = listOf(
            message("u0", "run-0", Participant.USER, null, 0, 1),
            message("m0", "run-0", Participant.MODEL, "u0", 1, 2),
            message("m1", "run-1", Participant.MODEL, "u0", 0, 3),
            message("m2", "run-2", Participant.MODEL, "u0", 0, 4),
            message("m3", "run-3", Participant.MODEL, "u0", 0, 5),
        )
        val runs = listOf(
            run("run-0", null, 1),
            run("run-1", "run-0", 3),
            run("run-2", "run-0", 4),
            run("run-3", "run-0", 5),
        )

        val plan = BranchDeletionPlanner.plan(
            rootMessageId = "m2",
            messages = messages,
            runs = runs,
            messageSelections = mapOf("u0" to "m2"),
            runSelections = mapOf("run-0" to "run-2"),
        )

        assertEquals(setOf("m2"), plan.deletedMessageIds)
        assertEquals(setOf("run-2"), plan.rootRunIdsToDelete)
        assertEquals("m1", plan.messageSelections["u0"])
        assertEquals("run-1", plan.runSelections["run-0"])
        assertTrue("m3" !in plan.deletedMessageIds)
    }

    @Test
    fun deletingFirstRegeneration_fallsForwardOnlyWhenNoPreviousBranchExists() {
        val messages = listOf(
            message("u0", "run-0", Participant.USER, null, 0, 1),
            message("m0", "run-0", Participant.MODEL, "u0", 1, 2),
            message("m1", "run-1", Participant.MODEL, "u0", 0, 3),
        )

        val plan = BranchDeletionPlanner.plan(
            rootMessageId = "m0",
            messages = messages,
            runs = listOf(run("run-0", null, 1), run("run-1", "run-0", 3)),
            messageSelections = mapOf("u0" to "m0"),
            runSelections = emptyMap(),
        )

        assertEquals("m1", plan.messageSelections["u0"])
        assertFalse("u0" in plan.deletedMessageIds)
        assertFalse("run-0" in plan.deletedRunIds)
    }

    @Test
    fun deletingEditedUser_removesItsWholeSubtreeAndSelectsPreviousEdit() {
        val messages = listOf(
            message("previous", "previous-run", Participant.MODEL, null, 0, 1),
            message("u-left", "left-run", Participant.USER, "previous", 0, 2),
            message("m-left", "left-run", Participant.MODEL, "u-left", 1, 3),
            message("u-right", "right-run", Participant.USER, "previous", 0, 4),
            message("m-right", "right-run", Participant.MODEL, "u-right", 1, 5),
        )
        val runs = listOf(
            run("previous-run", null, 1),
            run("left-run", "previous-run", 2),
            run("right-run", "previous-run", 4),
        )

        val plan = BranchDeletionPlanner.plan(
            rootMessageId = "u-right",
            messages = messages,
            runs = runs,
            messageSelections = mapOf("previous" to "u-right"),
            runSelections = mapOf("previous-run" to "right-run"),
        )

        assertEquals(setOf("u-right", "m-right"), plan.deletedMessageIds)
        assertEquals(setOf("right-run"), plan.deletedRunIds)
        assertEquals("u-left", plan.messageSelections["previous"])
        assertEquals("left-run", plan.runSelections["previous-run"])
        assertTrue("u-left" !in plan.deletedMessageIds)
        assertTrue("m-left" !in plan.deletedMessageIds)
    }

    private fun message(
        id: String,
        runId: String,
        participant: Participant,
        parentId: String?,
        sequence: Long,
        timestamp: Long,
    ) = MessageEntity(
        id = id,
        conversationId = "conversation",
        parentId = parentId,
        text = id,
        participant = participant,
        timestamp = timestamp,
        runId = runId,
        runSequence = sequence,
    )

    private fun run(
        id: String,
        parentRunId: String?,
        startedAt: Long,
    ) = RunEntity(
        id = id,
        conversationId = "conversation",
        parentRunId = parentRunId,
        status = RunStatus.COMPLETED,
        activeSlot = null,
        startedAt = startedAt,
        lastCheckpointAt = startedAt,
        endedAt = startedAt,
        endReason = RunEndReason.MODEL_COMPLETED,
    )
}
