package com.lxseek.chat.tool

import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.viewmodel.GenerationContext

internal object ShellToolDefinitions {
    fun build(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.shellEnabled) return emptyList()
        if (ctx.shellDevices.isEmpty() && !ctx.sandboxEnabled) return emptyList()

        val hasLocal = ctx.sandboxEnabled
        val allDeviceNames = buildList {
            if (hasLocal) add("Local Sandbox")
            addAll(ctx.shellDevices.map { d -> "\"${d.name}\"" })
        }
        val deviceNamesStr = allDeviceNames.joinToString(", ")

        val serverPropDesc = if (allDeviceNames.size == 1) {
            "The shell server name (optional, defaults to the only available server: ${allDeviceNames[0]})."
        } else {
            "The shell server name. Use list_shells to see available servers: $deviceNamesStr."
        }
        // timeout_ms is REQUIRED: the model must decide how long the tool call should wait. Conch
        // foreground execution is durable from the start, so expiry returns its job id without
        // killing or restarting the command.
        val shellRequiredParams =
            if (allDeviceNames.size == 1) listOf("command", "timeout_ms")
            else listOf("command", "server", "timeout_ms")

        val conchDeviceNames = ctx.shellDevices
            .filter { it.type != "ssh" }
            .map { it.name.ifBlank { "Untitled" } }
        val shellTools = buildList {
            add(ToolDefinition(function = ToolFunction(
                name = "list_shells",
                description = "List configured shell servers including the local sandbox (if enabled).",
                parameters = ToolParameters(properties = emptyMap(), required = emptyList())
            )))
            add(ToolDefinition(function = ToolFunction(
                name = "execute_shell_command",
                description = "Execute a shell command. Set background=true for a durable Conch job that survives client disconnects.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "command" to ToolProperty("string", "The shell command to execute."),
                        "server" to ToolProperty("string", serverPropDesc),
                        "timeout_ms" to ToolProperty("integer", "Required. Foreground wait budget in milliseconds (hard ceiling 295000ms inside the tool call). On Conch, a command still running at that point continues as the same durable job and returns its job_id; it is never killed or restarted. With background=true this instead bounds the durable job's runtime (up to Conch policy)."),
                        "workdir" to ToolProperty("string", "Working directory (optional)."),
                        "background" to ToolProperty("boolean", "Start a durable background job on Conch and return its job_id immediately (optional, default false)."),
                    ),
                    required = shellRequiredParams
                )
            )))
            add(ToolDefinition(function = ToolFunction(
                name = "execute_shell_batch",
                description = "Execute the same shell command on multiple servers in parallel and return aggregated results. Use this instead of multiple execute_shell_command calls when you need to run the same command across several servers (e.g. checking disk space on all nodes, rolling out a config change). Each server is gated by the same confirmation policy as execute_shell_command.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "command" to ToolProperty("string", "The shell command to execute on all target servers."),
                        "servers" to ToolProperty(
                            type = "array",
                            description = "List of server names to execute the command on. Each must be a valid server from list_shells. Cannot include \"Local Sandbox\".",
                            items = ToolProperty("string", "Server name")
                        ),
                        "timeout_ms" to ToolProperty("integer", "Per-server wait budget in milliseconds (hard ceiling 295000ms)."),
                        "workdir" to ToolProperty("string", "Working directory (optional, applied to all servers)."),
                    ),
                    required = listOf("command", "servers", "timeout_ms")
                )
            )))
            add(ToolDefinition(function = ToolFunction(
                name = "list_processes",
                description = "List running processes on a shell server with structured output (pid, user, cpu%, mem%, command). More reliable than parsing raw ps output.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "server" to ToolProperty("string", serverPropDesc),
                        "max_count" to ToolProperty("integer", "Maximum number of processes to return (optional, default 50, max 200)."),
                        "sort_by" to ToolProperty("string", "Sort field: 'cpu' (default), 'mem', or 'pid'."),
                    ),
                    required = emptyList()
                )
            )))
            add(ToolDefinition(function = ToolFunction(
                name = "kill_process",
                description = "Send a signal to a process by PID. Default signal is TERM. Use KILL for force-kill. Always verify the PID with list_processes first.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "pid" to ToolProperty("integer", "Process ID to signal."),
                        "signal" to ToolProperty("string", "Signal name (optional, default TERM). Common: TERM, KILL, HUP, INT, STOP, CONT."),
                        "server" to ToolProperty("string", serverPropDesc),
                    ),
                    required = listOf("pid")
                )
            )))
            add(ToolDefinition(function = ToolFunction(
                name = "system_stats",
                description = "Get a structured system resource snapshot: load average, memory, disk usage, uptime. More reliable than parsing raw uptime/free/df output.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "server" to ToolProperty("string", serverPropDesc),
                    ),
                    required = emptyList()
                )
            )))
            add(ToolDefinition(function = ToolFunction(
                name = "tail_follow",
                description = "Read the last N lines of a file (like tail -n). For continuous following, use execute_shell_command with background=true and 'tail -f'.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "path" to ToolProperty("string", "Absolute path to the file."),
                        "max_lines" to ToolProperty("integer", "Number of lines to read from the end (optional, default 100, max 10000)."),
                        "server" to ToolProperty("string", serverPropDesc),
                    ),
                    required = listOf("path")
                )
            )))
            if (conchDeviceNames.isNotEmpty()) {
                val jobServerDescription = if (conchDeviceNames.size == 1) {
                    "Conch server name (optional; defaults to ${conchDeviceNames.single()})."
                } else {
                    "Conch server name. Available: ${conchDeviceNames.joinToString(", ")}."
                }
                val jobRequired = if (conchDeviceNames.size == 1) {
                    emptyList()
                } else {
                    listOf("server")
                }
                add(ToolDefinition(function = ToolFunction(
                    name = "list_shell_jobs",
                    description = "List durable background shell jobs on a Conch server.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "server" to ToolProperty("string", jobServerDescription),
                        ),
                        required = jobRequired,
                    ),
                )))
                add(ToolDefinition(function = ToolFunction(
                    name = "get_shell_job",
                    description = "Get status and bounded output for a durable Conch shell job. Prefer wait_for_job for blocking use.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "job_id" to ToolProperty("string", "The Conch job id."),
                            "server" to ToolProperty("string", jobServerDescription),
                        ),
                        required = listOf("job_id") + jobRequired,
                    ),
                )))
                add(ToolDefinition(function = ToolFunction(
                    name = "wait_for_job",
                    description = "Block until a durable Conch shell job finishes or timeout_ms elapses, then return its final output. Preferred over polling get_shell_job. If it returns timed_out=true the job is still running — call wait_for_job again to keep waiting.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "job_id" to ToolProperty("string", "The Conch job id."),
                            "timeout_ms" to ToolProperty("integer", "Required. Maximum time to block in milliseconds before returning (whether or not the job finished). The hard ceiling for a single call is ${ShellDurableJobExecutor.maxWaitMs(ctx)}ms; larger values are clamped to it and the result says so. To wait longer, call wait_for_job again."),
                            "server" to ToolProperty("string", jobServerDescription),
                        ),
                        required = listOf("job_id", "timeout_ms") + jobRequired,
                    ),
                )))
                add(ToolDefinition(function = ToolFunction(
                    name = "stop_shell_job",
                    description = "Stop a running durable Conch shell job and its process tree.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "job_id" to ToolProperty("string", "The Conch job id."),
                            "server" to ToolProperty("string", jobServerDescription),
                        ),
                        required = listOf("job_id") + jobRequired,
                    ),
                )))
            }
        }

        val fileServerProperty = if (allDeviceNames.size == 1) {
            ToolProperty("string", "The shell server name (optional, defaults to the only available server).")
        } else {
            ToolProperty("string", "The shell server name. Available: $deviceNamesStr.")
        }
        val fileRequired = if (allDeviceNames.size == 1) emptyList<String>() else listOf("server")

        val fileTools = listOf(
            ToolDefinition(function = ToolFunction(
                name = "file_read",
                description = "Read a file from a shell server or local sandbox.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "path" to ToolProperty("string", "Absolute path to the file."),
                        "server" to fileServerProperty,
                        "offset" to ToolProperty("integer", "Byte offset (optional)."),
                        "limit" to ToolProperty("integer", "Max bytes to read (optional, default 1MB).")
                    ),
                    required = listOf("path") + fileRequired
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "file_write",
                description = "Write content to a file on a shell server or local sandbox.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "path" to ToolProperty("string", "Absolute path to the file."),
                        "content" to ToolProperty("string", "Content to write."),
                        "server" to fileServerProperty
                    ),
                    required = listOf("path", "content") + fileRequired
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "file_edit",
                description = "Edit a file on a shell server or local sandbox by replacing old_string with new_string.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "path" to ToolProperty("string", "Absolute path to the file."),
                        "old_string" to ToolProperty("string", "The exact text to find and replace."),
                        "new_string" to ToolProperty("string", "The replacement text."),
                        "server" to fileServerProperty,
                        "replace_all" to ToolProperty("boolean", "Replace all occurrences (optional, default false).")
                    ),
                    required = listOf("path", "old_string", "new_string") + fileRequired
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "file_glob",
                description = "List files on a shell server or local sandbox matching a glob pattern.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "pattern" to ToolProperty("string", "Glob pattern matched against file names (e.g. '*.go', '*.md')."),
                        "server" to fileServerProperty,
                        "path" to ToolProperty("string", "Base directory for the search (optional)."),
                        "depth" to ToolProperty("integer", "Max directory levels to search below 'path': 1 = base directory only, higher values recurse deeper, 0 = unlimited recursion. Omit for the server default.")
                    ),
                    required = listOf("pattern") + fileRequired
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "file_grep",
                description = "Search for a regex pattern in files on a shell server or local sandbox.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "pattern" to ToolProperty("string", "Regular expression pattern to search for."),
                        "server" to fileServerProperty,
                        "path" to ToolProperty("string", "File or directory to search in (optional)."),
                        "glob" to ToolProperty("string", "Filter files by glob pattern (optional).")
                    ),
                    required = listOf("pattern") + fileRequired
                )
            ))
        )

        val imageTools = if (conchDeviceNames.isEmpty()) {
            emptyList()
        } else {
            val imageServerDescription = if (conchDeviceNames.size == 1) {
                "Conch server name (optional; defaults to ${conchDeviceNames.single()})."
            } else {
                "Conch server name. Available: ${conchDeviceNames.joinToString(", ")}."
            }
            val imageRequired = if (conchDeviceNames.size == 1) {
                listOf("path")
            } else {
                listOf("path", "server")
            }
            listOf(
                ToolDefinition(
                    function = ToolFunction(
                        name = "view_image",
                        description =
                            "Load an image from a Conch device and return it as visual context.",
                        parameters = ToolParameters(
                            properties = mapOf(
                                "path" to ToolProperty(
                                    "string",
                                    "Absolute path to the image file.",
                                ),
                                "server" to ToolProperty("string", imageServerDescription),
                            ),
                            required = imageRequired,
                        ),
                    ),
                ),
            )
        }

        return shellTools + fileTools + imageTools
    }
}
