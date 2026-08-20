package com.lxseek.chat.tool

import com.lxseek.chat.api.HttpClient
import com.lxseek.chat.data.ShellDeviceConfig
import com.lxseek.chat.sandbox.SandboxManager
import com.lxseek.chat.util.ShellClient
import com.lxseek.chat.util.SshClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal sealed interface Backend {
    /** The remote device this backend targets, or null for the local sandbox (never gated). */
    val device: ShellDeviceConfig?
    suspend fun executeCommand(cmd: String, workdir: String, timeoutMs: Int): String
    fun executeCommandEvents(
        cmd: String,
        workdir: String,
        timeoutMs: Int,
    ): Flow<ToolExecutionEvent> = flow {
        emit(ToolExecutionEvent.Completed(executeCommand(cmd, workdir, timeoutMs)))
    }
    suspend fun fileRead(path: String, offset: Long, limit: Long): String
    suspend fun fileWrite(path: String, content: String): String?
    suspend fun fileGlob(pattern: String, basePath: String, depth: Int?): Result<List<String>>
    suspend fun fileGrep(pattern: String, basePath: String, fileGlob: String): Result<List<ShellClient.GrepMatch>>
    fun close()
}

internal class ConchBackend(override val device: ShellDeviceConfig) : Backend {
    private val url = device.serverUrl.trimEnd('/')
    private val apiKey = device.apiKey
    private val pubKey = device.conchPublicKey
    private val deviceName = device.name

    private val client: ShellClient by lazy { ShellClient(url, apiKey, pubKey) }

    override suspend fun executeCommand(cmd: String, workdir: String, timeoutMs: Int): String =
        executeCommandInternal(cmd, workdir, timeoutMs) { }

    override fun executeCommandEvents(
        cmd: String,
        workdir: String,
        timeoutMs: Int,
    ): Flow<ToolExecutionEvent> = flow {
        val result = executeCommandInternal(cmd, workdir, timeoutMs) { delta ->
            emit(ToolExecutionEvent.OutputDelta(delta))
        }
        emit(ToolExecutionEvent.Completed(result))
    }

