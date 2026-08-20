package com.lxseek.chat.tool

import com.lxseek.chat.viewmodel.GenerationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal fun getConchBackend(
    serverName: String,
    ctx: GenerationContext,
): ConchBackend? {
    val conchDevices = ctx.shellDevices.filter { it.type != "ssh" }
    val device = if (serverName.isNotBlank()) {
        conchDevices.find { it.name.equals(serverName, ignoreCase = true) }
    } else {
        conchDevices.singleOrNull()
    } ?: return null
    return ConchBackend(device)
}

internal fun conchServerNotFoundMessage(serverName: String, ctx: GenerationContext): String {
    val names = ctx.shellDevices
        .filter { it.type != "ssh" }
        .map { it.name.ifBlank { "Untitled" } }
    return when {
        names.isEmpty() -> "No Conch server is configured. Background jobs require Conch."
        serverName.isNotBlank() ->
            "Unknown Conch server \"$serverName\". Available: ${names.joinToString(", ")}."
        names.size > 1 ->
            "Multiple Conch servers are available. Specify one: ${names.joinToString(", ")}."
        else -> "Conch server is unavailable."
    }
}

internal class ShellDurableJobExecutor {
    suspend fun executeDurableForeground(
        backend: ConchBackend,
        command: String,
        workdir: String,
        waitMs: Int,
    ): String {
        val startResult = try {
            backend.startJob(command, workdir, BACKGROUND_JOB_MAX_MS)
        } catch (e: Exception) {
            return jsonError(
                "execute_shell_command",
                e.message ?: "Failed to start durable foreground job",
                server = backend.device.name,
                command = command,
            )
        }
        val startObj = try {
            Json.parseToJsonElement(startResult).jsonObject
        } catch (_: Exception) {
            return startResult
        }
        if (startObj["error"] != null) return startResult
        val jobId = (startObj["job_id"] as? JsonPrimitive)?.content
            ?.takeIf(String::isNotBlank)
            ?: return jsonError(
                "execute_shell_command",
                "Conch started a job without returning job_id",
                server = backend.device.name,
                command = command,
            )

        val start = System.currentTimeMillis()
        var pollIntervalMs = INITIAL_WAIT_POLL_MS
        var consecutiveFailures = 0
        var lastFailure: String? = null
        try {
        while (currentCoroutineContext().isActive) {
            val raw = try {
                backend.getJob(jobId).also { consecutiveFailures = 0 }
            } catch (e: Exception) {
                consecutiveFailures++
                lastFailure = e.message ?: e.javaClass.simpleName
                if (consecutiveFailures >= MAX_WAIT_POLL_FAILURES) {
                    return buildJsonObject {
                        put("type", "execute_shell_command")
                        put("error", "poll_failed")
                        put(
                            "message",
                            "Durable job could not be polled $consecutiveFailures times: " +
                                lastFailure,
                        )
                        put("server", backend.device.name)
                        put("command", command)
                        put("job_id", jobId)
                        put("durable", true)
                        put("state", "unknown")
                        put(
                            "note",
                            "The command may still be running. Keep this job_id and retry with " +
                                "wait_for_job or get_shell_job; it was not killed or restarted.",
                        )
                    }.toString()
                }
                null
            }
            if (raw != null && isTerminalJobPayload(raw)) {
                val result = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
                return buildJsonObject {
                    put("type", "execute_shell_command")
                    put("server", backend.device.name)
                    put("command", command)
                    put("job_id", jobId)
                    put("durable", true)
                    if (result != null) put("result", result) else put("result_raw", raw)
                }.toString()
            }
            val elapsed = System.currentTimeMillis() - start
            if (elapsed >= waitMs) {
                return buildJsonObject {
                    put("type", "execute_shell_command")
                    put("server", backend.device.name)
                    put("command", command)
                    put("job_id", jobId)
                    put("background", true)
                    put("state", "running")
                    put("waited_ms", elapsed)
                    put(
                        "note",
                        "Foreground wait expired; the same durable job is still running. Use " +
                            "wait_for_job to await it. The command was not killed or restarted.",
                    )
                }.toString()
            }
            val remaining = (waitMs - elapsed).toInt()
            kotlinx.coroutines.delay(pollIntervalMs.coerceAtMost(remaining).toLong())
            pollIntervalMs = (pollIntervalMs * 2).coerceAtMost(MAX_WAIT_POLL_MS)
        }
        } catch (cancelled: CancellationException) {
            // A wait expiry intentionally leaves the durable job running. An explicit generation
            // Stop is different: it revokes this tool execution and stops the remote process tree.
            withContext(NonCancellable) { runCatching { backend.stopJob(jobId) } }
            throw cancelled
        }
        return jsonError(
            "execute_shell_command",
            "cancelled while durable job $jobId continues on ${backend.device.name}",
            server = backend.device.name,
            command = command,
        )
    }

