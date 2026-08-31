package com.lxseek.chat.mcp

import android.content.Context
import com.lxseek.chat.data.McpServerConfig
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.model.ToolImageAttachment
import com.lxseek.chat.tool.ToolExecutionResult
import com.lxseek.chat.tool.ToolImageStore
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.min

/**
 * Process-wide MCP supervisor.
 *
 * Every server owns its own client and retry job. Jobs are siblings under the app's SupervisorJob:
 * a broken endpoint can only update its own snapshot and cannot cancel another server or a model
 * generation. Tool definitions are immutable snapshots, so request construction never blocks on
 * network I/O.
 */
class McpRegistry(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val INITIAL_RETRY_MS = 5_000L
        private const val MAX_RETRY_MS = 5L * 60L * 1_000L
    }

    private data class Runtime(
        val config: McpServerConfig,
        val client: McpProtocolClient,
        var connectionJob: Job? = null,
    ) {
        /**
         * 连续失败退避（M6）：存在 Runtime 上、跨重连循环保留。此前 retryMs 是
         * launchConnectionLoop 的局部变量，工具失败触发重连即从头计 5s，连续
         * 失败时退避被反复重置、重连频率失控；现在只有连接成功才重置。
         */
        @Volatile var retryMs: Long = INITIAL_RETRY_MS

        fun close() {
            connectionJob?.cancel()
            connectionJob = null
            client.close()
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val imageStore = ToolImageStore(context)
    private val lock = Any()
    private val runtimes = mutableMapOf<String, Runtime>()
    private val _snapshots = MutableStateFlow<Map<String, McpServerSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<String, McpServerSnapshot>> = _snapshots.asStateFlow()

    /** Bridges server → client elicitation requests to the UI and back (MCP 2025-11-25). */
    private val elicitationController = McpElicitationController()

    init {
        scope.launch {
            settings.mcpServers.collect(::reconcile)
        }
    }

    fun enabledTools(): List<McpToolDescriptor> =
        snapshots.value.values
            .asSequence()
            .filter { it.status == McpConnectionStatus.CONNECTED }
            .flatMap { it.tools.asSequence() }
            .filter(McpToolDescriptor::enabled)
            .sortedBy(McpToolDescriptor::publicName)
            .toList()

    fun descriptor(publicName: String): McpToolDescriptor? =
        snapshots.value.values.asSequence()
            .flatMap { it.tools.asSequence() }
            .firstOrNull { it.enabled && it.publicName == publicName }

    fun refresh(serverId: String) {
        synchronized(lock) {
            val current = settings.mcpServers.value.firstOrNull { it.id == serverId } ?: return
            replaceRuntimeLocked(current)
        }
    }

    /** Pending server → client elicitation prompt awaiting a user answer (or null). */
    val elicitationPending: StateFlow<McpElicitationController.PendingElicitation?>
        get() = elicitationController.pending

    /** Called by the UI to resolve a pending elicitation with the user's answer. */
    fun resolveElicitation(result: McpElicitationResult) = elicitationController.resolve(result)

    /** Called by the UI to cancel a pending elicitation. */
    fun cancelElicitation() = elicitationController.cancel()

    suspend fun execute(publicName: String, arguments: String): ToolExecutionResult {
        val descriptor = descriptor(publicName)
            ?: return ToolExecutionResult("Unknown or disabled MCP tool: $publicName", isError = true)
        val runtime = synchronized(lock) { runtimes[descriptor.serverId] }
            ?: return ToolExecutionResult(
                "MCP server '${descriptor.serverName}' is not enabled",
                isError = true,
            )
        val args = runCatching { json.parseToJsonElement(arguments).asObjectOrNull() }
            .getOrNull()
            ?: return ToolExecutionResult(
                "MCP tool '$publicName' requires a complete JSON object",
                isError = true,
            )

        return try {
            val payload = runtime.client.callTool(descriptor.remote.name, args)
            val attachments = withContext(Dispatchers.IO) {
                payload.images.map { image ->
                    imageStore.persistBase64(
                        data = image.data,
                        mimeType = image.mimeType,
                        filePrefix = "mcp",
                    )
                }
            }
            val structured = payload.structuredContent?.toString()
            val contentText = payload.textParts
                .filter(String::isNotBlank)
                .joinToString("\n\n")
            // MCP servers commonly repeat structuredContent as a text block for older clients.
            // Compare the actual JSON trees, not spelling, so the detail UI never shows the same
            // payload twice while still preserving distinct explanatory text.
            val displayText = contentText
                .takeIf(String::isNotBlank)
                ?.takeUnless { text ->
                    payload.structuredContent != null &&
                        runCatching { json.parseToJsonElement(text) }.getOrNull() ==
                        payload.structuredContent
                }
            // Guard oversized output against runaway token usage (mirrors cc-haha's
            // mcpValidation.ts). Text and structured JSON are budget-checked separately
            // so the model never receives a multi-megabyte payload.
            val guardedText = McpOutputGuard.guard(contentText)
            val guardedStructured = structured?.let { McpOutputGuard.guard(it).text }
            val resultText = buildString {
                append(guardedText.text)
                if (!guardedStructured.isNullOrBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append(guardedStructured)
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
            updateConnected(runtime, keepTools = true)
            ToolExecutionResult(
                text = resultText,
                images = attachments,
                structuredContent = guardedStructured,
                displayText = displayText?.let { McpOutputGuard.guard(it).text },
                isError = payload.isError,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // M6：连接层失败（IOException，含会话过期）才置 ERROR 并重连；工具级
            // 失败（参数错、响应解析失败等）不重连也不降级快照，避免"工具失败
            // 即重连"的抖动把服务器从 LLM 工具列表里抖没。
            if (e is java.io.IOException) {
                markError(runtime, e)
                scheduleRetry(runtime)
            } else {
                DebugLog.e(
                    "McpRegistry",
                    "MCP tool ${descriptor.remote.name} failed (non-connection, no reconnect)",
                    e,
                )
            }
            ToolExecutionResult(
                text = "MCP tool '${descriptor.remote.name}' failed: ${userMessage(e)}",
                isError = true,
            )
        }
    }

    /**
     * List MCP resources across connected servers (optionally filtered by server name).
     * A server that does not implement resources is skipped, mirroring cc-haha's
     * ListMcpResourcesTool which isolates one server's failure from the rest.
     */
    suspend fun listResources(serverFilter: String?): List<McpResourceListing> {
        val targets = synchronized(lock) { runtimes.values.toList() }
        val listings = mutableListOf<McpResourceListing>()
        for (runtime in targets) {
            val serverName = runtime.config.name.ifBlank { runtime.config.url }
            if (serverFilter != null && serverFilter != serverName) continue
            if (snapshots.value[runtime.config.id]?.status != McpConnectionStatus.CONNECTED) continue
            val resources = try {
                runtime.client.listResources()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            resources.forEach { resource ->
                listings += McpResourceListing(
                    uri = resource.uri,
                    name = resource.name,
                    description = resource.description,
                    mimeType = resource.mimeType,
                    server = serverName,
                )
            }
        }
        return listings
    }

    /**
     * Read a single MCP resource by URI from the named server (`resources/read`). Text content
     * is inlined; image blobs are persisted as multimodal attachments; other binary blobs are
     * reported as not inlinable instead of dumping base64 into the model context.
     */
    suspend fun readResource(serverName: String, uri: String): ToolExecutionResult {
        val runtime = synchronized(lock) {
            runtimes.values.firstOrNull { it.config.name.ifBlank { it.config.url } == serverName }
        } ?: return ToolExecutionResult("MCP server '$serverName' not found", isError = true)
        if (snapshots.value[runtime.config.id]?.status != McpConnectionStatus.CONNECTED) {
            return ToolExecutionResult("MCP server '$serverName' is not connected", isError = true)
        }
        return try {
            val contents = runtime.client.readResource(uri)
            val attachments = mutableListOf<ToolImageAttachment>()
            val texts = mutableListOf<String>()
            contents.forEach { content ->
                val mimeType = content.mimeType.orEmpty()
                when {
                    !content.blob.isNullOrBlank() && mimeType.startsWith("image/") ->
                        runCatching {
                            imageStore.persistBase64(
                                data = content.blob,
                                mimeType = mimeType,
                                filePrefix = "mcp-resource",
                            )
                        }.getOrNull()?.let(attachments::add)
                    !content.text.isNullOrBlank() -> texts += content.text
                    !content.blob.isNullOrBlank() -> texts +=
                        "[Binary resource (${mimeType.ifBlank { "unknown" }}) is not inlined on this device.]"
                }
            }
            val guardedText = McpOutputGuard.guard(texts.joinToString("\n\n"))
            val resultText = buildString {
                append(guardedText.text)
                if (attachments.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append(
                        if (attachments.size == 1) {
                            "[The resource contains one image. It is attached as visual context.]"
                        } else {
                            "[The resource contains ${attachments.size} images. They are attached as visual context.]"
                        },
                    )
                }
                if (isEmpty()) append("[MCP resource '$uri' returned no readable content]")
            }
            ToolExecutionResult(text = resultText, images = attachments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult(
                text = "Failed to read MCP resource '$uri': ${userMessage(e)}",
                isError = true,
            )
        }
    }

    /**
     * 增量 diff（M7）：按 id 增删 runtime，配置未变化的服务器原样保留
     * （`existing?.config != config` 是值比较，DataStore 重放相同配置不会重建连接）。
     */
    private fun reconcile(configs: List<McpServerConfig>) {
        synchronized(lock) {
            val desiredIds = configs.mapTo(mutableSetOf(), McpServerConfig::id)
            val removedIds = runtimes.keys.filter { it !in desiredIds }
            removedIds.forEach { id ->
                runtimes.remove(id)?.close()
            }
            configs.forEach { config ->
                val existing = runtimes[config.id]
                when {
                    !config.enabled || config.url.isBlank() -> {
                        runtimes.remove(config.id)?.close()
                        // 已是 IDLE 的快照不重复发布（settings flow 每次全量 emit）。
                        val current = _snapshots.value[config.id]
                        if (current?.status != McpConnectionStatus.IDLE) {
                            putSnapshot(
                                McpServerSnapshot(
                                    serverId = config.id,
                                    status = McpConnectionStatus.IDLE,
                                ),
                            )
                        }
                    }
                    existing?.config != config -> replaceRuntimeLocked(config)
                }
            }
            if (removedIds.isNotEmpty() || _snapshots.value.keys.any { it !in desiredIds }) {
                _snapshots.update { current ->
                    current.filterKeys(desiredIds::contains)
                }
            }
        }
    }

    private fun replaceRuntimeLocked(config: McpServerConfig) {
        runtimes.remove(config.id)?.close()
        // 展开 ${VAR} / ${VAR:-default}（url + headers 值）。缺失变量直接报错，
        // 不带着未解析占位符去建立连接。
        val expanded = McpEnvExpansion.expand(config)
        if (expanded.missingVars.isNotEmpty()) {
            putSnapshot(
                McpServerSnapshot(
                    serverId = config.id,
                    status = McpConnectionStatus.ERROR,
                    error = "Undefined MCP env vars: ${expanded.missingVars.joinToString(", ")}",
                ),
            )
            return
        }
        val runtime = try {
            Runtime(
                config = config,
                client = McpProtocolClient(
                    endpoint = normalizeEndpoint(expanded.config.url),
                    customHeaders = expanded.config.headers,
                    transportType = config.transport,
                    serverId = config.id,
                    serverName = config.name.ifBlank { config.url },
                    elicitationHandler = elicitationController::elicit,
                ),
            )
        } catch (e: IllegalArgumentException) {
            putSnapshot(
                McpServerSnapshot(
                    serverId = config.id,
                    status = McpConnectionStatus.ERROR,
                    error = userMessage(e),
                ),
            )
            return
        }
        runtimes[config.id] = runtime
        runtime.connectionJob = launchConnectionLoop(runtime)
    }

    private fun launchConnectionLoop(runtime: Runtime): Job = scope.launch(Dispatchers.IO) {
        while (isActive && isCurrent(runtime)) {
            putSnapshot(
                McpServerSnapshot(
                    serverId = runtime.config.id,
                    status = McpConnectionStatus.CONNECTING,
                    tools = snapshots.value[runtime.config.id]?.tools.orEmpty(),
                ),
            )
            try {
                val remoteTools = runtime.client.listTools()
                    .distinctBy(McpRemoteTool::name)
                    .sortedBy(McpRemoteTool::name)
                val descriptors = remoteTools.map { remote ->
                        McpToolDescriptor(
                            publicName = publicMcpToolName(runtime.config.id, remote.name),
                            serverId = runtime.config.id,
                            serverName = runtime.config.name.ifBlank { runtime.config.url },
                            remote = remote,
                            enabled = remote.name !in runtime.config.disabledTools,
                        )
                    }
                if (!isCurrent(runtime)) return@launch
                // M6：连接成功才重置退避，连续失败期间退避持续翻倍、重连频率受控。
                runtime.retryMs = INITIAL_RETRY_MS
                putSnapshot(
                    McpServerSnapshot(
                        serverId = runtime.config.id,
                        status = McpConnectionStatus.CONNECTED,
                        tools = descriptors,
                        lastSyncedAt = System.currentTimeMillis(),
                    ),
                )
                return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                markError(runtime, e)
                // M6：退避读/写在 Runtime 字段上，跨重连循环保留，不因循环重启从头计。
                delay(runtime.retryMs)
                runtime.retryMs = min(MAX_RETRY_MS, runtime.retryMs * 2)
            }
        }
    }

    private fun scheduleRetry(runtime: Runtime) {
        synchronized(lock) {
            if (!isCurrent(runtime) || runtime.connectionJob?.isActive == true) return
            runtime.connectionJob = launchConnectionLoop(runtime)
        }
    }

    private fun updateConnected(runtime: Runtime, keepTools: Boolean) {
        if (!isCurrent(runtime)) return
        val previous = snapshots.value[runtime.config.id]
        // L：成功执行工具是高频路径；原快照已是 CONNECTED 且工具集不变时，
        // 新旧快照值相等，直接跳过，避免每次调用都重建 map。
        if (previous?.status == McpConnectionStatus.CONNECTED &&
            (keepTools || previous.tools.isEmpty())
        ) {
            return
        }
        putSnapshot(
            McpServerSnapshot(
                serverId = runtime.config.id,
                status = McpConnectionStatus.CONNECTED,
                tools = if (keepTools) previous?.tools.orEmpty() else emptyList(),
                lastSyncedAt = previous?.lastSyncedAt ?: System.currentTimeMillis(),
            ),
        )
    }

    private fun markError(runtime: Runtime, error: Exception) {
        if (!isCurrent(runtime)) return
        DebugLog.e("McpRegistry", "MCP server ${runtime.config.id} failed", error)
        val previous = snapshots.value[runtime.config.id]
        putSnapshot(
            McpServerSnapshot(
                serverId = runtime.config.id,
                status = McpConnectionStatus.ERROR,
                tools = previous?.tools.orEmpty(),
                error = userMessage(error),
                lastSyncedAt = previous?.lastSyncedAt,
            ),
        )
    }

    private fun isCurrent(runtime: Runtime): Boolean =
        synchronized(lock) { runtimes[runtime.config.id] === runtime }

    private fun putSnapshot(snapshot: McpServerSnapshot) {
        _snapshots.update { it + (snapshot.serverId to snapshot) }
    }

    private fun normalizeEndpoint(raw: String): String {
        val value = raw.trim()
        val uri = runCatching { java.net.URI(value) }.getOrNull()
            ?: throw IllegalArgumentException("Invalid MCP URL")
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            "MCP URL must use http or https"
        }
        require(uri.host != null && uri.userInfo == null && uri.fragment == null) {
            "Invalid MCP URL"
        }
        return value
    }

    private fun userMessage(error: Throwable): String =
        error.localizedMessage?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
}
