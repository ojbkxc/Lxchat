package com.lxseek.chat.viewmodel

import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.data.repository.SettingsRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CustomModelConfigurationControllerTest {
    @Test
    fun `model replacement migrates settings then conversations before updating active projection`() =
        runTest {
            val settings = settings(setOf("Old:model"))
            val conversations = mockk<ConversationRepository>()
            coEvery { conversations.replaceConfiguredModelReferences(any(), any()) } returns Unit
            val callbacks = mutableListOf<Pair<String, String?>>()
            val controller = controller(
                conversations = conversations,
                settings = settings,
                onReplaced = { old, new -> callbacks += old to new },
            )

            controller.updateModel(
                oldModelId = "Old:model",
                provider = "  New  ",
                modelId = " model-v2 ",
                alias = "Alias",
            )
            runCurrent()

            coVerifyOrder {
                settings.replaceCustomModel("Old:model", "New:model-v2", "Alias")
                conversations.replaceConfiguredModelReferences("Old:model", "New:model-v2")
            }
            assertEquals(listOf("Old:model" to "New:model-v2"), callbacks)
        }

    @Test
    fun `missing duplicate and blank replacements are rejected before durable mutation`() =
        runTest {
            val settings = settings(setOf("Old:model", "New:duplicate"))
            val conversations = mockk<ConversationRepository>()
            val callbacks = mutableListOf<Pair<String, String?>>()
            val controller = controller(
                conversations = conversations,
                settings = settings,
                onReplaced = { old, new -> callbacks += old to new },
            )

            controller.updateModel("Missing:model", "New", "value", "")
            controller.updateModel("Old:model", "New", "duplicate", "")
            controller.updateModel("Old:model", " ", "value", "")
            runCurrent()

            coVerify(exactly = 0) { settings.replaceCustomModel(any(), any(), any()) }
            coVerify(exactly = 0) {
                conversations.replaceConfiguredModelReferences(any(), any())
            }
            assertEquals(emptyList<Pair<String, String?>>(), callbacks)
        }

    @Test
    fun `provider rename and model delete preserve registry and durable ordering`() = runTest {
        val providers = mockk<ProviderRegistry>()
        val settings = settings(setOf("Custom:model"))
        val conversations = mockk<ConversationRepository>()
        every { providers.renameCustom("Old", " New ") } returns true
        coEvery { conversations.renameConfiguredProviderModelReferences(any(), any()) } returns Unit
        coEvery { conversations.replaceConfiguredModelReferences(any(), any()) } returns Unit
        val callbacks = mutableListOf<Pair<String, String?>>()
        val controller = controller(
            providers = providers,
            conversations = conversations,
            settings = settings,
            onReplaced = { old, new -> callbacks += old to new },
        )

        controller.renameProvider("Old", " New ")
        controller.deleteModel("Custom:model")
        runCurrent()

        coVerify(exactly = 1) {
            conversations.renameConfiguredProviderModelReferences("Old", "New")
        }
        coVerifyOrder {
            settings.replaceCustomModel("Custom:model", null, "")
            conversations.replaceConfiguredModelReferences("Custom:model", null)
        }
        assertEquals(listOf("Custom:model" to null), callbacks)
    }

    private fun kotlinx.coroutines.test.TestScope.controller(
        providers: ProviderRegistry = mockk(),
        conversations: ConversationRepository,
        settings: SettingsRepository,
        onReplaced: (String, String?) -> Unit,
    ) = CustomModelConfigurationController(
        providers = providers,
        conversations = conversations,
        settings = settings,
        scope = this,
        onModelReferenceReplaced = onReplaced,
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun settings(models: Set<String>): SettingsRepository =
        mockk<SettingsRepository>().also { settings ->
            every { settings.customModels } returns MutableStateFlow(models)
            coEvery { settings.replaceCustomModel(any(), any(), any()) } just Runs
        }
}
