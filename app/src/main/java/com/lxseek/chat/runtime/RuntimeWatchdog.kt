package com.lxseek.chat.runtime

import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Engine crash watchdog: periodically polls engine process liveness and restarts crashed
 * engines automatically with exponential backoff.
 *
 * Design (borrowed from dph / DeepSeek-Harness-Android):
 * - Poll every [EngineConfig.intervalMs] (default 5s) to check whether the engine process
 *   is still alive via [RuntimeProcessManager.isRunning].
 * - On crash, restart the engine by re-invoking the start pipeline through [restarter].
 * - START_STICKY semantics: the watchdog coroutine itself never exits on a single restart
 *   failure. It keeps retrying with exponential backoff (5s -> 10s -> 20s -> 40s, capped at
 *   60s). A successful restart resets the backoff to the normal poll interval.
 * - Optional port probe: if [EngineConfig.healthPort] is set, the watchdog confirms the port
 *   accepts a TCP connection before considering the restart successful; otherwise process
 *   liveness alone is used. The port is supplied explicitly because [RuntimeManifest] does
 *   not declare a health port and we must not edit other files.
 *
 * Restart safety: the default [restarter] calls [RuntimeEngineManager.start], which routes
 * through [RuntimeProcessManager.start]. That path clears the stale process entry without
 * invoking the stop handler, so engine files are NOT deleted during recovery. Calling
 * [RuntimeProcessManager.stop] here would trigger `removeEngineFiles` and break recovery.
 *
 * Pure Kotlin system API only — zero external dependencies.
 */
class RuntimeWatchdog(
    private val engineManager: RuntimeEngineManager,
    private val processManager: RuntimeProcessManager,
    private val scope: CoroutineScope,
) {
    /** Per-engine supervision configuration. */
    data class EngineConfig(
        val enabled: Boolean = true,
        val intervalMs: Long = DEFAULT_INTERVAL_MS,
        /** Health-check port for liveness probe; null = use process.isAlive only. */
        val healthPort: Int? = null,
        /** Upper bound for the inter-restart backoff. */
        val maxBackoffMs: Long = MAX_BACKOFF_MS,
    )

    /** Restart strategy override; default delegates to [RuntimeEngineManager.start]. */
    var restarter: suspend (String) -> Unit = { id -> engineManager.start(id) }

    private val configs = ConcurrentHashMap<String, EngineConfig>()
    private val jobs = ConcurrentHashMap<String, Job>()

    @Volatile
    private var globallyEnabled = true

    /** Master switch. Disabling stops all active loops but keeps per-engine configs. */
    fun setEnabled(enabled: Boolean) {
        globallyEnabled = enabled
        if (!enabled) stopAll()
    }

    /** Register an engine for supervision and start its watchdog loop. */
    fun watch(engineId: String, config: EngineConfig = EngineConfig()) {
        configs[engineId] = config
        if (config.enabled) startWatchdog(engineId)
    }

    /** Stop supervising an engine (does not stop the engine process itself). */
    fun unwatch(engineId: String) {
        configs.remove(engineId)
        stopWatchdog(engineId)
    }

    /** Start the watchdog loop for a single engine (idempotent). */
    fun startWatchdog(engineId: String) {
        if (!globallyEnabled) return
        val cfg = configs[engineId] ?: EngineConfig()
        if (!cfg.enabled) return
        jobs[engineId]?.let { if (it.isActive) return }
        jobs[engineId] = scope.launch(Dispatchers.Default) { loop(engineId, cfg) }
    }

    /** Stop the watchdog loop for a single engine. */
    fun stopWatchdog(engineId: String) {
        jobs.remove(engineId)?.cancel()
    }

    /** Stop every active watchdog loop. */
    fun stopAll() {
        jobs.keys.toList().forEach { stopWatchdog(it) }
    }

    /** Snapshot of currently supervised engine ids. */
    fun supervised(): Set<String> = configs.keys.toSet()

    private suspend fun loop(engineId: String, cfg: EngineConfig) {
        var backoff = 0L
        DebugLog.i(TAG, "watchdog started for $engineId (interval=${cfg.intervalMs}ms)")
        while (scope.isActive) {
            delay(if (backoff > 0) backoff else cfg.intervalMs)
            if (!scope.isActive) break
            if (!processManager.isRunning(engineId)) {
                DebugLog.w(TAG, "engine $engineId crashed, attempting restart")
                val ok = try {
                    restarter(engineId)
                    probeHealth(engineId, cfg)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLog.e(TAG, "restart $engineId failed", e)
                    false
                }
                if (ok) {
                    backoff = 0L
                    DebugLog.i(TAG, "engine $engineId restarted successfully")
                } else {
                    backoff = nextBackoff(backoff, cfg)
                    DebugLog.w(TAG, "engine $engineId restart failed, next retry in ${backoff}ms")
                }
            } else {
                backoff = 0L
            }
        }
        DebugLog.i(TAG, "watchdog stopped for $engineId")
    }

    /** Confirm the engine is truly serving: TCP port probe if configured, else liveness. */
    private fun probeHealth(engineId: String, cfg: EngineConfig): Boolean {
        val port = cfg.healthPort ?: return processManager.isRunning(engineId)
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), PROBE_TIMEOUT_MS.toInt())
                true
            }
        } catch (e: Exception) {
            DebugLog.w(TAG, "health probe $engineId port $port failed", e)
            false
        }
    }

    /** Exponential backoff: 5s -> 10s -> 20s -> 40s, capped at [EngineConfig.maxBackoffMs]. */
    private fun nextBackoff(current: Long, cfg: EngineConfig): Long {
        val next = when (current) {
            0L -> 5_000L
            5_000L -> 10_000L
            10_000L -> 20_000L
            20_000L -> 40_000L
            else -> current * 2
        }
        return next.coerceAtMost(cfg.maxBackoffMs)
    }

    private companion object {
        const val TAG = "RuntimeWatchdog"
        const val DEFAULT_INTERVAL_MS = 5_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val PROBE_TIMEOUT_MS = 2_000L
    }
}