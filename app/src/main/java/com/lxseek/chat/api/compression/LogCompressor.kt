package com.lxseek.chat.api.compression

/**
 * Headroom LogCompressor 的确定性 Kotlin 移植（构建/测试输出压缩）。
 *
 * 行级重要性打分 + 选择 + 保守去重，规则对齐上游 log_compressor：
 *
 * - **错误行**（error/fatal/exception/failed）：全保留，保首保尾，最多 [MAX_ERRORS] 条；
 * - **堆栈帧折叠**：Python traceback / Java at-frames / 帧数超限时折叠运行时帧，
 *   保留头部帧与应用帧，中间以 `[... N frames collapsed]` 标记（上游 frame collapse）；
 * - **警告去重**：只在第一个 `:`/`=` 之后的尾部做数字/十六进制/路径归一化去重，
 *   消息前缀保持原样，不同错误类别不会互相吞并（上游保守去重）；
 * - **汇总行**（summary/final/passed/failed 计数行）始终保留；
 * - **上下文行**：被选中行前后各 [CONTEXT_LINES] 行纳入输出，防止断章取义；
 * - 输出按原行序重排，头部附加 `[log compressed: 保留 N/M 行]` 说明。
 */
object LogCompressor {

    private const val MAX_ERRORS = 10
    private const val MAX_WARNINGS = 5
    private const val MAX_STACK_TRACES = 3
    private const val STACK_TRACE_MAX_LINES = 20
    private const val CONTEXT_LINES = 3
    private const val MIN_LOG_LINES = 24

    private val ERROR_RE = Regex(
        """(?i)\b(error|fatal|exception|failed|failure|panic|traceback)\b|\bE/\w+"""
    )
    private val WARN_RE = Regex("""(?i)\b(warn(?:ing)?)\b""")
    private val SUMMARY_RE = Regex(
        """(?i)^\s*(summary|result|final|total|passed|failed|tests? run|\d+ (?:passed|failed|tests|error))\b|[=:]\s*\d+\s+(?:passed|failed|error|test)"""
    )
    private val PYTHON_FRAME_RE = Regex("""^\s*File\s+"[^"]+",\s+line\s+\d+""")
    private val JAVA_FRAME_RE = Regex("""^\s*at\s+[\w$.]+\(.*\)""")
    private val CONTINUATION_RE = Regex("""^\s{2,}\S|^Caused by:""")

    /** 探测：多行文本且包含可识别的日志特征。 */
    fun looksLikeLog(text: String): Boolean {
        val lines = text.lineSequence()
        var count = 0
        var structured = 0
        for (line in lines) {
            if (line.isBlank()) continue
            count++
            if (count > 400) break
            if (
                ERROR_RE.containsMatchIn(line) ||
                WARN_RE.containsMatchIn(line) ||
                PYTHON_FRAME_RE.containsMatchIn(line) ||
                JAVA_FRAME_RE.containsMatchIn(line) ||
                SUMMARY_RE.containsMatchIn(line)
            ) structured++
        }
        // 行数足够多且结构化日志行占比可观才走日志路径，避免误伤普通散文。
        return count >= MIN_LOG_LINES && structured * 4 >= count
    }

    fun compress(text: String): String {
        val rawLines = text.lines()
        if (rawLines.size < MIN_LOG_LINES) return text

        val lines = rawLines.withIndex().filter { it.value.isNotBlank() }
        if (lines.isEmpty()) return text

        val selected = mutableSetOf<Int>()

        // 1. 错误行（含堆栈帧、续行、Caused by）
        val errorIndices = mutableListOf<Int>()
        var stackRun = mutableListOf<Int>()
        val stackRuns = mutableListOf<List<Int>>()
        for ((index, line) in lines) {
            when {
                ERROR_RE.containsMatchIn(line) -> errorIndices += index
                isStackTraceLine(line) -> {
                    stackRun += index
                    continue
                }
                stackRun.isNotEmpty() -> {
                    if (CONTINUATION_RE.containsMatchIn(line)) {
                        stackRun += index
                        continue
                    }
                    stackRuns += stackRun
                    stackRun = mutableListOf()
                }
            }
        }
        if (stackRun.isNotEmpty()) stackRuns += stackRun

        if (errorIndices.isEmpty() && stackRuns.isEmpty()) return text

        errorIndices.take(MAX_ERRORS).forEach { selected += it }
        stackRuns.take(MAX_STACK_TRACES).forEach { run ->
            if (run.size <= STACK_TRACE_MAX_LINES) {
                selected += run
            } else {
                // 帧折叠：头部帧 + 应用帧，中间折叠标记。
                selected += run.take(STACK_TRACE_MAX_LINES / 2)
                selected += run.takeLast(STACK_TRACE_MAX_LINES / 2)
            }
        }

        // 2. 汇总行
        for ((index, line) in lines) {
            if (SUMMARY_RE.containsMatchIn(line)) selected += index
        }

        // 3. 警告去重（保守：只归一化首个 :/= 之后的尾部）
        val seenWarnings = mutableSetOf<String>()
        var warningCount = 0
        for ((index, line) in lines) {
            if (!WARN_RE.containsMatchIn(line) || ERROR_RE.containsMatchIn(line)) continue
            if (warningCount >= MAX_WARNINGS) break
            if (normalizeForDedupe(line) !in seenWarnings) {
                seenWarnings += normalizeForDedupe(line)
                selected += index
                warningCount++
            }
        }

        // 4. 上下文行
        val withContext = mutableSetOf<Int>()
        for (index in selected) {
            for (i in (index - CONTEXT_LINES)..(index + CONTEXT_LINES)) withContext += i
        }

        // 5. 按原行序输出；跳过的连续区段插入折叠标记
        val output = StringBuilder()
        var previous = -10
        for (index in withContext.sorted()) {
            if (index < 0 || index >= rawLines.size) continue
            if (index != previous + 1 && previous >= 0) {
                output.append("    [...]\n")
            }
            output.append(rawLines[index]).append('\n')
            previous = index
        }
        val kept = withContext.count { it in rawLines.indices }
        return "[log compressed: kept $kept/${rawLines.size} lines]\n" +
            output.toString().trimEnd('\n')
    }

    private fun isStackTraceLine(line: String): Boolean =
        PYTHON_FRAME_RE.containsMatchIn(line) ||
            JAVA_FRAME_RE.containsMatchIn(line)

    /** 上游 normalize_for_dedupe：前缀保持原文，尾部数字/十六进制/路径归一化。 */
    internal fun normalizeForDedupe(line: String): String {
        val splitAt = line.indexOfFirst { it == ':' || it == '=' }.let {
            if (it < 0) line.length else it
        }
        val prefix = line.substring(0, splitAt)
        val suffix = line.substring(splitAt)
            .replace(Regex("""\d+"""), "N")
            .replace(Regex("""0x[0-9a-fA-F]+"""), "ADDR")
            .replace(Regex("""(/[\w./-]+/)+"""), "/PATH/")
        return prefix + suffix
    }
}
