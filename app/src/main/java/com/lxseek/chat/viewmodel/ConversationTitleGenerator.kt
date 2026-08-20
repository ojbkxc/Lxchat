package com.lxseek.chat.viewmodel

import com.lxseek.chat.api.LocalModelSerializer
import com.lxseek.chat.api.ProviderConfig
import com.lxseek.chat.api.StreamEvent
import com.lxseek.chat.data.BuiltInPrompts
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.model.ModelId
import com.lxseek.chat.model.Participant
import com.lxseek.chat.util.Constants
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val TITLE_WHITESPACE = Regex("\\s+")

internal fun fallbackConversationTitle(response: String): String =
    response.replace(TITLE_WHITESPACE, " ").trim().take(60)

/**
 * UI-independent conversation title generation shared by foreground chats and headless Tasks.
 * It owns provider/key resolution and persistence so both paths obey the same cold-start,
 * custom-provider, local-model serialization, and title-cleanup rules.
 */
class ConversationTitleGenerator(
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val providers: ProviderRegistry,
) {
    sealed interface Result {
        data class Success(val title: String) : Result
        data class Failure(val reason: String) : Result
    }

    suspend fun generateAndPersist(conversationId: String): Result {
        settings.awaitInitialLoad()
        providers.awaitInitialSync()

        val conversation = conversations.getConversation(conversationId)
            ?: return Result.Failure("Conversation not found")
        val snapshot = conversations.getMessagesForConversationSnapshot(conversationId)
        val path = ConversationUiState.resolvePath(
            allMessages = snapshot.map { entity ->
                ChatMessage(
                    id = entity.id,
                    parentId = entity.parentId,
                    text = entity.text,
                    participant = entity.participant,
                    timestamp = entity.timestamp,
                    status = entity.status,
                    modelName = entity.modelName,
                    runId = entity.runId,
                    runSequence = entity.runSequence,
                    consumedAtPass = entity.consumedAtPass,
                )
            },
            streamingMsg = null,
            selectedChildren = conversations.restoreBranchSelections(conversationId),
        )
        val firstUser = path.firstOrNull {
            it.participant == Participant.USER && it.text.isNotBlank()
        } ?: return Result.Failure("Conversation has no user message")
        val firstModel = path.firstOrNull {
            it.participant == Participant.MODEL && it.text.isNotBlank()
        }

        val configuredTitleModel = settings.titleGenerationModel.value
        val prefixedModelId = configuredTitleModel?.takeIf { it.isNotBlank() }
            ?: conversation.modelId?.takeIf { it.isNotBlank() }
            ?: firstModel?.modelName?.takeIf { it.isNotBlank() }
            ?: settings.selectedModel.value
        if (prefixedModelId.isBlank()) return Result.Failure("No title model selected")

        val providerName = providers.providerForModel(prefixedModelId)
        val activeKey = settings.awaitActiveKey(providerName)?.takeIf { it.isNotBlank() }
            ?: settings.resolveActiveKey(providerName).orEmpty()
        if (!providers.isConfigured(providerName, activeKey)) {
            return Result.Failure("Provider not configured: $providerName")
        }

        val summary = if (firstModel != null) {
            "User: ${firstUser.text}\nAssistant: ${firstModel.text.take(500)}"
        } else {
            firstUser.text
        }
        val titlePrompt = listOf(
            ChatMessage(
                text = "Generate a short title (5 words maximum) for this conversation:\n\n" +
                    "$summary\n\nRespond with ONLY the title text, no quotes, no punctuation, " +
                    "no explanation.",
                participant = Participant.USER,
                status = MessageStatus.SUCCESS,
            )
        )
        val modelId = ModelId.parse(prefixedModelId).modelName
        val provider = providers.getInstanceOrNull(providerName)
            ?: return Result.Failure("Provider not registered: $providerName")
        val config = ProviderConfig(
            apiKey = activeKey,
            modelId = modelId,
            systemPrompt = settings.titleGenerationPrompt.value.ifBlank {
                BuiltInPrompts.TITLE_GENERATION_SYSTEM
            },
            maxContextWindow = com.lxseek.chat.model.ContextBudget.MIN_TOKENS,
            thinkingEnabled = false,
            baseUrl = providers.getEffectiveBaseUrl(providerName),
        )

        var title = ""
        var providerError: String? = null
        suspend fun collectTitle() {
            provider.generateResponse(titlePrompt, config).collect { event ->
                when (event) {
                    is StreamEvent.TextChunk -> title += event.text
                    is StreamEvent.Error -> providerError = event.message
                    else -> Unit
                }
            }
        }

        try {
            if (providerName == Constants.PROVIDER_LOCAL) {
                LocalModelSerializer.mutex.withLock {
                    withContext(Dispatchers.IO) { collectTitle() }
                }
            } else {
                collectTitle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e(
                "ConversationTitleGenerator",
                "Title generation failed for provider=$providerName model=$modelId",
                e,
            )
            return Result.Failure(e.localizedMessage ?: "Title generation failed")
        }

        providerError?.let { error ->
            DebugLog.e("ConversationTitleGenerator", "Title generation error: $error")
            return Result.Failure(error)
        }
        val cleaned = fallbackConversationTitle(title)
        if (cleaned.isBlank()) return Result.Failure("Provider returned an empty title")

        if (
            conversations.updateConversationTitleIfUnchanged(
                id = conversationId,
                expectedTitle = conversation.title,
                newTitle = cleaned,
            )
        ) {
            return Result.Success(cleaned)
        }
        val current = conversations.getConversation(conversationId)
            ?: return Result.Failure("Conversation was deleted")
        // A manual rename (or another title generation) won the race. Preserve the newer title
        // and report success so headless task fallback cannot overwrite it immediately afterward.
        return Result.Success(current.title)
    }
}
