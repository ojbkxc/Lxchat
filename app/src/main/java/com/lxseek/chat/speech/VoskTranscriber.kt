package com.lxseek.chat.speech

import android.content.Context
import com.lxseek.chat.util.AppLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileInputStream
import com.lxseek.chat.util.DebugLog

class VoskTranscriber(private val context: Context) {

    companion object {
        private const val TAG = "VoskTranscriber"
        private const val SAMPLE_RATE = 16000f

        val AVAILABLE_LANGUAGES = listOf(
            LanguageModel("en", "English (Small)", "vosk-model-small-en-us-0.15",
                "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", 40_000_000L, false),
            LanguageModel("en-full", "English (Full - Best)", "vosk-model-en-us-0.22-lgraph",
                "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22-lgraph.zip", 128_000_000L, true),
            LanguageModel("ru", "Русский (Small)", "vosk-model-small-ru-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip", 45_000_000L, false),
            LanguageModel("ru-full", "Русский (Full - Best)", "vosk-model-ru-0.42",
                "https://alphacephei.com/vosk/models/vosk-model-ru-0.42.zip", 1_800_000_000L, true),
            LanguageModel("de", "Deutsch (Small)", "vosk-model-small-de-0.15",
                "https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip", 45_000_000L, false),
            LanguageModel("de-full", "Deutsch (Full)", "vosk-model-de-0.21",
                "https://alphacephei.com/vosk/models/vosk-model-de-0.21.zip", 1_900_000_000L, true),
            LanguageModel("es", "Español (Small)", "vosk-model-small-es-0.42",
                "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip", 39_000_000L, false),
            LanguageModel("es-full", "Español (Full)", "vosk-model-es-0.42",
                "https://alphacephei.com/vosk/models/vosk-model-es-0.42.zip", 1_400_000_000L, true),
            LanguageModel("fr", "Français (Small)", "vosk-model-small-fr-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip", 41_000_000L, false),
            LanguageModel("fr-full", "Français (Full)", "vosk-model-fr-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-fr-0.22.zip", 1_400_000_000L, true),
            LanguageModel("it", "Italiano (Small)", "vosk-model-small-it-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip", 48_000_000L, false),
            LanguageModel("it-full", "Italiano (Full)", "vosk-model-it-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-it-0.22.zip", 1_200_000_000L, true),
            LanguageModel("pt", "Português (Small)", "vosk-model-small-pt-0.3",
                "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip", 31_000_000L, false),
            LanguageModel("pt-full", "Português (Full)", "vosk-model-pt-fb-v0.1.1-20220516_2113",
                "https://alphacephei.com/vosk/models/vosk-model-pt-fb-v0.1.1-20220516_2113.zip", 1_600_000_000L, true),
            LanguageModel("zh", "中文 (Small)", "vosk-model-small-cn-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip", 42_000_000L, false),
            LanguageModel("zh-full", "中文 (Full)", "vosk-model-cn-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-cn-0.22.zip", 1_300_000_000L, true),
            LanguageModel("ja", "日本語 (Small)", "vosk-model-small-ja-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip", 48_000_000L, false),
            LanguageModel("ja-full", "日本語 (Full)", "vosk-model-ja-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-ja-0.22.zip", 1_000_000_000L, true),
            LanguageModel("uk", "Українська (Nano)", "vosk-model-small-uk-v3-nano",
                "https://alphacephei.com/vosk/models/vosk-model-small-uk-v3-nano.zip", 73_000_000L, false),
            LanguageModel("uk-full", "Українська (Full)", "vosk-model-uk-v3",
                "https://alphacephei.com/vosk/models/vosk-model-uk-v3.zip", 343_000_000L, true),
            LanguageModel("pl", "Polski (Small)", "vosk-model-small-pl-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-small-pl-0.22.zip", 50_000_000L, false),
            LanguageModel("hi", "हिन्दी (Small)", "vosk-model-small-hi-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip", 42_000_000L, false),
            LanguageModel("hi-full", "हिन्दी (Full)", "vosk-model-hi-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-hi-0.22.zip", 1_500_000_000L, true),
            LanguageModel("ko", "한국어 (Small)", "vosk-model-small-ko-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip", 82_000_000L, false),
            LanguageModel("tr", "Türkçe (Small)", "vosk-model-small-tr-0.3",
                "https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip", 35_000_000L, false),
            LanguageModel("vi", "Tiếng Việt (Small)", "vosk-model-small-vn-0.4",
                "https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip", 32_000_000L, false),
            LanguageModel("nl", "Nederlands (Small)", "vosk-model-small-nl-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-small-nl-0.22.zip", 39_000_000L, false),
            LanguageModel("ca", "Català (Small)", "vosk-model-small-ca-0.4",
                "https://alphacephei.com/vosk/models/vosk-model-small-ca-0.4.zip", 42_000_000L, false),
            LanguageModel("fa", "فارسی (Small)", "vosk-model-small-fa-0.5",
                "https://alphacephei.com/vosk/models/vosk-model-small-fa-0.5.zip", 47_000_000L, false),
            LanguageModel("fa-full", "فارسی (Full)", "vosk-model-fa-0.5",
                "https://alphacephei.com/vosk/models/vosk-model-fa-0.5.zip", 1_000_000_000L, true),
            LanguageModel("kz", "Қазақша (Small)", "vosk-model-small-kz-0.15",
                "https://alphacephei.com/vosk/models/vosk-model-small-kz-0.15.zip", 42_000_000L, false),
            LanguageModel("sv", "Svenska (Small)", "vosk-model-small-sv-rhasspy-0.15",
                "https://alphacephei.com/vosk/models/vosk-model-small-sv-rhasspy-0.15.zip", 35_000_000L, false),
            LanguageModel("cs", "Čeština (Small)", "vosk-model-small-cs-0.4-rhasspy",
                "https://alphacephei.com/vosk/models/vosk-model-small-cs-0.4-rhasspy.zip", 44_000_000L, false),
            LanguageModel("el", "Ελληνικά (Small)", "vosk-model-el-gr-0.7",
                "https://alphacephei.com/vosk/models/vosk-model-el-gr-0.7.zip", 54_000_000L, false),
            LanguageModel("id", "Bahasa Indonesia", "vosk-model-small-id-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-small-id-0.22.zip", 42_000_000L, false),
        )

        val SMALL_MODELS = AVAILABLE_LANGUAGES.filter { !it.isFullSize }
        val FULL_MODELS = AVAILABLE_LANGUAGES.filter { it.isFullSize }

        fun getLanguageByCode(code: String): LanguageModel =
            AVAILABLE_LANGUAGES.find { it.code == code } ?: run {
                DebugLog.w(
                    "VoskTranscriber",
                    "Language code '$code' not found in AVAILABLE_LANGUAGES, falling back to '${AVAILABLE_LANGUAGES.first().code}'"
                )
                AVAILABLE_LANGUAGES.first()
            }

        fun getBaseLanguageCode(code: String): String =
            code.split("-").first()

        private val CJK_RANGES = arrayOf(
            '\u3400'..'\u4DBF',  // CJK Ext A
            '\u4E00'..'\u9FFF',  // CJK Unified
            '\uF900'..'\uFAFF',  // CJK Compat
            '\u3000'..'\u303F',  // CJK Symbols / punctuation
            '\uFF00'..'\uFFEF',  // Fullwidth forms（，。？：！「」）
        )

        private fun isCjkChar(ch: Char): Boolean {
            val cp = ch.code
            for (range in CJK_RANGES) if (cp in range.first.code..range.last.code) return true
            return false
        }

        /**
         * Vosk's zh models emit every word space-separated (e.g. "减慢 晚上 前 了 我 教养 桂花"),
         * which is noisy for a chat input box. This compacts adjacent CJK segments into a natural
         * stream while preserving punctuation and leaving non-Chinese text (English, numbers,
         * timestamps untouched), so output stays readable for any language.
         */
        fun normalizeResult(raw: String): String {
            if (raw.isBlank()) return raw
            val tokens = raw.trim().split(' ')
            val sb = StringBuilder(tokens.sumOf { it.length } + raw.length / 2)
            for ((index, token) in tokens.withIndex()) {
                if (index > 0) {
                    val prevChar = sb.last()
                    val nextChar = token.firstOrNull() ?: ' '
                    if (isCjkChar(prevChar) && isCjkChar(nextChar)) {
                        // join two CJK tokens without a space; skip otherwise
                    } else {
                        sb.append(' ')
                    }
                }
                sb.append(token)
            }
            val joined = sb.toString().trim()
            // Clean stray full/half-width whitespace that can appear around CJK punctuation.
            return joined.replace(Regex("\\s+([，。、；：！？〕」』\\)]|[,.;:!?)])(?=\\s*[\\u3400-\\u9FFF\\uFF00-\\uFFEF])")) { m ->
                m.groupValues[1]
            }.trim()
        }
    }

