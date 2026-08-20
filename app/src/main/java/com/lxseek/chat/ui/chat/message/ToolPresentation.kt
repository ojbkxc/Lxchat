package com.lxseek.chat.ui.chat.message

import com.lxseek.chat.model.MessageSegment
import com.lxseek.chat.model.ToolExecutionStates
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal enum class ToolKind {
    MEMORY_LIST,
    MEMORY_READ,
    MEMORY_CREATE,
    MEMORY_EDIT,
    MEMORY_DELETE,
    MEMORY_UPDATE_ACTIVE,
    WEB_SEARCH,
    WEB_FETCH,
    CONVERSATION_SEARCH,
    CONVERSATION_LIST,
    CONVERSATION_READ,
    SHELL_LIST,
    SHELL_EXECUTE,
    SHELL_JOB_LIST,
    SHELL_JOB_GET,
    SHELL_JOB_STOP,
    FILE_READ,
    FILE_WRITE,
    FILE_EDIT,
    FILE_GLOB,
    FILE_GREP,
    IMAGE_VIEW,
    IMAGE_GENERATE,
    TASK_CREATE,
    TASK_LIST,
    TASK_DELETE,
    LOOP_START,
    LOOP_STOP,
    MCP,
    UNKNOWN,
}

internal enum class ToolPresentationState {
    CALLING,
    RUNNING,
    COMPLETED,
    EMPTY,
    FAILED,
    STOPPED,
    BACKGROUND_RUNNING,
}

internal data class ToolPresentation(
    val toolName: String,
    val kind: ToolKind,
    val state: ToolPresentationState,
    val arguments: JsonObject?,
    val result: JsonElement?,
    val rawArguments: String?,
    val rawResult: String?,
    val rawTextResult: String?,
    val rawStructuredResult: String?,
    val liveOutput: String?,
    val subject: String?,
    val device: String?,
    val count: Int?,
    val errorMessage: String?,
    val exitCode: Int?,
    val jobId: String?,
    val outputLength: Int?,
) {
    val isActive: Boolean
        get() = state == ToolPresentationState.CALLING ||
            state == ToolPresentationState.RUNNING ||
            state == ToolPresentationState.BACKGROUND_RUNNING
}

internal object ToolPresentationResolver {
    private val json = Json { ignoreUnknownKeys = true }

    fun kindForToolName(toolName: String?): ToolKind = kindFor(toolName.orEmpty())

    fun resolve(segment: MessageSegment): ToolPresentation {
        val toolName = segment.toolName.orEmpty()
        val kind = kindFor(toolName)
        val resultElement = parseElement(
            segment.toolStructuredResult ?: segment.toolResult,
        )
        val resultEnvelope = resultElement as? JsonObject
        val resultObject = effectiveResultObject(kind, resultEnvelope)
        val errorCode = resultObject.string("error") ?: resultEnvelope.string("error")
        val exitCode = resultObject.int("exit_code") ?: resultEnvelope.int("exit_code")
        val explicitState = stateFromWire(segment.toolState)
        // Until a result arrives, arguments can still be a JSON prefix and can also contain a very
        // large file payload. Never strictly parse that growing buffer on the UI thread. The
        // bounded prefix resolver below extracts only live summary hints; final semantic parsing
        // remains available once the call has produced a result.
        val argumentsAwaitingResult =
            segment.toolResult == null && segment.toolStructuredResult == null
        val args = if (argumentsAwaitingResult) null else parseObject(segment.toolArgs)
        val streamingHints = StreamingToolArgumentHintResolver.resolve(kind, segment.toolArgs)
        val background = resultEnvelope.boolean("background") == true ||
            resultObject.string("state").equals("running", ignoreCase = true) &&
            (resultObject.string("job_id") ?: resultEnvelope.string("job_id")) != null
        val nonZeroShellExit = kind == ToolKind.SHELL_EXECUTE &&
            exitCode != null &&
            exitCode != 0
        val count = semanticCount(kind, resultObject)
        val semanticEmpty = isSemanticEmpty(
            kind = kind,
            rawResult = segment.toolResult.orEmpty(),
            result = resultObject,
            count = count,
            errorCode = errorCode,
        )
        val error = if (errorCode != null && !semanticEmpty) {
            resultObject.string("message")
                ?.takeIf { it.isNotBlank() }
                ?: errorCode.replace('_', ' ')
        } else {
            segment.toolResult
                ?.takeIf { it.startsWith("Error", ignoreCase = true) }
        }
        val state = when {
            segment.toolResult == null -> explicitState ?: run {
                if (segment.toolProgress.isNullOrEmpty()) ToolPresentationState.CALLING
                else ToolPresentationState.RUNNING
            }
            semanticEmpty -> ToolPresentationState.EMPTY
            error != null -> ToolPresentationState.FAILED
            background -> ToolPresentationState.BACKGROUND_RUNNING
            explicitState == ToolPresentationState.STOPPED -> ToolPresentationState.STOPPED
            nonZeroShellExit -> ToolPresentationState.FAILED
            else -> explicitState ?: ToolPresentationState.COMPLETED
        }
        return ToolPresentation(
            toolName = toolName,
            kind = kind,
            state = state,
            arguments = args,
            result = resultObject ?: resultElement,
            rawArguments = segment.toolArgs,
            rawResult = segment.toolResult,
            rawTextResult = segment.toolResultText,
            rawStructuredResult = segment.toolStructuredResult,
            liveOutput = segment.toolProgress,
            subject = normalizeToolSummarySubject(
                subject(kind, args, resultObject)
                    ?: subject(kind, args, resultEnvelope)
                    ?: streamingHints.subject,
            ),
            device = resultEnvelope.string("server")
                ?: resultObject.string("server")
                ?: segment.toolTarget
                ?: args.string("server")
                ?: streamingHints.server,
            count = count,
            errorMessage = error,
            exitCode = exitCode,
            jobId = resultEnvelope.string("job_id") ?: resultObject.string("job_id"),
            outputLength = resultObject.string("output")?.length,
        )
    }

