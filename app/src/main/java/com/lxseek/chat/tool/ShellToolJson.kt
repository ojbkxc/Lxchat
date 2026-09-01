package com.lxseek.chat.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit

internal fun parseToolArgs(arguments: String): Map<String, JsonElement> {
    return try {
        val argsStr = arguments.ifBlank { "{}" }
        Json.decodeFromString<Map<String, JsonElement>>(argsStr)
    } catch (_: Exception) { emptyMap() }
}

internal fun jsonError(type: String, message: String, server: String? = null, command: String? = null): String {
    return buildJsonObject {
        if (type.isNotBlank()) put("type", type)
        put("error", "error"); put("message", message)
        if (server != null) put("server", server)
        if (command != null) put("command", command)
    }.toString()
}

internal fun arg(args: Map<String, JsonElement>, key: String): String {
    return (args[key] as? JsonPrimitive)?.content ?: ""
}

internal fun boolArg(args: Map<String, JsonElement>, key: String): Boolean =
    (args[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true

/** root shell 执行结果（runRoot 的返回值）。 */
internal data class RootResult(val exitCode: Int, val output: String)

/**
 * 以 root（su -c）执行命令并等待结束。stdout/stderr 在 waitFor 之前就开始在
 * 工作线程上读取，避免管道缓冲区写满导致子进程被阻塞的经典死锁。
 * 超时则 destroyForcibly，读取线程最多再等 1s。
 */
internal fun runRoot(cmd: String, timeoutMs: Int): RootResult {
    return try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val stdoutHolder = arrayOf("")
        val stderrHolder = arrayOf("")
        val stdoutThread = Thread {
            stdoutHolder[0] = try { p.inputStream.bufferedReader().use { it.readText() } } catch (_: Exception) { "" }
        }.also { it.start() }
        val stderrThread = Thread {
            stderrHolder[0] = try { p.errorStream.bufferedReader().use { it.readText() } } catch (_: Exception) { "" }
        }.also { it.start() }
        val waitOk = p.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        if (!waitOk) p.destroyForcibly()
        stdoutThread.join(1_000)
        stderrThread.join(1_000)
        val output = (stdoutHolder[0] + stderrHolder[0]).trim()
        val exitCode = if (waitOk) runCatching { p.exitValue() }.getOrDefault(-1) else -1
        RootResult(exitCode, output)
    } catch (e: Exception) {
        RootResult(-1, "error: ${e.message}")
    }
}

/** runRoot + 统一 JSON 结果包装（type/command/exit_code/output，output 截断到 [maxOutput]）。 */
internal fun rootToolResult(name: String, cmd: String, res: RootResult, maxOutput: Int): String {
    return buildJsonObject {
        put("type", name)
        put("command", cmd)
        put("exit_code", res.exitCode)
        put("output", res.output.take(maxOutput))
    }.toString()
}

/** POSIX 单引号转义（shellQuote）。 */
internal fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
