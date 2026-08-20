package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.service.AppForegroundTracker
import com.lxseek.chat.util.Constants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections

/**
 * Coordinates user confirmation of shell commands issued by remote MCP / shell servers.
 *
 * Owns the pending-confirmation [StateFlow], the per-session trust set, and the
 * suspend/await handshake between the generation pipeline (which asks) and the UI
 * (which answers). Extracted from [ChatViewModel] as a single responsibility so the
 * trust policy lives in one place and is independently testable.
 */
class ShellConfirmationController(
    private val settings: SettingsRepository,
    private val appForeground: StateFlow<Boolean> = AppForegroundTracker.foreground,
) {
    data class PendingShellCommand(
        val server: String,
        val summary: String,
        val deferred: CompletableDeferred<Boolean>
    )

    private val _pendingShellCommand = MutableStateFlow<PendingShellCommand?>(null)
    val pendingShellCommand: StateFlow<PendingShellCommand?> = _pendingShellCommand.asStateFlow()

    // Servers the user chose to trust for the rest of this app session.
    private val sessionAllowedServers = Collections.synchronizedSet(mutableSetOf<String>())

    // One prompt on screen at a time. Without this, parallel conversations overwrite each
    // other's pending prompt: the loser's dialog never renders and its confirm() silently
    // times out refused.
    private val promptMutex = Mutex()

    /** Suspends until the user resolves the prompt; returns whether the command may run. */
    suspend fun confirm(server: String, summary: String): Boolean {
        if (!settings.shellConfirmEnabled.value) return true
        if (sessionAllowedServers.contains(server)) return true
        // A Worker has no UI surface while the app is backgrounded. Refuse immediately instead
        // of occupying the tool loop for the full confirmation timeout.
        if (!appForeground.value) return false
        return promptMutex.withLock {
            // Re-check after the wait — the user may have trusted this server while an
            // earlier conversation's prompt was up.
            if (sessionAllowedServers.contains(server)) return@withLock true
            if (!appForeground.value) return@withLock false
            val deferred = CompletableDeferred<Boolean>()
            _pendingShellCommand.value = PendingShellCommand(server, summary, deferred)
            try {
                // Activity recreation keeps this process-scoped prompt alive. Moving the whole app
                // to the background is different: there is no visible decision surface, so cancel
                // the wait immediately. The timeout remains a final safety bound for a foreground
                // Activity that never answers.
                withTimeout(Constants.SHELL_CONFIRM_TIMEOUT_MS) {
                    coroutineScope {
                        val backgrounded = async {
                            appForeground.filter { foreground -> !foreground }.first()
                            false
                        }
                        try {
                            select {
                                deferred.onAwait { allowed -> allowed }
                                backgrounded.onAwait { allowed -> allowed }
                            }
                        } finally {
                            backgrounded.cancel()
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                false
            } finally {
                if (_pendingShellCommand.value?.deferred === deferred) _pendingShellCommand.value = null
            }
        }
    }

    /** Called by the UI to resolve a pending confirmation. */
    fun resolve(allow: Boolean, alwaysAllowServer: Boolean = false) {
        val pending = _pendingShellCommand.value ?: return
        if (allow && alwaysAllowServer) sessionAllowedServers.add(pending.server)
        pending.deferred.complete(allow)
        _pendingShellCommand.value = null
    }

    fun setEnabled(enabled: Boolean) = settings.setShellConfirmEnabled(enabled)
}
