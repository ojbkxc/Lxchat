package com.lxseek.chat.ui.chat.message

internal enum class StreamingJsonStatus {
    INCOMPLETE,
    COMPLETE,
    INVALID,
}

internal enum class StreamingJsonScalarKind {
    STRING,
    NUMBER,
    BOOLEAN,
    NULL,
}

internal sealed interface StreamingJsonNode {
    val complete: Boolean
}

internal data class StreamingJsonObject(
    val entries: List<StreamingJsonEntry>,
    override val complete: Boolean,
) : StreamingJsonNode

internal data class StreamingJsonArray(
    val values: List<StreamingJsonNode>,
    override val complete: Boolean,
) : StreamingJsonNode

internal data class StreamingJsonScalar(
    val content: String,
    val kind: StreamingJsonScalarKind,
    override val complete: Boolean,
) : StreamingJsonNode

internal data class StreamingJsonEntry(
    val key: String,
    val keyComplete: Boolean,
    val value: StreamingJsonNode?,
)

internal data class StreamingJsonDocument(
    val root: StreamingJsonNode?,
    val status: StreamingJsonStatus,
    val errorOffset: Int? = null,
    /** One or more top-level values separated strictly by JSON whitespace. */
    val roots: List<StreamingJsonNode> = root?.let(::listOf).orEmpty(),
)

/**
 * Prefix-aware JSON parser for arguments that are still arriving from a structured tool-call
 * protocol. It accepts only JSON grammar: truncation is [StreamingJsonStatus.INCOMPLETE], a closed
 * document is [StreamingJsonStatus.COMPLETE], and an impossible prefix is
 * [StreamingJsonStatus.INVALID].
 *
 * The parser never inserts missing quotes, delimiters, keys, or values. Completed nodes and the
 * currently open leaf are returned as a structural tree so the UI can keep rendering while the
 * final object is unfinished.
 */
internal object StreamingJsonParser {
    fun parse(source: String): StreamingJsonDocument = Parser(source).parseDocument()

