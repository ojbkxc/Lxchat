package com.lxseek.chat.viewmodel

import com.lxseek.chat.api.EmbeddingClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Executes one non-persisting embedding connectivity probe for the settings UI. */
internal class RemoteEmbeddingConnectionTester(
    private val resolveApiKey: () -> String?,
    private val resolveBaseUrl: () -> String,
    private val computeEmbedding: suspend (
        text: String,
        apiKey: String,
        modelName: String,
        baseUrl: String,
    ) -> FloatArray? = EmbeddingClient::computeEmbedding,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun test(
        modelName: String,
        baseUrl: String,
        apiKey: String = "",
    ): String? {
        val effectiveKey = apiKey.ifBlank { resolveApiKey().orEmpty() }
        val effectiveUrl = baseUrl.ifBlank(resolveBaseUrl)
        return withContext(ioDispatcher) {
            try {
                val result = computeEmbedding(
                    "test connection",
                    effectiveKey,
                    modelName,
                    effectiveUrl,
                )
                if (result != null) {
                    "OK (dim=${result.size})"
                } else {
                    "Request failed. Check API key, URL, and model name."
                }
            } catch (error: Exception) {
                error.message ?: "Error"
            }
        }
    }
}
