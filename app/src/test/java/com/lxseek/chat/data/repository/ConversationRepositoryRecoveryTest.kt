package com.lxseek.chat.data.repository

import com.lxseek.chat.data.local.ChatDao
import com.lxseek.chat.util.DebugLog
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ConversationRepositoryRecoveryTest {
    @Test
    fun transientRecoveryFailureRetriesBeforeOpeningGenerationBarrier() = runTest {
        mockkObject(DebugLog)
        every { DebugLog.e(any(), any(), any()) } returns Unit
        try {
            val dao = mockk<ChatDao>()
            coEvery { dao.recoverOrphanedRuns(any()) } throws
                IllegalStateException("database temporarily busy") andThen 1
            val repository = ConversationRepository(dao)

            repository.ensureRunRecovery()
            // The completed barrier is process-idempotent.
            repository.ensureRunRecovery()

            coVerify(exactly = 2) { dao.recoverOrphanedRuns(any()) }
        } finally {
            unmockkObject(DebugLog)
        }
    }
}
