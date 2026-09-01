package com.lxseek.chat.api.compression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [InputTokenSaver.compressToolResult] 的围栏保护行为测试。
 *
 * 重点回归：旧实现按 split("```") 重建时丢失全部闭合 "```"，
 * 导致发给模型的代码围栏永不闭合。以下用例覆盖：
 * - 无围栏文本的正常压缩路径（触发 / 不触发长度阈值）；
 * - 完整围栏（单个 / 多个）的分隔符与内容逐字保留；
 * - 奇数个 "```"（未闭合围栏）不崩溃、不丢文本；
 * - 围栏内超长可压缩内容永不压缩；
 * - 围栏外超长文本仍触发压缩。
 */
class InputTokenSaverTest {

    /** 与实现保持一致的围栏分隔符。 */
    private val fence = "```"

    @Test
    fun emptyResultIsReturnedAsIs() {
        assertEquals("", InputTokenSaver.compressToolResult(""))
    }

    @Test
    fun shortTextWithoutFenceIsReturnedAsIs() {
        val text = "短文本不触发压缩，应原样返回。"

        assertEquals(text, InputTokenSaver.compressToolResult(text))
    }

    @Test
    fun plainTextWithoutFenceIsCompressed() {
        val text = compressibleJson()
        val compressed = InputTokenSaver.compressToolResult(text)

        assertTrue(compressed.length < text.length)
    }

    @Test
    fun singleFenceIsPreservedIntact() {
        val fenced = "```kotlin\nval answer = 42\n```"
        val result = compressibleJson() + "\n" + fenced + "\n"
        val compressed = InputTokenSaver.compressToolResult(result)

        assertTrue(compressed.length < result.length)
        assertTrue(compressed.contains(fenced))
        assertEquals(2, fenceDelimiterCount(compressed))
    }

    @Test
    fun multipleFencesArePreservedIntact() {
        val fenceA = "```json\n{\"ok\": true}\n```"
        val fenceB = "```bash\necho done\n```"
        val result = compressibleJson() + "\n" + fenceA +
            "\n中间散文说明，短于阈值不压缩。\n" + fenceB + compressibleJson()
        val compressed = InputTokenSaver.compressToolResult(result)

        assertTrue(compressed.length < result.length)
        assertTrue(compressed.contains(fenceA))
        assertTrue(compressed.contains(fenceB))
        assertEquals(4, fenceDelimiterCount(compressed))
    }

    @Test
    fun oddUnclosedFenceDoesNotCrashOrLoseText() {
        val result = "```python\nprint(\"unclosed\")\n这段文本的围栏没有闭合"

        assertEquals(result, InputTokenSaver.compressToolResult(result))
    }

    @Test
    fun oddUnclosedFenceKeepsTrailingTextWhenOutsideCompresses() {
        val tail = "```python\nprint(\"unclosed\")\n后续行仍在围栏内、未闭合\n"
        val result = compressibleJson() + "\n" + tail
        val compressed = InputTokenSaver.compressToolResult(result)

        assertTrue(compressed.endsWith(tail))
        assertTrue(compressed.length < result.length)
        assertEquals(1, fenceDelimiterCount(compressed))
    }

    @Test
    fun fenceContentIsNeverCompressed() {
        val result = "前置说明，短于阈值。\n```\n" + compressibleJson() +
            "\n```\n后置说明，短于阈值。\n"

        assertEquals(result, InputTokenSaver.compressToolResult(result))
    }

    /** 统计围栏分隔符出现次数（用于断言开、闭分隔符均未丢失）。 */
    private fun fenceDelimiterCount(text: String): Int = text.split(fence).size - 1

    /** 生成远超 [ContentRouter.MIN_COMPRESS_LENGTH] 的 pretty JSON，含大量可剥离的空值字段。 */
    private fun compressibleJson(): String = buildString {
        append("[\n")
        repeat(80) { index ->
            append(
                """
                {
                  "index": ${index},
                  "status": "pending",
                  "note": null,
                  "label": "",
                  "tags": [],
                  "meta": {},
                  "description": "row-${index}-filler"
                }
                """.trimIndent()
            )
            if (index < 79) append(",")
            append("\n")
        }
        append("]\n")
    }
}