    private suspend fun executeCommandInternal(
        cmd: String,
        workdir: String,
        timeoutMs: Int,
        onOutput: suspend (String) -> Unit,
    ): String {
        if (url.isBlank()) return jsonError("execute_shell_command", "Server \"$deviceName\" has no URL configured.")
        if (!client.fetchPublicKey() && apiKey.isNotBlank()) {
            return jsonError(
                "execute_shell_command",
                client.lastError ?: "Conch public-key exchange failed for $url",
                server = deviceName,
            )
        }
        val prepared = client.prepareRequest(cmd, timeoutMs, workdir)
        val handle = try {
            HttpClient.streamPost(
                "${prepared.serverUrl}/execute",
                prepared.body,
                prepared.headers,
            )
        } catch (e: Exception) {
            return jsonError(
                "execute_shell_command",
                com.lxseek.chat.util.describeConchRequestFailure(
                    prepared.serverUrl,
                    "/execute request",
                    e,
                ),
                server = deviceName,
                command = cmd,
            )
        }
        if (handle.code !in 200..299) {
            val detail = handle.errorBody
                ?.take(240)
                ?.ifBlank { "empty response" }
                ?: "empty response"
            handle.close()
            return jsonError(
                "execute_shell_command",
                "Conch at ${prepared.serverUrl} returned HTTP ${handle.code}: $detail",
                server = deviceName,
                command = cmd,
            )
        }
        return try {
            val output = StringBuilder()
            var exitCode: Int? = null
            var errorMessage: String? = null
            // Conch's structured discriminator for "the deadline killed the process".
            // Never infer this from the message text: a command's OWN timeout (curl's
            // "Operation timed out", a Go "i/o timeout") reads identically and would cause a
            // non-idempotent command to be silently re-run as a background job.
            var timedOut = false
            // Non-fatal degradation (currently output truncation). Kept apart from
            // errorMessage so a truncated line still reports the command's real exit code
            // instead of relabelling a successful command as execution_error.
            var warningMessage: String? = null
            var currentEvent: String? = null
            val aesKey = client.getSessionKey()
            stream@ while (currentCoroutineContext().isActive) {
                val line = handle.readLine() ?: break
                when {
                    line.startsWith("event: ") -> currentEvent = line.substring(7).trim()
                    line.startsWith("data: ") -> {
                        var dataStr = line.substring(6).trim()
                        if (aesKey != null) {
                            try {
                                dataStr = client.decryptSseData(dataStr)
                            } catch (e: Exception) {
                                errorMessage =
                                    "Conch stream decryption failed at $url: " +
                                        (e.message ?: e.javaClass.simpleName)
                                break@stream
                            }
                        }
                        val dataJson = try { Json.parseToJsonElement(dataStr).jsonObject } catch (_: Exception) { null } ?: continue
                        when (currentEvent) {
                            "line" -> {
                                val text = (dataJson["line"] as? JsonPrimitive)?.content
                                if (text != null) {
                                    val delta = "$text\n"
                                    output.append(delta)
                                    onOutput(delta)
                                }
                            }
                            "result" -> exitCode = (dataJson["exit_code"] as? JsonPrimitive)?.content?.toIntOrNull()
                            "warning" -> {
                                if (warningMessage == null) {
                                    warningMessage =
                                        (dataJson["message"] as? JsonPrimitive)?.content
                                }
                            }
                            "error" -> {
                                errorMessage = (dataJson["message"] as? JsonPrimitive)?.content
                                timedOut = (dataJson["timed_out"] as? JsonPrimitive)
                                    ?.content?.toBooleanStrictOrNull() == true
                            }
                        }
                    }
                }
            }
            buildJsonObject {
                put("type", "execute_shell_command"); put("server", deviceName); put("command", cmd)
                if (errorMessage != null) { put("error", "execution_error"); put("message", errorMessage); if (timedOut) put("timed_out", true) }
                else { put("exit_code", exitCode ?: -1) }
                // Emitted alongside a normal exit code on purpose: the output is incomplete but
                // the command itself succeeded or failed on its own terms.
                warningMessage?.let { put("warning", it) }
                put("output", output.toString().trimEnd())
            }.toString()
        } catch (e: Exception) {
            jsonError("execute_shell_command", e.message ?: "Unknown error", server = deviceName, command = cmd)
        } finally { handle.close() }
    }

    override suspend fun fileRead(path: String, offset: Long, limit: Long): String {
        val result = client.fileRead(path, offset, limit)
        if (result.error != null) return jsonError("file_read", result.error, server = deviceName)
        return buildJsonObject {
            put("type", "file_read"); put("server", deviceName); put("path", path)
            put("content", result.content); put("lines", result.lines)
        }.toString()
    }

    override suspend fun fileWrite(path: String, content: String): String? =
        client.fileWrite(path, content)?.let { jsonError("file_write", it, server = deviceName) }

    override suspend fun fileGlob(pattern: String, basePath: String, depth: Int?): Result<List<String>> =
        client.fileGlob(pattern, basePath, depth)

    override suspend fun fileGrep(pattern: String, basePath: String, fileGlob: String): Result<List<ShellClient.GrepMatch>> =
        client.fileGrep(pattern, basePath, fileGlob)

    override fun close() {}

    suspend fun startJob(cmd: String, workdir: String, timeoutMs: Int): String =
        client.startJob(cmd, timeoutMs, workdir)

    suspend fun listJobs(): String = client.listJobs()

    suspend fun getJob(jobId: String): String = client.getJob(jobId)

    suspend fun stopJob(jobId: String): String = client.stopJob(jobId)

    suspend fun viewImage(path: String): ShellClient.FileImageResult =
        client.fileImage(path)
}

internal class SshBackend(override val device: ShellDeviceConfig) : Backend {
    private val host = device.sshHost
    private val port = device.sshPort
    private val user = device.sshUser
    private val password = device.sshPassword
    private val deviceName = device.name
    private val hostKey = device.sshHostKey

