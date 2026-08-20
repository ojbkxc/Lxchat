package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.EmbeddingModelConfig
import com.lxseek.chat.data.EmbeddingModelType
import com.lxseek.chat.data.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SemanticSearchServiceTest {
    @Test
    fun searchForwardsQueryLimitAndCompletePointInTimeContext() = runTest {
        val settings = mockk<SettingsRepository>()
        every { settings.accessSavedMemories } returns state(false)
        every { settings.accessActiveMemory } returns state(true)
        every { settings.accessPastConversations } returns state(false)
        every { settings.modelSearchMethod } returns state("semantic")
        every { settings.ragThreshold } returns state(0.75f)
        every { settings.searchMatchLimit } returns state(11)
        every { settings.searchContextWindow } returns state(12)
        every { settings.webSearchEnabled } returns state(true)
        every { settings.webSearchApiKeys } returns state(mapOf("provider" to "key"))
        every { settings.webSearchProvider } returns state("provider")
        every { settings.webSearchNumResults } returns state(13)
        every { settings.webSearchBaseUrl } returns state("https://search")
        val embedding = EmbeddingModelConfig(
            id = "embedding",
            name = "Embedding",
            type = EmbeddingModelType.REMOTE,
        )
        var capturedQuery = ""
        var capturedLimit = 0
        lateinit var capturedContext: GenerationContext
        val service = SemanticSearchService(
            settings = settings,
            activeEmbeddingConfig = { embedding },
            resolveEmbeddingApiKey = { "embedding-key" },
            search = { query, limit, context ->
                capturedQuery = query
                capturedLimit = limit
                capturedContext = context
                emptyList()
            },
        )

        service.search("query", 17)

        assertEquals("query", capturedQuery)
        assertEquals(17, capturedLimit)
        assertEquals(
            GenerationContext(
                accessSavedMemories = false,
                accessActiveMemory = true,
                accessPastConversations = false,
                modelSearchMethod = "semantic",
                activeEmbeddingConfig = embedding,
                embeddingApiKey = "embedding-key",
                ragThreshold = 0.75f,
                searchMatchLimit = 11,
                searchContextWindow = 12,
                webSearchEnabled = true,
                webSearchApiKeys = mapOf("provider" to "key"),
                webSearchProvider = "provider",
                webSearchNumResults = 13,
                webSearchBaseUrl = "https://search",
            ),
            capturedContext,
        )
    }

    private companion object {
        fun <T> state(value: T) = MutableStateFlow(value)
    }
}
