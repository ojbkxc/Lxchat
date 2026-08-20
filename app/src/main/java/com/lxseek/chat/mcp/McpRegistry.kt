package com.lxseek.chat.mcp

import android.content.Context
import com.lxseek.chat.data.McpServerConfig
import com.lxseek.chat.data.repository.SettingsRepository
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
            updateConnected(runtime, keepTools = true)
            ToolExecutionResult(
                text = resultText,
                images = attachments,
                structuredContent = structured,
                displayText = displayText,
                isError = payload.isError,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            markError(runtime, e)
            scheduleRetry(runtime)
            ToolExecutionResult(
                text = "MCP tool '${descriptor.remote.name}' failed: ${userMessage(e)}",
                isError = true,
            )
        }
    }

    private fun reconcile(configs: List<McpServerConfig>) {
        synchronized(lock) {
            val desiredIds = configs.mapTo(mutableSetOf(), McpServerConfig::id)
            runtimes.keys.filter { it !in desiredIds }.forEach { id ->
                runtimes.remove(id)?.close()
            }
            configs.forEach { config ->
                val existing = runtimes[config.id]
                when {
                    !config.enabled || config.url.isBlank() -> {
                        runtimes.remove(config.id)?.close()
                        putSnapshot(
                            McpServerSnapshot(
                                serverId = config.id,
                                status = McpConnectionStatus.IDLE,
                            ),
                        )
                    }
                    existing?.config != config -> replaceRuntimeLocked(config)
                }
            }
            _snapshots.update { current ->
                current.filterKeys(desiredIds::contains)
            }
        }
    }

    private fun replaceRuntimeLocked(config: McpServerConfig) {
        runtimes.remove(config.id)?.close()
        val runtime = try {
            Runtime(
                config = config,
                client = McpProtocolClient(
                    endpoint = normalizeEndpoint(config.url),
                    customHeaders = config.headers,
                    transportType = config.transport,
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
        var retryMs = INITIAL_RETRY_MS
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
                delay(retryMs)
                retryMs = min(MAX_RETRY_MS, retryMs * 2)
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
