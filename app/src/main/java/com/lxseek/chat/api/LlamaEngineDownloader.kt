package com.lxseek.chat.api

import android.content.Context
import com.lxseek.chat.BuildConfig
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads the `lxchat_llama` native library from GitHub Releases into
 * `filesDir/native/lxchat_llama/liblxchat_llama.so` so [LlamaEngine.loadNative]
 * can `System.load` it.
 *
 * The .so is a downloadable component (NOT packaged in the APK — see
 * app/src/main/cpp/CMakeLists.txt). CI uploads it as
 * `lxchat_llama-arm64-v8a.so` to the Release tagged `v{VERSION}`.
 *
 * Surface: a singleton [progress] flow that the Settings > System Status page
 * collects to render download progress, and [download] / [delete] entry points.
 */
object LlamaEngineDownloader {
    private const val TAG = "LlamaEngineDownloader"

    /** GitHub repo that hosts the Release Assets. */
    private const val RELEASE_REPO = "ojbkxc/lxchat-runtime"

    /** ABI → Release-Asset filename suffix. Only arm64-v8a for now. */
    private fun assetNameForAbi(abi: String): String = "lxchat_llama-$abi.so"

    /** Current device ABI — Lxchat only ships arm64-v8a. */
    private val deviceAbi: String by lazy {
        // Build.SUPPORTED_ABIS[0] is the preferred ABI; we only ship arm64-v8a.
        android.os.Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" } ?: "arm64-v8a"
    }

    /** Release download URL for the current device's ABI. */
    fun downloadUrl(): String {
        val version = BuildConfig.VERSION_NAME
        return "https://github.com/$RELEASE_REPO/releases/download/v$version/${assetNameForAbi(deviceAbi)}"
    }

    /** Live download progress: -1 = idle, 0..100 = percent, -2 = failed. */
    private val _progress = MutableStateFlow(-1)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    /** True iff a download is currently in flight. */
    private val _downloading = MutableStateFlow(false)
    val downloading: StateFlow<Boolean> = _downloading.asStateFlow()

    /** Download (or re-download) the .so into filesDir. Idempotent while running. */
    suspend fun download(context: Context): Boolean = withContext(Dispatchers.IO) {
        // M7 修复：CAS 原子化「检查-置位」，杜绝并发 download() 双开下载（原先
        // check-then-act 的 `.value` 读改写存在竞态窗口）。
        if (!_downloading.compareAndSet(false, true)) {
            DebugLog.w(TAG, "Download already in progress")
            return@withContext false
        }
        _progress.value = 0
        val targetFile = File(LlamaEngine.nativeSoPath(context))
        val tmp = File(targetFile.parentFile, "${targetFile.name}.tmp")
        try {
            targetFile.parentFile?.mkdirs()
            if (tmp.exists()) tmp.delete()
            val url = downloadUrl()
            DebugLog.i(TAG, "Downloading $url → ${targetFile.absolutePath}")
            HttpClient.downloadToFile(url, tmp, onProgress = { done, total ->
                if (total > 0) {
                    _progress.value = ((done * 100) / total).toInt().coerceIn(0, 100)
                }
            })
            // Atomic move into place. If a stale .so is already loaded into the
            // process, this won't take effect until the next process start —
            // documented limitation, see LlamaEngine.loadNative.
            if (targetFile.exists()) targetFile.delete()
            if (!tmp.renameTo(targetFile)) {
                tmp.copyTo(targetFile, overwrite = true)
                tmp.delete()
            }
            _progress.value = 100
            DebugLog.i(TAG, "Download complete: ${targetFile.length()} bytes")
            true
        } catch (e: Exception) {
            DebugLog.e(TAG, "Download failed", e)
            // L4 修复：失败时清理 tmp 残留，避免半成品 .so 干扰后续下载/加载。
            runCatching { tmp.delete() }
            _progress.value = -2
            false
        } finally {
            _downloading.value = false
        }
    }

    /** Remove the downloaded .so (the APK is unaffected). */
    fun delete(context: Context): Boolean {
        val f = File(LlamaEngine.nativeSoPath(context))
        return if (f.exists()) f.delete() else true
    }
}