package com.lxseek.chat.viewmodel

import android.app.Application
import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.data.MemoryManager
import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.model.RunEffectIdentity
import com.lxseek.chat.model.ToolExecutionStates
import com.lxseek.chat.sandbox.SandboxManagerFactory
import com.lxseek.chat.tool.ActionTraceBus
import com.lxseek.chat.tool.ActionTraceEntry
import com.lxseek.chat.tool.ActionTraceToolProvider
import com.lxseek.chat.tool.AgentMode
import com.lxseek.chat.tool.AskUserToolProvider
import com.lxseek.chat.tool.ImageGenToolProvider
import com.lxseek.chat.tool.MemoryToolProvider
import com.lxseek.chat.tool.PlanHandler
import com.lxseek.chat.tool.PlanStateHolder
import com.lxseek.chat.tool.PlanToolProvider
import com.lxseek.chat.tool.RagToolProvider
import com.lxseek.chat.tool.RiskLevel
import com.lxseek.chat.tool.ShellToolProvider
import com.lxseek.chat.tool.ToolApprovalRequest
import com.lxseek.chat.tool.ToolApprovalResult
import com.lxseek.chat.tool.ToolExecutionEvent
import com.lxseek.chat.tool.ToolExecutionResult
import com.lxseek.chat.tool.ToolImageStore
import com.lxseek.chat.tool.ToolPresentationMetadata
import com.lxseek.chat.tool.ToolProvider
import com.lxseek.chat.tool.ToolTier
import com.lxseek.chat.tool.ToolTierPolicy
import com.lxseek.chat.tool.WebSearchToolProvider
import com.lxseek.chat.tool.needsOuterApproval
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

internal data class AuthorizedToolCall(
    val batchIdentity: RunEffectIdentity,
    val callId: String,
    val name: String,
    val arguments: String,
    val context: GenerationContext,
)

internal data class AuthorizedToolResult(
    val batchIdentity: RunEffectIdentity,
    val callId: String,
    val result: ToolExecutionResult,
)

internal interface GenerationToolPresentationSource {
    fun presentationMetadata(name: String): ToolPresentationMetadata?
}

/**
 * Executes one tool call from an already mailbox-authorized batch.
 *
 * This component owns the ToolProvider instances and their provider-local resources. It never
 * chooses a tool round, advances a Provider pass, persists a result, invokes the reducer, or
 * releases a runtime slot. Progress is presentation-only; the returned result retains the exact
 * accepted batch identity and call id supplied by the caller.
 */