    private class Parser(
        private val source: String,
    ) {
        private var cursor = 0
        private var errorOffset: Int? = null

        fun parseDocument(): StreamingJsonDocument {
            skipWhitespace()
            if (atEnd()) {
                return StreamingJsonDocument(
                    root = null,
                    status = StreamingJsonStatus.INCOMPLETE,
                )
            }

            val roots = mutableListOf<StreamingJsonNode>()
            var status = StreamingJsonStatus.INCOMPLETE
            while (!atEnd() && errorOffset == null) {
                val parsed = parseValue()
                parsed.node?.let(roots::add)
                if (errorOffset != null) {
                    status = StreamingJsonStatus.INVALID
                    break
                }
                if (!parsed.complete) {
                    status = StreamingJsonStatus.INCOMPLETE
                    break
                }

                val separatorLength = skipWhitespace()
                if (atEnd()) {
                    status = StreamingJsonStatus.COMPLETE
                    break
                }
                // Concatenated values without an actual JSON-whitespace boundary remain invalid.
                if (separatorLength == 0) {
                    fail()
                    status = StreamingJsonStatus.INVALID
                    break
                }
            }
            if (errorOffset != null) {
                status = StreamingJsonStatus.INVALID
            }
            return StreamingJsonDocument(
                root = roots.firstOrNull(),
                status = status,
                errorOffset = errorOffset,
                roots = roots.toList(),
            )
        }

        private fun parseValue(): ParsedNode {
            skipWhitespace()
            if (atEnd()) return ParsedNode(node = null, complete = false)
            return when (source[cursor]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> {
                    val parsed = parseString()
                    ParsedNode(
                        node = StreamingJsonScalar(
                            content = parsed.content,
                            kind = StreamingJsonScalarKind.STRING,
                            complete = parsed.complete,
                        ),
                        complete = parsed.complete,
                    )
                }
                't' -> parseKeyword("true", StreamingJsonScalarKind.BOOLEAN)
                'f' -> parseKeyword("false", StreamingJsonScalarKind.BOOLEAN)
                'n' -> parseKeyword("null", StreamingJsonScalarKind.NULL)
                '-', in '0'..'9' -> parseNumber()
                else -> {
                    fail()
                    ParsedNode(node = null, complete = false)
                }
            }
        }

        private fun parseObject(): ParsedNode {
            cursor++ // {
            val entries = mutableListOf<StreamingJsonEntry>()
            skipWhitespace()
            if (atEnd()) return objectNode(entries, complete = false)
            if (source[cursor] == '}') {
                cursor++
                return objectNode(entries, complete = true)
            }

            while (errorOffset == null) {
                if (atEnd()) return objectNode(entries, complete = false)
                if (source[cursor] != '"') {
                    fail()
                    return objectNode(entries, complete = false)
                }
                val key = parseString()
                if (!key.complete) {
                    entries += StreamingJsonEntry(
                        key = key.content,
                        keyComplete = false,
                        value = null,
                    )
                    return objectNode(entries, complete = false)
                }

                skipWhitespace()
                if (atEnd()) {
                    entries += StreamingJsonEntry(key.content, keyComplete = true, value = null)
                    return objectNode(entries, complete = false)
                }
                if (source[cursor] != ':') {
                    fail()
                    entries += StreamingJsonEntry(key.content, keyComplete = true, value = null)
                    return objectNode(entries, complete = false)
                }
                cursor++
                skipWhitespace()
                if (atEnd()) {
                    entries += StreamingJsonEntry(key.content, keyComplete = true, value = null)
                    return objectNode(entries, complete = false)
                }

                val value = parseValue()
                entries += StreamingJsonEntry(
                    key = key.content,
                    keyComplete = true,
                    value = value.node,
                )
                if (errorOffset != null || !value.complete) {
                    return objectNode(entries, complete = false)
                }

                skipWhitespace()
                if (atEnd()) return objectNode(entries, complete = false)
                when (source[cursor]) {
                    '}' -> {
                        cursor++
                        return objectNode(entries, complete = true)
                    }
                    ',' -> {
                        cursor++
                        skipWhitespace()
                        if (atEnd()) return objectNode(entries, complete = false)
                    }
                    else -> {
                        fail()
                        return objectNode(entries, complete = false)
                    }
                }
            }
            return objectNode(entries, complete = false)
        }

        private fun parseArray(): ParsedNode {
            cursor++ // [
            val values = mutableListOf<StreamingJsonNode>()
            skipWhitespace()
            if (atEnd()) return arrayNode(values, complete = false)
            if (source[cursor] == ']') {
                cursor++
                return arrayNode(values, complete = true)
            }

            while (errorOffset == null) {
                if (atEnd()) return arrayNode(values, complete = false)
                val value = parseValue()
                value.node?.let(values::add)
                if (errorOffset != null || !value.complete) {
                    return arrayNode(values, complete = false)
                }

                skipWhitespace()
                if (atEnd()) return arrayNode(values, complete = false)
                when (source[cursor]) {
                    ']' -> {
                        cursor++
                        return arrayNode(values, complete = true)
                    }
                    ',' -> {
                        cursor++
                        skipWhitespace()
                        if (atEnd()) return arrayNode(values, complete = false)
                    }
                    else -> {
                        fail()
                        return arrayNode(values, complete = false)
                    }
                }
            }
            return arrayNode(values, complete = false)
        }

        private fun parseString(): ParsedString {
            cursor++ // opening quote
            val content = StringBuilder()
            while (!atEnd() && errorOffset == null) {
                when (val char = source[cursor++]) {
                    '"' -> return ParsedString(content.toString(), complete = true)
                    '\\' -> {
                        if (atEnd()) return ParsedString(content.toString(), complete = false)
                        when (val escape = source[cursor++]) {
                            '"' -> content.append('"')
                            '\\' -> content.append('\\')
                            '/' -> content.append('/')
                            'b' -> content.append('\b')
                            'f' -> content.append('\u000C')
                            'n' -> content.append('\n')
                            'r' -> content.append('\r')
                            't' -> content.append('\t')
                            'u' -> {
                                var value = 0
                                var digits = 0
                                while (digits < 4 && !atEnd()) {
                                    val digit = source[cursor].digitToIntOrNull(16)
                                    if (digit == null) {
                                        fail()
                                        return ParsedString(content.toString(), complete = false)
                                    }
                                    value = value * 16 + digit
                                    cursor++
                                    digits++
                                }
                                if (digits < 4) {
                                    return ParsedString(content.toString(), complete = false)
                                }
                                content.append(value.toChar())
                            }
                            else -> {
                                fail(cursor - 1)
                                return ParsedString(content.toString(), complete = false)
                            }
                        }
                    }
                    else -> {
                        if (char.code < 0x20) {
                            fail(cursor - 1)
                            return ParsedString(content.toString(), complete = false)
                        }
                        content.append(char)
                    }
                }
            }
            return ParsedString(content.toString(), complete = false)
        }

        private fun parseKeyword(
            keyword: String,
            kind: StreamingJsonScalarKind,
        ): ParsedNode {
            val start = cursor
            var keywordIndex = 0
            while (keywordIndex < keyword.length && !atEnd()) {
                if (source[cursor] != keyword[keywordIndex]) {
                    fail()
                    break
                }
                cursor++
                keywordIndex++
            }
            val complete = errorOffset == null && keywordIndex == keyword.length
            return ParsedNode(
                node = StreamingJsonScalar(
                    content = source.substring(start, cursor),
                    kind = kind,
                    complete = complete,
                ),
                complete = complete,
            )
        }

        private fun parseNumber(): ParsedNode {
            val start = cursor
            if (source[cursor] == '-') {
                cursor++
                if (atEnd()) return numberNode(start, complete = false)
            }

            when {
                atEnd() -> return numberNode(start, complete = false)
                source[cursor] == '0' -> cursor++
                source[cursor] in '1'..'9' -> {
                    cursor++
                    while (!atEnd() && source[cursor].isDigit()) cursor++
                }
                else -> {
                    fail()
                    return numberNode(start, complete = false)
                }
            }

            if (!atEnd() && source[cursor] == '.') {
                cursor++
                if (atEnd()) return numberNode(start, complete = false)
                if (!source[cursor].isDigit()) {
                    fail()
                    return numberNode(start, complete = false)
                }
                while (!atEnd() && source[cursor].isDigit()) cursor++
            }

            if (!atEnd() && (source[cursor] == 'e' || source[cursor] == 'E')) {
                cursor++
                if (!atEnd() && (source[cursor] == '+' || source[cursor] == '-')) cursor++
                if (atEnd()) return numberNode(start, complete = false)
                if (!source[cursor].isDigit()) {
                    fail()
                    return numberNode(start, complete = false)
                }
                while (!atEnd() && source[cursor].isDigit()) cursor++
            }
            return numberNode(start, complete = true)
        }

        private fun objectNode(
            entries: List<StreamingJsonEntry>,
            complete: Boolean,
        ): ParsedNode = ParsedNode(
            node = StreamingJsonObject(entries.toList(), complete),
            complete = complete,
        )

        private fun arrayNode(
            values: List<StreamingJsonNode>,
            complete: Boolean,
        ): ParsedNode = ParsedNode(
            node = StreamingJsonArray(values.toList(), complete),
            complete = complete,
        )

        private fun numberNode(start: Int, complete: Boolean): ParsedNode = ParsedNode(
            node = StreamingJsonScalar(
                content = source.substring(start, cursor),
                kind = StreamingJsonScalarKind.NUMBER,
                complete = complete,
            ),
            complete = complete,
        )

        private fun skipWhitespace(): Int {
            val start = cursor
            while (!atEnd() && source[cursor].isJsonWhitespace()) cursor++
            return cursor - start
        }

        private fun fail(offset: Int = cursor) {
            if (errorOffset == null) errorOffset = offset
        }

        private fun atEnd(): Boolean = cursor >= source.length
    }

    private data class ParsedNode(
        val node: StreamingJsonNode?,
        val complete: Boolean,
    )

    private data class ParsedString(
        val content: String,
        val complete: Boolean,
    )

    private fun Char.isJsonWhitespace(): Boolean =
        this == ' ' || this == '\t' || this == '\r' || this == '\n'
}
