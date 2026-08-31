package com.lxseek.chat.localmodels

import android.content.Context
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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
 */
class GgufDownloader private constructor(private val context: Context) {

    companion object {
        @Volatile private var instance: GgufDownloader? = null
        fun getInstance(context: Context): GgufDownloader {
            return instance ?: synchronized(this) {
                instance ?: GgufDownloader(context.applicationContext).also { instance = it }
            }
        }
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val states = ConcurrentHashMap<String, MutableStateFlow<GgufDownloadState>>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val pauseFlags = ConcurrentHashMap<String, Boolean>()

    private val modelDir: File by lazy {
        File(context.filesDir, "gguf_models").apply { if (!exists()) mkdirs() }
    }

    fun getDownloadState(entryId: String): StateFlow<GgufDownloadState> =
        states.getOrPut(entryId) { MutableStateFlow(GgufDownloadState.Idle) }.asStateFlow()

    fun modelFile(entryId: String): File = File(modelDir, "chat_model_$entryId.gguf")
    private fun tempFile(entryId: String): File = File(modelDir, "chat_model_$entryId.gguf.part")

    /** Start (or resume) downloading [entry]. [onCompleted] is called on the IO scope. */
    fun startDownload(entry: GgufCatalogEntry, onCompleted: (File) -> Unit) {
        if (pauseFlags.remove(entry.id) == true) {
            // Resuming from pause — just clear the flag and fall through to launch.
        }
        jobs[entry.id]?.cancel()
        val stateFlow = states.getOrPut(entry.id) { MutableStateFlow(GgufDownloadState.Idle) }
        jobs[entry.id] = appScope.launch {
            downloadInternal(entry, stateFlow, onCompleted)
        }
    }

    /** Pause the download. The .part file is kept for later resume. */
    fun pauseDownload(entryId: String) {
        pauseFlags[entryId] = true
        jobs[entryId]?.cancel()
        val state = states[entryId]?.value
        if (state is GgufDownloadState.Downloading) {
            states[entryId]?.value = GgufDownloadState.Paused(state.progress, state.downloadedBytes)
        }
    }

    /** Cancel and delete the .part file. */
    fun cancelDownload(entryId: String) {
        pauseFlags.remove(entryId)
        jobs[entryId]?.cancel()
        tempFile(entryId).delete()
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
            stateFlow.value = GgufDownloadState.Connecting

            // 1) HEAD request to get total size.
            val totalBytes = withContext(Dispatchers.IO) {
                val headReq = Request.Builder().url(entry.url).head().build()
                client.newCall(headReq).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        stateFlow.value = GgufDownloadState.Failed("服务器返回 ${resp.code}")
                        return@downloadInternal
                    }
                    resp.header("Content-Length")?.toLongOrNull()
                        ?: entry.sizeBytes // Fall back to catalog size if server doesn't report.
                }
            }

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
                client.newCall(req).execute().use { resp ->
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
                            if (pauseFlags[entry.id] == true) {
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
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Pause or cancel — state already set by pauseDownload/cancelDownload.
            throw e
        } catch (e: Exception) {
            DebugLog.e("GgufDownloader", "download ${entry.id} failed", e)
            stateFlow.value = GgufDownloadState.Failed(e.message ?: "下载失败")
        }
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
        appScope.cancel()
    }
}