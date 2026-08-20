package com.lxseek.chat.tool

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import com.lxseek.chat.util.ShellQuote

/**
 * Structured process / system monitoring tools that wrap shell commands with JSON parsing.
 * Each tool runs a command via the existing [Backend] abstraction and parses the text output
 * into structured JSON so the model can reliably interpret the results instead of regex-guessing
 * raw terminal text.
 */
internal class ShellMonitorTools {

    /**
     * Lists running processes. Uses `ps` with portable flags, falling back across common
     * Android/Linux variants. Returns a JSON array of {pid, user, cpu, mem, command}.
     */
    suspend fun listProcesses(
        backend: Backend,
        maxCount: Int,
        sortBy: String,
    ): String = try {
        val limit = maxCount.coerceIn(1, 200)
        val sortFlag = when (sortBy) {
            "mem" -> "-%mem"
            "pid" -> "pid"
            else -> "-%cpu"
        }
        val cmd = buildPsCommand(limit, sortFlag)
        val raw = backend.executeCommand(cmd, "", 10_000)
        val text = extractOutputText(raw)
        val processes = parsePsOutput(text, limit)
        buildJsonObject {
            put("type", "list_processes")
            put("sort_by", sortBy)
            put("count", processes.size)
            putJsonArray("processes") {
                processes.forEach { p ->
                    add(buildJsonObject {
                        put("pid", p.pid)
                        put("user", p.user)
                        put("cpu", p.cpu)
                        put("mem", p.mem)
                        put("command", p.command)
                    })
                }
            }
        }.toString()
    } catch (e: Exception) {
        jsonError("list_processes", e.message ?: "Failed to list processes")
    }

    /**
     * Sends a signal to a process by PID. Default signal is TERM (15).
     */
    suspend fun killProcess(
        backend: Backend,
        pid: Int,
        signal: String,
    ): String = try {
        val sig = validateSignal(signal)
        val safePid = pid.coerceAtLeast(1)
        val cmd = if (sig == "TERM") "kill $safePid" else "kill -$sig $safePid"
        val raw = backend.executeCommand(cmd, "", 5_000)
        val text = extractOutputText(raw)
        val exitCode = extractExitCode(raw)
        if (exitCode == 0 || text.isBlank()) {
            buildJsonObject {
                put("type", "kill_process")
                put("pid", pid)
                put("signal", sig)
                put("success", true)
            }.toString()
        } else {
            buildJsonObject {
                put("type", "kill_process")
                put("pid", pid)
                put("signal", sig)
                put("success", false)
                put("error", text.trim())
            }.toString()
        }
    } catch (e: Exception) {
        jsonError("kill_process", e.message ?: "Failed to kill process")
    }

    /**
     * Collects a one-shot system resource snapshot: load average, CPU usage, memory, disk.
     */
    suspend fun systemStats(backend: Backend): String = try {
        val loadAvg = runCatching {
            extractOutputText(backend.executeCommand("cat /proc/loadavg", "", 5_000))
        }.getOrDefault("").trim()

        val memInfo = runCatching {
            extractOutputText(backend.executeCommand("free -b 2>/dev/null || cat /proc/meminfo", "", 5_000))
        }.getOrDefault("").trim()

        val diskInfo = runCatching {
            extractOutputText(backend.executeCommand("df -B1 2>/dev/null | head -20", "", 5_000))
        }.getOrDefault("").trim()

        val uptime = runCatching {
            extractOutputText(backend.executeCommand("cat /proc/uptime 2>/dev/null || uptime", "", 5_000))
        }.getOrDefault("").trim()

        buildJsonObject {
            put("type", "system_stats")
            put("loadavg", parseLoadAvg(loadAvg))
            put("memory", parseMemInfo(memInfo))
            putJsonArray("disk") {
                parseDiskInfo(diskInfo).forEach { d ->
                    add(buildJsonObject {
                        put("filesystem", d.filesystem)
                        put("total", d.total)
                        put("used", d.used)
                        put("available", d.available)
                        put("mount", d.mount)
                    })
                }
            }
            put("uptime_seconds", parseUptime(uptime))
        }.toString()
    } catch (e: Exception) {
        jsonError("system_stats", e.message ?: "Failed to get system stats")
    }

    /**
     * Reads the last [maxLines] lines of a file (like `tail -n`). For continuous following,
     * the model should use execute_shell_command with background=true and `tail -f`.
     */
    suspend fun tailFollow(
        backend: Backend,
        path: String,
        maxLines: Int,
    ): String = try {
        val lines = maxLines.coerceIn(1, 10_000)
        val safePath = ShellQuote.sanitize(path)
        val cmd = "tail -n $lines ${ShellQuote.quote(safePath)} 2>&1"
        val raw = backend.executeCommand(cmd, "", 10_000)
        val text = extractOutputText(raw)
        val exitCode = extractExitCode(raw)
        buildJsonObject {
            put("type", "tail_follow")
            put("path", path)
            put("lines_requested", lines)
            put("exit_code", exitCode)
            put("content", text)
        }.toString()
    } catch (e: Exception) {
        jsonError("tail_follow", e.message ?: "Failed to tail file")
    }