    /** Durable foreground Shell results wrap the terminal job payload in a `result` envelope. */
    private fun effectiveResultObject(
        kind: ToolKind,
        envelope: JsonObject?,
    ): JsonObject? {
        if (envelope == null) return null
        if (kind != ToolKind.SHELL_EXECUTE && kind != ToolKind.SHELL_JOB_GET) return envelope
        return envelope["result"] as? JsonObject ?: envelope
    }

    private fun kindFor(name: String): ToolKind = when (name) {
        "list_memory_files" -> ToolKind.MEMORY_LIST
        "read_memory_file" -> ToolKind.MEMORY_READ
        "create_memory_file" -> ToolKind.MEMORY_CREATE
        "edit_memory_file" -> ToolKind.MEMORY_EDIT
        "delete_memory_file" -> ToolKind.MEMORY_DELETE
        "update_active_memory" -> ToolKind.MEMORY_UPDATE_ACTIVE
        "web_search" -> ToolKind.WEB_SEARCH
        "web_fetch" -> ToolKind.WEB_FETCH
        "search_conversations" -> ToolKind.CONVERSATION_SEARCH
        "list_conversations" -> ToolKind.CONVERSATION_LIST
        "read_conversation" -> ToolKind.CONVERSATION_READ
        "list_shells" -> ToolKind.SHELL_LIST
        "execute_shell_command" -> ToolKind.SHELL_EXECUTE
        "list_shell_jobs" -> ToolKind.SHELL_JOB_LIST
        "get_shell_job" -> ToolKind.SHELL_JOB_GET
        "stop_shell_job" -> ToolKind.SHELL_JOB_STOP
        "file_read" -> ToolKind.FILE_READ
        "file_write" -> ToolKind.FILE_WRITE
        "file_edit" -> ToolKind.FILE_EDIT
        "file_glob" -> ToolKind.FILE_GLOB
        "file_grep" -> ToolKind.FILE_GREP
        "view_image" -> ToolKind.IMAGE_VIEW
        "generate_image" -> ToolKind.IMAGE_GENERATE
        "create_task" -> ToolKind.TASK_CREATE
        "list_tasks" -> ToolKind.TASK_LIST
        "delete_task" -> ToolKind.TASK_DELETE
        "start_loop" -> ToolKind.LOOP_START
        "stop_loop" -> ToolKind.LOOP_STOP
        else -> if (name.startsWith("mcp_")) ToolKind.MCP else ToolKind.UNKNOWN
    }