    data class LanguageModel(
        val code: String,
        val displayName: String,
        val modelName: String,
        val downloadUrl: String,
        val sizeBytes: Long,
        val isFullSize: Boolean = false
    )

    private var model: Model? = null
    private var isModelLoaded = false
    private var currentLanguage: String = "en"

    private var secondaryModel: Model? = null
    private var secondaryLanguage: String? = null
    private var isMultilingualEnabled = false

    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress

    fun isReady(): Boolean = isModelLoaded && model != null

    fun getCurrentLanguage(): String = currentLanguage

    fun isMultilingual(): Boolean = isMultilingualEnabled && secondaryModel != null

    fun needsModelDownload(languageCode: String = currentLanguage): Boolean {
        val modelDir = getModelDirectory(languageCode)
        return !modelDir.exists() || !File(modelDir, "am/final.mdl").exists()
    }

    fun needsModelDownload(): Boolean = needsModelDownload(currentLanguage)

    fun getDownloadedLanguages(): List<String> {
        return AVAILABLE_LANGUAGES
            .filter { !needsModelDownload(it.code) }
            .map { it.code }
    }

    fun deleteModel(languageCode: String): Boolean {
        val modelDir = getModelDirectory(languageCode)

        if (languageCode == currentLanguage && isModelLoaded) {
            releaseModels()
        }

        if (languageCode == secondaryLanguage && secondaryModel != null) {
            secondaryModel?.close()
            secondaryModel = null
            secondaryLanguage = null
            isMultilingualEnabled = false
        }

        return try {
            if (modelDir.exists()) {
                modelDir.deleteRecursively()
                Log.i(TAG, "Deleted model for $languageCode")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete model $languageCode", e)
            false
        }
    }

    fun getModelSize(languageCode: String): Long {
        val modelDir = getModelDirectory(languageCode)
        return if (modelDir.exists()) {
            modelDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        } else 0L
    }

    suspend fun initialize(languageCode: String = currentLanguage): Boolean = withContext(Dispatchers.IO) {
        if (isModelLoaded && model != null && currentLanguage == languageCode) {
            return@withContext true
        }

        releaseModels()
        System.gc()

        try {
            val modelDir = getModelDirectory(languageCode)

            val possibleMdlFiles = listOf(
                "am/final.mdl",
                "am/model.mdl",
                "final.mdl",
                "model.mdl"
            )

            val foundMdlFile = possibleMdlFiles.map { File(modelDir, it) }.find { it.exists() }
                ?: modelDir.walkTopDown().find { it.isFile && it.name.endsWith(".mdl") }

            if (!modelDir.exists() || foundMdlFile == null) {
                Log.w(TAG, "Vosk model for $languageCode not found at ${modelDir.absolutePath}")
                if (modelDir.exists()) {
                    Log.w(TAG, "Model directory contents:")
                    modelDir.walkTopDown().forEach { file ->
                        Log.w(TAG, "  ${file.relativeTo(modelDir)} (${if (file.isDirectory) "dir" else "file"})")
                    }
                    val mdlFiles = modelDir.walkTopDown().filter { it.isFile && it.name.endsWith(".mdl") }.toList()
                    if (mdlFiles.isNotEmpty()) {
                        Log.w(TAG, "Found .mdl files: ${mdlFiles.joinToString { it.relativeTo(modelDir).toString() }}")
                    } else {
                        Log.w(TAG, "No .mdl files found in model directory")
                    }
                }
                return@withContext false
            }

            val expectedPath = "am/final.mdl"
            if (foundMdlFile.absolutePath != File(modelDir, expectedPath).absolutePath) {
                Log.i(TAG, "Found model file at ${foundMdlFile.relativeTo(modelDir)} instead of $expectedPath")
            }

            val rt = Runtime.getRuntime()
            val freeHeapMb = (rt.maxMemory() - rt.totalMemory() + rt.freeMemory()) / 1024 / 1024
            Log.i(TAG, "Loading Vosk model for $languageCode from ${modelDir.absolutePath} (free heap ~${freeHeapMb}MB)")

            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)

            model = Model(modelDir.absolutePath)
            isModelLoaded = true
            currentLanguage = languageCode

            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)

            Log.i(TAG, "Vosk model loaded for $languageCode")
            return@withContext true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            when (e) {
                is OutOfMemoryError -> {
                    Log.e(TAG, "Out of memory loading Vosk model for $languageCode", e)
                    System.gc()
                }
                else -> {
                    // Catch Error too (e.g. UnsatisfiedLinkError from the native vosk lib).
                    // The previous catch(Exception) missed it, so the failure was silent.
                    Log.e(TAG, "Failed to load Vosk model for $languageCode: ${e.message}", e)
                    Log.e(TAG, "Exception type: ${e.javaClass.name}")
                    e.cause?.let { Log.e(TAG, "Cause: ${it.message}", it) }
                }
            }
            isModelLoaded = false
            return@withContext false
        }
    }

    private fun releaseModels() {
        model?.close()
        model = null
        secondaryModel?.close()
        secondaryModel = null
        isModelLoaded = false
        secondaryLanguage = null
        isMultilingualEnabled = false
    }

    suspend fun initialize(): Boolean = initialize(currentLanguage)

    suspend fun initializeMultilingual(
        primaryLanguage: String,
        secondaryLanguageCode: String
    ): Boolean = withContext(Dispatchers.IO) {
        val primaryResult = initialize(primaryLanguage)
        if (!primaryResult) return@withContext false

        val runtime = Runtime.getRuntime()
        val freeMemory = runtime.freeMemory()
        val totalMemory = runtime.totalMemory()
        val maxMemory = runtime.maxMemory()
        val availableMemory = maxMemory - totalMemory + freeMemory

        val minRequiredMemory = 200 * 1024 * 1024L

        if (availableMemory < minRequiredMemory) {
            Log.w(TAG, "Not enough memory for multilingual mode (available: ${availableMemory / 1024 / 1024}MB)")
            isMultilingualEnabled = false
            return@withContext true
        }

        try {
            val secondaryModelDir = getModelDirectory(secondaryLanguageCode)
            if (secondaryModelDir.exists() && File(secondaryModelDir, "am/final.mdl").exists()) {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)

                secondaryModel?.close()
                secondaryModel = Model(secondaryModelDir.absolutePath)
                secondaryLanguage = secondaryLanguageCode
                isMultilingualEnabled = true

                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)

                Log.i(TAG, "Multilingual mode enabled: $primaryLanguage + $secondaryLanguageCode")
                return@withContext true
            } else {
                Log.w(TAG, "Secondary model not available for $secondaryLanguageCode")
                isMultilingualEnabled = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load secondary model", e)
            isMultilingualEnabled = false
        }
        return@withContext primaryResult
    }

    fun setMultilingualEnabled(enabled: Boolean) {
        isMultilingualEnabled = enabled
    }

    fun downloadModel(languageCode: String = "en"): Flow<DownloadState> = callbackFlow {
        val langModel = getLanguageByCode(languageCode)
        trySend(DownloadState.Downloading(0, 0, langModel.sizeBytes))

        withContext(Dispatchers.IO) {
            try {
                val modelDir = getModelDirectory(languageCode)
                val parentDir = modelDir.parentFile ?: context.filesDir

                if (!parentDir.exists()) {
                    parentDir.mkdirs()
                }

                Log.i(TAG, "Downloading Vosk model for $languageCode (${langModel.sizeBytes / 1_000_000}MB)")

                val url = java.net.URL(langModel.downloadUrl)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 60000
                connection.readTimeout = 300000
                connection.setRequestProperty("User-Agent", "LxChat/1.0")
                connection.connect()

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    trySend(DownloadState.Error("HTTP error: $responseCode"))
                    close()
                    return@withContext
                }

                val totalBytes = connection.contentLengthLong.let {
                    if (it > 0) it else langModel.sizeBytes
                }

                val zipFile = File(parentDir, "${langModel.modelName}.zip")
                var bytesDownloaded = 0L
                connection.inputStream.use { input ->
                    zipFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            bytesDownloaded += bytesRead

                            val progress = ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                            _downloadProgress.value = progress
                            trySend(DownloadState.Downloading(progress, bytesDownloaded, totalBytes))
                        }
                    }
                }

                Log.i(TAG, "Download complete (${bytesDownloaded / 1_000_000}MB), extracting...")
                trySend(DownloadState.Extracting(0))
                _downloadProgress.value = 0

                extractZipWithProgress(zipFile, parentDir) { progress ->
                    trySend(DownloadState.Extracting(progress))
                    _downloadProgress.value = progress
                }

                trySend(DownloadState.Extracting(95))
                _downloadProgress.value = 95

                val extractedDir = File(parentDir, langModel.modelName)
                if (extractedDir.exists() && extractedDir != modelDir) {
                    if (modelDir.exists()) {
                        modelDir.deleteRecursively()
                    }
                    val renamed = extractedDir.renameTo(modelDir)
                    if (!renamed) {
                        Log.w(TAG, "Failed to rename, copying instead...")
                        extractedDir.copyRecursively(modelDir, overwrite = true)
                        extractedDir.deleteRecursively()
                    }
                }

                if (zipFile.exists()) {
                    zipFile.delete()
                }

                Log.i(TAG, "Extraction complete, initializing model...")
                if (initialize(languageCode)) {
                    Log.i(TAG, "Model $languageCode ready!")
                    trySend(DownloadState.Complete(modelDir))
                } else {
                    trySend(DownloadState.Error("Failed to initialize model"))
                }

            } catch (e: Exception) {
                Log.e(TAG, "Download failed for $languageCode", e)
                // 局部快照便于 when 分支智能转换，避免 !! 强解
                val rawMsg = e.message
                val errorMsg = when {
                    e is java.net.UnknownHostException -> "No internet connection"
                    e is java.net.SocketTimeoutException -> "Connection timed out"
                    e is javax.net.ssl.SSLException -> "SSL/TLS error - check network settings"
                    e is java.io.IOException -> "Network error: ${e.localizedMessage ?: "IO error"}"
                    rawMsg.isNullOrBlank() -> "Unknown error (${e.javaClass.simpleName})"
                    else -> rawMsg
                }
                trySend(DownloadState.Error("Download failed: $errorMsg"))
            }
        }

        close()
        awaitClose { }
    }

    suspend fun transcribe(audioFile: File): String = withContext(Dispatchers.IO) {
        if (!isReady()) {
            if (!initialize()) {
                return@withContext "[Voice recognition unavailable - download model in settings]"
            }
        }

        val currentModel = model ?: return@withContext "[Model not loaded]"

        try {
            Log.i(TAG, "Transcribing with language: $currentLanguage" +
                if (isMultilingualEnabled && secondaryModel != null) " + $secondaryLanguage (multilingual)" else "" +
                ", file: ${audioFile.name}")

            val audioBytes = readWavFile(audioFile)
            Log.i(TAG, "Read ${audioBytes.size} bytes PCM (${audioBytes.size / (SAMPLE_RATE.toInt() * 2)}s of 16kHz mono) from ${audioFile.name}")

            val primaryResult = recognizeWithModel(currentModel, audioBytes, "primary")

            // 局部快照：secondaryModel 为可变 var，可能被并发释放置空，避免 !! 强解
            val secondary = secondaryModel
            if (isMultilingualEnabled && secondary != null) {
                val secondaryResult = recognizeWithModel(secondary, audioBytes, "secondary")

                val finalResult = mergeMultilingualResults(primaryResult, secondaryResult)
                Log.i(TAG, "Multilingual transcription: $finalResult")
                val normalizedMixed = normalizeResult(finalResult)
                return@withContext normalizedMixed.ifBlank {
                    "[Could not transcribe audio - speak louder or closer to mic]"
                }
            }

            if (primaryResult.isBlank()) {
                return@withContext "[Could not transcribe audio - speak louder or closer to mic]"
            }

            Log.i(TAG, "Transcription ($currentLanguage): $primaryResult")
            return@withContext normalizeResult(primaryResult)

        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed for $currentLanguage", e)
            return@withContext "[Transcription error: ${e.message}]"
        }
    }

    private fun recognizeWithModel(model: Model, audioBytes: ByteArray, label: String): String {
        if (audioBytes.isEmpty()) {
            Log.w(TAG, "recognizeWithModel($label): empty audio buffer — nothing to transcribe")
        }
        val recognizer = Recognizer(model, SAMPLE_RATE)
        recognizer.setMaxAlternatives(0)
        recognizer.setWords(true)

        val chunkSize = 8000
        var offset = 0
        var lastPartial = ""
        Log.i(TAG, "recognizeWithModel($label): ${audioBytes.size} bytes, ${audioBytes.size / chunkSize + 1} chunks")

        while (offset < audioBytes.size) {
            val end = minOf(offset + chunkSize, audioBytes.size)
            val chunk = audioBytes.copyOfRange(offset, end)

            if (recognizer.acceptWaveForm(chunk, chunk.size)) {
                val partial = recognizer.partialResult
                val partialText = JSONObject(partial).optString("partial", "")
                if (partialText.isNotBlank()) {
                    lastPartial = partialText
                    Log.d(TAG, "$label partial: $partialText")
                }
            }
            offset = end
        }

        val resultJson = recognizer.finalResult
        Log.d(TAG, "$label final result JSON: $resultJson")

        val result = JSONObject(resultJson).optString("text", "")
        recognizer.close()

        return if (result.isBlank() && lastPartial.isNotBlank()) {
            Log.i(TAG, "Using $label partial result: $lastPartial")
            lastPartial.trim()
        } else {
            result.trim()
        }
    }

    private fun mergeMultilingualResults(primary: String, secondary: String): String {
        if (primary.isBlank()) return secondary
        if (secondary.isBlank()) return primary

        val primaryWords = primary.split(" ").filter { it.isNotBlank() }
        val secondaryWords = secondary.split(" ").filter { it.isNotBlank() }

        if (primaryWords.size > secondaryWords.size * 2) return primary
        if (secondaryWords.size > primaryWords.size * 2) return secondary

        return if (primaryWords.size >= secondaryWords.size) primary else secondary
    }

    private fun readWavFile(file: File): ByteArray {
        Log.i(TAG, "readWavFile: ${file.name} (${file.length()} bytes on disk)")
        FileInputStream(file).use { fis ->
            val header = ByteArray(44)
            fis.read(header)
            val data = fis.readBytes()
            if (data.isEmpty()) {
                Log.w(TAG, "readWavFile: no audio data after WAV header — file is empty or corrupt")
            }
            return data
        }
    }

    private fun extractZipWithProgress(
        zipFile: File,
        destDir: File,
        onProgress: (Int) -> Unit = {}
    ) {
        var totalEntries = 0
        java.util.zip.ZipInputStream(zipFile.inputStream()).use { zis ->
            while (zis.nextEntry != null) {
                totalEntries++
                zis.closeEntry()
            }
        }

        Log.i(TAG, "Extracting $totalEntries files from ${zipFile.name}")

        var extractedCount = 0
        java.util.zip.ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val newFile = File(destDir, entry.name)

                // Zip Slip protection: reject entries that escape the destination directory.
                // Require an exact match or a separator boundary so "/foo/bar" does not
                // falsely contain "/foo/barbaz" (prefix without path boundary).
                val canonicalDestDir = destDir.canonicalFile
                val canonicalTargetFile = newFile.canonicalFile
                if (canonicalTargetFile != canonicalDestDir &&
                    !canonicalTargetFile.path.startsWith(canonicalDestDir.path + File.separator)
                ) {
                    throw SecurityException("Zip Slip detected: entry '${entry.name}' escapes target directory $destDir")
                }

                try {
                    if (entry.isDirectory) {
                        newFile.mkdirs()
                    } else {
                        newFile.parentFile?.mkdirs()
                        newFile.outputStream().use { fos ->
                            val buffer = ByteArray(8192)
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                fos.write(buffer, 0, len)
                            }
                        }
                    }

                    extractedCount++

                    if (extractedCount % 10 == 0 || extractedCount == totalEntries) {
                        val progress = ((extractedCount * 90) / totalEntries).coerceIn(0, 90)
                        onProgress(progress)
                        Log.d(TAG, "Extraction progress: $extractedCount/$totalEntries files ($progress%)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to extract ${entry.name}", e)
                    throw e
                }

                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        Log.i(TAG, "Extracted $extractedCount files successfully")
    }

    private fun getModelDirectory(languageCode: String = currentLanguage): File {
        return File(context.filesDir, "vosk/model-$languageCode")
    }

    // ------------------------------------------------------------------
    // Streaming real-time transcription (ChatGPT/Gemini live voice mode)
    // ------------------------------------------------------------------

    /**
     * Callback for streaming transcription. Partial results update the live
     * transcript display; final results fire when Vosk detects an endpoint or
     * [endSegment] is called explicitly. Errors are surfaced for fallback.
     */
    interface StreamingTranscriptionCallback {
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onError(error: String)
    }

    @Volatile private var streamingRecognizer: Recognizer? = null
    @Volatile private var streamingCallback: StreamingTranscriptionCallback? = null
    private val streamingLock = Any()

    /**
     * Start a streaming recognition session. Auto-initializes the model if the
     * model files exist on disk but have not been loaded yet. Returns true on
     * success.
     */
    suspend fun startStreamingSession(languageCode: String, callback: StreamingTranscriptionCallback): Boolean {
        // Auto-initialize when model files exist but haven't been loaded yet.
        // This handles the case where the model was downloaded but initialize()
        // was never called (e.g., process restart, or caller forgot to init).
        // Must happen outside synchronized block because initialize() uses
        // withContext(Dispatchers.IO) which switches threads.
        if (!isModelLoaded || model == null) {
            val modelDir = getModelDirectory(languageCode)
            val mdlFile = File(modelDir, "am/final.mdl")
            if (mdlFile.exists()) {
                Log.i(TAG, "startStreamingSession: model not loaded but files exist, auto-initializing for $languageCode")
                initialize(languageCode)
            }
        }
        synchronized(streamingLock) {
            if (!isModelLoaded || model == null) {
                Log.w(TAG, "startStreamingSession: model not loaded for $languageCode")
                callback.onError("Vosk model not loaded")
                return false
            }
            stopStreamingSessionInternal()
            return try {
                val recognizer = Recognizer(model, SAMPLE_RATE)
                recognizer.setMaxAlternatives(0)
                recognizer.setWords(true)
                streamingRecognizer = recognizer
                streamingCallback = callback
                Log.i(TAG, "Streaming session started (lang=$currentLanguage)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start streaming session", e)
                callback.onError("Failed to start streaming: ${e.message}")
                false
            }
        }
    }

    /**
     * Feed PCM 16-bit mono 16kHz audio to the streaming recognizer. Fires partial
     * results per chunk and a final result when Vosk detects an endpoint.
     */
    fun acceptWaveform(pcmData: ByteArray) {
        if (pcmData.isEmpty()) return
        synchronized(streamingLock) {
            val recognizer = streamingRecognizer ?: return
            val callback = streamingCallback ?: return
            try {
                val hasResult = recognizer.acceptWaveForm(pcmData, pcmData.size)
                if (hasResult) {
                    val finalJson = recognizer.finalResult
                    val finalText = normalizeResult(JSONObject(finalJson).optString("text", "").trim())
                    recognizer.reset()
                    Log.d(TAG, "stream endpoint detected by Vosk: final='$finalText'")
                    if (finalText.isNotBlank()) {
                        callback.onFinalResult(finalText)
                    }
                } else {
                    val partialJson = recognizer.partialResult
                    val partialText = normalizeResult(JSONObject(partialJson).optString("partial", ""))
                    if (partialText.isNotBlank()) {
                        Log.d(TAG, "stream partial: $partialText")
                        callback.onPartialResult(partialText)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "acceptWaveform failed", e)
                callback.onError("Waveform accept failed: ${e.message}")
            }
        }
    }

    /**
     * Force-end the current segment (e.g. on VAD silence). Returns the final text
     * or null if empty. The recognizer is reset for the next segment.
     */
    fun endSegment(): String? {
        synchronized(streamingLock) {
            val recognizer = streamingRecognizer ?: return null
            return try {
                val finalJson = recognizer.finalResult
                val finalText = normalizeResult(JSONObject(finalJson).optString("text", "").trim())
                recognizer.reset()
                Log.d(TAG, "endSegment: '$finalText'")
                finalText.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                Log.e(TAG, "endSegment failed", e)
                null
            }
        }
    }

    /** Stop the streaming session and release the recognizer. */
    fun stopStreamingSession() {
        synchronized(streamingLock) { stopStreamingSessionInternal() }
    }

    private fun stopStreamingSessionInternal() {
        try { streamingRecognizer?.close() } catch (e: Exception) {
            Log.e(TAG, "Failed to close streaming recognizer", e)
        }
        streamingRecognizer = null
        streamingCallback = null
    }

    fun release() {
        stopStreamingSession()
        releaseModels()
        System.gc()
    }

    fun getDiagnosticText(): String {
        val sb = StringBuilder()
        sb.append("=== VoskTranscriber Diagnostics ===\n")
        sb.append("isReady: ${isReady()}\n")
        sb.append("isModelLoaded: $isModelLoaded\n")
        sb.append("currentLanguage: $currentLanguage\n")
        sb.append("isMultilingual: ${isMultilingual()}\n")
        sb.append("secondaryLanguage: $secondaryLanguage\n")
        sb.append("downloadProgress: ${_downloadProgress.value}%\n")
        sb.append("Downloaded languages: ${getDownloadedLanguages()}\n")
        for (lang in AVAILABLE_LANGUAGES) {
            val dir = getModelDirectory(lang.code)
            if (dir.exists()) {
                val size = getModelSize(lang.code)
                sb.append("  ${lang.code}: ${size / 1_000_000}MB at ${dir.absolutePath}\n")
                // Key files a vosk model needs: the acoustic model + conf. If any are missing
                // the native Model() constructor fails even though the dir looks "downloaded".
                val amFinal = File(dir, "am/final.mdl").exists()
                val amModel = File(dir, "am/model.mdl").exists()
                val flatMdl = File(dir, "final.mdl").exists() || File(dir, "model.mdl").exists()
                val mfcc = File(dir, "conf/mfcc.conf").exists()
                val modelConf = File(dir, "conf/model.conf").exists()
                sb.append("      am/final.mdl=$amFinal am/model.mdl=$amModel flatMdl=$flatMdl conf/mfcc.conf=$mfcc conf/model.conf=$modelConf\n")
            }
        }
        if (!isModelLoaded) {
            // Report which required files are missing (no native Model() load here — this runs
            // on the UI thread from the diagnostics screen and must not block).
            sb.append("en load probe: ${getLoadFailureReason("en") ?: "required files present (load error will be logged on next attempt)"}\n")
        }
        return sb.toString()
    }

    /**
     * Read-only check of the files a vosk model needs to load. Returns null when the model
     * directory looks complete, or a description of the missing pieces otherwise. It does NOT
     * construct a native [Model] (that runs on the UI thread from the diagnostics screen);
     * a native load failure (e.g. UnsatisfiedLinkError) is instead recorded by [initialize]'s
     * catch-all Throwable handler and appears in AppLog.
     */
    fun getLoadFailureReason(languageCode: String = currentLanguage): String? {
        val modelDir = getModelDirectory(languageCode)
        if (!modelDir.exists()) return "model dir missing at ${modelDir.absolutePath}"
        val missing = mutableListOf<String>()
        val hasMdl = File(modelDir, "am/final.mdl").exists() ||
            File(modelDir, "am/model.mdl").exists() ||
            File(modelDir, "final.mdl").exists() ||
            File(modelDir, "model.mdl").exists()
        if (!hasMdl) missing.add("acoustic model (.mdl)")
        if (!File(modelDir, "conf/mfcc.conf").exists()) missing.add("conf/mfcc.conf")
        if (!File(modelDir, "conf/model.conf").exists()) missing.add("conf/model.conf")
        return if (missing.isEmpty()) null else "missing: ${missing.joinToString(", ")}"
    }

    sealed class DownloadState {
        object NotStarted : DownloadState()
        data class Downloading(val progress: Int, val bytesDownloaded: Long, val totalBytes: Long) : DownloadState()
        data class Extracting(val progress: Int) : DownloadState()
        data class Complete(val file: File) : DownloadState()
        data class Error(val message: String) : DownloadState()
    }
}