    suspend fun listShellJobs(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val serverName = arg(args, "server")
        val backend = getConchBackend(serverName, ctx)
            ?: return jsonError(
                "list_shell_jobs",
                conchServerNotFoundMessage(serverName, ctx),
                server = serverName,
            )
        return try {
            backend.listJobs()
        } catch (e: Exception) {
            jsonError(
                "list_shell_jobs",
                e.message ?: "Failed to list shell jobs",
                server = backend.device.name,
            )
        }
    }

    suspend fun getShellJob(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val jobId = arg(args, "job_id")
        if (jobId.isBlank()) return jsonError("get_shell_job", "job_id is required")
        val serverName = arg(args, "server")
        val backend = getConchBackend(serverName, ctx)
            ?: return jsonError(
                "get_shell_job",
                conchServerNotFoundMessage(serverName, ctx),
                server = serverName,
            )
        return try {
            backend.getJob(jobId)
        } catch (e: Exception) {
            jsonError(
                "get_shell_job",
                e.message ?: "Failed to get shell job",
                server = backend.device.name,
            )
        }
    }

    suspend fun waitForShellJob(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val jobId = arg(args, "job_id")
        if (jobId.isBlank()) return jsonError("wait_for_job", "job_id is required")
        val serverName = arg(args, "server")
        val backend = getConchBackend(serverName, ctx)
            ?: return jsonError(
                "wait_for_job",
                conchServerNotFoundMessage(serverName, ctx),
                server = serverName,
            )
        val rawTimeout = arg(args, "timeout_ms")
        if (rawTimeout.isBlank()) return jsonError(
            "wait_for_job", "timeout_ms is required", server = backend.device.name,
        )
        val requestedMs = rawTimeout.toIntOrNull()
            ?: return jsonError(
                "wait_for_job",
                "timeout_ms must be an integer, got \"$rawTimeout\"",
                server = backend.device.name,
            )
        // The whole tool call runs under GenerationManager's withTimeout(ctx.toolTimeoutMs). A wait
        // that reaches that outer ceiling is killed as a generic tool timeout, so its graceful
        // "still running, call again" note never fires. Cap the effective wait strictly below the
        // outer budget (leaving a margin to emit the note) so the structured result always wins.
        val ceilingMs = maxWaitMs(ctx)
        val timeoutMs = requestedMs.coerceIn(MIN_WAIT_JOB_MS, ceilingMs)
        // Report silent clamping. Otherwise a model that asked for 10 minutes reads timed_out=true
        // after ~5 and concludes the job hung for the full budget it never actually waited.
        val clampedFrom = requestedMs.takeIf { it > ceilingMs }
        val start = System.currentTimeMillis()
        // A transient poll failure must not abort the wait: the job keeps running on the device.
        // Only a sustained run of failures is fatal.
        var consecutiveFailures = 0
        var lastFailure: String? = null
        var pollIntervalMs = INITIAL_WAIT_POLL_MS
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val raw = try {
                backend.getJob(jobId).also { consecutiveFailures = 0 }
            } catch (e: Exception) {
                consecutiveFailures++
                lastFailure = e.message ?: e.javaClass.simpleName
                if (consecutiveFailures >= MAX_WAIT_POLL_FAILURES) {
                    return buildJsonObject {
                        put("type", "wait_for_job")
                        put("error", "poll_failed")
                        put(
                            "message",
                            "Failed to poll job $consecutiveFailures times in a row: $lastFailure",
                        )
                        put("server", backend.device.name)
                        put("job_id", jobId)
                        put("durable", true)
                        put("state", "unknown")
                        put(
                            "note",
                            "The job may still be running. Retry with the same job_id; it was not " +
                                "stopped by this wait failure.",
                        )
                    }.toString()
                }
                null
            }
            if (raw != null && isTerminalJobPayload(raw)) {
                val result = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
                return buildJsonObject {
                    put("type", "wait_for_job")
                    put("job_id", jobId)
                    put("waited_ms", System.currentTimeMillis() - start)
                    if (result != null) put("result", result) else put("result_raw", raw)
                }.toString()
            }
            val elapsed = System.currentTimeMillis() - start
            if (elapsed >= timeoutMs) {
                val clampNote = clampedFrom?.let {
                    " The requested timeout_ms=$it exceeded this tool call's ceiling of ${ceilingMs}ms and was clamped, so the job has only been waited on for that long."
                } ?: ""
                return buildJsonObject {
                    put("type", "wait_for_job")
                    put("job_id", jobId)
                    put("waited_ms", elapsed)
                    put("timed_out", true)
                    put(
                        "note",
                        "Job still running. Call wait_for_job again to keep waiting, or " +
                            "get_shell_job for a one-shot look.$clampNote",
                    )
                }.toString()
            }
            // Back off so a long wait does not hammer the device, but never overshoot the deadline.
            val remaining = (timeoutMs - elapsed).toInt()
            kotlinx.coroutines.delay(pollIntervalMs.coerceAtMost(remaining).toLong())
            pollIntervalMs = (pollIntervalMs * 2).coerceAtMost(MAX_WAIT_POLL_MS)
        }
        return jsonError("wait_for_job", "cancelled", server = backend.device.name)
    }

    /**
     * Decides whether a raw `/jobs/get` payload represents a finished job.
     *
     * Conch reports lifecycle in the **`state`** field (see conch shell/jobs.go): `running` and
     * `stopping` are live; `succeeded`, `failed`, `stopped` and `interrupted` are terminal. An
     * explicit server-side `error` (e.g. "job not found") is also terminal, because polling again
     * cannot change it. An unparseable or field-less payload is deliberately NOT terminal: a
     * transport hiccup must never be reported to the model as "the job finished".
     */
    internal fun isTerminalJobPayload(raw: String): Boolean {
        if (raw.isBlank()) return false
        val obj = try {
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .parseToJsonElement(raw).jsonObject
        } catch (_: Exception) {
            return false
        }
        if (obj["error"] != null) return true
        val state = (obj["state"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content
            ?.lowercase()
            ?: return false
        return state in TERMINAL_JOB_STATES
    }

    companion object {
        /** Conch's durable-job runtime ceiling (24h). */
        internal const val BACKGROUND_JOB_MAX_MS = 86_400_000

        private const val MIN_WAIT_JOB_MS = 1_000
        private const val WAIT_JOB_OUTER_MARGIN_MS = 5_000L

        internal fun maxWaitMs(ctx: GenerationContext): Int =
            (ctx.toolTimeoutMs - WAIT_JOB_OUTER_MARGIN_MS)
                .coerceAtLeast(MIN_WAIT_JOB_MS.toLong())
                .toInt()

        private const val INITIAL_WAIT_POLL_MS = 500
        private const val MAX_WAIT_POLL_MS = 5_000
        private const val MAX_WAIT_POLL_FAILURES = 5

        private val TERMINAL_JOB_STATES = setOf(
            "succeeded",
            "failed",
            "stopped",
            "interrupted",
        )
    }
}