    private fun stateFromWire(value: String?): ToolPresentationState? = when (value) {
        ToolExecutionStates.CALLING -> ToolPresentationState.CALLING
        ToolExecutionStates.RUNNING -> ToolPresentationState.RUNNING
        ToolExecutionStates.SUCCEEDED -> ToolPresentationState.COMPLETED
        ToolExecutionStates.EMPTY -> ToolPresentationState.EMPTY
        ToolExecutionStates.FAILED -> ToolPresentationState.FAILED
        ToolExecutionStates.STOPPED -> ToolPresentationState.STOPPED
        ToolExecutionStates.BACKGROUND_RUNNING -> ToolPresentationState.BACKGROUND_RUNNING
        else -> null
    }

    private fun parseObject(value: String?): JsonObject? =
        parseElement(value) as? JsonObject

    private fun parseElement(value: String?): JsonElement? {
        if (value == null) return null
        return runCatching { json.parseToJsonElement(value) }.getOrNull()
    }

    private fun semanticCount(kind: ToolKind, result: JsonObject?): Int? = when (kind) {
        ToolKind.MEMORY_LIST -> result.arraySize("files")
        ToolKind.WEB_SEARCH,
        ToolKind.CONVERSATION_SEARCH -> result.arraySize("results")
        ToolKind.CONVERSATION_LIST -> result.int("total")
            ?: result.arraySize("conversations")
        ToolKind.SHELL_LIST -> result.arraySize("devices")
        ToolKind.SHELL_JOB_LIST -> result.arraySize("jobs")
        ToolKind.FILE_GLOB -> result.arraySize("files")
        ToolKind.FILE_GREP -> result.arraySize("matches")
        ToolKind.TASK_LIST -> result.arraySize("tasks")
        else -> null
    }

    private fun isSemanticEmpty(
        kind: ToolKind,
        rawResult: String,
        result: JsonObject?,
        count: Int?,
        errorCode: String?,
    ): Boolean {
        if (rawResult.isEmpty()) return true
        if (count != null && count == 0) return true
        if (errorCode == "no_results") return true
        return when (kind) {
            ToolKind.FILE_READ -> result.string("content").isNullOrEmpty()
            else -> false
        }
    }

    private fun subject(
        kind: ToolKind,
        arguments: JsonObject?,
        result: JsonObject?,
    ): String? = when (kind) {
        ToolKind.MEMORY_READ,
        ToolKind.MEMORY_CREATE,
        ToolKind.MEMORY_EDIT,
        ToolKind.MEMORY_DELETE -> arguments.string("name")
            ?: arguments.array("names")?.singleOrNull()?.primitiveContent()
        ToolKind.WEB_SEARCH,
        ToolKind.CONVERSATION_SEARCH -> arguments.string("query")
        ToolKind.WEB_FETCH -> arguments.string("url")
        ToolKind.CONVERSATION_READ -> result.string("title")
            ?: arguments.string("conversation_id")
        ToolKind.SHELL_EXECUTE -> arguments.string("command")
            ?: result.string("command")
        ToolKind.SHELL_JOB_GET,
        ToolKind.SHELL_JOB_STOP -> arguments.string("job_id")
            ?: result.string("job_id")
        ToolKind.FILE_READ,
        ToolKind.FILE_WRITE,
        ToolKind.FILE_EDIT,
        ToolKind.IMAGE_VIEW -> arguments.string("path")
            ?: result.string("path")
        ToolKind.FILE_GLOB,
        ToolKind.FILE_GREP -> arguments.string("pattern")
            ?: result.string("pattern")
        ToolKind.IMAGE_GENERATE -> arguments.string("prompt")
        ToolKind.TASK_CREATE -> arguments.string("name")
        ToolKind.TASK_DELETE -> arguments.string("id_or_name")
            ?: arguments.string("name")
            ?: arguments.string("task_id")
        else -> null
    }

    private fun JsonObject?.string(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject?.int(key: String): Int? =
        (this?.get(key) as? JsonPrimitive)?.intOrNull

    private fun JsonObject?.boolean(key: String): Boolean? =
        (this?.get(key) as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject?.array(key: String): JsonArray? =
        this?.get(key) as? JsonArray

    private fun JsonObject?.arraySize(key: String): Int? =
        (this?.get(key) as? JsonArray)?.size

    private fun JsonElement.primitiveContent(): String? =
        (this as? JsonPrimitive)?.contentOrNull
}
