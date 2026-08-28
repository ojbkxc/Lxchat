package com.lxseek.chat.mcp

import com.lxseek.chat.service.AppForegroundTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * App-level handler for server → client elicitation requests (MCP 2025-11-25).
 *
 * The protocol client invokes [elicit] with the parsed request and sends the returned
 * [McpElicitationResult] back to the server. Implemented by [McpElicitationController],
 * which bridges the request to the UI and waits for the user's answer.
 */
internal fun interface McpElicitationHandler {
    suspend fun elicit(request: McpElicitationRequest): McpElicitationResult
}

/**
 * Coordinates the elicitation handshake between the MCP protocol layer (which asks) and
 * the UI (which answers), mirroring cc-haha's elicitationHandler / the app's own
 * [com.lxseek.chat.tool.AskUserController] pattern.
 *
 * Owns the pending [StateFlow] and the suspend/await handshake. One prompt at a time;
 * a backgrounded app refuses immediately since there is no decision surface.
 */
class McpElicitationController(
    private val appForeground: StateFlow<Boolean> = AppForegroundTracker.foreground,
) {
    data class PendingElicitation(
        val request: McpElicitationRequest,
        val deferred: CompletableDeferred<McpElicitationResult>,
    )

    private val _pending = MutableStateFlow<PendingElicitation?>(null)
    val pending: StateFlow<PendingElicitation?> = _pending.asStateFlow()

    // One prompt on screen at a time; parallel conversations must not overwrite each other.
    private val mutex = Mutex()

    /** Suspends until the user resolves the elicitation (or the timeout elapses). */
    suspend fun elicit(request: McpElicitationRequest): McpElicitationResult {
        // No UI surface while backgrounded — cancel immediately instead of occupying the
        // tool loop for the full elicitation timeout.
        if (!appForeground.value) return McpElicitationResult(McpElicitationResult.Cancel)
        return mutex.withLock {
            if (!appForeground.value) return@withLock McpElicitationResult(McpElicitationResult.Cancel)
            val deferred = CompletableDeferred<McpElicitationResult>()
            _pending.value = PendingElicitation(request, deferred)
            try {
                withTimeout(ELICITATION_TIMEOUT_MS) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                McpElicitationResult(McpElicitationResult.Cancel)
            } finally {
                if (_pending.value?.deferred === deferred) _pending.value = null
            }
        }
    }

    /** Called by the UI to resolve a pending elicitation with the user's answer. */
    fun resolve(result: McpElicitationResult) {
        val pending = _pending.value ?: return
        pending.deferred.complete(result)
        _pending.value = null
    }

    /** Called by the UI to cancel a pending elicitation (no answer sent back). */
    fun cancel() = resolve(McpElicitationResult(McpElicitationResult.Cancel))

    companion object {
        private const val ELICITATION_TIMEOUT_MS = 5L * 60L * 1_000L
    }
}
