package com.lxseek.chat.viewmodel

import com.lxseek.chat.model.ContextBudget
import com.lxseek.chat.model.ProviderPassResult
import com.lxseek.chat.model.RunEffect
import com.lxseek.chat.model.RunEffectIdentity
import com.lxseek.chat.model.RunEndReason
import com.lxseek.chat.model.RunStatus
import com.lxseek.chat.tool.AgentMode
import com.lxseek.chat.util.Constants

data class GenerationConfig(
    val providerName: String,
    val modelId: String,
    val apiKey: String,
    val effectiveSystemPrompt: String?,
    val maxContextWindow: Int = ContextBudget.DEFAULT_TOKENS,
    val codeExecutionEnabled: Boolean,
    val googleSearchEnabled: Boolean,
    val thinkingEnabled: Boolean,
    val thinkingLevel: String = "medium",
    val thinkingBudgetEnabled: Boolean = false,
    val thinkingBudgetTokens: Int = 4096,
    val openAiServiceTier: String? = null,
    val baseUrl: String?,
    val userPrepend: String? = null,
    val userPostpend: String? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null
)

data class GenerationContext(
    val conversationId: String? = null,
    val accessSavedMemories: Boolean = true,
    val accessActiveMemory: Boolean = true,
    val accessPastConversations: Boolean = true,
    val modelSearchMethod: String = "keyword",
    val activeEmbeddingConfig: com.lxseek.chat.data.EmbeddingModelConfig? = null,
    val embeddingApiKey: String = "",
    val ragThreshold: Float = 0.5f,
    val searchMatchLimit: Int = 10,
    val searchContextWindow: Int = 8,
    val webSearchEnabled: Boolean = false,
    val webSearchApiKeys: Map<String, String> = emptyMap(),
    val webSearchProvider: String = "duckduckgo",
    val webSearchNumResults: Int = 5,
    val webSearchBaseUrl: String = "",
    val imageGenEnabled: Boolean = false,
    val imageGenApiKey: String = "",
    val imageGenBaseUrl: String = "",
    val imageGenModel: String = "gpt-image-1",
    val imageGenSize: String = "1024x1024",
    val automationToolsEnabled: Boolean = false,
    /** Workers use WorkManager's foreground execution instead of starting our service. */
    val foregroundServiceManagedExternally: Boolean = false,
    val shellEnabled: Boolean = false,
    val shellDevices: List<com.lxseek.chat.data.ShellDeviceConfig> = emptyList(),
    val sandboxEnabled: Boolean = false,
    val sandboxSharedStorageEnabled: Boolean = false,
    val imageTranscriptionEnabled: Boolean = false,
    val imageTranscriptionModel: String? = null,
    val imageTranscriptionBatchSize: Int = 3,
    val imageTranscriptionPrompt: String = com.lxseek.chat.data.BuiltInPrompts.IMAGE_TRANSCRIPTION_USER,
    val transcriptionProviderName: String = "",
    val transcriptionModelId: String = "",
    val transcriptionApiKey: String = "",
    val transcriptionBaseUrl: String? = null,
    /** Wall-clock budget for a single tool execution; downgrades a blocking tool from a
     *  permanent generation hang to a recoverable tool error (#49). */
    val toolTimeoutMs: Long = Constants.TOOL_EXECUTION_TIMEOUT_MS,
    /** The agent execution mode that controls tool registration and approval policy. */
    val agentMode: AgentMode = AgentMode.Agent,
    /** Tool delivery tier: "core" (essential only), "extended" (+useful), "all" (+dangerous). Defaults to "all". */
    val toolTier: String = "all",
)

/**
 * The token-gated UI callbacks a single generation drives. Built once per call by
 * [ConversationGenerationState.callbacksFor], so each generation entry point
 * ([MessageGenerationController]'s send / regenerate / edit) wires the per-conversation
 * ownership tokens in exactly one place instead of re-threading lambdas by hand.
 *
 * Note: the generation-slot lifecycle (generating flag / active-conversation set) is owned by
 * [ConversationGenerationState]. These callbacks project token-gated UI state and carry identified
 * tool effects/results; GenerationManager does not directly mutate the process Run state.
 */
data class GenerationCallbacks(
    val onStreamUpdate: (com.lxseek.chat.model.ChatMessage) -> Unit,
    val onLoadingChange: (Boolean) -> Unit,
    val onStreamClear: () -> Unit,
    val isLatestPersist: () -> Boolean,
    /** The conversation mailbox must authorize every Provider pass before network execution. */
    val onProviderPassRequested: suspend (RunEffectIdentity) -> RunEffect.StartProviderPass?,
    /** The mailbox accepts the closed outcome before any text/tool continuation is consumed. */
    val onProviderPassCompleted: suspend (
        RunEffectIdentity,
        ProviderPassResult,
    ) -> RunEffect.ProviderPassAccepted?,
    /** The mailbox chooses the one normal terminal effect that may mutate Room. */
    val onRunFinalizationRequested: suspend (
        RunEffectIdentity,
        RunStatus,
        RunEndReason,
        Boolean,
    ) -> RunEffect.FinalizeRun?,
    /** Echo the exact Room result; true means the current mailbox state accepted it. */
    val onRunFinalizationCompleted: suspend (RunEffectIdentity, Boolean) -> Boolean,
    /** True when the user queued a send behind this generation. The tool loop checks it at
     *  each round boundary and ends the generation there so the queue can flush immediately
     *  (steering) instead of waiting out the entire loop. Headless runs keep the default. */
    val hasQueuedSends: () -> Boolean = { false },
    /** A validated Provider outcome is necessary but not sufficient: runtime identity must accept
     * the exact batch before any tool can execute. Defaults preserve the isolated headless test
     * adapter until Task ownership migrates fully in Phase 7. */
    val onToolBatchRequested: suspend (RunEffectIdentity) -> RunEffect.ExecuteToolBatch? = {
        RunEffect.ExecuteToolBatch(it.copy(effectId = "tool-batch-${it.effectId}"))
    },
    /** Authoritative results exist only after every call in the accepted batch completed. */
    val onToolBatchCompleted: suspend (RunEffectIdentity) -> RunEffect.CommitToolRound? = {
        RunEffect.CommitToolRound(it.copy(effectId = "tool-round-${it.effectId}"))
    },
    /** Only an accepted durable success result authorizes the next Provider pass. */
    val onToolRoundCommitted: suspend (RunEffectIdentity, Boolean) -> RunEffect? =
        { identity, success ->
            if (success) RunEffect.ContinueProviderPass(identity)
            else RunEffect.ToolRoundCommitFailed(identity)
        },
    val onToolRoundPersisted: suspend () -> Unit = {},
)
