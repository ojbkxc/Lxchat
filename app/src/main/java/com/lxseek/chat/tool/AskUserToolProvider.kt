package com.lxseek.chat.tool

import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.viewmodel.GenerationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID

/**
 * Provides the ask_user tool, allowing the agent to ask the user a question and wait for
 * the answer. Inspired by Marcel SSH's ask_user tool.
 *
 * The [AskUserController] manages the pending question handshake between the generation
 * pipeline (which asks) and the UI (which answers).
 */
class AskUserToolProvider(
    private val controller: AskUserController,
) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> = listOf(
        ToolDefinition(function = ToolFunction(
            name = ASK_USER,
            description = "Ask the user a question and wait for their answer. Use this when you need clarification, a decision, or information only the user can provide. Supports optional choices for the user to pick from.",
            parameters = ToolParameters(
                properties = mapOf(
                    "question" to ToolProperty("string", "The question to ask the user."),
                    "choices" to ToolProperty(
                        "array",
                        "Optional list of choices for the user to select from.",
                        items = ToolProperty("string", "A choice option."),
                    ),
                    "multiple" to ToolProperty("boolean", "If true and choices are provided, allow multiple selections (optional, default false)."),
                ),
                required = listOf("question"),
            ),
        )),
    )

    override fun handles(name: String): Boolean = name == ASK_USER

    override fun riskLevel(name: String): RiskLevel = RiskLevel.ReadOnly

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (name != ASK_USER) return jsonError("Unknown tool: $name")
        val args = parseArgs(arguments)
        val question = args["question"]?.jsonPrimitive?.contentOrNull
            ?: return jsonError("question is required")
        val choices = (args["choices"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        val multiple = (args["multiple"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false

        val answer = controller.ask(question, choices, multiple) ?: return jsonError("user did not answer (timed out or cancelled)")
        return buildJsonObject {
            put("type", ASK_USER)
            put("answered", true)
            if (answer.size == 1) {
                put("answer", answer.first())
            } else {
                putJsonArray("answers") { answer.forEach { add(JsonPrimitive(it)) } }
            }
        }.toString()
    }

    private fun parseArgs(arguments: String): JsonObject =
        runCatching { Json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))

    private fun jsonError(message: String): String = buildJsonObject {
        put("type", ASK_USER)
        put("error", message)
    }.toString()

    companion object {
        const val ASK_USER = "ask_user"
    }
}

/**
 * Coordinates the ask_user handshake between the generation pipeline and the UI.
 * Similar to [com.lxseek.chat.viewmodel.ShellConfirmationController] but for general
 * questions with optional choices.
 */
class AskUserController {
    data class PendingQuestion(
        val id: String,
        val question: String,
        val choices: List<String>,
        val multiple: Boolean,
        val deferred: CompletableDeferred<List<String>?>,
    )

    private val _pendingQuestion = MutableStateFlow<PendingQuestion?>(null)
    val pendingQuestion: StateFlow<PendingQuestion?> = _pendingQuestion.asStateFlow()

    private val mutex = Mutex()

    suspend fun ask(question: String, choices: List<String>, multiple: Boolean): List<String>? {
        return mutex.withLock {
            val deferred = CompletableDeferred<List<String>?>()
            val id = UUID.randomUUID().toString()
            _pendingQuestion.value = PendingQuestion(id, question, choices, multiple, deferred)
            try {
                withTimeout(ASK_USER_TIMEOUT_MS) { deferred.await() }
            } catch (_: TimeoutCancellationException) {
                null
            } finally {
                if (_pendingQuestion.value?.id == id) _pendingQuestion.value = null
            }
        }
    }

    fun resolve(answers: List<String>) {
        val pending = _pendingQuestion.value ?: return
        pending.deferred.complete(answers)
        _pendingQuestion.value = null
    }

    fun cancel() {
        val pending = _pendingQuestion.value ?: return
        pending.deferred.complete(null)
        _pendingQuestion.value = null
    }

    companion object {
        private const val ASK_USER_TIMEOUT_MS = 120_000L
    }
}
