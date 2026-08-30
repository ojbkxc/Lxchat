package com.lxseek.chat.api

import android.content.Context
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Embedding-side wrapper around the downloadable `lxchat_llama` native library.
 *
 * The .so is NO LONGER packaged in the APK (see app/src/main/cpp/CMakeLists.txt).
 * It is shipped as a Release Asset and downloaded at runtime into
 * `filesDir/native/lxchat_llama/liblxchat_llama.so`. Callers MUST invoke
 * [loadNative] (typically via LocalProvider.ensureEngineLoaded or
 * RagManager/EmbeddingCacheWorker/RagToolProvider preflight) before any
 * native method. [isNativeAvailable] is the cheap gate for every code path
 * that would otherwise touch a `native*` method.
 */
object LlamaEngine {
    private const val TAG = "LlamaEngine"

    /** Directory under filesDir where the downloadable .so lives. */
    private const val NATIVE_DIR = "native/lxchat_llama"
    private const val SO_NAME = "liblxchat_llama.so"

    @Volatile
    private var loaded = false

    /** Try to load the native library from app's files directory. Idempotent. */
    fun loadNative(context: Context): Boolean {
        if (loaded) return true
        val soFile = File(context.filesDir, "$NATIVE_DIR/$SO_NAME")
        if (!soFile.exists()) {
            DebugLog.w(TAG, "Native library not found at ${soFile.absolutePath}")
            return false
        }
        return try {
            // c++_shared is still packaged by the NDK in the APK (stl shared lib),
            // so loadLibrary works for it. The llama wrapper itself must come from
            // the downloaded path.
            runCatching { System.loadLibrary("c++_shared") }
            System.load(soFile.absolutePath)
            loaded = true
            DebugLog.i(TAG, "Native library loaded from ${soFile.absolutePath}")
            true
        } catch (e: UnsatisfiedLinkError) {
            DebugLog.e(TAG, "Failed to load native library", e)
            false
        }
    }

    /** Cheap gate: true iff [loadNative] has succeeded in this process. */
    fun isNativeAvailable(): Boolean = loaded

    /**
     * Check whether the .so file is present on disk (without attempting to load it).
     * Useful for UI status rows that want to distinguish "downloaded but not loaded"
     * from "not downloaded at all".
     */
    fun isNativeInstalled(context: Context): Boolean =
        File(context.filesDir, "$NATIVE_DIR/$SO_NAME").exists()

    /** Absolute path of the downloadable .so — used by the download UI. */
    fun nativeSoPath(context: Context): String =
        File(context.filesDir, "$NATIVE_DIR/$SO_NAME").absolutePath

    private external fun nativeLoadModel(path: String): Long
    private external fun nativeFreeModel(handle: Long)
    private external fun nativeComputeEmbedding(handle: Long, text: String): FloatArray?
    private external fun nativeGetEmbeddingDim(handle: Long): Int

    fun isModelReady(modelPath: String): Boolean {
        return modelPath.isNotBlank() && File(modelPath).exists() && File(modelPath).length() > 0
    }

    fun computeEmbedding(text: String, modelPath: String, beforeLoad: (() -> Unit)? = null): FloatArray? {
        val results = computeEmbeddings(listOf(text), modelPath, beforeLoad)
        return results.firstOrNull()
    }

    fun computeEmbeddings(texts: List<String>, modelPath: String, beforeLoad: (() -> Unit)? = null): List<FloatArray?> {
        if (!loaded) {
            DebugLog.e(TAG, "Native library not loaded — call loadNative(context) first")
            return texts.map { null }
        }
        if (texts.isEmpty()) return emptyList()
        return runBlocking {
            LocalModelSerializer.mutex.withLock {
                beforeLoad?.invoke()
                val start = System.currentTimeMillis()
                val handle = nativeLoadModel(modelPath)
                if (handle == 0L) {
                    DebugLog.e(TAG, "Failed to load model (${System.currentTimeMillis() - start}ms)")
                    return@runBlocking texts.map { null }
                }
                DebugLog.d(TAG, "Model loaded in ${System.currentTimeMillis() - start}ms, dim=${nativeGetEmbeddingDim(handle)}, processing ${texts.size} texts")
                try {
                    texts.mapIndexed { i, text ->
                        try {
                            val embd = nativeComputeEmbedding(handle, text)
                            if (embd == null) {
                                DebugLog.e(TAG, "nativeComputeEmbedding returned null for text len=${text.length} (${i+1}/${texts.size})")
                            }
                            embd
                        } catch (e: Exception) {
                            DebugLog.e(TAG, "Embedding computation crashed for text ${i+1}/${texts.size}", e)
                            null
                        }
                    }
                } finally {
                    nativeFreeModel(handle)
                    DebugLog.d(TAG, "Batch complete: ${texts.size} texts in ${System.currentTimeMillis() - start}ms")
                }
            }
        }
    }
}
