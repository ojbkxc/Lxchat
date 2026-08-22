package com.lxseek.chat.automation

import com.lxseek.chat.data.local.WorkflowStepConfig
import com.lxseek.chat.data.local.WorkflowStepType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * JSON (de)serialization for [WorkflowStepConfig]. Stored as the plain `configJson` TEXT column
 * on [com.lxseek.chat.data.local.WorkflowStepEntity]; a compact hand-rolled encoding avoids a
 * serialization plugin dependency on the entity class.
 */
object WorkflowConfigCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(config: WorkflowStepConfig): String = when (config) {
        is WorkflowStepConfig.Task -> json.encodeToString(
            JsonObject.serializer(),
            buildJsonTask(config),
        )
        is WorkflowStepConfig.Delay -> json.encodeToString(
            JsonObject.serializer(),
            JsonObject(mapOf("delayMs" to JsonPrimitive(config.delayMs))),
        )
    }

    fun decode(type: String, raw: String): WorkflowStepConfig? = try {
        val obj = json.decodeFromString(JsonObject.serializer(), raw)
        when (type) {
            WorkflowStepType.TASK -> WorkflowStepConfig.Task(
                prompt = obj["prompt"]?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                modelId = obj["modelId"]?.let { (it as? JsonPrimitive)?.contentOrNull },
            )
            WorkflowStepType.DELAY -> WorkflowStepConfig.Delay(
                delayMs = (obj["delayMs"] as? JsonPrimitive)?.longOrNull ?: 0L,
            )
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    /** Default human-readable title for a new step. */
    fun defaultTitle(type: String): String = when (type) {
        WorkflowStepType.TASK -> "生成回复"
        WorkflowStepType.DELAY -> "等待"
        else -> "步骤"
    }

    private fun buildJsonTask(config: WorkflowStepConfig.Task): JsonObject {
        val fields = mutableMapOf<String, JsonPrimitive>("prompt" to JsonPrimitive(config.prompt))
        config.modelId?.takeIf { it.isNotBlank() }?.let { fields["modelId"] = JsonPrimitive(it) }
        return JsonObject(fields)
    }
}