internal class GenerationToolExecutor private constructor(
    private val providers: List<ToolProvider>,
    private val imageGenProvider: ImageGenToolProvider?,
    private val onToolApproval: suspend (ToolApprovalRequest) -> ToolApprovalResult?,
    private val planStateHolder: PlanStateHolder?,
    private val actionTraceBus: ActionTraceBus? = null,
) : GenerationToolDefinitionSource, GenerationToolPresentationSource {
    companion object {
        private val FILE_TOOL_NAMES = setOf(
            "file_read",
            "file_write",
            "file_edit",
            "file_glob",
            "file_grep",
            "view_image",
        )

        fun createDefault(
            app: Application,
            conversations: ConversationRepository,
            memoryManager: MemoryManager,
            sandboxFactory: SandboxManagerFactory?,
            additionalProviders: List<ToolProvider>,
            confirmShellCommand: suspend (server: String, summary: String) -> Boolean,
            onToolApproval: suspend (ToolApprovalRequest) -> ToolApprovalResult? = { null },
            planToolProvider: PlanToolProvider? = null,
            askUserToolProvider: AskUserToolProvider? = null,
            planStateHolder: PlanStateHolder? = null,
            actionTraceBus: ActionTraceBus? = null,
        ): GenerationToolExecutor {
            val imageGenProvider = ImageGenToolProvider(app)
            val shellProvider = ShellToolProvider(
                sandboxFactory = sandboxFactory,
                imageStore = ToolImageStore(app),
            ).also { provider ->
                provider.confirm = confirmShellCommand
            }
            val baseProviders = listOf(
                MemoryToolProvider(memoryManager),
                WebSearchToolProvider(),
                RagToolProvider(conversations),
                imageGenProvider,
                shellProvider,
            )
            val planProviders = buildList {
                planToolProvider?.let { add(it) }
                askUserToolProvider?.let { add(it) }
            }
            val traceProvider = ActionTraceToolProvider()
            return GenerationToolExecutor(
                providers = baseProviders + planProviders + additionalProviders + listOf(traceProvider),
                imageGenProvider = imageGenProvider,
                onToolApproval = onToolApproval,
                planStateHolder = planStateHolder,
                actionTraceBus = actionTraceBus,
            )
        }

        internal fun forTest(providers: List<ToolProvider>): GenerationToolExecutor =
            GenerationToolExecutor(providers, imageGenProvider = null, onToolApproval = { null }, planStateHolder = null, actionTraceBus = null)
    }

    override fun definitions(context: GenerationContext): List<ToolDefinition> =
        providers.flatMap { it.definitions(context) }.filterByAgentMode(context).filterByTier(context)

    /** Filter out tools whose risk level is not allowed by the current [AgentMode]. */
    private fun List<ToolDefinition>.filterByAgentMode(context: GenerationContext): List<ToolDefinition> {
        if (context.agentMode == AgentMode.Agent || context.agentMode == AgentMode.Auto) return this
        return filter { def ->
            val risk = providers.firstOrNull { it.handles(def.function.name) }
                ?.riskLevel(def.function.name) ?: RiskLevel.ReadOnly
            context.agentMode.allowsRisk(risk)
        }
    }

    /** Filter out tools whose tier is not allowed by the current context's tool tier policy. */
    private fun List<ToolDefinition>.filterByTier(context: GenerationContext): List<ToolDefinition> {
        val allowedTiers = ToolTierPolicy.allowedTiers(context)
        if (allowedTiers.size == ToolTier.values().size) return this
        return filter { def -> ToolTierPolicy.tierOf(def.function.name) in allowedTiers }
    }

    fun imageDefinitions(context: GenerationContext): List<ToolDefinition> =
        imageGenProvider?.definitions(context).orEmpty()

    fun memoryDefinitions(context: GenerationContext): List<ToolDefinition> =
        providers.filterIsInstance<MemoryToolProvider>().flatMap { it.definitions(context) }
            .filterByAgentMode(context)

    fun webSearchDefinitions(context: GenerationContext): List<ToolDefinition> =
        providers.filterIsInstance<WebSearchToolProvider>().flatMap { it.definitions(context) }
            .filterByAgentMode(context)

    fun ragDefinitions(context: GenerationContext): List<ToolDefinition> =
        providers.filterIsInstance<RagToolProvider>().flatMap { it.definitions(context) }
            .filterByAgentMode(context)

    fun shellDefinitions(context: GenerationContext): List<ToolDefinition> =
        providers.filterIsInstance<ShellToolProvider>()
            .flatMap { it.definitions(context) }
            .filter { it.function.name !in FILE_TOOL_NAMES }
            .filterByAgentMode(context)

    fun fileDefinitions(context: GenerationContext): List<ToolDefinition> =
        providers.filterIsInstance<ShellToolProvider>()
            .flatMap { it.definitions(context) }
            .filter { it.function.name in FILE_TOOL_NAMES }
            .filterByAgentMode(context)

    override fun presentationMetadata(name: String): ToolPresentationMetadata? {
        if (name.isBlank()) return null
        for (provider in providers) {
            provider.presentationMetadata(name)?.let { return it }
        }
        return null
    }

    suspend fun semanticSearch(
        query: String,
        limit: Int,
        context: GenerationContext,
    ): List<Pair<MessageEntity, Float>> = providers.filterIsInstance<RagToolProvider>()
        .first()
        .semanticSearch(query, limit, context)

    fun drainGeneratedImages(conversationId: String): List<String> =
        imageGenProvider?.drainImages(conversationId).orEmpty()

    suspend fun execute(
        call: AuthorizedToolCall,
        onEvent: suspend (ToolExecutionEvent) -> Unit,
    ): AuthorizedToolResult {
        val startMs = System.currentTimeMillis()
        val completeArguments = call.arguments.ifBlank { "{}" }
        val argumentsAreCompleteObject = runCatching {
            Json.parseToJsonElement(completeArguments).jsonObject
        }.isSuccess
        if (!argumentsAreCompleteObject) {
            return call.result(
                ToolExecutionResult(
                    text = "Error executing tool '${call.name}': arguments are not a complete JSON object",
                    isError = true,
                ),
            )
        }

        // Best-effort extraction of the optional "server" field for action trace.
        // Not every tool carries a server argument; null is the correct value when absent.
        val serverName = runCatching {
            (Json.parseToJsonElement(completeArguments).jsonObject["server"] as? JsonPrimitive)?.contentOrNull
        }.getOrNull()

        val result = try {
            val provider = providers.firstOrNull { it.handles(call.name) }
                ?: return call.result(
                    ToolExecutionResult(text = "Unknown tool: ${call.name}", isError = true),
                )

            // ── Approval dispatch ───────────────────────────────────
            // Sandbox static analysis: decide whether the outer dispatcher needs to prompt.
            // Tools with an internal confirm gate (file_write/file_edit) are skipped here to
            // avoid double-prompting; the provider handles their confirmation internally.
            val riskLevel = provider.riskLevel(call.name)
            val requiresApproval = provider.requiresApprovalByDefault(call.name)
            if (needsOuterApproval(call.name, riskLevel, requiresApproval, call.context.agentMode)) {
                val approvalRequest = ToolApprovalRequest(
                    toolName = call.name,
                    arguments = completeArguments,
                    riskLevel = riskLevel,
                    agentMode = call.context.agentMode,
                    summary = buildApprovalSummary(call.name, completeArguments),
                )
                when (val approval = onToolApproval(approvalRequest)) {
                    null -> { /* no external gate configured — allow */ }
                    ToolApprovalResult.Approved -> { /* proceed */ }
                    is ToolApprovalResult.Denied -> return call.result(
                        ToolExecutionResult(
                            text = "Tool '${call.name}' was denied by user: ${approval.reason}",
                            isError = true,
                        ),
                    )
                }
            }

            // A blocking provider must not pin the stream consumer forever. The detached attempt
            // lets the deadline stop awaiting immediately; cancellation still reaches cooperative
            // provider work and the tool round receives a recoverable error.
            val attemptJob = Job()
            val attempt = CoroutineScope(currentCoroutineContext() + attemptJob).async {
                var completedResult: ToolExecutionResult? = null
                provider.executeEvents(call.name, completeArguments, call.context).collect { event ->
                    if (event is ToolExecutionEvent.Completed) {
                        completedResult = event.result
                    }
                    onEvent(event)
                }
                completedResult
                    ?: ToolExecutionResult(
                        text = "Error executing tool '${call.name}': provider ended without a result",
                        isError = true,
                    )
            }
            try {
                withTimeout(call.context.toolTimeoutMs) { attempt.await() }
            } finally {
                attemptJob.cancel()
            }
        } catch (error: TimeoutCancellationException) {
            ToolExecutionResult(
                text = "Error executing tool '${call.name}': timed out after ${call.context.toolTimeoutMs}ms",
                isError = true,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ToolExecutionResult(
                text = "Error executing tool '${call.name}': ${error.localizedMessage ?: "Unknown error"}",
                isError = true,
            )
        }

        val finalResult = applyPlanReflection(result, call)
        actionTraceBus?.record(
            ActionTraceEntry(
                toolName = call.name,
                argumentsSummary = completeArguments.take(500),
                resultSummary = finalResult.text.take(500),
                isError = finalResult.isError,
                server = serverName,
                conversationId = call.context.conversationId,
                runId = call.batchIdentity.runId,
                timestampMs = System.currentTimeMillis(),
                durationMs = System.currentTimeMillis() - startMs,
            )
        )
        return call.result(finalResult)
    }

    private fun applyPlanReflection(
        result: ToolExecutionResult,
        call: AuthorizedToolCall,
    ): ToolExecutionResult {
        if (result.isError) return result
        val holder = planStateHolder ?: return result
        val taskId = call.context.conversationId ?: return result
        if (call.name !in PlanToolProvider.TOOL_NAMES) return result
        val planResult = PlanHandler.handleToolOutput(call.name, result.text, holder, taskId)
        val overrideText = planResult.overrideText ?: return result
        return result.copy(text = overrideText)
    }

    private fun AuthorizedToolCall.result(result: ToolExecutionResult) = AuthorizedToolResult(
        batchIdentity = batchIdentity,
        callId = callId,
        result = result,
    )

    private fun buildApprovalSummary(toolName: String, arguments: String): String {
        val argPreview = arguments.take(300)
        return "$toolName($argPreview)"
    }
}

internal fun appendBoundedToolOutput(
    current: String?,
    delta: String,
    maxChars: Int = 32 * 1024,
): String {
    if (delta.isEmpty()) return current.orEmpty()
    val combined = current.orEmpty() + delta
    return if (combined.length <= maxChars) combined
    else combined.takeLast(maxChars)
}

internal fun finalToolState(result: String): String {
    if (result.isEmpty()) return ToolExecutionStates.EMPTY
    val resultObject = runCatching {
        Json.parseToJsonElement(result).jsonObject
    }.getOrNull()
    val errorCode = (resultObject?.get("error") as? JsonPrimitive)?.content
    if (errorCode == "no_results") return ToolExecutionStates.EMPTY
    if (result.startsWith("Error", ignoreCase = true) || errorCode != null) {
        return ToolExecutionStates.FAILED
    }
    val isBackground = (resultObject?.get("background") as? JsonPrimitive)
        ?.content
        ?.toBooleanStrictOrNull() == true ||
        (
            (resultObject?.get("state") as? JsonPrimitive)
                ?.content
                ?.equals("running", ignoreCase = true) == true &&
                resultObject.get("job_id") != null
            )
    return if (isBackground) ToolExecutionStates.BACKGROUND_RUNNING
    else ToolExecutionStates.SUCCEEDED
}
