package com.lxseek.chat.api.util

import com.lxseek.chat.api.ToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Raised only before opening an HTTP request. A provider request that cannot be proven to satisfy
 * its wire-format grammar must fail locally rather than relying on a remote 400 response.
 */
class RequestFormatException(
    val provider: String,
    val violations: List<String>,
) : IllegalStateException(
    "$provider request validation failed: ${violations.joinToString("; ")}"
)

internal fun requireValidRequestFormat(
    provider: String,
    violations: List<String>,
) {
    if (violations.isNotEmpty()) {
        throw RequestFormatException(provider, violations.distinct())
    }
}

private val requestValidationJson = Json { ignoreUnknownKeys = true }
internal val safeWireToolName = Regex("[A-Za-z0-9_-]{1,64}")
internal val safeWireToolCallId = Regex("[A-Za-z0-9_-]{1,128}")

/**
 * Final serialized-body gate. Object validators prove the typed request graph; this proves that
 * serializer configuration did not omit or reshape mandatory wire fields before network I/O.
 */
internal fun requireValidSerializedRequest(
    provider: String,
    body: String,
    requiredStringFields: Set<String> = emptySet(),
    requiredArrayFields: Set<String> = emptySet(),
) {
    val root = runCatching { requestValidationJson.parseToJsonElement(body) as? JsonObject }
        .getOrNull()
    val violations = mutableListOf<String>()
    if (root == null) {
        violations += "serialized request is not a JSON object"
    } else {
        requiredStringFields.forEach { field ->
            val value = runCatching { root[field]?.jsonPrimitive?.content }.getOrNull()
            if (value.isNullOrBlank()) violations += "serialized $field is absent or blank"
        }
        requiredArrayFields.forEach { field ->
            val value = root[field] as? JsonArray
            if (value.isNullOrEmpty()) violations += "serialized $field is absent or empty"
        }
    }
    requireValidRequestFormat(provider, violations)
}

internal fun validateToolDefinitions(tools: List<ToolDefinition>?): List<String> {
    if (tools.isNullOrEmpty()) return emptyList()
    val violations = mutableListOf<String>()
    val names = mutableSetOf<String>()
    tools.forEachIndexed { index, tool ->
        val function = tool.function
        if (tool.type != "function") violations += "tools[$index].type must be function"
        if (function.name.isBlank()) {
            violations += "tools[$index].function.name is blank"
        } else if (!function.name.matches(safeWireToolName)) {
            violations += "tools[$index].function.name is not wire-safe"
        } else if (!names.add(function.name)) {
            violations += "duplicate tool name ${function.name}"
        }
        if (function.parameters.type != "object") {
            violations += "tool ${function.name} parameters must be an object"
        }
        val unknownRequired =
            function.parameters.required.toSet() - function.parameters.properties.keys
        if (unknownRequired.isNotEmpty()) {
            violations += "tool ${function.name} requires undefined properties"
        }
        function.parameters.properties.forEach { (propertyName, property) ->
            if (propertyName.isBlank()) {
                violations += "tool ${function.name} has a blank property name"
            }
            if (property.type.isBlank()) {
                violations += "tool ${function.name} property $propertyName has no type"
            }
            if (property.type == "array" && property.items == null) {
                violations += "tool ${function.name} array $propertyName has no items schema"
            }
        }
    }
    return violations
}
