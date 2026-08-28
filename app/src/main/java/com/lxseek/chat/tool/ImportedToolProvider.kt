package com.lxseek.chat.tool

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.util.DebugLog
import com.lxseek.chat.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 零代码工具导入：把一份 `imported_tools.json` 里的工具定义（kind=http 或 intent）注册给模型调用。
 *
 * 适合"给 AI 加一个外部队列/HTTP 接口/启动某应用"这类轻量扩展，无需走完整 Plugin/MCP 流程。
 * 文件位于 app 私有目录 `filesDir/imported_tools.json`，形如：
 * { "tools": [ { "name": "post_to_queue", "description": "...", "kind": "http",
 *   "method": "POST", "url": "https://example.com/q", "json_body": { "topic": "{topic}" } } ] }
 * 参数占位符写成 "{param}"，执行时用调用实参替换。http 仅允许 http/https。
 */
class ImportedToolProvider(private val app: Application) : ToolProvider {

    @Serializable
    private data class ImportedTool(
        val name: String,
        val description: String = "",
        val kind: Kind = Kind.HTTP,
        val parameters: Map<String, ParamSpec> = emptyMap(),
        val required: List<String> = emptyList(),
        val method: String = "GET",
        val url: String = "",
        val headers: Map<String, String> = emptyMap(),
        @SerialName("json_body") val jsonBody: JsonElement? = null,
        @SerialName("intent_action") val intentAction: String? = null,
        @SerialName("intent_data") val intentData: String? = null,
        @SerialName("intent_package") val intentPackage: String? = null,
    )

    @Serializable
    private data class ParamSpec(val type: String = "string", val description: String = "")

    @Serializable
    private enum class Kind { @SerialName("http") HTTP, @SerialName("intent") INTENT }

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private fun file(): File = File(app.filesDir, "imported_tools.json")

    /** (re)read the definitions on every request so edits take effect without restarting. */
    private fun definitions(): List<ImportedTool> {
        val f = file()
        if (!f.exists()) return emptyList()
        return try {
            val root = json.parseToJsonElement(f.readText()).jsonObject
            val arr = root["tools"] ?: return emptyList()
            (arr as? JsonArray)?.mapNotNull { el ->
                runCatching { json.decodeFromJsonElement(ImportedTool.serializer(), el) }.getOrNull()
            }?.filter { it.name.isNotBlank() } ?: emptyList()
        } catch (e: Exception) {
            DebugLog.e(TAG, "Failed to parse imported_tools.json", e)
            emptyList()
        }
    }

    override fun toolDescriptors(ctx: GenerationContext): List<ToolDescriptor> =
        listOf(deployDescriptor()) + definitions().map { t ->
            ToolDescriptor(
                definition = ToolDefinition(function = ToolFunction(
                    name = t.name,
                    description = t.description.ifBlank { "Imported HTTP tool." },
                    parameters = ToolParameters(
                        properties = t.parameters.mapValues { (_, s) ->
                        if (s.type == "array") {
                            ToolProperty(
                                "array",
                                s.description,
                                items = ToolProperty("object", "Array element."),
                            )
                        } else {
                            ToolProperty(s.type, s.description)
                        }
                    },
                        required = t.required,
                    ),
                )),
                riskLevel = when (t.kind) {
                    Kind.INTENT -> RiskLevel.Moderate
                    Kind.HTTP -> if (t.method.equals("GET", true)) RiskLevel.LowRisk else RiskLevel.Moderate
                },
                tier = ToolTier.Extended,
            )
        }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> =
        toolDescriptors(ctx).map { it.definition }

    private fun deployDescriptor(): ToolDescriptor = ToolDescriptor(
        definition = ToolDefinition(function = ToolFunction(
            name = DEPLOY,
            description = "Register or update zero-code tools for later use by writing them to app storage. Pass a JSON 'tools' array. Each item supports kind 'http' (name, description, url, method, headers, json_body, parameters, required) or 'intent' (name, description, intent_action, intent_data, intent_package, parameters, required). 'url' must be http/https. Deployed tools become callable, using '{param}' placeholders for arguments.",
            parameters = ToolParameters(
                properties = mapOf(
                    "tools" to ToolProperty(
                        type = "array",
                        description = "Array of tool definitions to register.",
                        items = ToolProperty(
                            type = "object",
                            description = "A single imported tool definition.",
                        ),
                    ),
                ),
                required = listOf("tools"),
            ),
        )),
        riskLevel = RiskLevel.Moderate,
        tier = ToolTier.Extended,
    )

