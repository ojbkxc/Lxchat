package com.lxseek.chat.tool

/**
 * 静态高危命令防护 —— AI 获得全命令执行权限后的最终兜底护栏。
 *
 * 允许执行任意命令，但拦截少数「灾难性、几乎必然错误」的操作：
 * - 递归删除根目录 / 系统关键目录（`rm -rf /`、`rm -rf /etc` 等）；
 * - 直接向块设备写入（`dd of=/dev/sdX`、`of=/dev/mmcblkX` 等）；
 * - fork bomb 特征（`name(){ … | … & }; …`）。
 *
 * 仅做纯文本静态分析、不执行命令；命中即返回拦截原因，由调用方拒绝执行。
 */
object CommandSafetyGuard {

    private val SYSTEM_DELETE_PATHS = listOf(
        "/etc", "/usr", "/bin", "/sbin", "/lib", "/lib64", "/boot", "/sys",
        "/proc", "/dev", "/var", "/root",
    )

    private val SHELL_NAMES = listOf("bash", "sh", "zsh", "dash", "ash", "ksh")

    /**
     * 返回拦截原因；合法或未知命令返回 null（放行）。
     * [command] 为完整 shell 命令行。
     */
    fun blockedReason(command: String): String? {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return null
        if (isForkBomb(trimmed)) {
            return "安全防护：检测到 fork bomb 特征，已拦截该命令。"
        }
        val segments = splitTopLevelSegments(trimmed)
        if (segments == null || segments.isEmpty()) return null
        val topLevelPipe = hasTopLevelPipe(trimmed)
        for (segment in segments) {
            val blocked = checkSegment(segment, topLevelPipe) ?: continue
            return blocked
        }
        return null
    }

    /**
     * 引号感知的顶层段拆分：把命令按 `; && || | ' \n' 拆成若干独立段。
     * 返回 null 表示无法可靠静态解析（未闭合引号），此时不拦（交给确认环节兜底）。
     */
    private fun splitTopLevelSegments(command: String): List<String>? {
        val segments = mutableListOf<String>()
        val current = StringBuilder()
        var inSingle = false
        var inDouble = false
        var i = 0
        val n = command.length
        while (i < n) {
            val c = command[i]
            if (inSingle) {
                current.append(c)
                if (c == '\'') inSingle = false
                i++
            } else if (inDouble) {
                current.append(c)
                if (c == '\\' && i + 1 < n) { current.append(command[i + 1]); i += 2; continue }
                if (c == '"') inDouble = false
                i++
            } else {
                when {
                    c == '\'' -> { inSingle = true; current.append(c); i++ }
                    c == '"' -> { inDouble = true; current.append(c); i++ }
                    c == '\\' -> { if (i + 1 < n) { current.append(command[i + 1]); i += 2 } else i++ }
                    c == ';' || c == '\n' -> { flush(segments, current); i++ }
                    c == '&' -> { flush(segments, current); i += if (i + 1 < n && command[i + 1] == '&') 2 else 1 }
                    c == '|' -> { flush(segments, current); i += if (i + 1 < n && command[i + 1] == '|') 2 else 1 }
                    else -> { current.append(c); i++ }
                }
            }
        }
        if (inSingle || inDouble) return null
        flush(segments, current)
        return segments
    }

    private fun flush(segments: MutableList<String>, current: StringBuilder) {
        val s = current.toString().trim()
        if (s.isNotEmpty()) segments.add(s)
        current.setLength(0)
    }

    /** 顶层是否出现管道（`|`，非 `||`），用于裸 shell 接收端判断。 */
    private fun hasTopLevelPipe(command: String): Boolean {
        return splitTopLevelSegments(command)
            ?.filter { it.contains('|') }
            ?.isNotEmpty() == true
    }

    private fun checkSegment(segment: String, topLevelPipe: Boolean): String? {
        val tokens = tokenize(segment) ?: return null
        val effective = skipEnvAssigns(tokens)
        val program = effective.firstOrNull() ?: return null

        if (isRmCommand(program)) {
            val (recursive, force, paths) = analyzeRmArgs(effective)
            if (recursive && paths.any { isDangerousDeleteTarget(it) }) {
                return "安全防护：禁止递归删除根目录或系统关键目录（$program ${effective.joinToString(" ")}）"
            }
        }

        if (program == "dd") {
            for (i in 1 until effective.size) {
                val a = effective[i]
                if (a.startsWith("of=")) {
                    val target = normalizePath(a.substring(3))
                    if (target.startsWith("/dev/")) {
                        return "安全防护：禁止向块设备写入数据（dd of=$target），已拦截。"
                    }
                }
            }
        }

        // 不允许把输出管道喂给裸 shell 解释器（`... | bash`），避免任意脚本执行绕过。
        if (topLevelPipe && isBareShell(program, effective)) {
            return "安全防护：禁止将命令管道输入到裸 shell 解释器（$program）。"
        }

        return null
    }

