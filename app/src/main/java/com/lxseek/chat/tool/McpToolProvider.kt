package com.lxseek.chat.tool

import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.mcp.McpOutputGuard
import com.lxseek.chat.mcp.McpRegistry
import com.lxseek.chat.mcp.McpResourceListing
import com.lxseek.chat.viewmodel.GenerationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Exposes connected MCP servers' tools to the model, plus two host-level resource tools
 * (mirroring cc-haha's ListMcpResourcesTool / ReadMcpResourceTool) so the model can discover
 * and read server resources that would otherwise be invisible to it.
 */
class McpToolProvider(
    private val registry: McpRegistry,
) : ToolProvider {
    companion object {
        const val LIST_RESOURCES_TOOL = "list_mcp_resources"
        const val READ_RESOURCE_TOOL = "read_mcp_resource"
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val resourceTools = listOf(
        ToolDefinition(
            function = ToolFunction(
                name = LIST_RESOURCES_TOOL,
                description = "List resources (files, schemas, documents) exposed by connected MCP servers. " +
                    "Use this to discover what data a server offers before reading a specific resource.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "server" to ToolProperty(
                            type = "string",
                            description = "Optional server name to filter by. Omit to list resources from all connected servers.",
                        ),
                    ),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = READ_RESOURCE_TOOL,
                description = "Read a specific MCP resource by its URI (e.g. 'file:///etc/hosts' or 'log://today'). " +
                    "Text content is returned inline; image content is attached as visual context; other binary " +
                    "content is not inlined.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "server" to ToolProperty(
                            type = "string",
                            description = "The MCP server name that provides this resource.",
                        ),
                        "uri" to ToolProperty(
                            type = "string",
                            description = "The resource URI to read.",
                        ),
                    ),
                    required = listOf("server", "uri"),
                ),
            ),
        ),
    )

    private fun isResourceTool(name: String): Boolean =
        name == LIST_RESOURCES_TOOL || name == READ_RESOURCE_TOOL

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        val remote = registry.enabledTools()
        // Resource tools only pay their token cost when at least one server is connected.
        return remote.map { it.asToolDefinition() } +
            if (remote.isNotEmpty()) resourceTools else emptyList()
    }

    override fun handles(name: String): Boolean =
        isResourceTool(name) || registry.descriptor(name) != null

    override fun riskLevel(name: String): RiskLevel =
        if (registry.descriptor(name) != null) RiskLevel.Moderate else RiskLevel.ReadOnly

    override fun requiresApprovalByDefault(name: String): Boolean =
        !isResourceTool(name) && registry.descriptor(name) != null

    override fun presentationMetadata(name: String): ToolPresentationMetadata? {
        if (isResourceTool(name)) {
            return ToolPresentationMetadata(
                displayName = if (name == LIST_RESOURCES_TOOL) {
                    "List resources"
                } else {
                    "Read resource"
                },
            )
        }
        return registry.descriptor(name)?.let { descriptor ->
            ToolPresentationMetadata(
                displayName = descriptor.remote.name,
                target = descriptor.serverName,
            )
        }
    }

    private fun stringArg(arguments: String, key: String): String? = runCatching {
        val element = json.parseToJsonElement(arguments)
        if (element is JsonObject) {
            (element[key] as? JsonPrimitive)?.contentOrNull
        } else {
            null
        }
    }.getOrNull()

    private fun renderResourceListings(server: String?): String {
        val listings = registry.listResources(server)
        val payload = if (listings.isEmpty()) {
            "No resources found. MCP servers may still provide tools even if they have no resources."
        } else {
            json.encodeToString<List<McpResourceListing>>(listings)
        }
        return McpOutputGuard.guard(payload).text
    }

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String = when (name) {
        LIST_RESOURCES_TOOL -> renderResourceListings(stringArg(arguments, "server"))
        READ_RESOURCE_TOOL -> {
            val server = stringArg(arguments, "server")
            val uri = stringArg(arguments, "uri")
            if (server.isNullOrBlank() || uri.isNullOrBlank()) {
                return "Both 'server' and 'uri' arguments are required."
            }
            registry.readResource(server, uri).text
        }
        else -> registry.execute(name, arguments).text
    }

    override fun executeEvents(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): Flow<ToolExecutionEvent> = flow {
        if (!isResourceTool(name)) {
            presentationMetadata(name)?.target?.let { target ->
                emit(ToolExecutionEvent.TargetResolved(target))
            }
            emit(ToolExecutionEvent.Progress("Calling MCP tool"))
            emit(ToolExecutionEvent.Completed(registry.execute(name, arguments)))
            return@flow
        }
        emit(
            ToolExecutionEvent.Progress(
                if (name == LIST_RESOURCES_TOOL) {
                    "Listing MCP resources"
                } else {
                    "Reading MCP resource"
                },
            ),
        )
        val result = when (name) {
            LIST_RESOURCES_TOOL -> ToolExecutionResult(
                text = renderResourceListings(stringArg(arguments, "server")),
            )
            READ_RESOURCE_TOOL -> {
                val server = stringArg(arguments, "server")
                val uri = stringArg(arguments, "uri")
                if (server.isNullOrBlank() || uri.isNullOrBlank()) {
                    ToolExecutionResult(
                        text = "Both 'server' and 'uri' arguments are required.",
                        isError = true,
                    )
                } else {
                    registry.readResource(server, uri)
                }
            }
            else -> registry.execute(name, arguments)
        }
        emit(ToolExecutionEvent.Completed(result))
    }
}
