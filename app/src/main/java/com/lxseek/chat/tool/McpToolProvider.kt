package com.lxseek.chat.tool

import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.mcp.McpRegistry
import com.lxseek.chat.viewmodel.GenerationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class McpToolProvider(
    private val registry: McpRegistry,
) : ToolProvider {
    override fun definitions(ctx: GenerationContext): List<ToolDefinition> =
        registry.enabledTools().map { it.asToolDefinition() }

    override fun handles(name: String): Boolean = registry.descriptor(name) != null

    override fun riskLevel(name: String): RiskLevel =
        if (registry.descriptor(name) != null) RiskLevel.Moderate else RiskLevel.ReadOnly

    override fun requiresApprovalByDefault(name: String): Boolean =
        registry.descriptor(name) != null

    override fun presentationMetadata(name: String): ToolPresentationMetadata? =
        registry.descriptor(name)?.let { descriptor ->
            ToolPresentationMetadata(
                displayName = descriptor.remote.name,
                target = descriptor.serverName,
            )
        }

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String = registry.execute(name, arguments).text

    override fun executeEvents(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): Flow<ToolExecutionEvent> = flow {
        presentationMetadata(name)?.target?.let { target ->
            emit(ToolExecutionEvent.TargetResolved(target))
        }
        emit(ToolExecutionEvent.Progress("Calling MCP tool"))
        emit(ToolExecutionEvent.Completed(registry.execute(name, arguments)))
    }
}
