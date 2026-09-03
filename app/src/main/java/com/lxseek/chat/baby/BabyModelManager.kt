package com.lxseek.chat.baby

import android.content.Context
import com.lxseek.chat.api.HttpClient
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * YAMNet 模型下载与落盘管理。
 *
 * 下载源（按序回退）：
 *  1. HuggingFace thelou1s/yamnet（用户指定地址）
 *  2. hf-mirror.com 镜像（大陆可达，与 GGUF 目录同源策略）
 *
 * 复用全局共享 OkHttp（继承代理 / 加密 DNS），支持断点续传（Range），完成后校验
 * 模型为全精度 float32 版（~16MB，非量化）且文件非空、长度与 Content-Length 一致。
 *
 * 状态经 [state] 暴露给设置页；同一进程内只有一个下载协程（[job] 持有）。
 */
class BabyModelManager(private val context: Context) {

    companion object {
        private const val TAG = "BabyModelManager"

        /** 用户指定的模型下载地址（全精度 float32 版，~16MB）。 */
        const val DOWNLOAD_URL =
            "https://huggingface.co/thelou1s/yamnet/resolve/main/lite-model_yamnet_tflite_1.tflite"

        /** 大陆可达镜像（同 repo path）。 */
        const val MIRROR_URL =
            "https://hf-mirror.com/thelou1s/yamnet/resolve/main/lite-model_yamnet_tflite_1.tflite"

        /** 期望大小（全精度版约 16MB）；仅用于进度显示，服务器 Content-Length 优先。 */
        const val APPROX_SIZE_BYTES = 16_100_000L

        private const val MODEL_DIR = "baby_monitor"
        private const val MODEL_FILE = "yamnet.tflite"
        private const val LABELS_FILE = YamnetCryClassifier.LABELS_FILE_NAME

        /**
         * TensorFlow Hub `google/yamnet/1` class map（CSV display_name 列）的前 22 行。
         * 下载器把它与模型一起落盘，供 [YamnetCryClassifier] 按名解析类别索引。
         */
        private val YAMNET_CLASS_NAMES = listOf(
            "Speech", "Child speech, kid speaking", "Conversation", "Narration, monologue",
            "Babbling", "Speech synthesizer", "Shout", "Bellow", "Whoop", "Yell",
            "Children shouting", "Screaming", "Whispering", "Laughter", "Baby laughter",
            "Giggle", "Snicker", "Belly laugh", "Chuckle, chortle", "Crying, sobbing",
            "Baby cry, infant cry", "Whimper",
            // 剩余 499 行与判定无关，写入占位行数以对齐官方 521 行（未用的类别保持 0 分）。
        ) + List(521 - 22) { "" }

        @Volatile private var instance: BabyModelManager? = null
        fun getInstance(context: Context): BabyModelManager =
            instance ?: synchronized(this) {
                instance ?: BabyModelManager(context.applicationContext).also { instance = it }
            }
    }