    // ── Command builders ───────────────────────────────────

    private fun buildPsCommand(limit: Int, sortFlag: String): String {
        return tryPsAux(limit, sortFlag)
    }

    private fun tryPsAux(limit: Int, sortFlag: String): String {
        return when {
            sortFlag.startsWith("-") -> "ps aux --sort=$sortFlag 2>/dev/null | head -n $limit || ps -eo pid,user,%cpu,%mem,comm 2>/dev/null | head -n $limit || ps 2>/dev/null | head -n $limit"
            else -> "ps -eo pid,user,%cpu,%mem,comm 2>/dev/null | head -n $limit || ps aux 2>/dev/null | head -n $limit || ps 2>/dev/null | head -n $limit"
        }
    }

    // ── Output parsers ─────────────────────────────────────

    private data class ProcessInfo(
        val pid: Int,
        val user: String,
        val cpu: String,
        val mem: String,
        val command: String,
    )

    private fun parsePsOutput(text: String, limit: Int): List<ProcessInfo> {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val result = mutableListOf<ProcessInfo>()
        for (i in lines.indices) {
            if (i == 0 && lines[0].contains("PID", ignoreCase = true)) continue
            if (result.size >= limit) break
            val parts = lines[i].trim().split(Regex("\\s+"))
            if (parts.size < 2) continue
            val pid = parts[0].toIntOrNull() ?: continue
            val user = parts.getOrElse(1) { "?" }
            val cpu = parts.getOrElse(2) { "0.0" }.removeSuffix("%")
            val mem = parts.getOrElse(3) { "0.0" }.removeSuffix("%")
            val command = if (parts.size > 4) parts.drop(4).joinToString(" ") else parts.getOrElse(4) { "?" }
            result.add(ProcessInfo(pid, user, cpu, mem, command.take(200)))
        }
        return result
    }

    private data class DiskInfo(
        val filesystem: String,
        val total: Long,
        val used: Long,
        val available: Long,
        val mount: String,
    )

    private fun parseDiskInfo(text: String): List<DiskInfo> {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val result = mutableListOf<DiskInfo>()
        for (i in lines.indices) {
            if (i == 0 && lines[0].contains("Filesystem", ignoreCase = true)) continue
            val parts = lines[i].trim().split(Regex("\\s+"))
            if (parts.size < 6) continue
            val total = parts[1].toLongOrNull() ?: continue
            val used = parts[2].toLongOrNull() ?: continue
            val avail = parts[3].toLongOrNull() ?: continue
            result.add(DiskInfo(parts[0], total, used, avail, parts.last()))
        }
        return result
    }

    private fun parseLoadAvg(text: String): String {
        val parts = text.trim().split(Regex("\\s+"))
        return if (parts.size >= 3) "${parts[0]} ${parts[1]} ${parts[2]}" else text.take(100)
    }

    private fun parseMemInfo(text: String): String {
        val lines = text.lines()
        if (lines.isEmpty()) return text.take(500)
        if (lines.any { it.contains("total", ignoreCase = true) && it.contains("used", ignoreCase = true) }) {
            return lines.take(3).joinToString("\n")
        }
        return lines.take(10).joinToString("\n")
    }

    private fun parseUptime(text: String): String {
        val parts = text.trim().split(Regex("\\s+"))
        return parts.firstOrNull { it.toDoubleOrNull() != null } ?: text.take(50)
    }

    // ── Utilities ──────────────────────────────────────────

    private fun validateSignal(signal: String): String {
        val upper = signal.uppercase()
        return if (upper in VALID_SIGNALS) upper else "TERM"
    }

    private fun extractOutputText(raw: String): String {
        return try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(raw).let {
                (it as? kotlinx.serialization.json.JsonObject) ?: return raw
            }
            (obj["output"] as? JsonPrimitive)?.content
                ?: (obj["content"] as? JsonPrimitive)?.content
                ?: raw
        } catch (_: Exception) { raw }
    }

    private fun extractExitCode(raw: String): Int {
        return try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(raw).let {
                (it as? kotlinx.serialization.json.JsonObject) ?: return 0
            }
            (obj["exit_code"] as? JsonPrimitive)?.content?.toIntOrNull()
                ?: (obj["exitCode"] as? JsonPrimitive)?.content?.toIntOrNull()
                ?: 0
        } catch (_: Exception) { 0 }
    }

    companion object {
        private val VALID_SIGNALS = setOf(
            "HUP", "INT", "QUIT", "ILL", "TRAP", "ABRT", "BUS", "FPE", "KILL", "USR1",
            "SEGV", "USR2", "PIPE", "ALRM", "TERM", "STKFLT", "CHLD", "CONT", "STOP",
            "TSTP", "TTIN", "TTOU", "URG", "XCPU", "XFSZ", "VTALRM", "PROF", "WINCH",
            "IO", "PWR", "SYS",
        )
    }
}
