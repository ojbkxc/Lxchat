package com.lxseek.chat.tool

import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.data.ShellDeviceConfig
import com.lxseek.chat.sandbox.SandboxManagerFactory
import com.lxseek.chat.util.Constants
import com.lxseek.chat.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class ShellToolProvider(
    private val sandboxFactory: SandboxManagerFactory? = null,
    private val imageStore: ToolImageStore? = null,
) : ToolProvider {

    private val sandbox = sandboxFactory?.create()
    private val durableJobs = ShellDurableJobExecutor()
    private val monitorTools = ShellMonitorTools()

    /**
     * Optional user-confirmation gate for state-changing operations. The isolated local sandbox
     * normally proceeds directly; once shared storage is mounted, local commands/writes are gated
     * too because they can mutate files outside the app sandbox.
     */
    var confirm: (suspend (server: String, summary: String) -> Boolean)? = null

    private suspend fun confirmTarget(
        device: ShellDeviceConfig?,
        summary: String,
        localSharedStorageExposed: Boolean = false,
    ): Boolean {
        if (device == null && !localSharedStorageExposed) return true
        val target = device?.name?.ifBlank { "${device.type} server" }
            ?: "Local Sandbox · /mnt/shared"
        return confirm?.invoke(target, summary) ?: true
    }

    private fun targetsSharedStorage(path: String): Boolean {
        val normalized = path.trim().replace('\\', '/').replace(Regex("/+"), "/")
        return normalized == "/mnt/shared" || normalized.startsWith("/mnt/shared/")
    }

    // ── Helpers ────────────────────────────────────────────

    private fun resolveShellDevice(serverName: String, ctx: GenerationContext): ShellDeviceConfig? {
        if (serverName.equals("Local Sandbox", ignoreCase = true)) return null
        return if (serverName.isNotBlank()) {
            ctx.shellDevices.find { it.name.equals(serverName, ignoreCase = true) }
        } else if (ctx.shellDevices.size == 1) {
            ctx.shellDevices.first()
        } else null
    }

    private fun serverNotFoundMessage(serverName: String, ctx: GenerationContext): String {
        val hasSandbox = ctx.sandboxEnabled && sandboxFactory?.isAvailable() == true
        val allNames = buildList {
            if (hasSandbox) add("\"Local Sandbox\"")
            addAll(ctx.shellDevices.map { "\"${it.name}\"" })
        }
        return if (allNames.size == 1) {
            "Unknown server: $serverName. Use ${allNames[0]} or omit the server parameter."
        } else {
            val names = allNames.joinToString(", ")
            if (serverName.isBlank()) "Multiple servers available. Use list_shells to see them, then specify one: $names."
            else "Unknown server: $serverName. Available: $names."
        }
    }

    private suspend fun getBackend(serverName: String, ctx: GenerationContext): Backend? {
        // Local Sandbox
        if (serverName.equals("Local Sandbox", ignoreCase = true) && ctx.sandboxEnabled) {
            if (sandbox?.isAvailable() == true) return SandboxBackend(sandbox)
            if (sandbox != null) return null
        }
        if (serverName.isBlank()) {
            if (ctx.sandboxEnabled && sandbox?.isAvailable() == true) {
                return SandboxBackend(sandbox)
            }
        }
        val device = resolveShellDevice(serverName, ctx) ?: return null
        return when (device.type) {
            "ssh" -> SshBackend(device)
            else -> ConchBackend(device)
        }
    }

    // ── ToolProvider interface ─────────────────────────────

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> =
        ShellToolDefinitions.build(ctx)

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        return when (name) {
            "list_shells" -> listShells(ctx)
            "execute_shell_command" -> executeShellCommand(arguments, ctx)
            "execute_shell_batch" -> executeShellBatch(arguments, ctx)
            "list_shell_jobs" -> durableJobs.listShellJobs(arguments, ctx)
            "get_shell_job" -> durableJobs.getShellJob(arguments, ctx)
            "wait_for_job" -> durableJobs.waitForShellJob(arguments, ctx)
            "stop_shell_job" -> stopShellJob(arguments, ctx)
            "file_read" -> executeFileRead(arguments, ctx)
            "file_write" -> executeFileWrite(arguments, ctx)
            "file_edit" -> executeFileEdit(arguments, ctx)
            "file_glob" -> executeFileGlob(arguments, ctx)
            "file_grep" -> executeFileGrep(arguments, ctx)
            "view_image" -> executeViewImage(arguments, ctx).text
            "list_processes" -> executeListProcesses(arguments, ctx)
            "kill_process" -> executeKillProcess(arguments, ctx)
            "system_stats" -> executeSystemStats(arguments, ctx)
            "tail_follow" -> executeTailFollow(arguments, ctx)
            else -> "Unknown tool: $name"
        }
    }

    override fun executeEvents(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): Flow<ToolExecutionEvent> {
        return when (name) {
            "execute_shell_command" -> executeShellCommandEvents(arguments, ctx)
            "view_image" -> flow {
                emit(ToolExecutionEvent.Completed(executeViewImage(arguments, ctx)))
            }
            else -> super<ToolProvider>.executeEvents(name, arguments, ctx)
        }
    }

    override fun handles(name: String): Boolean = name in setOf(
        "list_shells", "execute_shell_command", "execute_shell_batch",
        "list_shell_jobs", "get_shell_job", "wait_for_job", "stop_shell_job",
        "file_read", "file_write", "file_edit", "file_glob", "file_grep", "view_image",
        "list_processes", "kill_process", "system_stats", "tail_follow"
    )

    override fun riskLevel(name: String): RiskLevel = when (name) {
        "list_shells", "list_shell_jobs", "get_shell_job", "wait_for_job" -> RiskLevel.ReadOnly
        "file_read", "file_glob", "file_grep", "view_image" -> RiskLevel.ReadOnly
        "list_processes", "system_stats", "tail_follow" -> RiskLevel.ReadOnly
        "execute_shell_command" -> RiskLevel.Moderate
        "execute_shell_batch" -> RiskLevel.Moderate
        "stop_shell_job", "file_write", "file_edit", "kill_process" -> RiskLevel.HighRisk
        else -> RiskLevel.ReadOnly
    }

    override fun requiresApprovalByDefault(name: String): Boolean = when (name) {
        "file_write", "file_edit", "stop_shell_job", "kill_process" -> true
        else -> false
    }

    // ── list_shells ────────────────────────────────────────

    private suspend fun listShells(ctx: GenerationContext): String {
        val items = buildList {
            val sandboxOk = ctx.sandboxEnabled && sandbox?.isAvailable() == true
            if (sandboxOk) {
                add(buildJsonObject {
                    put("name", "Local Sandbox")
                    put("description", "Alpine Linux on-device")
                    put("type", "local")
                })
            }
            ctx.shellDevices.forEach { d ->
                add(buildJsonObject {
                    put("name", d.name.ifBlank { "Untitled" })
                    put("description", d.description)
                    put("type", d.type)
                    when (d.type) {
                        "ssh" -> { put("host", d.sshHost); put("port", d.sshPort) }
                        else -> put("url", d.serverUrl)
                    }
                })
            }
        }
        return buildJsonObject {
            put("type", "list_shells")
            putJsonArray("devices") { items.forEach { add(it) } }
        }.toString()
    }

    // ── Shell execution ────────────────────────────────────

    private suspend fun executeShellCommand(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val command = arg(args, "command")
        if (command.isBlank()) return jsonError("execute_shell_command", "no_command")
        val serverName = arg(args, "server")
        val background = boolArg(args, "background")
        val foregroundMaxMs = Constants.TOOL_EXECUTION_TIMEOUT_MS.toInt()
        val timeoutMax = if (background) {
            ShellDurableJobExecutor.BACKGROUND_JOB_MAX_MS
        } else {
            foregroundMaxMs
        }
        val rawTimeout = arg(args, "timeout_ms")
        if (rawTimeout.isBlank()) return jsonError(
            "execute_shell_command", "timeout_ms is required", server = serverName, command = command,
        )
        val timeoutMs = (rawTimeout.toIntOrNull()
            ?: return jsonError(
                "execute_shell_command",
                "timeout_ms must be an integer, got \"$rawTimeout\"",
                server = serverName,
                command = command,
            )).coerceIn(1000, timeoutMax)
        val workdir = arg(args, "workdir")

        if (background) {
            val backend = getConchBackend(serverName, ctx)
                ?: return jsonError(
                    "execute_shell_command",
                    conchServerNotFoundMessage(serverName, ctx),
                    server = serverName,
                    command = command,
                )
            if (!confirmTarget(backend.device, "start background job: $ $command")) {
                return jsonError(
                    "execute_shell_command",
                    "denied_by_user: the user declined to run this background command",
                    server = backend.device.name,
                    command = command,
                )
            }
            return try {
                backend.startJob(command, workdir, timeoutMs)
            } catch (e: Exception) {
                jsonError(
                    "execute_shell_command",
                    e.message ?: "Failed to start background job",
                    server = backend.device.name,
                    command = command,
                )
            }
        }

        val backend = getBackend(serverName, ctx)
            ?: return jsonError("execute_shell_command", serverNotFoundMessage(serverName, ctx))
        return try {
            // Gate on the backend's ACTUAL target: with a blank server name the sandbox wins
            // resolution, while resolveShellDevice() would name an unrelated remote device.
            if (!confirmTarget(
                    backend.device,
                    "$ $command",
                    localSharedStorageExposed =
                        backend.device == null && ctx.sandboxSharedStorageEnabled,
                )
            ) {
                return jsonError("execute_shell_command", "denied_by_user: the user declined to run this command", server = serverName, command = command)
            }
            if (backend is ConchBackend) {
                durableJobs.executeDurableForeground(
                    backend = backend,
                    command = command,
                    workdir = workdir,
                    waitMs = timeoutMs.coerceAtMost(maxWaitMs(ctx)),
                )
            } else {
                backend.executeCommand(command, workdir, timeoutMs)
            }
        } finally {
            backend.close()
        }
    }

    // ── Batch shell execution ──────────────────────────────

    private suspend fun executeShellBatch(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val command = arg(args, "command")
        if (command.isBlank()) return jsonError("execute_shell_batch", "no_command")
        val rawTimeout = arg(args, "timeout_ms")
        if (rawTimeout.isBlank()) return jsonError(
            "execute_shell_batch", "timeout_ms is required", command = command,
        )
        val timeoutMs = (rawTimeout.toIntOrNull()
            ?: return jsonError(
                "execute_shell_batch",
                "timeout_ms must be an integer, got \"$rawTimeout\"",
                command = command,
            )).coerceIn(1000, Constants.TOOL_EXECUTION_TIMEOUT_MS.toInt())
        val workdir = arg(args, "workdir")
        val requestedServers = (args["servers"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content?.ifBlank { null } }
            ?: emptyList()
        val serverNames = if (requestedServers.isEmpty()) {
            ctx.shellDevices.map { it.name }
        } else {
            requestedServers
        }
        if (serverNames.isEmpty()) return jsonError(
            "execute_shell_batch",
            "no servers specified and no shell servers are configured. Use list_shells to see available servers.",
            command = command,
        )
        if (serverNames.any { it.equals("Local Sandbox", ignoreCase = true) }) return jsonError(
            "execute_shell_batch",
            "Local Sandbox is not allowed in batch execution",
            command = command,
        )
        val missing = serverNames.filter { resolveShellDevice(it, ctx) == null }
        if (missing.isNotEmpty()) return jsonError(
            "execute_shell_batch",
            "Unknown server(s): ${missing.joinToString(", ")}",
            command = command,
        )
        val combinedTarget = serverNames.joinToString(", ")
        val summary = "batch execute on $combinedTarget: $ $command"
        if (confirm?.invoke(combinedTarget, summary) == false) return jsonError(
            "execute_shell_batch",
            "denied_by_user: the user declined to run this batch command",
            command = command,
        )
        val results = coroutineScope {
            serverNames.map { server ->
                async {
                    try {
                        val backend = getBackend(server, ctx)
                        if (backend == null) {
                            buildJsonObject {
                                put("server", server)
                                put("error", "error")
                                put("message", serverNotFoundMessage(server, ctx))
                            }
                        } else {
                            try {
                                parseBackendResult(server, backend.executeCommand(command, workdir, timeoutMs))
                            } finally {
                                backend.close()
                            }
                        }
                    } catch (e: Exception) {
                        buildJsonObject {
                            put("server", server)
                            put("error", "error")
                            put("message", e.message ?: "Unknown error")
                        }
                    }
                }
            }.awaitAll()
        }
        return buildJsonObject {
            put("type", "execute_shell_batch")
            put("command", command)
            putJsonArray("results") { results.forEach { add(it) } }
        }.toString()
    }

    private fun parseBackendResult(server: String, raw: String): JsonObject {
        val parsed = try {
            Json.parseToJsonElement(raw).jsonObject
        } catch (_: Exception) {
            null
        }
        if (parsed == null) return buildJsonObject {
            put("server", server)
            put("output", raw)
        }
        val exitCode = (parsed["exit_code"] as? JsonPrimitive)?.content?.toIntOrNull()
        val output = (parsed["output"] as? JsonPrimitive)?.content
        val errMsg = (parsed["message"] as? JsonPrimitive)?.content
        val isError = (parsed["error"] as? JsonPrimitive)?.content == "error"
        return buildJsonObject {
            put("server", server)
            if (isError && errMsg != null) {
                put("error", "error")
                put("message", errMsg)
            } else {
                put("exit_code", exitCode ?: -1)
            }
            if (output != null) put("output", output) else put("output", raw)
        }
    }

    /**
     * Starts a Conch command as a durable job, then treats foreground execution as a bounded wait
     * on that same process. A wait expiry returns ownership to the model through job_id; it never
     * kills or replays the command.
     */

    private fun executeShellCommandEvents(
        arguments: String,
        ctx: GenerationContext,
    ): Flow<ToolExecutionEvent> = flow {
        val args = parseToolArgs(arguments)
        val command = arg(args, "command")
        if (command.isBlank()) {
            emit(ToolExecutionEvent.Completed(jsonError("execute_shell_command", "no_command")))
            return@flow
        }
        val serverName = arg(args, "server")
        if (boolArg(args, "background")) {
            val device = resolveShellDevice(serverName, ctx)?.takeIf { it.type != "ssh" }
            if (device == null) {
                emit(
                    ToolExecutionEvent.Completed(
                        jsonError(
                            "execute_shell_command",
                            conchServerNotFoundMessage(serverName, ctx),
                            server = serverName,
                            command = command,
                        ),
                    ),
                )
                return@flow
            }
            emit(ToolExecutionEvent.TargetResolved(device.name))
            emit(ToolExecutionEvent.Progress("Starting durable background job"))
            // executeShellCommand owns the one confirmation and backend lifecycle.
            emit(ToolExecutionEvent.Completed(executeShellCommand(arguments, ctx)))
            return@flow
        }
        val foregroundMaxMs = Constants.TOOL_EXECUTION_TIMEOUT_MS.toInt()
        val rawTimeout = arg(args, "timeout_ms")
        if (rawTimeout.isBlank()) {
            emit(
                ToolExecutionEvent.Completed(
                    jsonError(
                        "execute_shell_command", "timeout_ms is required",
                        server = serverName, command = command,
                    ),
                ),
            )
            return@flow
        }
        val timeoutMs = (rawTimeout.toIntOrNull()
            ?: run {
                emit(
                    ToolExecutionEvent.Completed(
                        jsonError(
                            "execute_shell_command",
                            "timeout_ms must be an integer, got \"$rawTimeout\"",
                            server = serverName,
                            command = command,
                        ),
                    ),
                )
                return@flow
            }).coerceIn(1000, foregroundMaxMs)
        val workdir = arg(args, "workdir")
        val backend = getBackend(serverName, ctx)
        if (backend == null) {
            emit(
                ToolExecutionEvent.Completed(
                    jsonError("execute_shell_command", serverNotFoundMessage(serverName, ctx)),
                ),
            )
            return@flow
        }
        emit(
            ToolExecutionEvent.TargetResolved(
                backend.device?.name?.ifBlank { "Untitled" } ?: "Local Sandbox",
            ),
        )
        try {
            if (!confirmTarget(
                    backend.device,
                    "$ $command",
                    localSharedStorageExposed =
                        backend.device == null && ctx.sandboxSharedStorageEnabled,
                )
            ) {
                emit(
                    ToolExecutionEvent.Completed(
                        jsonError(
                            "execute_shell_command",
                            "denied_by_user: the user declined to run this command",
                            server = serverName,
                            command = command,
                        ),
                    ),
                )
                return@flow
            }
            if (backend is ConchBackend) {
                emit(ToolExecutionEvent.Progress("Running as a durable foreground job"))
                emit(
                    ToolExecutionEvent.Completed(
                        durableJobs.executeDurableForeground(
                            backend = backend,
                            command = command,
                            workdir = workdir,
                            waitMs = timeoutMs.coerceAtMost(maxWaitMs(ctx)),
                        )
                    )
                )
            } else {
                emit(ToolExecutionEvent.Progress("Running command"))
                backend.executeCommandEvents(command, workdir, timeoutMs).collect { emit(it) }
            }
        } finally {
            backend.close()
        }
    }

    // ── Durable job mutations ──────────────────────────────

    private suspend fun stopShellJob(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val jobId = arg(args, "job_id")
        if (jobId.isBlank()) return jsonError("stop_shell_job", "job_id is required")
        val serverName = arg(args, "server")
        val backend = getConchBackend(serverName, ctx)
            ?: return jsonError(
                "stop_shell_job",
                conchServerNotFoundMessage(serverName, ctx),
                server = serverName,
            )
        if (!confirmTarget(backend.device, "stop background shell job: $jobId")) {
            return jsonError(
                "stop_shell_job",
                "denied_by_user: the user declined to stop this job",
                server = backend.device.name,
            )
        }
        return try {
            backend.stopJob(jobId)
        } catch (e: Exception) {
            jsonError(
                "stop_shell_job",
                e.message ?: "Failed to stop shell job",
                server = backend.device.name,
            )
        }
    }

    // ── File tools ─────────────────────────────────────────

    private suspend fun executeViewImage(
        arguments: String,
        ctx: GenerationContext,
    ): ToolExecutionResult {
        val args = parseToolArgs(arguments)
        val path = arg(args, "path")
        if (path.isBlank()) {
            return ToolExecutionResult(
                text = jsonError("view_image", "path is required"),
                isError = true,
            )
        }
        val serverName = arg(args, "server")
        val backend = getConchBackend(serverName, ctx)
            ?: return ToolExecutionResult(
                text = jsonError(
                    "view_image",
                    conchServerNotFoundMessage(serverName, ctx),
                    server = serverName,
                ),
                isError = true,
            )
        val store = imageStore
            ?: return ToolExecutionResult(
                text = jsonError(
                    "view_image",
                    "Tool image storage is unavailable",
                    server = backend.device.name,
                ),
                isError = true,
            )
        return try {
            val remote = backend.viewImage(path)
            if (remote.error != null) {
                return ToolExecutionResult(
                    text = jsonError(
                        "view_image",
                        remote.error,
                        server = backend.device.name,
                    ),
                    isError = true,
                )
            }
            val attachment = withContext(Dispatchers.IO) {
                store.persistBase64(
                    data = remote.data,
                    mimeType = remote.mimeType,
                    filePrefix = "conch",
                )
            }
            ToolExecutionResult(
                text = buildJsonObject {
                    put("type", "view_image")
                    put("server", backend.device.name)
                    put("path", path)
                    put("mime_type", attachment.mimeType)
                    put("size", attachment.sizeBytes)
                    attachment.width?.let { put("width", it) }
                    attachment.height?.let { put("height", it) }
                    put("ok", true)
                }.toString(),
                images = listOf(attachment),
            )
        } catch (error: Exception) {
            ToolExecutionResult(
                text = jsonError(
                    "view_image",
                    error.message ?: "Failed to load image",
                    server = backend.device.name,
                ),
                isError = true,
            )
        } finally {
            backend.close()
        }
    }

    private suspend fun executeFileRead(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val path = arg(args, "path")
        if (path.isBlank()) return jsonError("file_read", "path is required")
        val serverName = arg(args, "server")
        val offset = arg(args, "offset").toLongOrNull() ?: 0L
        val limit = arg(args, "limit").toLongOrNull() ?: 0L

        val backend = getBackend(serverName, ctx)
            ?: return jsonError("file_read", serverNotFoundMessage(serverName, ctx))
        try {
            return backend.fileRead(path, offset, limit)
        } finally {
            backend.close()
        }
    }

    private suspend fun executeFileWrite(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val path = arg(args, "path")
        if (path.isBlank()) return jsonError("file_write", "path is required")
        val content = arg(args, "content")
        if (content.isBlank()) return jsonError("file_write", "content is required")
        val serverName = arg(args, "server")

        val backend = getBackend(serverName, ctx)
            ?: return jsonError("file_write", serverNotFoundMessage(serverName, ctx))
        try {
            if (!confirmTarget(
                    backend.device,
                    "write file: $path",
                    localSharedStorageExposed =
                        backend.device == null && targetsSharedStorage(path),
                )
            ) {
                return jsonError("file_write", "denied_by_user: the user declined to write this file", server = serverName)
            }
            val error = backend.fileWrite(path, content)
            if (error != null) return error
            return buildJsonObject {
                put("type", "file_write"); put("path", path); put("ok", true)
            }.toString()
        } finally {
            backend.close()
        }
    }

    private suspend fun executeFileEdit(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val path = arg(args, "path")
        if (path.isBlank()) return jsonError("file_edit", "path is required")
        val oldStr = arg(args, "old_string")
        if (oldStr.isBlank()) return jsonError("file_edit", "old_string is required")
        val newStr = arg(args, "new_string")
        val replaceAll = arg(args, "replace_all").equals("true", ignoreCase = true)
        val serverName = arg(args, "server")

        val backend = getBackend(serverName, ctx)
            ?: return jsonError("file_edit", serverNotFoundMessage(serverName, ctx))
        try {
            if (!confirmTarget(
                    backend.device,
                    "edit file: $path",
                    localSharedStorageExposed =
                        backend.device == null && targetsSharedStorage(path),
                )
            ) {
                return jsonError("file_edit", "denied_by_user: the user declined to edit this file", server = serverName)
            }
            // Read the file
            val rawContent = try {
                backend.fileRead(path, 0, 0)
            } catch (e: Exception) {
                return jsonError("file_edit", "read error: ${e.message}")
            }
            // Extract actual content (Conch wraps it in JSON, others return raw text)
            val actualContent = try {
                val obj = Json.parseToJsonElement(rawContent).jsonObject
                (obj["content"] as? JsonPrimitive)?.content ?: rawContent
            } catch (_: Exception) { rawContent }

            val count = actualContent.split(oldStr).size - 1
            if (count == 0) {
                return jsonError("file_edit", "old_string not found in file")
            }
            if (count > 1 && !replaceAll) {
                return jsonError("file_edit", "Found $count matches. Use replace_all=true or provide more context.")
            }
            val replaced = actualContent.replace(oldStr, newStr)
            val writeError = backend.fileWrite(path, replaced)
            if (writeError != null) {
                return jsonError("file_edit", "write error: $writeError")
            }
            return buildJsonObject {
                put("type", "file_edit"); put("path", path)
                put("replaced", if (replaceAll) count else 1)
            }.toString()
        } finally {
            backend.close()
        }
    }

    private suspend fun executeFileGlob(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val pattern = arg(args, "pattern")
        if (pattern.isBlank()) return jsonError("file_glob", "pattern is required")
        val serverName = arg(args, "server")
        val basePath = arg(args, "path")
        // Absent/blank → null → backward-compatible default behavior per backend.
        val depth = arg(args, "depth").toIntOrNull()

        val backend = getBackend(serverName, ctx)
            ?: return jsonError("file_glob", serverNotFoundMessage(serverName, ctx))
        try {
            val result = backend.fileGlob(pattern, basePath, depth)
            return result.fold(
                onSuccess = { files ->
                    buildJsonObject {
                        put("type", "file_glob"); put("pattern", pattern)
                        putJsonArray("files") { files.forEach { add(JsonPrimitive(it)) } }
                    }.toString()
                },
                onFailure = { e -> jsonError("file_glob", e.message ?: "Unknown error") }
            )
        } finally {
            backend.close()
        }
    }

    private suspend fun executeFileGrep(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val pattern = arg(args, "pattern")
        if (pattern.isBlank()) return jsonError("file_grep", "pattern is required")
        val serverName = arg(args, "server")
        val basePath = arg(args, "path")
        val fileGlob = arg(args, "glob")

        val backend = getBackend(serverName, ctx)
            ?: return jsonError("file_grep", serverNotFoundMessage(serverName, ctx))
        try {
            val result = backend.fileGrep(pattern, basePath, fileGlob)
            return result.fold(
                onSuccess = { matches ->
                    buildJsonObject {
                        put("type", "file_grep"); put("pattern", pattern)
                        putJsonArray("matches") {
                            matches.forEach { m ->
                                add(buildJsonObject {
                                    put("path", m.path); put("line", m.line); put("content", m.content)
                                })
                            }
                        }
                    }.toString()
                },
                onFailure = { e -> jsonError("file_grep", e.message ?: "Unknown error") }
            )
        } finally {
            backend.close()
        }
    }

    // ── Process / system monitoring tools ─────────────────

    private suspend fun executeListProcesses(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val serverName = arg(args, "server")
        val maxCount = arg(args, "max_count").toIntOrNull() ?: 50
        val sortBy = arg(args, "sort_by").ifBlank { "cpu" }
        val backend = getBackend(serverName, ctx)
            ?: return jsonError("list_processes", serverNotFoundMessage(serverName, ctx))
        return try {
            monitorTools.listProcesses(backend, maxCount, sortBy)
        } finally {
            backend.close()
        }
    }

    private suspend fun executeKillProcess(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val pid = arg(args, "pid").toIntOrNull()
            ?: return jsonError("kill_process", "pid is required and must be an integer")
        val signal = arg(args, "signal").ifBlank { "TERM" }
        val serverName = arg(args, "server")
        val backend = getBackend(serverName, ctx)
            ?: return jsonError("kill_process", serverNotFoundMessage(serverName, ctx))
        return try {
            if (!confirmTarget(backend.device, "kill -$signal $pid")) {
                return jsonError("kill_process", "denied_by_user: the user declined to kill this process", server = serverName)
            }
            monitorTools.killProcess(backend, pid, signal)
        } finally {
            backend.close()
        }
    }

    private suspend fun executeSystemStats(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val serverName = arg(args, "server")
        val backend = getBackend(serverName, ctx)
            ?: return jsonError("system_stats", serverNotFoundMessage(serverName, ctx))
        return try {
            monitorTools.systemStats(backend)
        } finally {
            backend.close()
        }
    }

    private suspend fun executeTailFollow(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val path = arg(args, "path")
        if (path.isBlank()) return jsonError("tail_follow", "path is required")
        val maxLines = arg(args, "max_lines").toIntOrNull() ?: 100
        val serverName = arg(args, "server")
        val backend = getBackend(serverName, ctx)
            ?: return jsonError("tail_follow", serverNotFoundMessage(serverName, ctx))
        return try {
            monitorTools.tailFollow(backend, path, maxLines)
        } finally {
            backend.close()
        }
    }

    companion object {
        internal fun maxWaitMs(ctx: GenerationContext): Int =
            ShellDurableJobExecutor.maxWaitMs(ctx)
    }
}
