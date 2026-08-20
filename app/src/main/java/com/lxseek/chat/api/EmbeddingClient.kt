package com.lxseek.chat.api

import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
private data class EmbeddingRequest(
    val input: String,
    val model: String
)

@Serializable
private data class BatchEmbeddingRequest(
    val input: List<String>,
    val model: String
)

object EmbeddingClient {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun computeEmbedding(
        text: String,
        apiKey: String,
        model: String = "text-embedding-3-small",
        baseUrl: String = "https://api.openai.com/v1"
    ): FloatArray? = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/embeddings"
            val body = json.encodeToString(EmbeddingRequest(input = text, model = model))
            val headers = buildMap {
                put("Content-Type", "application/json")
                if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey")
            }
            val response = HttpClient.post(url, body, headers) ?: return@withContext null
            val parsed = json.parseToJsonElement(response).jsonObject
            val data = parsed["data"]?.jsonArray ?: return@withContext null
            val embedding = data.firstOrNull()
                ?.jsonObject
                ?.get("embedding")
                ?.jsonArray
                ?: return@withContext null
            FloatArray(embedding.size) { i -> embedding[i].jsonPrimitive.float }
        } catch (e: Exception) {
            DebugLog.e("EmbeddingClient", "computeEmbedding failed", e)
            null
        }
    }

    suspend fun computeEmbeddings(
        texts: List<String>,
        apiKey: String,
        model: String = "text-embedding-3-small",
        baseUrl: String = "https://api.openai.com/v1"
    ): List<FloatArray?> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        try {
            val url = "$baseUrl/embeddings"
            val body = json.encodeToString(BatchEmbeddingRequest(input = texts, model = model))
            val headers = buildMap {
                put("Content-Type", "application/json")
                if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey")
            }
            val response = HttpClient.post(url, body, headers)
                ?: return@withContext texts.map { null }
            val parsed = json.parseToJsonElement(response).jsonObject
            val data = parsed["data"]?.jsonArray
                ?: return@withContext texts.map { null }
            data.map { item ->
                val embedding = item.jsonObject["embedding"]?.jsonArray ?: return@map null
                FloatArray(embedding.size) { i -> embedding[i].jsonPrimitive.float }
            }
        } catch (e: Exception) {
            DebugLog.e("EmbeddingClient", "computeEmbeddings failed", e)
            texts.map { null }
        }
    }
}
