package com.lxseek.chat.automation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Small coroutine read/write gate for Automation generation versus destructive bulk import.
 * Normal Task/Loop executions may run concurrently; an exclusive import first blocks newcomers,
 * waits for existing executions to finish/cancel, and then owns the graph until its transaction
 * completes. Cancellation while acquiring exclusivity always reopens the gate.
 */
class AutomationExecutionGate {
    private val mutex = Mutex()
    private var maintenance = false
    private var activeExecutions = 0
    private var idle = completedSignal()
    private var maintenanceReleased = completedSignal()

    suspend fun <T> withExecution(block: suspend () -> T): T {
        while (true) {
            val waitForMaintenance = mutex.withLock {
                if (maintenance) {
                    maintenanceReleased
                } else {
                    if (activeExecutions == 0) idle = CompletableDeferred()
                    activeExecutions += 1
                    null
                }
            }
            if (waitForMaintenance == null) break
            waitForMaintenance.await()
        }

        return try {
            block()
        } finally {
            mutex.withLock {
                activeExecutions -= 1
                check(activeExecutions >= 0) { "Automation execution gate underflow" }
                if (activeExecutions == 0) idle.complete(Unit)
            }
        }
    }

    suspend fun <T> withExclusiveImport(
        onQuiescing: suspend () -> Unit = {},
        block: suspend () -> T,
    ): T {
        var ownsMaintenance = false
        try {
            while (!ownsMaintenance) {
                val acquisition = mutex.withLock {
                    if (maintenance) {
                        ExclusiveAcquisition(false, maintenanceReleased)
                    } else {
                        maintenance = true
                        maintenanceReleased = CompletableDeferred()
                        ExclusiveAcquisition(
                            ownsMaintenance = true,
                            waitForIdle = idle.takeIf { activeExecutions > 0 },
                        )
                    }
                }
                if (acquisition.ownsMaintenance) {
                    ownsMaintenance = true
                    // New executions are blocked now. Cancel queued/running Workers before
                    // waiting for active engine calls to drain, otherwise a long provider call
                    // could make destructive import wait indefinitely.
                    onQuiescing()
                    acquisition.waitForIdle?.await()
                } else {
                    acquisition.waitForIdle?.await()
                }
            }
            return block()
        } finally {
            if (ownsMaintenance) {
                mutex.withLock {
                    maintenance = false
                    maintenanceReleased.complete(Unit)
                }
            }
        }
    }

    private data class ExclusiveAcquisition(
        val ownsMaintenance: Boolean,
        val waitForIdle: CompletableDeferred<Unit>?,
    )

    private companion object {
        fun completedSignal() = CompletableDeferred<Unit>().apply { complete(Unit) }
    }
}
