package com.lxseek.chat.runtime

import com.lxseek.chat.util.DebugLog

/**
 * Configuration for a sandboxed execution environment.
 *
 * @param maxExecutionTimeMs Maximum wall-clock time allowed for a single operation.
 * @param maxMemoryBytes Optional memory limit (advisory; not enforced on JVM).
 * @param allowedPaths File-system paths the sandbox is allowed to access.
 * @param blockedCommands Commands that are always rejected.
 */
data class SandboxConfig(
    val maxExecutionTimeMs: Long = 30_000L,
    val maxMemoryBytes: Long? = null,
    val allowedPaths: List<String> = emptyList(),
    val blockedCommands: List<String> = DEFAULT_BLOCKED_COMMANDS,
)

/**
 * Result of a sandboxed execution.
 *
 * @param success Whether the operation completed without error.
 * @param output The output string on success.
 * @param error Error message on failure, or null.
 * @param durationMs Actual execution duration in milliseconds.
 * @param wasBlocked True if the command was rejected by the sandbox policy.
 */
data class SandboxResult(
    val success: Boolean,
    val output: String,
    val error: String?,
    val durationMs: Long,
    val wasBlocked: Boolean,
)

/**
 * Sandboxed execution environment for unsafe tool operations.
 *
 * Inspired by zhikuncode-main's sandbox pattern: provides a layer of
 * policy checks (blocked commands, allowed paths, time limits) around
 * arbitrary tool execution to prevent destructive operations.
 *
 * Note: This is a policy gate, not a full OS-level sandbox. It checks
 * commands against a blocklist and enforces time limits, but does not
 * provide process isolation. For truly untrusted code, use a separate
 * process or container.
 */
class ToolSandbox(
    private val defaultConfig: SandboxConfig = SandboxConfig(),
) {

    /**
     * Executes [command] under the sandbox policy.
     *
     * If [config] is null, the default config is used.
     * Returns a [SandboxResult] indicating success, block, or error.
     */
    fun execute(command: String, config: SandboxConfig? = null): SandboxResult {
        val cfg = config ?: defaultConfig
        val start = System.currentTimeMillis()

        // Check blocked commands
        if (!checkCommand(command, cfg)) {
            DebugLog.w("Sandbox", "Blocked command: $command")
            return SandboxResult(
                success = false,
                output = "",
                error = "Command blocked by sandbox policy",
                durationMs = System.currentTimeMillis() - start,
                wasBlocked = true,
            )
        }

        // For now, the sandbox is a policy gate — actual execution is
        // delegated to the caller via withSandbox. This method returns
        // a placeholder indicating the command passed the policy check.
        return SandboxResult(
            success = true,
            output = "Command passed sandbox policy check",
            error = null,
            durationMs = System.currentTimeMillis() - start,
            wasBlocked = false,
        )
    }

    /**
     * Returns true if [command] is allowed by the sandbox policy.
     *
     * A command is blocked if it matches any entry in [SandboxConfig.blockedCommands]
     * (case-insensitive substring match).
     */
    fun checkCommand(command: String, config: SandboxConfig): Boolean {
        val normalized = command.trim().lowercase()
        return config.blockedCommands.none { blocked ->
            normalized.contains(blocked.lowercase())
        }
    }

    /**
     * Returns true if [path] is allowed by the sandbox policy.
     *
     * If [SandboxConfig.allowedPaths] is empty, all paths are allowed.
     * Otherwise, the path must start with one of the allowed prefixes.
     */
    fun checkPath(path: String, config: SandboxConfig): Boolean {
        if (config.allowedPaths.isEmpty()) return true
        return config.allowedPaths.any { allowed -> path.startsWith(allowed) }
    }

    /**
     * Executes [block] under the sandbox policy with a time limit.
     *
     * If the block completes within the time limit, returns a successful
     * [SandboxResult]. If the block throws, returns a failed result.
     * If the time limit is exceeded, returns a timeout error.
     */
    fun withSandbox(config: SandboxConfig, block: () -> String): SandboxResult {
        val start = System.currentTimeMillis()
        return try {
            val output = block()
            val duration = System.currentTimeMillis() - start
            if (duration > config.maxExecutionTimeMs) {
                DebugLog.w("Sandbox", "Execution exceeded time limit: ${duration}ms > ${config.maxExecutionTimeMs}ms")
                SandboxResult(
                    success = false,
                    output = "",
                    error = "Execution exceeded time limit (${duration}ms > ${config.maxExecutionTimeMs}ms)",
                    durationMs = duration,
                    wasBlocked = false,
                )
            } else {
                SandboxResult(
                    success = true,
                    output = output,
                    error = null,
                    durationMs = duration,
                    wasBlocked = false,
                )
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - start
            DebugLog.e("Sandbox", "Execution failed", e)
            SandboxResult(
                success = false,
                output = "",
                error = e.message,
                durationMs = duration,
                wasBlocked = false,
            )
        }
    }

    companion object {
        /** Commands that are blocked by default. */
        val DEFAULT_BLOCKED_COMMANDS = listOf(
            "rm -rf",
            "format",
            "del /f",
            "mkfs",
            "dd if=",
            "shutdown",
            "reboot",
            ":(){:|:&};:",
        )
    }
}