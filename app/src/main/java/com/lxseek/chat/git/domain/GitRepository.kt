package com.lxseek.chat.git.domain

import com.lxseek.chat.git.domain.model.GitBranch
import com.lxseek.chat.git.domain.model.GitCommandResult
import com.lxseek.chat.git.domain.model.GitCommit
import com.lxseek.chat.git.domain.model.GitFileChange
import com.lxseek.chat.git.domain.model.GitGraph
import com.lxseek.chat.git.domain.model.GitGraphRef
import com.lxseek.chat.git.domain.model.GitRemote
import com.lxseek.chat.git.domain.model.GraphCommit
import com.lxseek.chat.git.domain.model.GitStatus
import com.lxseek.chat.git.domain.model.GitTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Git 命令的实际执行抽象。由 Lxchat 工具层按沙箱/远程后端实现：给定一条完整 `git ...` 命令字符串，
 * 在 [workdir]（仓库根目录，null 表示继承默认工作目录）下执行并返回退出码 + 合并输出。
 * 领域层只依赖此接口，不关心具体后端（本地沙箱 / SSH / Conch）。
 */
fun interface GitCommandRunner {
    suspend fun execute(command: String, workdir: String?, timeoutMs: Int): GitCommandResult
}

/** git 写命令/解析失败（非零退出码）时抛出，携带 git 原始输出供上层如实呈现失败原因。 */
class GitCommandFailureException(message: String) : Exception(message)

/**
 * 执行 git 命令并解析输出（移植自 aicode 的 GitRepository，依赖抽象为 [GitCommandRunner]）。
 *
 * 所有输出解析为纯领域模型。命令经 [shellQuote] 逐参数转义后拼成单条 `git ...` 字符串交给执行层，
 * 故格式串里的 `|`、`%(...)`、含空格的路径都能安全传递。
 *
 * @param runner 实际的命令执行后端。
 * @param workdir 仓库根目录；为 null 时由后端决定默认工作目录。
 */
