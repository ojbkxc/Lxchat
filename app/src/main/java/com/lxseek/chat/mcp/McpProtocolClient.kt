package com.lxseek.chat.mcp

import com.lxseek.chat.data.McpTransportType
import com.lxseek.chat.util.Constants
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

    private var initialized = false
    private var initializedGeneration: Long? = null

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

    override fun close() {
        initialized = false
        initializedGeneration = null
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
                put("capabilities", buildJsonObject {})
                put(
                    "clientInfo",
                    buildJsonObject {
                        put("name", "LxChat")
                        put("version", "1.3.7")
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
}
