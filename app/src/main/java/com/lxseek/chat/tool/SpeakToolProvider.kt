package com.lxseek.chat.tool

import android.app.Application
import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.util.TtsManager
import com.lxseek.chat.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * TTS 独立语音通道：允许 Agent 主动调用 `speak` 朗读任意文本（可与聊天正文不同，用于播报进度、
 * 朗读书面/分角色台词、读列表等）。与"回复自动朗读"开关解耦——两者都在时优先本通道，避免重复念。
 *
 * 复用 [TtsManager]（系统引擎 / 网络 provider 均可），不引入新引擎，零包体开销。
 */
class SpeakToolProvider(private val app: Application) : ToolProvider {

    override fun toolDescriptors(ctx: GenerationContext): List<ToolDescriptor> = listOf(
        ToolDescriptor(
            definition = ToolDefinition(function = ToolFunction(
                name = SPEAK,
                description = "Speak the given text aloud using the device TTS engine. Use this when the user asks you to read something out loud, narrate, or when spoken output helps. The text you speak can be shorter or different from your written reply (e.g. just the key line).",
                parameters = ToolParameters(
                    properties = mapOf(
                        "text" to ToolProperty("string", "The text to speak aloud."),
                        "language" to ToolProperty("string", "Optional language hint: 'zh', 'en', or 'system' (default 'system')."),
                        "rate" to ToolProperty("number", "Optional speech rate multiplier (0.5 - 2.0, default 1.0)."),
                    ),
                    required = listOf("text"),
                ),
            )),
            riskLevel = RiskLevel.LowRisk,
            tier = ToolTier.Extended,
        ),
    )

    override fun handles(name: String): Boolean = name == SPEAK

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (name != SPEAK) return error("Unknown tool: $name")
        val args = parseArgs(arguments)
        val text = args["text"]?.jsonPrimitive?.contentOrNull ?: return error("text is required")
        if (text.isBlank()) return error("text must not be blank")
        val language = args["language"]?.jsonPrimitive?.contentOrNull ?: "system"
        val rate = (args["rate"] as? JsonPrimitive)?.let { it.contentOrNull?.toFloatOrNull() } ?: 1.0f

        TtsManager.init(app)
        val started = TtsManager.speak(text = text, language = language, rate = rate)
        if (!started && !TtsManager.isAvailable.value) {
            return error("TTS not available. Ask the user to check device text-to-speech settings.")
        }
        return buildJsonObject {
            put("status", if (started) "speaking" else "queued")
            put("text", text)
        }.toString()
    }

    private fun parseArgs(arguments: String): JsonObject =
        runCatching { Json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))

    private fun error(message: String): String = buildJsonObject {
        put("status", "error")
        put("error", message)
    }.toString()

    companion object {
        const val SPEAK = "speak"
    }
}