package com.lxseek.chat.ui.chat.message

private const val MAX_TOOL_ARGUMENT_HINT_PREFIX_CHARS = 4_096
internal const val MAX_TOOL_SUMMARY_SUBJECT_CHARS = 120

internal data class StreamingToolArgumentHints(
    val subject: String?,
    val server: String?,
)

/**
 * Extracts only the small pieces of an unfinished argument document needed by the compact tool
 * card. Tool payloads such as file content can be very large, so the UI must not strictly parse
 * the entire growing JSON buffer on every stream snapshot.
 *
 * Tool schemas put their identifying field before large payload fields. Parsing a bounded JSON
 * prefix therefore keeps command/path/query text live while making the per-frame cost constant.
 * [StreamingJsonParser] preserves open string leaves, including split escapes, without inventing
 * missing JSON syntax.
 */
internal object StreamingToolArgumentHintResolver {
    fun resolve(
        kind: ToolKind,
        rawArguments: String?,
    ): StreamingToolArgumentHints {
        val source = rawArguments
            ?.takeIf { it.isNotBlank() }
            ?.take(MAX_TOOL_ARGUMENT_HINT_PREFIX_CHARS)
            ?: return StreamingToolArgumentHints(subject = null, server = null)
        val root = StreamingJsonParser.parse(source).root as? StreamingJsonObject
            ?: return StreamingToolArgumentHints(subject = null, server = null)

        val subject = when (kind) {
            ToolKind.MEMORY_READ,
            ToolKind.MEMORY_CREATE,
            ToolKind.MEMORY_EDIT,
            ToolKind.MEMORY_DELETE -> root.scalar("name")
                ?: root.firstArrayScalar("names")
            ToolKind.WEB_SEARCH,
            ToolKind.CONVERSATION_SEARCH -> root.scalar("query")
            ToolKind.WEB_FETCH -> root.scalar("url")
            ToolKind.CONVERSATION_READ -> root.scalar("conversation_id")
            ToolKind.SHELL_EXECUTE -> root.scalar("command")
            ToolKind.SHELL_JOB_GET,
            ToolKind.SHELL_JOB_STOP -> root.scalar("job_id")
            ToolKind.FILE_READ,
            ToolKind.FILE_WRITE,
            ToolKind.FILE_EDIT,
            ToolKind.IMAGE_VIEW -> root.scalar("path")
            ToolKind.FILE_GLOB,
            ToolKind.FILE_GREP -> root.scalar("pattern")
            ToolKind.IMAGE_GENERATE -> root.scalar("prompt")
            ToolKind.TASK_CREATE -> root.scalar("name")
            ToolKind.TASK_DELETE -> root.scalar("id_or_name")
                ?: root.scalar("name")
                ?: root.scalar("task_id")
            else -> null
        }

        return StreamingToolArgumentHints(
            subject = normalizeToolSummarySubject(subject),
            server = normalizeToolSummarySubject(root.scalar("server")),
        )
    }

    private fun StreamingJsonObject.scalar(key: String): String? =
        entries
            .firstOrNull { it.keyComplete && it.key == key }
            ?.value
            .let { it as? StreamingJsonScalar }
            ?.content

    private fun StreamingJsonObject.firstArrayScalar(key: String): String? =
        entries
            .firstOrNull { it.keyComplete && it.key == key }
            ?.value
            .let { it as? StreamingJsonArray }
            ?.values
            ?.firstOrNull()
            .let { it as? StreamingJsonScalar }
            ?.content
}

internal fun normalizeToolSummarySubject(
    value: String?,
    maxCharacters: Int = MAX_TOOL_SUMMARY_SUBJECT_CHARS,
): String? {
    require(maxCharacters > 0)
    return value
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.take(maxCharacters)
}
