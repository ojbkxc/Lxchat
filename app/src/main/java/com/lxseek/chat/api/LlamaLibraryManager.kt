package com.lxseek.chat.api

import android.content.Context
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Manages the downloadable liblxchat_llama.so native extension.
 *
 * llama.cpp is no longer packaged inside the APK to keep the install size small.
 * Instead the JNI wrapper shared library is fetched on demand into the app's
 * private files directory and loaded via [LlamaChatEngine.loadLibrary] /
 * [LlamaEngine.loadLibrary].
 */
class LlamaLibraryManager(private val context: Context) {
    companion object {
        private const val TAG = "LlamaLibraryManager"
        private const val LIBRARY_FILE_NAME = "liblxchat_llama.so"
        // TODO: Replace with actual download URL for the llama library
        private const val DOWNLOAD_URL = "https://github.com/ojbkxc/Lxchat/releases/latest/download/liblxchat_llama.so"
    }

    private val libraryDir: File by lazy {
        File(context.filesDir, "native_libs").apply { mkdirs() }
    }

    val libraryPath: String
        get() = File(libraryDir, LIBRARY_FILE_NAME).absolutePath

    fun isLibraryDownloaded(): Boolean = File(libraryPath).exists()

    fun downloadLibrary(): Flow<DownloadProgress> = flow {
        val targetFile = File(libraryPath)
        if (targetFile.exists()) {
            emit(DownloadProgress.Completed)
            return@flow
        }
        emit(DownloadProgress.Downloading(0))
        try {
            val client = OkHttpClient()
            val request = Request.Builder().url(DOWNLOAD_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(DownloadProgress.Failed("HTTP ${response.code}"))
                    return@flow
                }
                val totalBytes = response.body?.contentLength() ?: -1
                var downloadedBytes = 0L
                response.body?.byteStream()?.use { input ->
                    targetFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            if (totalBytes > 0) {
                                emit(DownloadProgress.Downloading((downloadedBytes * 100 / totalBytes).toInt()))
                            }
                        }
                    }
                }
            }
            emit(DownloadProgress.Completed)
        } catch (e: Exception) {
            DebugLog.e(TAG, "Download failed", e)
            targetFile.delete()
            emit(DownloadProgress.Failed(e.message ?: "Unknown error"))
        }
    }

    sealed class DownloadProgress {
        data class Downloading(val percent: Int) : DownloadProgress()
        object Completed : DownloadProgress()
        data class Failed(val message: String) : DownloadProgress()
    }
}