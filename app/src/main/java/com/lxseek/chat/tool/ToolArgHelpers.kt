package com.lxseek.chat.tool

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Shared helpers extracted from multiple [ToolProvider] implementations to eliminate
 * copy-paste duplication. These are pure utilities used by providers that parse JSON
 * tool arguments and build homogeneous error envelopes.
 *
 * 参数解析已统一到共享核心 [argPrimitive]（W4F 合并）：本文件的顶层
 * [argString]/[argInt]/[argLong] 与 AndroidAppControllerToolProvider、ImToolProvider
 * 的私有实现均委托该核心完成「解析 + 取值 + 异常兜底」，三者原有的语义差异
 * （"null" 字面量归一化、trim 与否、JSON null 的返回值）改由各调用方的后处理
 * 参数化表达，对外行为与合并前保持一致（详见 [argPrimitive] 文档）。
 */

/** Build a [ToolDefinition] from the common (name, description, properties, required) tuple. */
fun tool(
    name: String,
    description: String,
    properties: Map<String, ToolProperty>,
    required: List<String>,
): ToolDefinition = ToolDefinition(
    function = ToolFunction(
        name = name,
        description = description,
        parameters = ToolParameters(properties = properties, required = required),
    ),
)

/**
 * 共享的单值参数解析核心（W4F：合并三处重复的 argString/argInt/argLong 实现）。
 *
 * 统一完成：空白 [arguments] 按 `"{}"` 兜底 → 解析为 Map → 取出 [key] 对应的原始
 * [JsonPrimitive]；非法 JSON、顶层非对象、value 非简单值时统一返回 null。
 *
 * 各调用方的语义差异在调用方一侧用后处理表达，核心本身不做归一化：
 * - 本文件 [argString]：字面量 `"null"` 字符串归一化为 null（JSON null 的
 *   JsonNull.content 恰为 "null"，两者一并归一化），数值不做 trim；
 * - AndroidAppControllerToolProvider.argValue：值交给 parse 回调前先 trim；
 * - ImToolProvider：采用 contentOrNull 语义（JSON null → null/空串），并传入自带
 *   ignoreUnknownKeys 的 Json 实例——该配置只影响 data class 解码的字段匹配，
 *   不影响 Map<String, JsonPrimitive> 的解码，行为与默认实例一致。
 *
 * 注意：返回值可能是 [kotlinx.serialization.json.JsonNull]（其 content 为字面量
 * "null"），是否归一化由调用方决定，以保持各自的对外语义不变。
 */
fun argPrimitive(key: String, arguments: String, json: Json = Json): JsonPrimitive? = try {
    json.decodeFromString<Map<String, JsonPrimitive>>(arguments.ifBlank { "{}" })[key]
} catch (_: Exception) {
    null
}

/** Read a string argument from a JSON tool-arguments blob. Returns null for missing/`null`/invalid. */
fun argString(key: String, arguments: String): String? =
    argPrimitive(key, arguments)?.content?.let { if (it == "null") null else it }

/** Read an Int argument from a JSON tool-arguments blob. Returns null for missing/invalid. */
fun argInt(key: String, arguments: String): Int? =
    argPrimitive(key, arguments)?.content?.toIntOrNull()

/** Read a Long argument from a JSON tool-arguments blob. Returns null for missing/invalid. */
fun argLong(key: String, arguments: String): Long? =
    argPrimitive(key, arguments)?.content?.toLongOrNull()

/** Read a Boolean argument from a JSON tool-arguments blob. Returns null for missing/invalid. */
fun argBool(key: String, arguments: String): Boolean? =
    argString(key, arguments)?.toBooleanStrictOrNull()

/**
 * Build a homogeneous tool-error JSON envelope. [type] is the provider-specific discriminator
 * (e.g. `"device_error"`, `"contact_error"`); callers pass their own constant so the wire
 * format stays byte-identical after deduplication.
 */
fun toolError(type: String, code: String, message: String?): String = buildJsonObject {
    put("type", type)
    put("error", code)
    if (!message.isNullOrBlank()) put("message", message)
}.toString()

/** Check whether [permission] is granted for [context] without throwing. */
fun checkPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED