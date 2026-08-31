package com.lxseek.chat.service

import android.util.Base64
import com.lxseek.chat.util.DebugLog
import com.lxseek.chat.util.PathSanitizer
import com.lxseek.chat.util.ProgressThrottle
import com.lxseek.chat.util.ShellClient
import com.lxseek.chat.util.SshClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileInputStream

/**
 * 会话生命周期状态。
 *
 * - [Connecting]   连接建立中（握手/鉴权进行中）
 * - [Connected]    已连接，可执行命令/传输文件
 * - [Disconnected] 已主动断开或尚未连接
 * - [Failed]       连接或握手失败，[lastError] 含原因
 */
enum class SessionState { Connecting, Connected, Disconnected, Failed }

/**
 * 远程连接配置。由 [RemoteDeviceLoop.openSession] 根据设备协议与凭据构造，
 * 在 [RemoteDeviceSession] 构造时注入。
 *
 * - [Ssh]   走 [SshClient]（JSch exec/sftp），适用于开放 SSH 的对端
 * - [Conch] 走 [ShellClient]（Conch HTTP /jobs API），适用于运行 Conch 的对端
 */
sealed class RemoteConnectionConfig {
    /** SSH 后端配置。 */
    data class Ssh(
        val host: String,
        val port: Int = 22,
        val user: String,
        val password: String,
        /** 已 pin 的服务器主机密钥（base64）；留空 + [allowUnknownHostKey]=true 则捕获模式。 */
        val pinnedHostKey: String = "",
        val allowUnknownHostKey: Boolean = false,
        val connectTimeoutMs: Int = 30_000,
    ) : RemoteConnectionConfig()

    /** Conch HTTP 后端配置。 */
    data class Conch(
        val serverUrl: String,
        val apiKey: String,
        val cachedPublicKey: String = "",
    ) : RemoteConnectionConfig()
}

/**
 * 与一个远程设备的会话。整合路径净化（T13）与进度节流（T16）。
 *
 * 后端
 * ----
 * - [RemoteConnectionConfig.Ssh]   → [SshClient]：`executeCommand` 直连 exec 通道
 * - [RemoteConnectionConfig.Conch] → [ShellClient]：`executeCommand` 走 `startJob` + 轮询 `getJob`
 *
 * 文件传输
 * --------
 * 统一走"分块 base64 + 远程 `base64 -d` 追加"方案，基于 [executeCommand]，因此两种后端
 * 共用同一传输逻辑，且每个分块都能通过 [ProgressThrottle] 节流进度回调：
 *  1. 首块 `printf '%s' '<b64>' | base64 -d > '<remote>'`（覆盖）
 *  2. 后续 `printf '%s' '<b64>' | base64 -d >> '<remote>'`（追加）
 *
 * 路径净化
 * --------
 * - [remotePath] 经 [PathSanitizer.sanitizeRelativePath] 净化，拒绝 `..`/绝对路径/控制字符/
 *   Windows drive/UNC 等；净化后的相对路径再拼接到 [remoteBaseDir] 之下。
 * - [localPath] 经 [PathSanitizer.resolveSafe] 解析到 [localBaseDir] 之内，含符号链接逃逸检测。
 *
 * 线程安全：会话字段用 `@Volatile` 标记；同一会话不应被多协程并发使用（与 [SshClient] 约定一致）。
 *
 * @param config       连接配置
 * @param localBaseDir 本地文件传输的信任基目录；[transferFile] 的 [localPath] 必须在此目录内
 * @param remoteBaseDir 远程工作目录；[transferFile] 的 [remotePath] 相对于此目录解析
 * @param progressThrottle 进度节流器；默认 200ms / 1%
 */
