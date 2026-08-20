package com.lxseek.chat.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun parseToolArgs(arguments: String): Map<String, JsonElement> {
    return try {
        val argsStr = arguments.ifBlank { "{}" }
        Json.decodeFromString<Map<String, JsonElement>>(argsStr)
    } catch (_: Exception) { emptyMap() }
}

internal fun jsonError(type: String, message: String, server: String? = null, command: String? = null): String {
    return buildJsonObject {
        if (type.isNotBlank()) put("type", type)
        put("error", "error"); put("message", message)
        if (server != null) put("server", server)
        if (command != null) put("command", command)
    }.toString()
}

internal fun arg(args: Map<String, JsonElement>, key: String): String {
    return (args[key] as? JsonPrimitive)?.content ?: ""
}

internal fun boolArg(args: Map<String, JsonElement>, key: String): Boolean =
    (args[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true
