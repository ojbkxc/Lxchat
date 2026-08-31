package com.lxseek.chat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class DefaultSystemPromptTest {
    @Test
    fun titleForLocale_usesChineseDefaultForChineseLocale() {
        assertEquals("Default", DefaultSystemPrompt.titleForLocale(Locale.ENGLISH))
        assertEquals("\u9ed8\u8ba4", DefaultSystemPrompt.titleForLocale(Locale.SIMPLIFIED_CHINESE))
    }

    @Test
    fun create_includesActiveMemoryAndToolPolicy_omitsRuntimeContext() {
        val entry = DefaultSystemPrompt.create(Locale.ENGLISH)
        val systemPrompt = PredefinedVariables.compile(
            entry.systemItems,
            mapOf(
                PredefinedVariables.ACTIVE_MEMORY to "User prefers concise answers."
            )
        )

        assertFalse(systemPrompt.contains("<lxchat_runtime_context>"))
        assertFalse(systemPrompt.contains("<current_date>"))
        assertFalse(systemPrompt.contains("<current_time>"))
        assertTrue(systemPrompt.contains("<active_memory_context>\nUser prefers concise answers.\n</active_memory_context>"))
        // 37333c9 起默认提示词重写为中文简洁版：工具策略与会员二元制文案均以中文描述
        assertTrue(systemPrompt.contains("工具使用："))
        assertTrue(systemPrompt.contains("只用 LxChat 当前提供的工具"))
        assertFalse(systemPrompt.contains("generate_image"))
    }

    @Test
    fun hasOldRuntimeContext_detectsOldEntries() {
        val entry = DefaultSystemPrompt.create(Locale.ENGLISH)
        assertFalse(DefaultSystemPrompt.hasOldRuntimeContext(entry))

        // Simulate an old entry by injecting a custom item with the legacy tag
        val oldItems = entry.systemItems.toMutableList()
        oldItems.add(0, PromptTemplateItem(type = PromptItemType.CUSTOM, value = "prefix <lxchat_runtime_context> old content"))
        val oldEntry = entry.copy(systemItems = oldItems)
        assertTrue(DefaultSystemPrompt.hasOldRuntimeContext(oldEntry))
    }

    @Test
    fun create_wrapsUserMessagesWithSentDateAndTimeMetadata() {
        val entry = DefaultSystemPrompt.create(Locale.ENGLISH)
        val prefix = PredefinedVariables.compile(
            entry.userPrependItems,
            mapOf(
                PredefinedVariables.SENT_DATE to "2026-06-17",
                PredefinedVariables.SENT_TIME to "21:35:10"
            ),
            emptyMap()
        )
        val suffix = PredefinedVariables.compile(entry.userPostpendItems, emptyMap(), emptyMap())

        assertEquals("<lxchat_user_message sent_date=\"2026-06-17\" sent_time=\"21:35:10\">\n", prefix)
        assertEquals("\n</lxchat_user_message>", suffix)
    }
}
