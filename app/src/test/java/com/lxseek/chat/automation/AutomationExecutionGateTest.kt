package com.lxseek.chat.automation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AutomationExecutionGateTest {
    @Test
    fun exclusiveImportWaitsForActiveExecutionAndBlocksNewOnes() = runTest {
        val gate = AutomationExecutionGate()
        val releaseFirst = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        launch {
            gate.withExecution {
                events += "first-start"
                firstStarted.complete(Unit)
                releaseFirst.await()
                events += "first-end"
            }
        }
        firstStarted.await()
        val import = async {
            gate.withExclusiveImport(onQuiescing = { events += "quiesce" }) {
                events += "import"
            }
        }
        runCurrent()
        val second = async {
            gate.withExecution { events += "second" }
        }
        runCurrent()

        assertFalse(import.isCompleted)
        assertFalse(second.isCompleted)
        releaseFirst.complete(Unit)
        import.await()
        second.await()

        assertEquals(
            listOf("first-start", "quiesce", "first-end", "import", "second"),
            events,
        )
    }

    @Test
    fun cancelledExclusiveWaiterReopensGate() = runTest {
        val gate = AutomationExecutionGate()
        val releaseExecution = CompletableDeferred<Unit>()
        val executionStarted = CompletableDeferred<Unit>()
        val running = launch {
            gate.withExecution {
                executionStarted.complete(Unit)
                releaseExecution.await()
            }
        }
        executionStarted.await()
        val waitingImport = launch { gate.withExclusiveImport { error("must not run") } }
        runCurrent()
        waitingImport.cancel()
        waitingImport.join()
        releaseExecution.complete(Unit)
        running.join()

        var entered = false
        gate.withExecution { entered = true }
        assertTrue(entered)
    }
}
