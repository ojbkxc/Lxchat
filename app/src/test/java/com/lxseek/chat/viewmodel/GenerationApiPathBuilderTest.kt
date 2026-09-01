package com.lxseek.chat.viewmodel

import com.lxseek.chat.agent.GenerationConfig
import com.lxseek.chat.agent.GenerationContext
import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.Participant
import com.lxseek.chat.util.Constants
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationApiPathBuilderTest {
    @Test
    fun `caller snapshot produces compact-bounded path and exact provider config`() = runTest {
        val repository = mockk<ConversationRepository>(relaxed = true)
        // 构造函数新增了尾随可选参数 planStateHolder，trailing lambda 会错误地
        // 绑定到它；此处显式命名参数，将 lambda 绑定到 toolDefinitions。
        val builder = GenerationApiPathBuilder(repository, toolDefinitions = { emptyList() })
        val compact = message("${Constants.COMPACT_MSG_PREFIX}boundary", parentId = "old", sequence = 1)
        val user = message("user", parentId = compact.id, sequence = 2, participant = Participant.USER)
        val model = message("model", parentId = user.id, sequence = 3)

        val path = builder.build(
            GenerationApiPathRequest(
                parentId = model.id,
                conversationId = "conversation",
                isRegenerate = false,
                replaceMessageId = null,
                config = generationConfig(),
                context = GenerationContext(),
                loadedMessages = listOf(message("old", null, 0), compact, user, model),
            ),
        )

        assertEquals(listOf(compact.id, user.id, model.id), path.messages.map { it.id })
        assertEquals("model-id", path.providerConfig.modelId)
        assertEquals("system", path.providerConfig.systemPrompt)
        assertTrue(path.providerConfig.tools.orEmpty().isEmpty())
        coVerify(exactly = 0) { repository.getMessagesForConversationSnapshot(any()) }
    }

    @Test
    fun `regeneration excludes the replaced message and its suffix`() = runTest {
        val repository = mockk<ConversationRepository>(relaxed = true)
        val builder = GenerationApiPathBuilder(repository, toolDefinitions = { emptyList() })
        val user = message("user", null, 0, Participant.USER)
        val replaced = message("replaced", user.id, 1)

        val path = builder.build(
            GenerationApiPathRequest(
                parentId = replaced.id,
                conversationId = "conversation",
                isRegenerate = true,
                replaceMessageId = replaced.id,
                config = generationConfig(),
                context = GenerationContext(),
                loadedMessages = listOf(user, replaced),
            ),
        )

        assertEquals(listOf(user.id), path.messages.map { it.id })
    }

    private fun generationConfig() = GenerationConfig(
        providerName = "provider",
        modelId = "model-id",
        apiKey = "key",
        effectiveSystemPrompt = "system",
        codeExecutionEnabled = false,
        googleSearchEnabled = false,
        thinkingEnabled = false,
        baseUrl = null,
    )

    private fun message(
        id: String,
        parentId: String?,
        sequence: Long,
        participant: Participant = Participant.MODEL,
    ) = MessageEntity(
        id = id,
        conversationId = "conversation",
        parentId = parentId,
        text = id,
        status = MessageStatus.SUCCESS,
        participant = participant,
        timestamp = sequence,
        modelName = "model-id",
        runId = "run",
        runSequence = sequence,
    )
}
