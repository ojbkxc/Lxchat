package com.lxseek.chat.data.local

import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.model.RunEndReason
import com.lxseek.chat.model.RunStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDaoRunRecoveryTest {
    @Test
    fun recoveryExecutesTheExactSnapshotEffectAndStopsItsInFlightModel() = runTest {
        val dao = mockk<ChatDao>()
        val run = liveRun(status = RunStatus.ACTIVE)
        val message = MessageEntity(
            id = "message",
            conversationId = CONVERSATION_ID,
            text = "partial",
            status = MessageStatus.THINKING,
            participant = Participant.MODEL,
            timestamp = 2L,
            runId = RUN_ID,
            runSequence = 0,
        )
        val checkpoint = slot<MessageStreamCheckpoint>()
        coEvery { dao.recoverOrphanedRuns(any()) } coAnswers { callOriginal() }
        coEvery { dao.getOrphanedLiveRuns() } returns listOf(run)
        coEvery { dao.getMessagesForRuns(listOf(RUN_ID)) } returns listOf(message)
        coEvery { dao.updateMessageCheckpoint(capture(checkpoint)) } returns 1
        coEvery {
            dao.terminalizeLiveRun(
                RUN_ID,
                RunStatus.STOPPED,
                RunEndReason.PROCESS_RECOVERED,
                99L,
            )
        } returns 1

        assertEquals(1, dao.recoverOrphanedRuns(99L))
        assertEquals(MessageStatus.STOPPED, checkpoint.captured.status)
        coVerify(exactly = 1) {
            dao.terminalizeLiveRun(
                RUN_ID,
                RunStatus.STOPPED,
                RunEndReason.PROCESS_RECOVERED,
                99L,
            )
        }
    }

    @Test
    fun recoveryRejectsALostExactRunUpdate() = runTest {
        val dao = mockk<ChatDao>()
        coEvery { dao.recoverOrphanedRuns(any()) } coAnswers { callOriginal() }
        coEvery { dao.getOrphanedLiveRuns() } returns listOf(liveRun(RunStatus.STOPPING))
        coEvery { dao.getMessagesForRuns(listOf(RUN_ID)) } returns emptyList()
        coEvery { dao.terminalizeLiveRun(any(), any(), any(), any()) } returns 0

        val failure = runCatching { dao.recoverOrphanedRuns(99L) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
    }

    private fun liveRun(status: RunStatus) = RunEntity(
        id = RUN_ID,
        conversationId = CONVERSATION_ID,
        parentRunId = null,
        status = status,
        activeSlot = 1,
        startedAt = 1L,
        lastCheckpointAt = 2L,
        stopRequestedAt = if (status == RunStatus.STOPPING) 2L else null,
        currentPass = 3,
    )

    private companion object {
        const val CONVERSATION_ID = "conversation"
        const val RUN_ID = "run"
    }
}