    private val client: SshClient by lazy {
        SshClient(
            host, port, user, password,
            pinnedHostKey = hostKey,
            // Un-pinned devices stay usable (capture-only); once a key is pinned it is enforced.
            allowUnknownHostKey = hostKey.isBlank()
        )
    }

    override suspend fun executeCommand(cmd: String, workdir: String, timeoutMs: Int): String {
        if (host.isBlank()) return jsonError("execute_shell_command", "SSH device \"$deviceName\" has no host configured.")
        return try {
            val result = client.executeCommand(cmd, workdir, timeoutMs)
            buildJsonObject {
                put("type", "execute_shell_command"); put("server", deviceName); put("command", cmd)
                put("exit_code", result.exitCode)
                put("output", (result.stdout + if (result.stderr.isNotBlank()) "\n${result.stderr}" else "").trimEnd())
            }.toString()
        } catch (e: Exception) {
            jsonError("execute_shell_command", e.message ?: "Unknown error", server = deviceName, command = cmd)
        }
    }

    override suspend fun fileRead(path: String, offset: Long, limit: Long): String {
        return try {
            val content = client.fileRead(path, offset, limit)
            buildJsonObject {
                put("type", "file_read"); put("server", deviceName); put("path", path)
                put("content", content); put("lines", content.lines().size)
            }.toString()
        } catch (e: Exception) {
            jsonError("file_read", "SFTP read failed: ${e.message}", server = deviceName)
        }
    }

    override suspend fun fileWrite(path: String, content: String): String? =
        client.fileWrite(path, content)?.let { jsonError("file_write", it, server = deviceName) }

    override suspend fun fileGlob(pattern: String, basePath: String, depth: Int?): Result<List<String>> =
        Result.success(client.fileGlob(pattern, basePath, depth))

    override suspend fun fileGrep(pattern: String, basePath: String, fileGlob: String): Result<List<ShellClient.GrepMatch>> =
        client.fileGrep(pattern, basePath, fileGlob).map { matches ->
            matches.map { ShellClient.GrepMatch(it.path, it.line, it.content) }
        }

    override fun close() { client.close() }
}

internal class SandboxBackend(sandbox: SandboxManager?) : Backend {
    override val device: ShellDeviceConfig? get() = null
    private val mgr = sandbox ?: throw IllegalStateException("Sandbox not available")

    override suspend fun executeCommand(cmd: String, workdir: String, timeoutMs: Int): String {
        if (!mgr.isAvailable()) return jsonError("execute_shell_command", "Local Sandbox is not installed.")
        return try {
            val result = mgr.executeCommand(cmd, workdir, timeoutMs)
            buildJsonObject {
                put("type", "execute_shell_command"); put("server", "Local Sandbox"); put("command", cmd)
                put("exit_code", result.exitCode)
                put("output", (result.stdout + if (result.stderr.isNotBlank()) "\n${result.stderr}" else "").trimEnd())
            }.toString()
        } catch (e: Exception) {
            jsonError("execute_shell_command", e.message ?: "Unknown error", server = "Local Sandbox", command = cmd)
        }
    }

    override suspend fun fileRead(path: String, offset: Long, limit: Long): String {
        return try {
            val content = mgr.fileRead(path, offset, limit)
            buildJsonObject {
                put("type", "file_read"); put("server", "Local Sandbox"); put("path", path)
                put("content", content); put("lines", content.lines().size)
            }.toString()
        } catch (e: Exception) {
            jsonError("file_read", e.message ?: "Read failed", server = "Local Sandbox")
        }
    }

    override suspend fun fileWrite(path: String, content: String): String? =
        mgr.fileWrite(path, content)?.let { jsonError("file_write", it, server = "Local Sandbox") }

    override suspend fun fileGlob(pattern: String, basePath: String, depth: Int?): Result<List<String>> =
        Result.success(mgr.fileGlob(pattern, basePath, depth))

    override suspend fun fileGrep(pattern: String, basePath: String, fileGlob: String): Result<List<ShellClient.GrepMatch>> =
        mgr.fileGrep(pattern, basePath, fileGlob).map { matches ->
            matches.map { ShellClient.GrepMatch(it.path, it.line, it.content) }
        }

    override fun close() {}
}
