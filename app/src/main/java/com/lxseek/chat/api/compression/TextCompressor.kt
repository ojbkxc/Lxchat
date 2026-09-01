package com.lxseek.chat.api.compression

/**
 * Headroom SearchCompressor / whitespace 折叠思想的通用文本压缩器。
 *
 * 处理 JSON 与日志之外的纯文本工具输出（网页抓取、目录列表、长 help 文本）：
 *
 * - **空白折叠**：3 个以上连续空行折叠为 1 行；行尾空白去除；
 * - **缩进剥离**：每行超过 2 个的前导空格折叠（保留嵌套相对关系）；
 * - **连续重复行折叠**：相同行连续出现 3 次以上折叠为 `行 × N`。
 *
 * 不删任何有内容的行，只折叠机器格式的冗余。
 */
object TextCompressor {

    private const val MIN_LENGTH = 4096
    private const val REPEAT_THRESHOLD = 3

    fun looksCompressible(text: String): Boolean = text.length >= MIN_LENGTH

    fun compress(text: String): String {
        if (!looksCompressible(text)) return text
        val lines = text.lines()
        val output = mutableListOf<String>()
        var repeatCount = 1
        var previous: String? = null

        for (line in lines) {
            val normalized = normalizeLine(line)
            if (normalized == previous && normalized.isNotBlank()) {
                repeatCount++
                continue
            }
            if (previous != null) {
                flushRepeat(output, previous, repeatCount)
            }
            previous = normalized
            repeatCount = 1
        }
        if (previous != null) flushRepeat(output, previous, repeatCount)

        // 多于 2 个连续空行折叠为一个。
        val collapsed = mutableListOf<String>()
        var blankRun = 0
        for (line in output) {
            if (line.isBlank()) {
                blankRun++
                if (blankRun > 1) continue
            } else {
                blankRun = 0
            }
            collapsed += line
        }
        val result = collapsed.joinToString("\n")
        return if (result.length < text.length) result else text
    }

    private fun normalizeLine(line: String): String {
        val stripped = line.trimEnd()
        val indent = stripped.length - stripped.trimStart().length
        return if (indent > 2) "  " + stripped.trimStart() else stripped
    }

    private fun flushRepeat(output: MutableList<String>, line: String, count: Int) {
        output += line
        if (count >= REPEAT_THRESHOLD) {
            output += "[... repeated ${count - 1} more times ...]"
        } else {
            repeat(count - 1) { output += line }
        }
    }
}
