package com.lxseek.chat.api

import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import java.io.File

object LlamaEngine {
    private const val TAG = "LlamaEngine"
    private var libraryLoaded = false

    init {
        // c++_shared is still bundled in the APK; the llama JNI wrapper
        // (liblxchat_llama.so) is now loaded on demand via loadLibrary().
        System.loadLibrary("c++_shared")
    }

    /**
     * Dynamically load liblxchat_llama.so from an absolute filesystem path.
     * Returns true if the library is already loaded or was loaded successfully.
     * Returns false when [libraryPath] is null, the file does not exist, or the
     * load fails with an UnsatisfiedLinkError.
     */
    fun loadLibrary(libraryPath: String?): Boolean {
        if (libraryLoaded) return true
        if (libraryPath == null) return false
        val file = File(libraryPath)
        if (!file.exists()) return false
        return try {
            System.load(libraryPath)
            libraryLoaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            DebugLog.e(TAG, "Failed to load llama library", e)
            false
        }
    }

    fun isLibraryAvailable(): Boolean = libraryLoaded

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
        if (texts.isEmpty()) return emptyList()
        if (!libraryLoaded) {
            DebugLog.e(TAG, "Native library not loaded; call LlamaEngine.loadLibrary(path) first")
            return texts.map { null }
        }
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
