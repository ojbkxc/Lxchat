package com.lxseek.chat.mcp

import com.lxseek.chat.data.McpTransportType
import com.lxseek.chat.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared MCP JSON-RPC lifecycle.
 *
 * Transport-specific connection/session behavior lives behind [McpClientTransport]. Requests are
 * serialized because initialization and session replacement must be observed atomically.
 */
internal class McpProtocolClient(
    endpoint: String,
    customHeaders: Map<String, String>,
    transportType: McpTransportType,
    private val serverId: String = "",
    private val serverName: String = "",
    private val elicitationHandler: McpElicitationHandler? = null,
) : AutoCloseable {
    companion object {
        private const val STREAMABLE_HTTP_PROTOCOL_VERSION = "2025-11-25"
        private const val LEGACY_SSE_PROTOCOL_VERSION = "2024-11-05"
        private const val MAX_TOOL_PAGES = 100
        private val SUPPORTED_PROTOCOL_VERSIONS = setOf(
            STREAMABLE_HTTP_PROTOCOL_VERSION,
            "2025-06-18",
            "2025-03-26",
            LEGACY_SSE_PROTOCOL_VERSION,
        )
    }

    private val protocolVersion = when (transportType) {
        McpTransportType.STREAMABLE_HTTP -> STREAMABLE_HTTP_PROTOCOL_VERSION
        McpTransportType.SSE -> LEGACY_SSE_PROTOCOL_VERSION
    }
    private val transport = createMcpClientTransport(
        type = transportType,
        endpoint = endpoint,
        customHeaders = customHeaders,
        protocolVersion = protocolVersion,
    )
    private val ids = AtomicLong(0)
    private val mutex = Mutex()
    // Server-initiated requests (elicitation) are handled off the request lock on this scope so
    // a long user interaction never blocks the tool-loop serialization mutex.
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var initialized = false
    private var initializedGeneration: Long? = null

    init {
        transport.setServerRequestListener { envelope -> handleServerRequest(envelope) }
    }

    suspend fun listTools(): List<McpRemoteTool> = mutex.withLock {
        retryAfterSessionExpiry {
            ensureInitializedLocked()
            val tools = mutableListOf<McpRemoteTool>()
            var cursor: String? = null
            repeat(MAX_TOOL_PAGES) {
                val params = if (cursor == null) {
                    buildJsonObject {}
                } else {
                    buildJsonObject { put("cursor", cursor) }
                }
                val result = requestLocked("tools/list", params)
                val page = result["tools"] as? JsonArray
                    ?: throw IOException("MCP tools/list returned no tools array")
                page.forEach { element ->
                    val obj = element.asObjectOrNull() ?: return@forEach
                    val name = (obj["name"] as? JsonPrimitive)?.contentOrNull
                        ?.takeIf(String::isNotBlank)
                        ?: return@forEach
                    tools += McpRemoteTool(
                        name = name,
                        description = (obj["description"] as? JsonPrimitive)
                            ?.contentOrNull
                            .orEmpty(),
                        inputSchema = obj["inputSchema"]?.asObjectOrNull()
                            ?: buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {})
                            },
                    )
                }
                cursor = (result["nextCursor"] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.takeIf(String::isNotBlank)
                if (cursor == null) return@retryAfterSessionExpiry tools
            }
            throw IOException("MCP tools/list exceeded $MAX_TOOL_PAGES pages")
        }
    }

    suspend fun callTool(name: String, arguments: JsonObject): McpCallPayload = mutex.withLock {
        retryAfterSessionExpiry {
            ensureInitializedLocked()
            val result = requestLocked(
                method = "tools/call",
                params = buildJsonObject {
                    put("name", name)
                    put("arguments", arguments)
                },
            )
            parseCallPayload(result)
        }
    }

    /**
     * Enumerate resources exposed by this server (`resources/list`), paginating like tools.
     * Servers that do not implement resources throw; callers treat that as "no resources".
     */
    suspend fun listResources(): List<McpResource> = mutex.withLock {
        retryAfterSessionExpiry {
            ensureInitializedLocked()
            val resources = mutableListOf<McpResource>()
            var cursor: String? = null
            repeat(MAX_TOOL_PAGES) {
                val params = if (cursor == null) {
                    buildJsonObject {}
                } else {
                    buildJsonObject { put("cursor", cursor) }
                }
                val result = requestLocked("resources/list", params)
                val page = result["resources"] as? JsonArray
                    ?: throw IOException("MCP resources/list returned no resources array")
                page.forEach { element ->
                    val obj = element.asObjectOrNull() ?: return@forEach
                    val uri = (obj["uri"] as? JsonPrimitive)?.contentOrNull
                        ?.takeIf(String::isNotBlank)
                        ?: return@forEach
                    resources += McpResource(
                        uri = uri,
                        name = (obj["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                        description = (obj["description"] as? JsonPrimitive)
                            ?.contentOrNull
                            .orEmpty(),
                        mimeType = (obj["mimeType"] as? JsonPrimitive)?.contentOrNull,
                    )
                }
                cursor = (result["nextCursor"] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.takeIf(String::isNotBlank)
                if (cursor == null) return@retryAfterSessionExpiry resources
            }
            throw IOException("MCP resources/list exceeded $MAX_TOOL_PAGES pages")
        }
    }

    /**
     * Read a single resource by URI (`resources/read`). Returned items may be text or base64
     * blobs; callers decide how to inline/persist each one (mirrors cc-haha's ReadMcpResourceTool).
     */
    suspend fun readResource(uri: String): List<McpResourceContent> = mutex.withLock {
        retryAfterSessionExpiry {
            ensureInitializedLocked()
            val result = requestLocked(
                method = "resources/read",
                params = buildJsonObject { put("uri", uri) },
            )
            val contents = mutableListOf<McpResourceContent>()
            (result["contents"] as? JsonArray).orEmpty().forEach { element ->
                val obj = element.asObjectOrNull() ?: return@forEach
                contents += McpResourceContent(
                    uri = (obj["uri"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    mimeType = (obj["mimeType"] as? JsonPrimitive)?.contentOrNull,
                    text = (obj["text"] as? JsonPrimitive)?.contentOrNull,
                    blob = (obj["blob"] as? JsonPrimitive)?.contentOrNull,
                )
            }
            contents
        }
    }

    override fun close() {
        initialized = false
        initializedGeneration = null
        serverScope.cancel()
        transport.close()
    }

    private suspend fun ensureInitializedLocked() {
        val generation = transport.ensureReady()
        if (initialized && initializedGeneration == generation) return
        initialized = false
        initializedGeneration = null
        initializeLocked(generation)
    }

    private suspend fun initializeLocked(generation: Long) {
        val result = requestLocked(
            method = "initialize",
            params = buildJsonObject {
                put("protocolVersion", protocolVersion)
                put(
                    "capabilities",
                    buildJsonObject {
                        // Server → client elicitation (forms / URL confirmation), MCP 2025-11-25.
                        if (elicitationHandler != null) put("elicitation", true)
                        // Client consumes server resources (resources/list, resources/read).
                        put("resources", buildJsonObject { put("listChanged", false) })
                    },
                )
                put(
                    "clientInfo",
                    buildJsonObject {
                        put("name", "LxChat")
                        // 版本随构建走，避免硬编码漂移（TtsManager 诊断日志同源）。
                        put("version", com.lxseek.chat.BuildConfig.VERSION_NAME)
                    },
                )
            },
        )
        val negotiated = (result["protocolVersion"] as? JsonPrimitive)?.contentOrNull
            ?: throw IOException("MCP initialize returned no protocolVersion")
        if (negotiated !in SUPPORTED_PROTOCOL_VERSIONS) {
            throw IOException("Unsupported MCP protocol version: $negotiated")
        }
        notificationLocked("notifications/initialized", buildJsonObject {})
        if (transport.ensureReady() != generation) {
            throw McpSessionExpiredException("MCP session changed during initialization")
        }
        initializedGeneration = generation
        initialized = true
    }

    private suspend fun <T> retryAfterSessionExpiry(block: suspend () -> T): T {
        var expiry: McpSessionExpiredException? = null
        repeat(2) { attempt ->
            try {
                return block()
            } catch (error: McpSessionExpiredException) {
                expiry = error
                initialized = false
                initializedGeneration = null
                transport.resetSession()
                if (attempt == 1) throw error
            }
        }
        throw checkNotNull(expiry)
    }

    private suspend fun requestLocked(method: String, params: JsonObject): JsonObject {
        val id = ids.incrementAndGet()
        val envelope = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val response = transport.send(
            envelope = envelope,
            expectResponse = true,
            callTimeoutMillis = if (method == "tools/call") {
                Constants.TOOL_EXECUTION_TIMEOUT_MS
            } else {
                Constants.NETWORK_TOOL_TIMEOUT_MS
            },
        ) ?: throw IOException("MCP $method returned an empty response")
        val responseId = (response["id"] as? JsonPrimitive)?.contentOrNull
        if (responseId != id.toString()) {
            throw IOException("MCP $method returned mismatched response id")
        }
        response["error"]?.takeUnless { it is JsonNull }?.let { error ->
            val errorObj = error.asObjectOrNull()
            val message = (errorObj?.get("message") as? JsonPrimitive)?.contentOrNull
                ?: error.toString()
            throw IOException("MCP $method failed: $message")
        }
        return response["result"]?.asObjectOrNull()
            ?: throw IOException("MCP $method returned no result")
    }

    private suspend fun notificationLocked(method: String, params: JsonObject) {
        transport.send(
            envelope = buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", params)
            },
            expectResponse = false,
            callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS,
        )
    }

    private fun parseCallPayload(result: JsonObject): McpCallPayload {
        val texts = mutableListOf<String>()
        val images = mutableListOf<McpImagePayload>()
        (result["content"] as? JsonArray).orEmpty().forEach { element ->
            val item = element.asObjectOrNull() ?: return@forEach
            when ((item["type"] as? JsonPrimitive)?.contentOrNull) {
                "text" -> (item["text"] as? JsonPrimitive)?.contentOrNull?.let(texts::add)
                "image" -> {
                    val data = (item["data"] as? JsonPrimitive)?.contentOrNull
                    val mimeType = (item["mimeType"] as? JsonPrimitive)?.contentOrNull
                    if (!data.isNullOrBlank() && !mimeType.isNullOrBlank()) {
                        images += McpImagePayload(data, mimeType)
                    }
                }
                "resource" -> {
                    val resource = item["resource"]?.asObjectOrNull()
                    val mimeType = (resource?.get("mimeType") as? JsonPrimitive)?.contentOrNull
                    val blob = (resource?.get("blob") as? JsonPrimitive)?.contentOrNull
                    val text = (resource?.get("text") as? JsonPrimitive)?.contentOrNull
                    if (!blob.isNullOrBlank() && mimeType?.startsWith("image/") == true) {
                        images += McpImagePayload(blob, mimeType)
                    } else if (!text.isNullOrBlank()) {
                        texts += text
                    }
                }
            }
        }
        return McpCallPayload(
            textParts = texts,
            images = images,
            structuredContent = result["structuredContent"]?.takeUnless { it is JsonNull },
            isError = (result["isError"] as? JsonPrimitive)?.contentOrNull
                ?.toBooleanStrictOrNull() == true,
        )
    }

    /**
     * Invoked by the transport for server-initiated JSON-RPC requests (e.g. `elicitation/form`).
     * Dispatches onto [serverScope] so the user interaction never blocks the tool loop, then
     * posts the JSON-RPC response back to the server.
     */
    private fun handleServerRequest(envelope: JsonObject) {
        val id = envelope["id"] ?: return
        val method = (envelope["method"] as? JsonPrimitive)?.contentOrNull ?: return
        val params = envelope["params"]?.asObjectOrNull() ?: buildJsonObject {}
        serverScope.launch {
            val outcome = runCatching { processServerRequest(method, params) }
            val response = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                if (outcome.isSuccess) {
                    put("result", outcome.getOrThrow())
                } else {
                    put(
                        "error",
                        buildJsonObject {
                            put("code", -32000)
                            put(
                                "message",
                                outcome.exceptionOrNull()?.localizedMessage
                                    ?: "Unhandled server request",
                            )
                        },
                    )
                }
            }
            runCatching {
                transport.sendServerResponse(response, Constants.NETWORK_TOOL_TIMEOUT_MS)
            }
        }
    }

    private suspend fun processServerRequest(method: String, params: JsonObject): JsonObject {
        val handler = elicitationHandler
            ?: throw IOException("MCP server request '$method' is not supported")
        return when (method) {
            "elicitation/form" -> handleElicitationForm(params, handler)
            "elicitation/url" -> handleElicitationUrl(params, handler)
            else -> throw IOException("Unsupported MCP server request: $method")
        }
    }

    private suspend fun handleElicitationForm(
        params: JsonObject,
        handler: McpElicitationHandler,
    ): JsonObject {
        val request = McpElicitationRequest(
            serverId = serverId,
            serverName = serverName,
            mode = McpElicitationMode.FORM,
            message = (params["message"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            requestedSchema = params["requestedSchema"]?.asObjectOrNull(),
        )
        val result = handler.elicit(request)
        return buildJsonObject {
            put("action", result.action)
            result.content?.let { put("content", it) }
        }
    }

    private suspend fun handleElicitationUrl(
        params: JsonObject,
        handler: McpElicitationHandler,
    ): JsonObject {
        val request = McpElicitationRequest(
            serverId = serverId,
            serverName = serverName,
            mode = McpElicitationMode.URL,
            message = (params["message"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            url = (params["url"] as? JsonPrimitive)?.contentOrNull,
            elicitationId = (params["elicitationId"] as? JsonPrimitive)?.contentOrNull,
        )
        val result = handler.elicit(request)
        return buildJsonObject { put("action", result.action) }
    }
}
