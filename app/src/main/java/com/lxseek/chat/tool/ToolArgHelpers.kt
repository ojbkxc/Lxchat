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
 * Providers whose `argString`/`argInt` semantics differ (e.g. blank-vs-null handling)
 * keep their own overrides and must not switch to these.
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

/** Read a string argument from a JSON tool-arguments blob. Returns null for missing/`null`/invalid. */
fun argString(key: String, arguments: String): String? {
    val stripped = arguments.ifBlank { "{}" }
    return try {
        val el = Json.decodeFromString<Map<String, JsonPrimitive>>(stripped)[key]
        val v = el?.content ?: return null
        if (v == "null") null else v
    } catch (_: Exception) {
        null
    }
}

/** Read an Int argument from a JSON tool-arguments blob. Returns null for missing/invalid. */
fun argInt(key: String, arguments: String): Int? {
    val stripped = arguments.ifBlank { "{}" }
    return try {
        Json.decodeFromString<Map<String, JsonPrimitive>>(stripped)[key]?.content?.toIntOrNull()
    } catch (_: Exception) {
        null
    }
}

/** Read a Long argument from a JSON tool-arguments blob. Returns null for missing/invalid. */
fun argLong(key: String, arguments: String): Long? {
    val stripped = arguments.ifBlank { "{}" }
    return try {
        Json.decodeFromString<Map<String, JsonPrimitive>>(stripped)[key]?.content?.toLongOrNull()
    } catch (_: Exception) {
        null
    }
}

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