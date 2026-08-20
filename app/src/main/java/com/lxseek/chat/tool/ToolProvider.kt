package com.lxseek.chat.tool

import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.model.ToolImageAttachment
import com.lxseek.chat.viewmodel.GenerationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed interface ToolExecutionEvent {
    /** Incremental user-visible output. It is never sent to the model as a partial result. */
    data class OutputDelta(val text: String) : ToolExecutionEvent

    /** The concrete device selected after resolving optional tool arguments. */
    data class TargetResolved(val target: String) : ToolExecutionEvent

    /** A low-volume lifecycle update. It is not command output. */
    data class Progress(val message: String) : ToolExecutionEvent

    /** Exactly one authoritative model-facing result. */
    data class Completed(val result: ToolExecutionResult) : ToolExecutionEvent {
        constructor(text: String) : this(ToolExecutionResult(text = text))
    }
}

/**
 * Provider-neutral tool output. Text remains the protocol-facing result while images are
 * persisted as private files and projected into a normal user multimodal turn for the next model
 * round. Structured JSON stays distinct so arbitrary text is never classified by its spelling.
 */
data class ToolExecutionResult(
    val text: String,
    val images: List<ToolImageAttachment> = emptyList(),
    val structuredContent: String? = null,
    /** Human-readable content for UI display when [text] also carries protocol JSON/attachments. */
    val displayText: String? = null,
    val isError: Boolean = false,
)

/** Provider-owned presentation metadata resolved without exposing protocol routing IDs to the UI. */
data class ToolPresentationMetadata(
    val displayName: String,
    val target: String? = null,
)

/**
 * Interface for tool providers that supply tool definitions and execution
 * logic to the LLM generation pipeline. Each implementation manages a
 * specific category of tools (memory, web search, RAG, shell, etc.).
 */
interface ToolProvider {
    /** The tool definitions this provider exposes for the given context.
     *  Returns empty list when the provider is disabled. */
    fun definitions(ctx: GenerationContext): List<ToolDefinition>

    /** Execute a named tool with the given JSON arguments string.
     *  Returns the result string (usually JSON). */
    suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String

    /**
     * Streaming execution contract. One-shot providers inherit the adapter; streaming providers
     * emit progress/deltas and finish with exactly one [ToolExecutionEvent.Completed].
     */
    fun executeEvents(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): Flow<ToolExecutionEvent> = flow {
        emit(ToolExecutionEvent.Completed(ToolExecutionResult(execute(name, arguments, ctx))))
    }

    /** Whether this provider can execute the given tool name. */
    fun handles(name: String): Boolean

    /**
     * Resolve stable UI metadata as soon as a streamed tool name is complete. The default keeps
     * built-in tools on their localized presentation path.
     */
    fun presentationMetadata(name: String): ToolPresentationMetadata? = null

    /**
     * The risk level for a given tool name. Defaults to [RiskLevel.ReadOnly] so providers that
     * only expose read-only tools need not override this.
     */
    fun riskLevel(name: String): RiskLevel = RiskLevel.ReadOnly

    /**
     * Whether this tool forces an explicit user approval regardless of the current [AgentMode].
     * Useful for untrusted external tools (e.g. MCP tools from untrusted servers).
     */
    fun requiresApprovalByDefault(name: String): Boolean = false
}
