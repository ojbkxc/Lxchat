package com.lxseek.chat.speech

import com.lxseek.chat.util.AppLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class WhisperTranscriber(
    private val apiKeyProvider: () -> String?,
    private val baseUrlProvider: () -> String = { "https://api.groq.com/openai/v1/audio/transcriptions" },
    private val modelProvider: () -> String = { "whisper-large-v3" },
) {
    companion object {
        private const val TAG = "WhisperTranscriber"
        private const val TIMEOUT_MS = 60000
        private const val TRANSCRIPTION_PATH = "/audio/transcriptions"

        /** Accepts either a full transcription endpoint or an OpenAI-compatible base URL and
         *  returns the real transcription URL. Providers hand the app their LLM chat base (e.g.
         *  "…/openai/v1"); sending that verbatim as the POST target 404s, so we append the
         *  speech path when it is missing. */
        fun normalizeTranscriptionUrl(raw: String): String {
            val base = raw.trim().trimEnd('/')
            if (base.isEmpty()) return base
            return if (base.endsWith(TRANSCRIPTION_PATH)) base else "$base$TRANSCRIPTION_PATH"
        }
    }

    suspend fun transcribe(
        audioFile: File,
        language: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            return@withContext Result.failure(Exception("API key not configured"))
        }

        var connection: HttpsURLConnection? = null
        try {
            val url = URL(normalizeTranscriptionUrl(baseUrlProvider()))
            val boundary = "----WebKitFormBoundary${System.currentTimeMillis()}"

            connection = (url.openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            connection.outputStream.use { output ->
                val writer = output.bufferedWriter()

                writer.write("--$boundary\r\n")
                writer.write("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
                writer.write(modelProvider())
                writer.write("\r\n")

                language?.let {
                    writer.write("--$boundary\r\n")
                    writer.write("Content-Disposition: form-data; name=\"language\"\r\n\r\n")
                    writer.write(it)
                    writer.write("\r\n")
                }

                writer.write("--$boundary\r\n")
                writer.write("Content-Disposition: form-data; name=\"response_format\"\r\n\r\n")
                writer.write("json")
                writer.write("\r\n")

                writer.write("--$boundary\r\n")
                writer.write("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n")
                writer.write("Content-Type: audio/wav\r\n\r\n")
                writer.flush()

                audioFile.inputStream().use { input ->
                    input.copyTo(output)
                }

                writer.write("\r\n--$boundary--\r\n")
                writer.flush()
            }

            val responseCode = connection.responseCode

            if (responseCode != 200) {
                val error = try {
                    connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                } catch (e: Exception) { "Error $responseCode" }
                Log.e(TAG, "Whisper API error: $error")
                return@withContext Result.failure(Exception("Transcription failed: $responseCode"))
            }

            val response = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            val text = json.optString("text", "").trim()

            if (text.isBlank()) {
                return@withContext Result.failure(Exception("No speech detected"))
            }

            Log.i(TAG, "Transcription successful: ${text.take(50)}...")
            Result.success(text)

        } catch (e: Exception) {
            Log.e(TAG, "Whisper transcription error", e)
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }
}
