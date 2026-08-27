package com.lxseek.chat.plugin.market

import android.content.Context
import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.data.McpServerConfig
import com.lxseek.chat.mcp.McpProtocolClient
import com.lxseek.chat.mcp.McpToolDescriptor
import com.lxseek.chat.mcp.publicMcpToolName
import com.lxseek.chat.tool.RiskLevel
import com.lxseek.chat.tool.ToolExecutionResult
import com.lxseek.chat.tool.ToolImageStore
import com.lxseek.chat.tool.ToolPresentationMetadata
import com.lxseek.chat.tool.ToolProvider
import com.lxseek.chat.viewmodel.GenerationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.asObjectOrNull
import kotlinx.serialization.json.Json
import kotlin.math.min

/**
 * 面向单个 MCP 服务的工具提供者，供市场 MCP 插件使用。
 *
 * 与内置 [com.lxseek.chat.mcp.McpRegistry] 的差别：只管理一个服务、生命周期跟随插件
 * 启用状态（[start] 由插件 onEnable 触发，[close] 由 onDisable 触发），不常驻后台。
 * 工具命名复用 [publicMcpToolName]，与内置 MCP 路径保持一致。
 */
class ScopedMcpToolProvider(
    private val context: Context,
    private val pluginId: String,
    private val config: McpServerConfig,
    private val scope: CoroutineScope,
) : ToolProvider {
    private val json = Json { ignoreUnknownKeys = true }
    private val imageStore = ToolImageStore(context)

    private var client: McpProtocolClient? = null
    private var descriptors: List<McpToolDescriptor> = emptyList()
    private var connectionJob: Job? = null

    /** 建立连接并拉取工具列表；失败时指数退避重试，直到被 [close] 取消。 */
    fun start() {
        if (connectionJob?.isActive == true) return
        if (config.url.isBlank()) return
        client?.close()
        client = McpProtocolClient(
            endpoint = config.url,
            customHeaders = config.headers,
            transportType = config.transport,
        )
        connectionJob = scope.launch(Dispatchers.IO) {
            var retryMs = INITIAL_RETRY_MS
            while (isActive) {
                try {
                    val remoteTools = checkNotNull(client).listTools()
                        .distinctBy { it.name }
                        .sortedBy { it.name }
                    descriptors = remoteTools.map { remote ->
                        McpToolDescriptor(
                            publicName = publicMcpToolName(pluginId, remote.name),
                            serverId = pluginId,
                            serverName = config.name.ifBlank { config.url },
                            remote = remote,
                            enabled = remote.name !in config.disabledTools,
                        )
                    }
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    delay(retryMs)
                    retryMs = min(MAX_RETRY_MS, retryMs * 2)
                }
            }
        }
    }

    /** 关闭连接并取消重试任务（由插件 onDisable 触发）。 */
    fun close() {
        connectionJob?.cancel()
        connectionJob = null
        client?.close()
        client = null
        descriptors = emptyList()
    }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> =
        descriptors.map { it.asToolDefinition() }

    override fun handles(name: String): Boolean =
        descriptors.any { it.publicName == name }

    override fun presentationMetadata(name: String): ToolPresentationMetadata? =
        descriptors.firstOrNull { it.publicName == name }?.let { descriptor ->
            ToolPresentationMetadata(
                displayName = descriptor.remote.name,
                target = descriptor.serverName,
            )
        }

    override fun riskLevel(name: String): RiskLevel =
        if (handles(name)) RiskLevel.Moderate else RiskLevel.ReadOnly

    override fun requiresApprovalByDefault(name: String): Boolean = handles(name)

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String {
        val descriptor = descriptors.firstOrNull { it.publicName == name }
            ?: return errorText("Unknown or disabled MCP tool: $name")
        val c = client ?: return errorText("MCP server is not connected")
        val args = runCatching { json.parseToJsonElement(arguments).asObjectOrNull() }
            .getOrNull()
            ?: return errorText("MCP tool '$name' requires a complete JSON object")
        return try {
            val payload = c.callTool(descriptor.remote.name, args)
            val attachments = withContext(Dispatchers.IO) {
                payload.images.map { image ->
                    imageStore.persistBase64(image.data, image.mimeType, "mcp")
                }
            }
            val structured = payload.structuredContent?.toString()
            val contentText = payload.textParts
                .filter(String::isNotBlank)
                .joinToString("\n\n")
            val resultText = buildString {
                append(contentText)
                if (!structured.isNullOrBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append(structured)
                }
                if (attachments.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append(
                        if (attachments.size == 1) {
                            "[The tool returned one image. It is attached as visual context.]"
                        } else {
                            "[The tool returned ${attachments.size} images. They are attached as visual context.]"
                        },
                    )
                }
                if (isEmpty()) append(if (payload.isError) "MCP tool failed" else "MCP tool completed")
            }
            ToolExecutionResult(
                text = resultText,
                images = attachments,
                structuredContent = structured,
                isError = payload.isError,
            ).text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorText("MCP tool '${descriptor.remote.name}' failed: ${e.message}")
        }
    }

    private fun errorText(message: String): String =
        ToolExecutionResult(message, isError = true).text

    private companion object {
        const val INITIAL_RETRY_MS = 5_000L
        const val MAX_RETRY_MS = 5L * 60L * 1_000L
    }
}
