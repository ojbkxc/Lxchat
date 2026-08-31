package com.lxseek.chat.localmodels

import android.content.Context
import com.lxseek.chat.api.HttpClient
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/** Sealed state for a single model download. */
sealed class GgufDownloadState {
    object Idle : GgufDownloadState()
    object Connecting : GgufDownloadState()
    data class Downloading(val progress: Float, val speed: String, val downloadedBytes: Long, val totalBytes: Long) : GgufDownloadState()
    data class Paused(val progress: Float, val downloadedBytes: Long) : GgufDownloadState()
    data class Completed(val file: File) : GgufDownloadState()
    data class Failed(val error: String) : GgufDownloadState()
}

/**
 * Singleton download manager for GGUF models. Downloads run in an application-scoped
 * coroutine so they survive UI lifecycle changes. Each model has an independent
 * StateFlow that the UI subscribes to.
 *
 * Design based on Operit's MnnModelDownloadManager: singleton + applicationScope,
 * per-model StateFlow, HEAD pre-check, resumable downloads, pause/resume, speed display.
 *
 * H3 修复（暂停/取消/续传的并发语义）：
 *  - **唯一协程**：每个下载同一时刻只有一个协程在写 `.part`；恢复/重试前先
 *    `cancelAndJoin` 旧协程（[writeLocks] 串行化），确保它真正退出后才启动新的。
 *    `Job.cancel()` 对阻塞 IO 无效，因此暂停/取消同时 `Call.cancel()` 关闭底层
 *    socket，让阻塞中的 read 立刻抛异常返回。
 *  - **语义区分**：暂停 = 终止写入但保留 `.part`；取消 = 终止下载并删除 `.part`。
 *  - **写入互斥**：`Map<entryId, Mutex>` 保证同一目标文件不会被双协程同写。
 */
class GgufDownloader private constructor(private val context: Context) {