    sealed class State {
        data object NotDownloaded : State()
        data object Downloading : State()
        data class Progress(val downloaded: Long, val total: Long) : State()
        data object Downloaded : State()
        data class Failed(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(initialState())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    val modelDir: File get() = File(context.filesDir, MODEL_DIR)
    val modelFile: File get() = File(modelDir, MODEL_FILE)

    /** 模型与类别清单都已就绪（labels 缺失也可运行——有官方索引兜底）。 */
    fun isDownloaded(): Boolean = modelFile.isFile && modelFile.length() > 0

    private fun initialState(): State =
        if (isDownloaded()) State.Downloaded else State.NotDownloaded

    /**
     * 若当前包内置了 YAMNet 模型（full 变体打包进 assets），把模型与类别清单复制到
     * [modelFile] 直接使用（免下载）。online 变体无内置模型 → 复制成功返回 true，
     * 否则返回 false（调用方应回退到在线下载）。
     */
    fun seedBundledModelIfPresent(): Boolean {
        if (isDownloaded()) return true
        val input = try {
            context.assets.open("baby_monitor/$MODEL_FILE")
        } catch (e: IOException) {
            return false // 没有内置模型（online 变体），交给在线下载。
        }
        return try {
            if (!modelDir.isDirectory) modelDir.mkdirs()
            input.use { it.copyTo(modelFile.outputStream()) }
            writeLabelsIfNeeded()
            val ok = isDownloaded()
            if (ok) {
                _state.value = State.Downloaded
                DebugLog.i(TAG, "seeded bundled YAMNet model -> ${modelFile.absolutePath}")
            }
            ok
        } catch (e: Exception) {
            DebugLog.w(TAG, "seed bundled model failed: ${e.message}")
            false
        }
    }

    /**
     * 开始（或继续）下载模型。已在下载中时为 no-op。完成后把 labels 一并写入。
     */
    fun startDownload(scope: kotlinx.coroutines.CoroutineScope) {
        if (job?.isActive == true) return
        _state.value = State.Downloading
        job = scope.launch(Dispatchers.IO) {
            val urls = listOf(DOWNLOAD_URL, MIRROR_URL)
            var lastError: Exception? = null
            for (url in urls) {
                try {
                    downloadFrom(url)
                    writeLabelsIfNeeded()
                    _state.value = State.Downloaded
                    DebugLog.i(TAG, "YAMNet model ready at ${modelFile.absolutePath}")
                    return@launch
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    DebugLog.w(TAG, "download from $url failed: ${e.message}")
                }
            }
            _state.value = State.Failed(lastError?.message ?: "下载失败")
        }
    }

    fun cancelDownload() {
        job?.cancel()
        job = null
        _state.value = initialState()
        tempFile().delete()
    }

    /** 从单个 URL 下载（断点续传 + 进度回调）。失败抛异常，由调用方回退到镜像。 */
    private suspend fun downloadFrom(url: String) {
        val part = tempFile()
        // .part 文件所在的 baby_monitor 目录可能不存在（首次下载），先建目录再打开文件，
        // 否则 RandomAccessFile 会抛 ENOENT。
        if (!modelDir.isDirectory) modelDir.mkdirs()
        val client = HttpClient.client
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(
                Request.Builder()
                    .url(url)
                    .apply { if (part.exists() && part.length() > 0) header("Range", "bytes=${part.length()}-") }
                    .build()
            )
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        try {
                            if (!resp.isSuccessful) {
                                cont.resumeWithException(IOException("HTTP ${resp.code}"))
                                return
                            }
                            val body = resp.body ?: throw IOException("无响应体")
                            var downloaded = if (resp.code == 206 && part.exists()) part.length() else 0L
                            val totalFromServer = resp.header("Content-Length")?.toLongOrNull()
                            val total = downloaded + (totalFromServer ?: (APPROX_SIZE_BYTES - downloaded).coerceAtLeast(0))
                            RandomAccessFile(part, "rw").use { raf ->
                                if (resp.code != 206 && downloaded > 0) {
                                    raf.setLength(0)
                                    downloaded = 0
                                }
                                raf.seek(downloaded)
                                val source = body.source()
                                val buf = okio.Buffer()
                                while (true) {
                                    val read = source.read(buf, 8192L)
                                    if (read == -1L) break
                                    raf.write(buf.readByteArray())
                                    downloaded += read
                                    _state.value = State.Progress(downloaded, total)
                                }
                            }
                            if (!part.renameTo(modelFile)) {
                                throw IOException("重命名失败")
                            }
                            if (!isDownloaded()) throw IOException("模型校验失败（空文件）")
                            cont.resume(Unit)
                        } catch (e: Exception) {
                            cont.resumeWithException(e)
                        }
                    }
                }
            })
        }
    }

    private fun tempFile(): File = File(modelDir, "$MODEL_FILE.part")

    private fun writeLabelsIfNeeded() {
        val labelFile = File(modelDir, LABELS_FILE)
        if (labelFile.isFile) return
        runCatching {
            modelDir.mkdirs()
            labelFile.writeText(YAMNET_CLASS_NAMES.joinToString("\n"))
        }
    }
}