class GitRepository(
    private val runner: GitCommandRunner,
    private val workdir: String? = null,
) {
    private companion object {
        /** 提交拓扑图每页加载条数。首批与每次「加载更多」都取这么多条，超过的需滚到底再拉。 */
        const val GRAPH_PAGE_SIZE = 100
        /** 只读 git 命令的默认执行超时。diff/日志可能较慢，给足余量。 */
        const val DEFAULT_TIMEOUT_MS = 30_000
    }

    /** 执行一条 `git` 子命令，返回合并后的 stdout+stderr 文本。仅用于只读命令：靠输出解析、容错。 */
    private suspend fun git(
        vararg args: String
    ): String = gitRaw(args)

    /**
     * 执行一条 `git` **写**子命令，据退出码判成败：非零（真实失败）抛 [GitCommandFailureException]
     * 携带 git 输出文本。空退出码（超时/异常）同样按失败抛，避免静默成功。
     */
    private suspend fun gitChecked(
        vararg args: String
    ): String {
        val cmd = buildString {
            append("git")
            args.forEach { append(' '); append(shellQuote(it)) }
        }
        val result = runner.execute(cmd, workdir, DEFAULT_TIMEOUT_MS)
        if (result.exitCode == 0) return result.output
        throw GitCommandFailureException(result.output.ifBlank { "git 退出码 ${result.exitCode}" })
    }

    /** 拼命令并跑（不判退出码），[git] 与 [gitChecked] 复用。 */
    private suspend fun gitRaw(args: Array<out String>): String {
        val cmd = buildString {
            append("git")
            args.forEach { append(' '); append(shellQuote(it)) }
        }
        return runner.execute(cmd, workdir, DEFAULT_TIMEOUT_MS).output
    }

    /** 当前仓库是否是一个 git 工作树内。后端异常时返回 false 而非抛出。 */
    suspend fun isRepo(): Boolean {
        return runCatching { git("rev-parse", "--is-inside-work-tree").trim() == "true" }
            .getOrElse { false }
    }

    /** 是否已配置至少一个远程仓库（`git remote` 输出非空）。 */
    suspend fun hasRemote(): Boolean = git("remote").trim().isNotEmpty()

    /** 远程仓库列表（`git remote -v`），每行形如 `origin\thttps://...\t(fetch)`。返回 name 与 URL。 */
    suspend fun remotes(): List<GitRemote> {
        val raw = git("remote", "-v")
        val seen = LinkedHashMap<String, String>()
        for (line in raw.lineSequence().map { it.removeSuffix("\r") }) {
            if (line.isBlank()) continue
            val tab = line.indexOf('\t')
            if (tab < 0) continue
            val name = line.substring(0, tab).trim()
            val rest = line.substring(tab + 1).trim().substringBefore("\t")
            if (name.isNotBlank() && rest.isNotBlank()) seen.putIfAbsent(name, rest)
        }
        return seen.map { (name, url) -> GitRemote(name, url) }
    }

    /** `git status` 聚合视图。 */
    suspend fun status(): GitStatus {
        val raw = git("status", "--porcelain=v1", "-b")
        val lines = raw.split('\n').map { it.removeSuffix("\r") }

        var branch = "(unknown)"
        var upstream: String? = null
        var isDetached = false
        var ahead = 0
        var behind = 0
        val staged = mutableListOf<GitFileChange>()
        val unstaged = mutableListOf<GitFileChange>()
        val untracked = mutableListOf<String>()

        for (line in lines) {
            if (line.isBlank()) continue
            if (line.startsWith("## ")) {
                val header = line.removePrefix("## ")
                isDetached = header.startsWith("HEAD (no branch)")
                val tracking = header.substringBefore(" [")
                if (isDetached) {
                    branch = "HEAD"
                } else {
                    branch = tracking.substringBefore("...").ifBlank { tracking }
                    upstream = tracking.substringAfter("...", "").ifBlank { null }
                }
                val bracket = header.substringAfter(" [", "")
                if (bracket.isNotBlank()) {
                    bracket.removeSuffix("]").split(",").forEach { tok ->
                        val t = tok.trim()
                        val n = t.filter { it.isDigit() }.toIntOrNull() ?: 0
                        if (t.startsWith("ahead")) ahead = n
                        else if (t.startsWith("behind")) behind = n
                    }
                }
                continue
            }
            if (line.length < 3) continue
            val x = line[0]
            val y = line[1]
            val rawPath = line.substring(3)
            // 重命名形如 "old -> new"，展示新路径。
            val path = unquotePorcelainPath(rawPath.substringAfter(" -> ").trim())

            if (x == '?' && y == '?') {
                untracked.add(path)
                continue
            }
            if (x != ' ' && x != '?') {
                staged.add(GitFileChange(path, x.toString(), staged = true))
            }
            if (y != ' ' && y != '?') {
                unstaged.add(GitFileChange(path, y.toString(), staged = false))
            }
        }
        return GitStatus(branch, ahead, behind, staged, unstaged, untracked, upstream, isDetached)
    }

    /** 本地 + 远程分支列表，当前分支高亮。 */
    suspend fun branches(): List<GitBranch> = loadAllRefs().branches

    suspend fun tags(): List<GitTag> = loadAllRefs().tags

    /** 最近 [limit] 条提交。 */
    suspend fun log(limit: Int = 50): List<GitCommit> {
        val raw = git("log", "--pretty=format:%H%x1f%h%x1f%an%x1f%ar%x1f%s", "-n", limit.toString())
        if (raw.isBlank() || raw.startsWith("fatal:")) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val parts = line.removeSuffix("\r").split('\u001f')
            if (parts.size < 5) null
            else GitCommit(parts[0], parts[1], parts[2], parts[3], parts[4])
        }
    }

    /** 某次提交改动的文件清单（name-status，多行 diff 的资源需求低）。 */
    suspend fun diffNameStatus(refRange: String): List<GitFileChange> {
        val raw = git("diff", "--name-status", "--no-renames", refRange)
        return withContext(Dispatchers.Default) {
            raw.lineSequence().mapNotNull { line ->
                val l = line.removeSuffix("\r").trim()
                val tab = l.indexOf('\t')
                if (l.isBlank() || tab < 0) null
                else {
                    val status = l.substring(0, tab).trim()
                    val path = l.substring(tab + 1).trim()
                    GitFileChange(path, status, staged = false)
                }
            }.toList()
        }
    }

    /** `git diff` 完整补丁文本（含统计），供 diff 工具返回给人读。空改为空串。 */
    suspend fun diffUnified(refRange: String?): String {
        val raw = if (refRange.isNullOrBlank()) git("diff") else git("diff", refRange)
        return if (raw.startsWith("fatal:")) "" else raw.trimEnd()
    }

    /**
     * 一次拉取全部分支/标签及其指向的提交哈希，解析为 [AllRefs]。`%(HEAD)` 标记当前分支，
     * `%(upstream:short)`/`%(upstream:track)` 提供跟踪与领先/落后计数。标签按 refname 倒序。
     */
    data class AllRefs(
        val branches: List<GitBranch>,
        val tags: List<GitTag>,
        val refsByCommit: Map<String, List<GitGraphRef>>
    )

    suspend fun loadAllRefs(): AllRefs = withContext(Dispatchers.Default) {
        val raw = runCatching {
            git(
                "for-each-ref",
                "--format=%(refname:short)\u001f%(objectname)\u001f%(HEAD)\u001f%(refname)\u001f%(upstream:short)\u001f%(upstream:track)",
                "refs/heads", "refs/remotes", "refs/tags"
            )
        }.getOrDefault("")
        if (raw.isBlank() || raw.startsWith("fatal:")) return@withContext AllRefs(emptyList(), emptyList(), emptyMap())
        val branches = mutableListOf<GitBranch>()
        val tags = mutableListOf<GitTag>()
        val refsByCommit = mutableMapOf<String, MutableList<GitGraphRef>>()
        for (line in raw.split('\n')) {
            val l = line.removeSuffix("\r").trim()
            if (l.isBlank()) continue
            val parts = l.split('\u001f')
            if (parts.size < 4) continue
            val name = parts[0]
            val hash = parts[1]
            val isHead = parts[2].trim() == "*"
            val refname = parts[3]
            val upstream = parts.getOrNull(4)?.ifBlank { null }
            val track = parts.getOrNull(5).orEmpty()
            val ahead = Regex("ahead (\\d+)").find(track)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val behind = Regex("behind (\\d+)").find(track)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val isRemote = refname.startsWith("refs/remotes")
            val isBranch = refname.startsWith("refs/heads") || isRemote
            refsByCommit.getOrPut(hash) { mutableListOf() }
                .add(GitGraphRef(name, isBranch, isHead && !isRemote, isRemote))
            if (isBranch) {
                branches.add(GitBranch(name, current = isHead && !isRemote, remote = isRemote, upstream = upstream, ahead = ahead, behind = behind))
            } else {
                tags.add(GitTag(name, hash.take(7)))
            }
        }
        tags.reverse()
        AllRefs(branches, tags, refsByCommit)
    }

    /**
     * 拓扑图视图：提交（含父哈希）+ 引用（分支/标签）+ 泳道布局。首批加载取 [GRAPH_PAGE_SIZE] 条。
     * 失败（非仓库/无提交）返回 [GitGraph.EMPTY]，不抛——UI 据空图显示空态。
     */
    suspend fun graph(
        limit: Int = GRAPH_PAGE_SIZE,
        refs: Map<String, List<GitGraphRef>> = emptyMap()
    ): GitGraph =
        graphAppend(emptyList(), refs, limit)

    /**
     * 分页加载下一批提交并整体重算泳道布局。从已加载数量处 `--skip` 取下一批，与 [existingCommits] 合并后
     * 整体调 [GitGraphBuilder] 重算——泳道分配依赖全局子父顺序，父提交可能跨批次指向已加载提交，故必须整图重算。
     */
    suspend fun graphAppend(
        existingCommits: List<GraphCommit>,
        refs: Map<String, List<GitGraphRef>> = emptyMap(),
        limit: Int = GRAPH_PAGE_SIZE
    ): GitGraph = withContext(Dispatchers.Default) {
        val skip = existingCommits.size
        val logRaw = runCatching { git("log", "--pretty=format:%H%x1f%h%x1f%an%x1f%ar%x1f%s%x1f%P%x1f%b", "--skip", skip.toString(), "-n", limit.toString()) }
            .getOrDefault("")
        if (logRaw.isBlank() || logRaw.startsWith("fatal:")) {
            return@withContext if (existingCommits.isEmpty()) GitGraph.EMPTY
            else GitGraphBuilder.buildGraph(existingCommits, refs, hasMore = false)
        }

        val newCommits = GitGraphBuilder.parseGraphCommits(logRaw)
        if (newCommits.isEmpty() && existingCommits.isEmpty()) return@withContext GitGraph.EMPTY

        val existingHashes = existingCommits.mapTo(HashSet()) { it.hash }
        val merged = existingCommits + newCommits.filter { it.hash !in existingHashes }
        GitGraphBuilder.buildGraph(merged, refs, hasMore = newCommits.size >= limit)
    }

    /**
     * porcelain v1 对含引号/反斜杠/控制字符的路径会整体加引号并做 C 风格转义
     * （如 `"a\"b.txt"`），这里做反向解析还原真实路径。
     */
    private fun unquotePorcelainPath(raw: String): String {
        if (!raw.startsWith("\"")) return raw
        val inner = raw.removeSurrounding("\"")
        val sb = StringBuilder(inner.length)
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '\\' && i + 1 < inner.length) {
                when (val n = inner[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    in '0'..'7' -> {
                        val end = minOf(i + 4, inner.length)
                        val octal = inner.substring(i + 1, end).takeWhile { it in '0'..'7' }
                        if (octal.length == 3) {
                            sb.append(octal.toInt(8).toChar())
                            i += 1 + octal.length
                        } else {
                            sb.append(c); i += 1
                        }
                    }
                    else -> { sb.append(c); i += 1 }
                }
            } else {
                sb.append(c); i += 1
            }
        }
        return sb.toString()
    }

    /**
     * 对单个 shell 参数做单引号转义。含「安全字符」之外的字符（空格、`|`、`$`、反引号、`*` 等）时
     * 整体包单引号，内嵌单引号用 `'\''` 关闭-转义-重开。注意 `|` 是 shell 管道符，**不可**列入安全集。
     */
    private fun shellQuote(arg: String): String {
        if (arg.isEmpty()) return "''"
        if (arg.all { it.isLetterOrDigit() || it in "_.@/:=+,%-" }) return arg
        return "'" + arg.replace("'", "'\\''") + "'"
    }
}