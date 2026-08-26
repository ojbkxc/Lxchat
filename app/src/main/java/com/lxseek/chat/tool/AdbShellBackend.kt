package com.lxseek.chat.tool

import com.lxseek.chat.adb.LadbManager

import com.lxseek.chat.data.ShellDeviceConfig
import com.lxseek.chat.util.ShellClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit

/**
 * Shell backend that executes commands via local ADB.
 *
 * Two modes:
 *  - **Root**: runs `su -c "cmd"` directly — zero configuration, no download needed.
 *  - **Wireless (LADB)**: pipes commands through a long-lived `adb shell` process
 *    managed by [LadbManager]. Requires the adb binary to be downloaded and the device
 *    paired once via wireless debugging.
 *
 * File operations (read/write/glob/grep) are implemented as shell commands on top of
 * [executeCommand], so both modes share the same code path.
 */
internal class AdbShellBackend(
    private val rootAvailable: Boolean,
    private val ladbManager: LadbManager?,
) : Backend {

    override val device: ShellDeviceConfig? = null

    // ── Command execution ───────────────────────────────────

    override suspend fun executeCommand(cmd: String, workdir: String, timeoutMs: Int): String {
        val actualCmd = if (workdir.isNotBlank()) "cd $workdir && $cmd" else cmd
        return if (rootAvailable) {
            executeRoot(actualCmd, cmd, timeoutMs)
        } else {
            executeLadb(actualCmd, cmd)
        }
    }

    private fun executeRoot(actualCmd: String, cmd: String, timeoutMs: Int): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", actualCmd))
            p.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            val output = p.inputStream.bufferedReader().use { it.readText() } +
                p.errorStream.bufferedReader().use { it.readText() }
            buildJsonObject {
                put("type", "execute_shell_command")
                put("server", "ADB Shell (root)")
                put("command", cmd)
                put("exit_code", p.exitValue())
                put("output", output.trimEnd())
            }.toString()
        } catch (e: Exception) {
            jsonError("execute_shell_command", e.message ?: "Root execution failed",
                server = "ADB Shell (root)", command = cmd)
        }
    }

    private fun executeLadb(actualCmd: String, cmd: String): String {
        val mgr = ladbManager ?: return jsonError(
            "execute_shell_command", "ADB extension not installed",
            server = "ADB Shell", command = cmd,
        )
        return if (mgr.isRunning()) {
            val output = mgr.sendCommand(actualCmd)
            buildJsonObject {
                put("type", "execute_shell_command")
                put("server", "ADB Shell (wireless)")
                put("command", cmd)
                put("exit_code", 0)
                put("output", output)
            }.toString()
        } else {
            jsonError("execute_shell_command",
                "ADB not connected. Please pair first or reconnect.",
                server = "ADB Shell", command = cmd)
        }
    }

    // ── File operations (implemented via shell commands) ─────

    override suspend fun fileRead(path: String, offset: Long, limit: Long): String {
        val cmd = if (offset > 0 || limit > 0) {
            val count = if (limit > 0) limit else Long.MAX_VALUE
            "dd if=\"$path\" bs=1 skip=$offset count=$count 2>/dev/null"
        } else {
            "cat \"$path\""
        }
        val raw = executeCommand(cmd, "", 30000)
        return try {
            val obj = Json.parseToJsonElement(raw).jsonObject
            val content = (obj["output"] as? JsonPrimitive)?.content ?: ""
            buildJsonObject {
                put("type", "file_read")
                put("server", obj["server"] ?: JsonPrimitive("ADB Shell"))
                put("path", path)
                put("content", content)
                put("lines", content.lines().size)
            }.toString()
        } catch (_: Exception) {
            jsonError("file_read", "Failed to read $path", server = "ADB Shell")
        }
    }

    override suspend fun fileWrite(path: String, content: String): String? {
        // Use a heredoc to avoid shell-escaping issues with arbitrary content.
        val cmd = "cat > \"$path\" <<'__LXADB_EOF__'\n$content\n__LXADB_EOF__"
        val raw = executeCommand(cmd, "", 30000)
        return try {
            val obj = Json.parseToJsonElement(raw).jsonObject
            val err = (obj["error"] as? JsonPrimitive)?.content
            if (err != null) jsonError("file_write", "Write failed: $err", server = "ADB Shell")
            else null
        } catch (_: Exception) {
            jsonError("file_write", "Write failed for $path", server = "ADB Shell")
        }
    }

    override suspend fun fileGlob(pattern: String, basePath: String, depth: Int?): Result<List<String>> {
        val depthArg = if (depth != null && depth > 0) "-maxdepth $depth" else ""
        val cmd = "find \"$basePath\" -name \"$pattern\" $depthArg 2>/dev/null"
        val raw = executeCommand(cmd, "", 30000)
        return try {
            val obj = Json.parseToJsonElement(raw).jsonObject
            val output = (obj["output"] as? JsonPrimitive)?.content ?: ""
            Result.success(output.lines().filter { it.isNotBlank() })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fileGrep(
        pattern: String,
        basePath: String,
        fileGlob: String,
    ): Result<List<ShellClient.GrepMatch>> {
        val includeArg = if (fileGlob.isNotBlank()) "--include=\"$fileGlob\"" else ""
        val cmd = "grep -rn \"$pattern\" \"$basePath\" $includeArg 2>/dev/null"
        val raw = executeCommand(cmd, "", 30000)
        return try {
            val obj = Json.parseToJsonElement(raw).jsonObject
            val output = (obj["output"] as? JsonPrimitive)?.content ?: ""
            val matches = output.lines().filter { it.isNotBlank() }.mapNotNull { line ->
                // grep -rn format: path:lineNum:content
                val firstColon = line.indexOf(':')
                val secondColon = line.indexOf(':', firstColon + 1)
                if (firstColon < 0 || secondColon < 0) return@mapNotNull null
                val path = line.substring(0, firstColon)
                val lineNum = line.substring(firstColon + 1, secondColon).toIntOrNull() ?: return@mapNotNull null
                val content = line.substring(secondColon + 1)
                ShellClient.GrepMatch(path, lineNum, content)
            }
            Result.success(matches)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun close() {
        // Root mode: nothing to close (each command is a standalone su -c process).
        // LADB mode: the shell process is managed by LadbManager singleton; do not kill it
        // here so subsequent commands can reuse the same connection.
    }
}