    private fun isRmCommand(program: String): Boolean = program == "rm" || program.endsWith("/rm")

    private fun isBareShell(program: String, tokens: List<String>): Boolean {
        if (program !in SHELL_NAMES) return false
        var i = 1
        while (i < tokens.size) {
            val a = tokens[i]
            if (a == "-c") return false
            if (!a.startsWith("-")) return false // 脚本路径参数
            i++
        }
        return true
    }

    /** 跳过段首形如 `NAME=value` 的环境赋值 token，返回有效的 token 列表。 */
    private fun skipEnvAssigns(tokens: List<String>): List<String> {
        var idx = 0
        while (idx < tokens.size && ENV_ASSIGN.matches(tokens[idx])) idx++
        return if (idx == 0) tokens else tokens.subList(idx, tokens.size)
    }

    private val ENV_ASSIGN = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*$")

    /**
     * 引号感知分词：剥离引号与重定向算子，识别绝对路径重定向目标。
     * 返回 null 表示不可静态解析（未闭合引号）。
     */
    private fun tokenize(segment: String): List<String>? {
        val tokens = mutableListOf<String>()
        val token = StringBuilder()
        var inSingle = false
        var inDouble = false
        var tokenStarted = false
        var i = 0
        val n = segment.length

        fun flushToken() {
            if (tokenStarted) {
                tokens.add(token.toString())
                token.setLength(0)
                tokenStarted = false
            }
        }

        while (i < n) {
            val c = segment[i]
            if (inSingle) {
                if (c == '\'') inSingle = false else { token.append(c); tokenStarted = true }
                i++
            } else if (inDouble) {
                if (c == '"') { inDouble = false; tokenStarted = true; i++ }
                else { token.append(c); tokenStarted = true; i++ }
            } else {
                when {
                    c == '\'' -> { inSingle = true; tokenStarted = true; i++ }
                    c == '"' -> { inDouble = true; tokenStarted = true; i++ }
                    c == '\\' -> { if (i + 1 < n) { token.append(segment[i + 1]); tokenStarted = true }; i += 2 }
                    c == '>' -> { flushToken(); i += if (i + 1 < n && segment[i + 1] == '>') 2 else 1 }
                    c == '<' -> { flushToken(); i++ }
                    c.isWhitespace() -> { flushToken(); i++ }
                    else -> { token.append(c); tokenStarted = true; i++ }
                }
            }
        }
        if (inSingle || inDouble) return null
        flushToken()
        return tokens
    }

    private data class RmArgs(
        val recursive: Boolean,
        val paths: List<String>,
    )

    private fun analyzeRmArgs(tokens: List<String>): RmArgs {
        var recursive = false
        val paths = mutableListOf<String>()
        var afterDoubleDash = false
        for (i in 1 until tokens.size) {
            val t = tokens[i]
            if (!afterDoubleDash && t == "--") { afterDoubleDash = true; continue }
            if (!afterDoubleDash && t.startsWith("-") && t.length > 1) {
                if (t == "--recursive" || (!t.startsWith("--") && (t.contains('r') || t.contains('R')))) {
                    recursive = true
                }
                continue
            }
            paths.add(t)
        }
        return RmArgs(recursive, paths)
    }

    private fun isDangerousDeleteTarget(path: String): Boolean {
        val norm = normalizePath(path)
        if (norm in setOf("/", "/*", "/.*", "~", "~/", "\$HOME", "\$HOME/")) return true
        if (norm == "/home" || norm == "/home/*") return true
        val rest = norm.removePrefix("/home/")
        if (rest.isNotEmpty() && !rest.contains('/')) return true // /home/<single-user>
        for (sys in SYSTEM_DELETE_PATHS) {
            if (norm == sys || norm.startsWith("$sys/") || norm.startsWith("$sys/*")) return true
        }
        return false
    }

    /** 词法规范化路径：合并 `//`、解析 `.` 与 `..`，保留前导 `~`/`$HOME`。 */
    internal fun normalizePath(path: String): String {
        val s = path.trim()
        if (s.isEmpty()) return s
        if (s == "~" || s == "\$HOME") return s
        val isAbs = s.startsWith('/')
        val parts = mutableListOf<String>()
        for (comp in s.split('/')) {
            when (comp) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty() && parts.last() != "..") parts.removeAt(parts.size - 1) else parts.add("..")
                else -> parts.add(comp)
            }
        }
        val joined = parts.joinToString("/")
        return if (isAbs) "/$joined" else joined
    }

    private fun isForkBomb(raw: String): Boolean {
        val s = raw.filter { !it.isWhitespace() }
        if (!s.contains("(){")) return false
        val idx = s.indexOf("(){")
        val after = s.substring(idx + 3)
        val end = after.indexOf("};")
        if (end < 0) return false
        val body = after.substring(0, end)
        return body.contains('|') && body.contains('&')
    }
}