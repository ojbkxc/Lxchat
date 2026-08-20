package com.lxseek.chat.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsModelsPageTest {
    @Test
    fun customModelsAreGroupedByProviderOrder() {
        val groups = customModelGroups(
            customModels = setOf(
                "Relay:zeta",
                "OpenAI:gpt-4.1",
                "Relay:alpha",
            ),
            providerOrder = listOf("OpenAI", "Relay"),
        )

        assertEquals(listOf("OpenAI", "Relay"), groups.map { it.providerName })
        assertEquals(listOf("Relay:alpha", "Relay:zeta"), groups[1].models)
    }

    @Test
    fun fetchedModelsExcludeManualEntries() {
        val groups = fetchedModelGroups(
            availableModels = linkedMapOf(
                "OpenAI" to listOf("OpenAI:gpt-4.1", "OpenAI:gpt-5"),
                "Relay" to listOf("Relay:sonnet"),
            ),
            customModels = setOf("OpenAI:gpt-5"),
            modelAliases = emptyMap(),
            query = "",
        )

        assertEquals(
            listOf("OpenAI:gpt-4.1", "Relay:sonnet"),
            groups.flatMap { it.models },
        )
    }

    @Test
    fun searchFiltersModelsAndDropsEmptyProviderGroups() {
        val groups = fetchedModelGroups(
            availableModels = linkedMapOf(
                "OpenAI" to listOf("OpenAI:gpt-4.1"),
                "Relay" to listOf("Relay:model-a", "Relay:model-b"),
            ),
            customModels = emptySet(),
            modelAliases = mapOf("Relay:model-b" to "Daily Sonnet"),
            query = "sonnet",
        )

        assertEquals(listOf("Relay"), groups.map { it.providerName })
        assertEquals(listOf("Relay:model-b"), groups.single().models)
    }

    @Test
    fun providerNameSearchKeepsThatProvidersModels() {
        val groups = fetchedModelGroups(
            availableModels = linkedMapOf(
                "OpenAI" to listOf("OpenAI:gpt-4.1"),
                "Relay" to listOf("Relay:model-a", "Relay:model-b"),
            ),
            customModels = emptySet(),
            modelAliases = emptyMap(),
            query = "relay",
        )

        assertEquals(listOf("Relay:model-a", "Relay:model-b"), groups.single().models)
    }
}