    override fun handles(name: String): Boolean = name == DEPLOY || definitions().any { it.name == name }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String =
        withContext(Dispatchers.IO) {
            if (name == DEPLOY) return@withContext executeDeploy(parseArgs(arguments))
            val tool = definitions().firstOrNull { it.name == name }
                ?: return@withContext errorJson("unknown_tool", "Unknown imported tool: $name")
            try {
                when (tool.kind) {
                    Kind.HTTP -> executeHttp(tool, parseArgs(arguments))
                    Kind.INTENT -> executeIntent(tool, parseArgs(arguments))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.e(TAG, "imported $name failed", e)
                errorJson("tool_error", e.message)
            }
        }

    private fun executeDeploy(args: JsonObject): String {
        val raw = args["tools"] as? JsonArray
            ?: return errorJson("bad_input", "'tools' must be an array of tool definitions.")
        val validated = raw.mapNotNull { el ->
            runCatching { json.decodeFromJsonElement(ImportedTool.serializer(), el) }.getOrNull()
        }.filter { it.name.isNotBlank() && isSafe(it) }
        if (validated.isEmpty()) {
            return errorJson("validation", "No valid tool definitions: http needs http/https url, intent needs intent_action or intent_data.")
        }
        val merged = LinkedHashMap<String, ImportedTool>()
        definitions().forEach { t -> merged[t.name] = t }
        validated.forEach { t -> merged[t.name] = t }
        val elements = merged.values.map { json.encodeToJsonElement(ImportedTool.serializer(), it) }
        val f = file()
        f.parentFile?.mkdirs()
        f.writeText(buildJsonObject { put("tools", JsonArray(elements)) }.toString())
        return buildJsonObject {
            put("status", "ok")
            put("deployed", validated.size)
            put("total", merged.size)
        }.toString()
    }

    private fun isSafe(t: ImportedTool): Boolean = when (t.kind) {
        Kind.HTTP -> t.url.startsWith("http://") || t.url.startsWith("https://")
        Kind.INTENT -> !t.intentAction.isNullOrBlank() || !t.intentData.isNullOrBlank()
    }

    private fun executeHttp(tool: ImportedTool, args: JsonObject): String {
        val urlStr = substitute(tool.url, args)
        if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
            return errorJson("bad_url", "Only http/https URLs are allowed for imported HTTP tools.")
        }
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = tool.method.uppercase()
            connectTimeout = 30000
            readTimeout = 30000
            tool.headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            tool.buildRequestBody(args)?.let { body ->
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            return buildJsonObject {
                put("status", "ok")
                put("http_status", code)
                put("body", body.take(MAX_RESPONSE))
            }.toString()
        } finally {
            conn.disconnect()
        }
    }

    private fun executeIntent(tool: ImportedTool, args: JsonObject): String {
        val intent = Intent(tool.intentAction ?: Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            tool.intentData?.let { data = Uri.parse(substitute(it, args)) }
            tool.intentPackage?.let { setPackage(it) }
        }
        val started = runCatching { app.startActivity(intent) }.isSuccess
        return buildJsonObject { put("status", if (started) "ok" else "error") }.toString()
    }

    /** Replace "{param}" placeholders with actual call arguments; unknown ones become empty. */
    private fun substitute(template: String, args: JsonObject): String {
        if ('{' !in template) return template
        var out = template
        for ((k, v) in args) {
            val text = (v as? JsonPrimitive)?.let { it.contentOrNull } ?: v.toString()
            out = out.replace("{$k}", text)
        }
        // strip any unresolved placeholders
        return out.replace(Regex("\\{[^}]+}"), "")
    }

    private fun ImportedTool.buildRequestBody(args: JsonObject): String? {
        val template = jsonBody ?: return null
        if (template is JsonPrimitive || (template as? JsonObject)?.isEmpty() == true) return null
        val substituted = template.substituteValue(args)
        return substituted.toString()
    }

    private fun JsonElement.substituteValue(args: JsonObject): JsonElement = when (this) {
        is JsonObject -> JsonObject(
            mapValues { (_, v) -> v.substituteValue(args) }
        )
        is JsonArray -> JsonArray(map { it.substituteValue(args) })
        is JsonPrimitive -> {
            contentOrNull?.takeIf { it.startsWith("{") && it.endsWith("}") }?.let { key ->
                val actual = substitute(key, args)
                if (actual == key) this else JsonPrimitive(actual)
            } ?: this
        }
    }

    private fun parseArgs(arguments: String): JsonObject =
        runCatching { Json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }

    private fun errorJson(error: String, message: String?): String = buildJsonObject {
        put("status", "error")
        put("error", error)
        if (!message.isNullOrBlank()) put("message", message)
    }.toString()

    private companion object {
        private const val TAG = "ImportedTool"
        private const val MAX_RESPONSE = 64 * 1024
        private const val DEPLOY = "deploy_tools"
    }
}