class RemoteDeviceSession(
    private val config: RemoteConnectionConfig,
    private val localBaseDir: File,
    private val remoteBaseDir: String = ".",
    private val progressThrottle: ProgressThrottle = ProgressThrottle(),
) {
    companion object {
        private const val TAG = "RemoteDeviceSession"

        /** 分块原始字节数；base64 后约 42 KB，单条 shell 命令长度安全。 */
        private const val TRANSFER_CHUNK_BYTES = 32 * 1024

        /** Conch job 轮询间隔（毫秒）。 */
        private const val CONCH_POLL_INTERVAL_MS = 200L

        /** 默认命令执行超时（毫秒）。 */
        private const val DEFAULT_CMD_TIMEOUT_MS = 60_000
    }

    @Volatile
    var state: SessionState = SessionState.Disconnected
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** 最近一次 connect 的对端设备（用于日志上下文）。 */
    @Volatile
    private var peerName: String? = null

    private var sshClient: SshClient? = null
    private var shellClient: ShellClient? = null

    /**
     * 建立连接。幂等：已连接时直接返回 true。
     *
     * @param device 发现到的对端设备（仅用于日志/上下文，连接参数取自构造时注入的 [config]）
     * @return true 已连接；false 失败（[lastError] 含原因，[state] = [SessionState.Failed]）
     */
    suspend fun connect(device: DiscoveredDevice): Boolean {
        if (state == SessionState.Connected) {
            DebugLog.w(TAG, "connect: already connected")
            return true
        }
        peerName = device.name
        state = SessionState.Connecting
        return try {
            when (config) {
                is RemoteConnectionConfig.Ssh -> connectSsh(config)
                is RemoteConnectionConfig.Conch -> connectConch(config)
            }
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            state = SessionState.Failed
            DebugLog.e(TAG, "connect failed for peer=${device.name}", e)
            false
        }
    }

    /** SSH 握手：执行一条无害命令验证连接可达。 */
    private suspend fun connectSsh(cfg: RemoteConnectionConfig.Ssh): Boolean {
        val client = SshClient(
            host = cfg.host,
            port = cfg.port,
            user = cfg.user,
            password = cfg.password,
            timeoutMs = cfg.connectTimeoutMs,
            pinnedHostKey = cfg.pinnedHostKey,
            allowUnknownHostKey = cfg.allowUnknownHostKey,
        )
        // 触发实际 TCP 连接 + 鉴权：执行一条固定回声命令验证链路
        val result = client.executeCommand(
            command = "echo lxchat-ok",
            workdir = "",
            execTimeoutMs = cfg.connectTimeoutMs,
        )
        if (!result.stdout.contains("lxchat-ok")) {
            client.close()
            throw IllegalStateException("SSH handshake failed: exit=${result.exitCode} stderr=${result.stderr.take(200)}")
        }
        sshClient = client
        state = SessionState.Connected
        lastError = null
        DebugLog.d(TAG, "SSH connected to ${cfg.host}:${cfg.port} (capturedHostKey=${client.capturedHostKey?.let { SshClient.fingerprintSha256(it) }})")
        return true
    }

    /** Conch 握手：拉取/校验服务端公钥（若启用鉴权）。 */
    private suspend fun connectConch(cfg: RemoteConnectionConfig.Conch): Boolean {
        val client = ShellClient(cfg.serverUrl, cfg.apiKey, cfg.cachedPublicKey)
        if (cfg.apiKey.isNotBlank() && !client.fetchPublicKey()) {
            throw IllegalStateException(client.lastError ?: "fetchPublicKey failed")
        }
        shellClient = client
        state = SessionState.Connected
        lastError = null
        DebugLog.d(TAG, "Conch connected to ${cfg.serverUrl} (auth=${cfg.apiKey.isNotBlank()})")
        return true
    }

    /**
     * 远程执行命令，返回 stdout。
     *
     * @param command  命令字符串（已由调用方负责 shell 转义；AI 生成命令应在更上层经白名单/确认）
     * @param workdir  工作目录；留空则使用构造时的 [remoteBaseDir]
     * @param timeoutMs 执行超时（毫秒）
     * @return stdout；失败时抛 [IllegalStateException]
     */
    suspend fun executeCommand(
        command: String,
        workdir: String = "",
        timeoutMs: Int = DEFAULT_CMD_TIMEOUT_MS,
    ): String {
        val s = state
        if (s != SessionState.Connected) {
            throw IllegalStateException("session not connected (state=$s, peer=$peerName)")
        }
        val wd = workdir.ifBlank { remoteBaseDir }
        return when (config) {
            is RemoteConnectionConfig.Ssh -> {
                // 局部快照：sshClient 为可变 var，可能被并发 close 置空，避免 !! 强解
                val ssh = sshClient
                    ?: throw IllegalStateException("SSH session not connected (state=$s, peer=$peerName)")
                val r = ssh.executeCommand(command, wd, timeoutMs)
                if (r.exitCode != 0) {
                    DebugLog.w(TAG, "ssh cmd exit=${r.exitCode} stderr=${r.stderr.take(200)}")
                }
                r.stdout
            }
            is RemoteConnectionConfig.Conch -> executeConchCommand(command, wd, timeoutMs)
        }
    }

    /**
     * Conch 远程执行：`startJob` + 轮询 `getJob` 直到完成或超时。
     *
     * 响应字段容错解析（[extractJsonField]）：缺失字段不抛异常，按"未完成"继续轮询。
     */
    private suspend fun executeConchCommand(
        command: String,
        workdir: String,
        timeoutMs: Int,
    ): String {
        // 局部快照：shellClient 为可变 var，可能被并发 close 置空，避免 !! 强解
        val client = shellClient
            ?: throw IllegalStateException("Conch session not connected (state=$state, peer=$peerName)")
        val startResp = client.startJob(command, timeoutMs, workdir)
        val jobId = extractJsonField(startResp, "job_id")
            ?: throw IllegalStateException("Conch startJob returned no job_id: ${startResp.take(200)}")

        val maxAttempts = (timeoutMs / CONCH_POLL_INTERVAL_MS).coerceAtLeast(1L).toInt()
        repeat(maxAttempts) {
            delay(CONCH_POLL_INTERVAL_MS)
            val getResp = client.getJob(jobId)
            val status = extractJsonField(getResp, "status") ?: "unknown"
            // 运行中态：继续轮询
            if (status == "running" || status == "started" || status == "pending" || status == "unknown") {
                return@repeat
            }
            // 终态
            val stdout = extractJsonField(getResp, "stdout") ?: getResp
            val stderr = extractJsonField(getResp, "stderr")
            if (!stderr.isNullOrEmpty()) {
                DebugLog.w(TAG, "conch cmd stderr: ${stderr.take(200)}")
            }
            return stdout
        }
        // 超时：尝试清理
        runCatching { client.stopJob(jobId) }
        throw IllegalStateException("Conch job $jobId timed out after ${timeoutMs}ms")
    }

    /** 从 JSON 字符串中容错提取一个字符串字段；解析失败返回 null。 */
    private fun extractJsonField(raw: String, field: String): String? = try {
        Json.parseToJsonElement(raw).jsonObject[field]?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }

    /**
     * 文件传输：本地 → 远程。
     *
     * 路径净化：
     * - [remotePath] 经 [PathSanitizer.sanitizeRelativePath] 净化后拼接到 [remoteBaseDir] 之下；
     *   含 `..`/绝对路径/控制字符等一律拒绝并抛 [IllegalArgumentException]。
     * - [localPath] 经 [PathSanitizer.resolveSafe] 解析到 [localBaseDir] 之内（含符号链接逃逸检测）。
     *
     * 进度节流：通过 [ProgressThrottle.emit] 节流 [onProgress] 回调（默认 200ms / 1%），
     * 完成时 [ProgressThrottle.forceEmit] 强制上报 100%。
     *
     * @param localPath  相对于 [localBaseDir] 的本地文件路径
     * @param remotePath 相对于 [remoteBaseDir] 的远程目标路径
     * @param onProgress 进度回调，取值 0.0–100.0
     */
    suspend fun transferFile(
        localPath: String,
        remotePath: String,
        onProgress: (Double) -> Unit,
    ) {
        val s = state
        if (s != SessionState.Connected) {
            throw IllegalStateException("session not connected (state=$s, peer=$peerName)")
        }

        // 1. 净化远程相对路径
        val cleanRemote = PathSanitizer.sanitizeRelativePath(remotePath)
            ?: throw IllegalArgumentException("unsafe remote path: $remotePath")
        // 2. 净化本地相对路径，解析到 localBaseDir 之下（含 symlink 逃逸检测）
        val localFile = PathSanitizer.resolveSafe(localBaseDir, localPath)
            ?: throw IllegalArgumentException("unsafe local path: $localPath")
        if (!localFile.isFile) {
            throw IllegalArgumentException("local file not found or not a regular file: $localFile")
        }

        // 3. 拼接远程绝对路径（远程一律用 '/' 分隔符）
        val fullRemote = if (remoteBaseDir.isBlank() || remoteBaseDir == ".") {
            cleanRemote
        } else {
            "$remoteBaseDir/$cleanRemote"
        }
        val escapedRemote = escapeShell(fullRemote)

        progressThrottle.reset()
        progressThrottle.emit(0.0, onProgress)

        // 4. 确保远程父目录存在
        val remoteDir = fullRemote.substringBeforeLast('/', "")
        if (remoteDir.isNotBlank()) {
            executeCommand("mkdir -p ${escapeShell(remoteDir)}")
        }

        val total = localFile.length()
        if (total == 0L) {
            // 空文件：创建空远程文件
            executeCommand("printf '' > $escapedRemote")
            progressThrottle.forceEmit(100.0, onProgress)
            DebugLog.d(TAG, "transferred empty file to $fullRemote")
            return
        }

        // 5. 分块 base64 传输
        withContext(Dispatchers.IO) {
            FileInputStream(localFile).use { fis ->
                val buffer = ByteArray(TRANSFER_CHUNK_BYTES)
                var sent = 0L
                var first = true
                while (true) {
                    val n = fis.read(buffer)
                    if (n <= 0) break
                    val chunk = if (n == buffer.size) buffer else buffer.copyOf(n)
                    val b64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
                    val redirect = if (first) ">" else ">>"
                    // printf '%s' '<b64>' | base64 -d ><|>> '<remote>'
                    val cmd = "printf '%s' ${escapeShell(b64)} | base64 -d $redirect $escapedRemote"
                    executeCommand(cmd)
                    sent += n
                    first = false
                    val pct = sent.toDouble() / total.toDouble() * 100.0
                    progressThrottle.emit(pct, onProgress)
                }
            }
        }
        // 6. 终态强制上报
        progressThrottle.forceEmit(100.0, onProgress)
        DebugLog.d(TAG, "transferred ${localFile.name} ($total bytes) to $fullRemote")
    }

    /**
     * 断开连接，释放底层资源。安全可重入。
     */
    fun disconnect() {
        sshClient?.close()
        sshClient = null
        shellClient = null
        val was = state
        state = SessionState.Disconnected
        if (was != SessionState.Disconnected) {
            DebugLog.d(TAG, "disconnected (was=$was, peer=$peerName)")
        }
    }

    /** 单引号 shell 转义：`'` → `'\''`，整体用单引号包裹。 */
    private fun escapeShell(s: String): String = "'${s.replace("'", "'\\''")}'"
}