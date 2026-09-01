package com.lxseek.chat.model

import com.lxseek.chat.util.Constants
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Bounds the size of a single persisted `messages` row so it can never exceed the platform
 * CursorWindow (~2MB) and trigger `SQLiteBlobTooBigException` / `Row too big to fit into
 * CursorWindow` (issue #51).
 *
 * Individual tool results are already clipped at capture time
 * ([Constants.MAX_TOOL_RESULT_LENGTH]), but a *model* message aggregates many tool rounds into a
 * single `toolCallJson` column — and the model answer `text` column is otherwise unbounded. This
 * guard bounds the large columns independently: [clipText] caps answer/reasoning text, and
 * [encodeSegmentsBounded] encodes a segment list while progressively trimming the largest stored
 * fields until the encoded value fits its share of the row budget.
 *
 * When trimming is needed, the largest tool-result field (including independently persisted
 * structured/display content, then live output and non-tool content) is halved with a truncation
 * marker. Losing fidelity in the oldest/largest tool results is the correct trade-off: they are
 * already far back in the conversation (likely falling out of the context window) and the
 * alternative is a crash. The algorithm strictly reduces the largest field each iteration and
 * gives up once every field is at the floor, so it always terminates.
 */
object MessagePersistenceGuard {

    /** Floor below which a field is no longer trimmed (keeps a useful residual instead of a
     *  uselessly tiny one, and guarantees termination when a row has many small segments). */
    private const val TRIM_FLOOR_CHARS = 2000

    internal const val TRUNCATION_MARKER = "\n…[truncated for persistence]"

    /**
     * Trim a persisted text column to a safe length. Avoid ending on an unmatched UTF-16 high
     * surrogate so a boundary through an emoji still persists valid Unicode.
     */
    fun clipText(text: String): String {
        if (text.length <= Constants.MAX_PERSISTED_TEXT_CHARS) return text
        var end = Constants.MAX_PERSISTED_TEXT_CHARS
        if (
            end in 1 until text.length &&
            Character.isHighSurrogate(text[end - 1]) &&
            Character.isLowSurrogate(text[end])
        ) {
            end--
        }
        return text.substring(0, end) + TRUNCATION_MARKER
    }

    /**
     * Encode [segments] to JSON, bounded to [maxBytes] UTF-8 bytes. When the encoded form would
     * exceed the budget, the largest trimmable field (a tool result, then a non-tool content) is
     * halved with a marker and the list re-encoded, repeating until it fits or every field is at
     * the floor. Returns `null` for an empty list so the column stays SQL NULL (matching prior
     * behaviour where callers passed `null` for "no segments").
     */
    fun encodeSegmentsBounded(
        segments: List<MessageSegment>?,
        maxBytes: Int = Constants.MAX_PERSISTED_SEGMENTS_BYTES,
    ): String? {
        if (segments.isNullOrEmpty()) return null
        var current: List<MessageSegment> = segments
        while (true) {
            val json = Json.encodeToString(current)
            if (utf8Size(json) <= maxBytes) return json
            val pick = current.withIndex().maxByOrNull { (_, s) -> trimmableSize(s) } ?: return json
            val seg = pick.value
            // A large number of individually-small segments, huge arguments, signatures, or image
            // metadata can exceed the budget even when no result/content field is trimmable.
            // Persisting the oversized JSON would recreate #51, so fail closed to SQL NULL; the
            // message's separately-persisted text remains readable and the database stays usable.
            if (!canTrim(seg)) return null
            current = current.toMutableList().also { it[pick.index] = trimLargest(seg) }
        }
    }

    /** Size of the field that trimming would shrink — drives "largest first" selection. */
    private fun trimmableSize(s: MessageSegment): Int {
        val result = s.toolResult?.length ?: 0
        val resultText = s.toolResultText?.length ?: 0
        val structuredResult = s.toolStructuredResult?.length ?: 0
        val progress = s.toolProgress?.length ?: 0
        val content = if (s.type == "tool") 0 else s.content.length
        return maxOf(result, resultText, structuredResult, progress, content)
    }

    private fun canTrim(s: MessageSegment): Boolean =
        (s.toolResult != null && s.toolResult.length > TRIM_FLOOR_CHARS) ||
            (s.toolResultText != null && s.toolResultText.length > TRIM_FLOOR_CHARS) ||
            (
                s.toolStructuredResult != null &&
                    s.toolStructuredResult.length > TRIM_FLOOR_CHARS
                ) ||
            (s.toolProgress != null && s.toolProgress.length > TRIM_FLOOR_CHARS) ||
            (s.type != "tool" && s.content.length > TRIM_FLOOR_CHARS)

    /** Halve the largest trimmable field of [s], preferring the tool result on ties. */
    private fun trimLargest(s: MessageSegment): MessageSegment {
        val result = s.toolResult
        val resultText = s.toolResultText
        val structuredResult = s.toolStructuredResult
        val progress = s.toolProgress
        val contentSize = if (s.type == "tool") 0 else s.content.length
        val largest = maxOf(
            result?.length ?: 0,
            resultText?.length ?: 0,
            structuredResult?.length ?: 0,
            progress?.length ?: 0,
            contentSize,
        )
        if (
            result != null &&
            result.length == largest &&
            result.length > TRIM_FLOOR_CHARS
        ) {
            return s.copy(toolResult = halveWithMarker(result))
        }
        if (
            resultText != null &&
            resultText.length == largest &&
            resultText.length > TRIM_FLOOR_CHARS
        ) {
            return s.copy(toolResultText = halveWithMarker(resultText))
        }
        if (
            structuredResult != null &&
            structuredResult.length == largest &&
            structuredResult.length > TRIM_FLOOR_CHARS
        ) {
            return s.copy(toolStructuredResult = halveWithMarker(structuredResult))
        }
        if (
            progress != null &&
            progress.length == largest &&
            progress.length > TRIM_FLOOR_CHARS
        ) {
            return s.copy(toolProgress = halveWithMarker(progress))
        }
        if (s.type != "tool" && s.content.length > TRIM_FLOOR_CHARS) {
            return s.copy(content = halveWithMarker(s.content))
        }
        return s
    }

    private fun halveWithMarker(value: String): String {
        val target = maxOf(value.length / 2, TRIM_FLOOR_CHARS)
        return if (value.length <= target) value else value.take(target) + TRUNCATION_MARKER
    }

    private fun utf8Size(s: String): Int = s.toByteArray(Charsets.UTF_8).size
}
