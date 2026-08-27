package com.lxseek.chat.tool

import com.lxseek.chat.adb.ShizukuManager

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
 *  - **Shizuku**: executes commands through [ShizukuManager] (Rikka Shizuku service).
 *    Requires the Shizuku app to be installed, its service running, and runtime
 *    permission granted. No wireless-debugging pairing or adb binary download needed.
 *
 * File operations (read/write/glob/grep) are implemented as shell commands on top of
 * [executeCommand], so both modes share the same code path.
 */
internal class AdbShellBackend(
    private val rootAvailable: Boolean,
    private val shizukuManager: ShizukuManager?,
) : Backend {

    override val device: ShellDeviceConfig? = null

    // ── Command execution ───────────────────────────────────

    override suspend fun executeCommand(cmd: String, workdir: String, timeoutMs: Int): String {
        // Strip "adb shell" prefix — the backend already provides device shell access,
        // so "adb shell ls" should become "ls". Handles both space and tab separators.
        val strippedCmd = cmd.removePrefix("adb shell ").removePrefix("adb\tshell\t").trim()
        val effectiveCmd = if (strippedCmd.isNotBlank() && strippedCmd != cmd.trim()) strippedCmd else cmd
        val actualCmd = if (workdir.isNotBlank()) "cd $workdir && $effectiveCmd" else effectiveCmd
        return if (rootAvailable) {
            executeRoot(actualCmd, cmd, timeoutMs)
        } else {
            executeShizuku(actualCmd, cmd, timeoutMs)
        }
    }

    private fun executeRoot(actualCmd: String, cmd: String, timeoutMs: Int): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", actualCmd))
            val waitOk = p.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            val output = p.inputStream.bufferedReader().use { it.readText() } +
                p.errorStream.bufferedReader().use { it.readText() }
            // 镜像到进程内日志源，方便在设置页直接看到 root 执行的命令与结果。
            com.lxseek.chat.adb.AdbLog.log(
                "root> $cmd  → exit=${if (waitOk) p.exitValue() else -1} out=${output.trim().take(200)}",
            )
            buildJsonObject {
                put("type", "execute_shell_command")
                put("server", "ADB Shell (root)")
                put("command", cmd)
                put("exit_code", if (waitOk) p.exitValue() else -1)
                put("output", output.trimEnd())
            }.toString()
        } catch (e: Exception) {
            com.lxseek.chat.adb.AdbLog.log("root> $cmd  → exception=${e.javaClass.name}: ${e.message}")
            jsonError("execute_shell_command", e.message ?: "Root execution failed",
                server = "ADB Shell (root)", command = cmd)
        }
    }

    private fun executeShizuku(actualCmd: String, cmd: String, timeoutMs: Int): String {
        val mgr = shizukuManager ?: return jsonError(
            "execute_shell_command", "Shizuku backend not configured",
            server = "ADB Shell", command = cmd,
        )
        if (!mgr.isShizukuInstalled()) {
            return jsonError(
                "execute_shell_command",
                "Shizuku app not installed. Please install Shizuku from Google Play.",
                server = "ADB Shell", command = cmd,
            )
        }
        if (!mgr.isShizukuRunning()) {
            return jsonError(
                "execute_shell_command",
                "Shizuku service not running. Please start Shizuku app.",
                server = "ADB Shell", command = cmd,
            )
        }
        if (!mgr.isPermissionGranted()) {
            return jsonError(
                "execute_shell_command",
                "Shizuku permission not granted. Please authorize in Settings.",
                server = "ADB Shell", command = cmd,
            )
        }
        return try {
            val output = mgr.executeCommand(actualCmd, timeoutMs)
            com.lxseek.chat.adb.AdbLog.log("shizuku> $cmd  → out=${output.trim().take(200)}")
            buildJsonObject {
                put("type", "execute_shell_command")
                put("server", "ADB Shell (Shizuku)")
                put("command", cmd)
                put("exit_code", 0)
                put("output", output)
            }.toString()
        } catch (e: Exception) {
            com.lxseek.chat.adb.AdbLog.log("shizuku> $cmd  → exception=${e.javaClass.name}: ${e.message}")
            jsonError("execute_shell_command", e.message ?: "Shizuku execution failed",
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
        // Shizuku mode: each command is a short-lived Process; nothing to keep alive.
    }
}