    companion object {
        private const val TAG = "GgufDownloader"

        @Volatile private var instance: GgufDownloader? = null
        fun getInstance(context: Context): GgufDownloader {
            return instance ?: synchronized(this) {
                instance ?: GgufDownloader(context.applicationContext).also { instance = it }
            }
        }
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 改用全局共享 OkHttp 客户端：继承全局代理与加密 DNS 设置（此前独立
     *  client 会绕过两者），且 `Call.cancel()` 能即时打断阻塞读。 */
    private val client get() = HttpClient.client

    private val states = ConcurrentHashMap<String, MutableStateFlow<GgufDownloadState>>()

    /** 每个下载当前唯一的协程引用（H3a）。 */
    private val jobs = ConcurrentHashMap<String, Job>()

    /** 每个下载当前用于打断阻塞 IO 的活动 Call（HEAD 或 GET）。 */
    private val activeCalls = ConcurrentHashMap<String, Call>()

    /** 暂停标记（H3b）：终止写入但保留 `.part`。 */
    private val pausedIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 取消标记（H3b）：终止下载并删除 `.part`。 */
    private val cancelledIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 同一目标的写入互斥（H3c）：任何时刻至多一个协程写同一 entryId 的 `.part`。 */
    private val writeLocks = ConcurrentHashMap<String, Mutex>()

    private val modelDir: File by lazy {
        File(context.filesDir, "gguf_models").apply { if (!exists()) mkdirs() }
    }

    fun getDownloadState(entryId: String): StateFlow<GgufDownloadState> =
        states.getOrPut(entryId) { MutableStateFlow(GgufDownloadState.Idle) }.asStateFlow()

    fun modelFile(entryId: String): File = File(modelDir, "chat_model_$entryId.gguf")
    private fun tempFile(entryId: String): File = File(modelDir, "chat_model_$entryId.gguf.part")

    /** Start (or resume) downloading [entry]. [onCompleted] is called on the IO scope. */
    fun startDownload(entry: GgufCatalogEntry, onCompleted: (File) -> Unit) {
        // 恢复/重试：清掉暂停与取消标记（取消标记也可能由无协程的 cancelDownload 留下）。
        pausedIds.remove(entry.id)
        cancelledIds.remove(entry.id)
        val stateFlow = states.getOrPut(entry.id) { MutableStateFlow(GgufDownloadState.Idle) }
        appScope.launch {
            writeLocks.computeIfAbsent(entry.id) { Mutex() }.withLock {
                // H3a：先 cancelAndJoin 旧协程，等它真正退出（旧协程也可能持有
                // writeLock 执行中——本协程在锁上排队，它释放锁时必然已退出），
                // 杜绝双协程同写同一 `.part` 文件。
                jobs[entry.id]?.cancelAndJoin()
                val job = currentCoroutineContext()[Job] ?: return@withLock
                jobs[entry.id] = job
                try {
                    downloadInternal(entry, stateFlow, onCompleted)
                } finally {
                    if (jobs[entry.id] === job) jobs.remove(entry.id)
                }
            }
        }
    }

    /**
     * Pause the download. The `.part` file is kept for later resume.
     * H3b：暂停 = 终止写入但保留 `.part`。`Call.cancel()` 让阻塞中的 read 立刻
     * 返回（`Job.cancel()` 对阻塞 IO 无效），协程随后退出。
     */
    fun pauseDownload(entryId: String) {
        pausedIds.add(entryId)
        activeCalls[entryId]?.cancel()
        jobs[entryId]?.cancel()
        val state = states[entryId]?.value
        if (state is GgufDownloadState.Downloading) {
            states[entryId]?.value = GgufDownloadState.Paused(state.progress, state.downloadedBytes)
        }
    }

    /**
     * Cancel the download and delete the `.part` file.
     * H3b：取消 = 终止下载并删除 `.part`。删除由持锁退出的协程（或本方法在
     * 确认无活动协程时）执行，避免删掉一个正在被写入的文件。
     */
    fun cancelDownload(entryId: String) {
        cancelledIds.add(entryId)
        activeCalls[entryId]?.cancel()
        jobs[entryId]?.cancel()
        val job = jobs[entryId]
        if (job == null || !job.isActive) {
            // 无活动协程：直接清理（有协程时它会自己删，见 downloadInternal）。
            tempFile(entryId).delete()
        }
        states[entryId]?.value = GgufDownloadState.Idle
    }

    /** Delete the completed model file. */
    fun deleteModelFile(entryId: String): Boolean {
        cancelDownload(entryId)
        return modelFile(entryId).delete()
    }

    /** Check if a model file already exists and is valid. */
    fun isModelDownloaded(entryId: String): Boolean {
        val f = modelFile(entryId)
        return f.exists() && f.length() > 0 && isValidGguf(f)
    }

    private suspend fun downloadInternal(
        entry: GgufCatalogEntry,
        stateFlow: MutableStateFlow<GgufDownloadState>,
        onCompleted: (File) -> Unit,
    ) {
        try {
            // startDownload 与 cancelDownload 竞态兜底：启动前已被取消则直接收尾。
            if (entry.id in cancelledIds) {
                finishCancelled(entry.id, stateFlow)
                return
            }
            stateFlow.value = GgufDownloadState.Connecting

            // 1) HEAD request to get total size.
            val totalBytes: Long? = withContext(Dispatchers.IO) {
                val headCall = client.newCall(Request.Builder().url(entry.url).head().build())
                registerCall(entry.id, headCall)
                try {
                    headCall.execute().use { resp ->
                        if (!resp.isSuccessful) {
                            stateFlow.value = GgufDownloadState.Failed("服务器返回 ${resp.code}")
                            null
                        } else {
                            resp.header("Content-Length")?.toLongOrNull()
                                ?: entry.sizeBytes // Fall back to catalog size if server doesn't report.
                        }
                    }
                } finally {
                    unregisterCall(entry.id, headCall)
                }
            }
            if (totalBytes == null) return

            // 2) Check if already fully downloaded.
            val finalFile = modelFile(entry.id)
            if (finalFile.exists() && finalFile.length() == totalBytes && isValidGguf(finalFile)) {
                stateFlow.value = GgufDownloadState.Completed(finalFile)
                onCompleted(finalFile)
                return
            }

            // 3) Resume from .part if it exists.
            val partFile = tempFile(entry.id)
            var downloadedBytes = if (partFile.exists()) partFile.length() else 0L

            // 4) Download with Range support.
            val builder = Request.Builder().url(entry.url)
            if (downloadedBytes > 0) {
                builder.header("Range", "bytes=$downloadedBytes-")
            }
            val req = builder.build()

            withContext(Dispatchers.IO) {
                val getCall = client.newCall(req)
                registerCall(entry.id, getCall)
                try {
                    getCall.execute().use { resp ->
                        if (!resp.isSuccessful && resp.code != 206) {
                            // Server doesn't support range or other error — restart from scratch.
                            if (resp.code == 200) {
                                downloadedBytes = 0
                            } else {
                                stateFlow.value = GgufDownloadState.Failed("下载失败 ${resp.code}")
                                return@withContext
                            }
                        }

                        val body = resp.body ?: run {
                            stateFlow.value = GgufDownloadState.Failed("无响应体")
                            return@withContext
                        }

                        val raf = RandomAccessFile(partFile, "rw")
                        raf.seek(downloadedBytes)

                        val source = body.source()
                        val buffer = okio.Buffer()
                        var lastSpeedTime = System.currentTimeMillis()
                        var lastSpeedBytes = downloadedBytes
                        val speedInterval = 500L

                        try {
                            while (true) {
                                // 循环头兜底检查（正常路径靠 Call.cancel 打断阻塞读）。
                                if (entry.id in cancelledIds) {
                                    finishCancelled(entry.id, stateFlow)
                                    return@withContext
                                }
                                if (entry.id in pausedIds) {
                                    stateFlow.value = GgufDownloadState.Paused(
                                        downloadedBytes.toFloat() / totalBytes,
                                        downloadedBytes,
                                    )
                                    return@withContext
                                }

                                val read = source.read(buffer, 8192L)
                                if (read == -1L) break

                                raf.write(buffer.readByteArray())
                                downloadedBytes += read

                                val now = System.currentTimeMillis()
                                if (now - lastSpeedTime >= speedInterval) {
                                    val elapsed = now - lastSpeedTime
                                    val bytesDiff = downloadedBytes - lastSpeedBytes
                                    val speedBps = if (elapsed > 0) bytesDiff * 1000 / elapsed else 0
                                    val speedStr = formatSpeed(speedBps)
                                    lastSpeedTime = now
                                    lastSpeedBytes = downloadedBytes
                                    stateFlow.value = GgufDownloadState.Downloading(
                                        progress = downloadedBytes.toFloat() / totalBytes,
                                        speed = speedStr,
                                        downloadedBytes = downloadedBytes,
                                        totalBytes = totalBytes,
                                    )
                                }
                            }
                        } finally {
                            raf.close()
                        }

                        // 5) Validate GGUF magic.
                        if (!isValidGguf(partFile)) {
                            partFile.delete()
                            stateFlow.value = GgufDownloadState.Failed("文件校验失败（非 GGUF 格式）")
                            return@withContext
                        }

                        // 6) Rename .part → final.
                        if (partFile.renameTo(finalFile)) {
                            stateFlow.value = GgufDownloadState.Completed(finalFile)
                            onCompleted(finalFile)
                        } else {
                            stateFlow.value = GgufDownloadState.Failed("文件重命名失败")
                        }
                    }
                } finally {
                    unregisterCall(entry.id, getCall)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协作取消（暂停/取消触发）：状态已由 pauseDownload/cancelDownload 设置，
            // 这里只补齐取消标记未被消费时的收尾。
            if (entry.id in cancelledIds) {
                finishCancelled(entry.id, stateFlow)
            }
            throw e
        } catch (e: Exception) {
            when {
                // Call.cancel() 打断阻塞 IO 后走到这里：按标记区分暂停/取消/失败。
                entry.id in cancelledIds -> finishCancelled(entry.id, stateFlow)
                entry.id in pausedIds -> {
                    // 暂停：保留 `.part`。状态已由 pauseDownload 设为 Paused；若
                    // 竞态窗口下未及设置，按 `.part` 长度补一次。
                    if (stateFlow.value !is GgufDownloadState.Paused) {
                        val len = tempFile(entry.id).length()
                        stateFlow.value = GgufDownloadState.Paused(0f, len)
                    }
                }
                else -> {
                    DebugLog.e(TAG, "download ${entry.id} failed", e)
                    // L4：失败时清理 `.part` 残留，避免半成品干扰下次续传。
                    runCatching { tempFile(entry.id).delete() }
                    stateFlow.value = GgufDownloadState.Failed(e.message ?: "下载失败")
                }
            }
        }
    }

    /** 取消收尾（H3b：取消=终止下载并删除 .part）。 */
    private fun finishCancelled(entryId: String, stateFlow: MutableStateFlow<GgufDownloadState>) {
        runCatching { tempFile(entryId).delete() }
        cancelledIds.remove(entryId)
        pausedIds.remove(entryId)
        stateFlow.value = GgufDownloadState.Idle
    }


    private fun registerCall(entryId: String, call: Call) {
        activeCalls[entryId] = call
    }

    private fun unregisterCall(entryId: String, call: Call) {
        activeCalls.remove(entryId, call)
    }

    /** Check first 4 bytes are "GGUF". */
    private fun isValidGguf(file: File): Boolean = try {
        val magic = ByteArray(4)
        file.inputStream().use { it.read(magic) }
        magic[0] == 'G'.code.toByte() &&
            magic[1] == 'G'.code.toByte() &&
            magic[2] == 'U'.code.toByte() &&
            magic[3] == 'F'.code.toByte()
    } catch (_: Exception) {
        false
    }

    private fun formatSpeed(bytesPerSecond: Long): String = when {
        bytesPerSecond >= 1_000_000 -> "%.1f MB/s".format(bytesPerSecond / 1_000_000.0)
        bytesPerSecond >= 1_000 -> "%.1f KB/s".format(bytesPerSecond / 1_000.0)
        else -> "$bytesPerSecond B/s"
    }

    fun shutdown() {
        // 先打断所有阻塞 IO，再取消协程作用域，避免协程滞留在阻塞读里。
        activeCalls.values.forEach { runCatching { it.cancel() } }
        appScope.cancel()
    }

}
