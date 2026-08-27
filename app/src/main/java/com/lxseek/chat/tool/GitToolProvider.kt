package com.lxseek.chat.tool

import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.git.domain.GitCommandFailureException
import com.lxseek.chat.git.domain.GitCommandRunner
import com.lxseek.chat.git.domain.GitRepository
import com.lxseek.chat.git.domain.model.GitCommandResult
import com.lxseek.chat.git.domain.model.GitRemote
import com.lxseek.chat.sandbox.SandboxManagerFactory
import com.lxseek.chat.viewmodel.GenerationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 只读 Git 工具提供器（移植自 aicode 的 Git 领域层并接入 Lxchat 沙箱执行）。
 *
 * 通过本地沙箱执行 `git` 的子集只读命令，把解析结果以 JSON 返回给模型，让 AI 能了解某仓库的
 * 状态 / 提交历史 / 分支 / 改动 / 远程信息，而不具备任何写操作。仓库根目录由 `path` 参数指定，
 * 缺省时交给沙箱默认工作目录。
 */
class GitToolProvider(
    private val sandboxFactory: SandboxManagerFactory? = null,
) : ToolProvider {

    private val sandbox = sandboxFactory?.create()

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        // Git 工具依赖本地沙箱执行；沙箱不可用时整体隐藏。
        if (sandbox?.isAvailableSync() != true) return emptyList()
        val pathProp = ToolProperty("string", "Git repository root directory (optional; defaults to the sandbox working directory).")
        return buildList {
            add(ToolDefinition(function = ToolFunction(
                name = "git_status",
                description = "Show the working tree status: current branch, ahead/behind, staged, unstaged and untracked file changes.",
                parameters = ToolParameters(properties = mapOf("path" to pathProp), required = emptyList())
            )))
            add(ToolDefinition(function = ToolFunction(
                name = "git_log",
                description = "Show recent commit history (hash, short hash, author, relative date, subject).",
                parameters = ToolParameters(
                    properties = mapOf(
                        "path" to pathProp,
                        "limit" to ToolProperty("integer", "Maximum number of commits to return (default 30).")
                    ),
                    required = emptyList()
                )
            )))
            add(ToolDefinition(function = ToolFunction(
                name = "git_diff",
                description = "Show the working-tree diff as unified text. Optionally pass a ref range (e.g. \"main..HEAD\") to show the diff between two commits/branches.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "path" to pathProp,
                        "ref_range" to ToolProperty("string", "Optional ref range (e.g. main..HEAD or <sha>). Blank shows unstaged changes.")
                    ),
                    required = emptyList()
                )
            )))
            add(ToolDefinition(function = ToolFunction(
                name = "git_branches",
                description = "List local and remote branches with tracking info (current, upstream, ahead/behind) and tags.",
                parameters = ToolParameters(properties = mapOf("path" to pathProp), required = emptyList())
            )))
            add(ToolDefinition(function = ToolFunction(
                name = "git_remote",
                description = "List configured remote repositories (name and fetch URL).",
                parameters = ToolParameters(properties = mapOf("path" to pathProp), required = emptyList())
            )))
        }
    }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String =
        withRepo(arguments) { repo, path, limit ->
            when (name) {
                "git_status" -> statusJson(repo)
                "git_log" -> logJson(repo, limit)
                "git_diff" -> diffJson(repo, path, arguments)
                "git_branches" -> branchesJson(repo)
                "git_remote" -> remoteJson(repo)
                else -> jsonError(name, "Unknown git tool: $name")
            }
        }

    override fun executeEvents(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): Flow<ToolExecutionEvent> = flow {
        if (!handles(name)) {
            emit(ToolExecutionEvent.Completed(jsonError(name, "Unknown git tool: $name")))
        } else {
            emit(ToolExecutionEvent.Completed(ToolExecutionResult(execute(name, arguments, ctx))))
        }
    }

    override fun handles(name: String): Boolean = name in setOf(
        "git_status", "git_log", "git_diff", "git_branches", "git_remote"
    )

    /** 全部为只读工具，统一标记为 [RiskLevel.ReadOnly]。 */
    override fun riskLevel(name: String): RiskLevel = RiskLevel.ReadOnly

    override fun requiresApprovalByDefault(name: String): Boolean = false

    // ── 执行管线 ───────────────────────────────────────────

    /**
     * 解析公共参数（path/limit），取到沙箱后端，构建 [GitRepository]，执行块后返回其 JSON。
     * 沙箱不可用时返回错误 JSON，不崩。
     */
    private suspend fun withRepo(
        arguments: String,
        block: suspend (GitRepository, String?, Int) -> String,
    ): String {
        val args = parseToolArgs(arguments)
        val path = arg(args, "path").ifBlank { null }
        val limit = (args["limit"] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceIn(1, 200) ?: 30

        val backend = getSandboxBackend()
            ?: return jsonError("git", "Local Sandbox is not available. Install the sandbox to use Git tools.")
        return try {
            val repo = GitRepository(SandboxGitRunner(backend), path)
            block(repo, path, limit)
        } catch (e: GitCommandFailureException) {
            jsonError("git", e.message ?: "Git command failed")
        } catch (e: Exception) {
            jsonError("git", e.message ?: "Git tool failed")
        } finally {
            backend.close()
        }
    }

    private fun getSandboxBackend(): Backend? {
        if (sandbox?.isAvailableSync() != true) return null
        return SandboxBackend(sandbox)
    }

    /** 把 [Backend.executeCommand] 的 JSON 输出解析回 [GitCommandResult]。 */
    private class SandboxGitRunner(
        private val backend: Backend,
    ) : GitCommandRunner {
        override suspend fun execute(command: String, workdir: String?, timeoutMs: Int): GitCommandResult {
            val raw = backend.executeCommand(command, workdir.orEmpty(), timeoutMs)
            val parsed = try {
                Json.parseToJsonElement(raw).jsonObject
            } catch (_: Exception) {
                null
            }
            if (parsed == null) return GitCommandResult(-1, raw)
            val isError = (parsed["error"] as? JsonPrimitive)?.content == "error"
            val exitCode = (parsed["exit_code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1
            val output = (parsed["output"] as? JsonPrimitive)?.content
            if (isError) {
                val message = (parsed["message"] as? JsonPrimitive)?.content ?: "Git backend error"
                return GitCommandResult(-1, message)
            }
            return GitCommandResult(exitCode, output ?: raw)
        }
    }

    // ── 各工具的实现 ────────────────────────────────────────

    private suspend fun statusJson(repo: GitRepository): String {
        if (!repo.isRepo()) return jsonError("git_status", "path is not a git repository")
        val s = repo.status()
        return buildJsonObject {
            put("type", "git_status")
            put("branch", s.branch)
            put("upstream", s.upstream ?: "")
            put("ahead", s.ahead)
            put("behind", s.behind)
            put("isDetached", s.isDetached)
            put("hasChanges", s.hasChanges)
            putJsonArray("staged") {
                s.staged.forEach { add(gitFileChange(it.path, it.statusCode)) }
            }
            putJsonArray("unstaged") {
                s.unstaged.forEach { add(gitFileChange(it.path, it.statusCode)) }
            }
            putJsonArray("untracked") {
                s.untracked.forEach { add(JsonPrimitive(it)) }
            }
        }.toString()
    }

    private suspend fun logJson(repo: GitRepository, limit: Int): String {
        if (!repo.isRepo()) return jsonError("git_log", "path is not a git repository")
        val commits = repo.log(limit)
        return buildJsonObject {
            put("type", "git_log")
            putJsonArray("commits") {
                commits.forEach { c ->
                    add(buildJsonObject {
                        put("hash", c.hash)
                        put("shortHash", c.shortHash)
                        put("author", c.author)
                        put("date", c.date)
                        put("message", c.message)
                    })
                }
            }
        }.toString()
    }

    private suspend fun diffJson(repo: GitRepository, path: String?, arguments: String): String {
        if (!repo.isRepo()) return jsonError("git_diff", "path is not a git repository")
        val args = parseToolArgs(arguments)
        val refRange = arg(args, "ref_range").ifBlank { null }
        val diff = repo.diffUnified(refRange)
        return buildJsonObject {
            put("type", "git_diff")
            put("ref_range", refRange ?: "")
            put("path", path ?: "")
            put("diff", diff)
        }.toString()
    }

    private suspend fun branchesJson(repo: GitRepository): String {
        if (!repo.isRepo()) return jsonError("git_branches", "path is not a git repository")
        val refs = repo.loadAllRefs()
        return buildJsonObject {
            put("type", "git_branches")
            putJsonArray("branches") {
                refs.branches.forEach { b ->
                    add(buildJsonObject {
                        put("name", b.name)
                        put("current", b.current)
                        put("remote", b.remote)
                        put("upstream", b.upstream ?: "")
                        put("ahead", b.ahead)
                        put("behind", b.behind)
                    })
                }
            }
            putJsonArray("tags") {
                refs.tags.forEach { t ->
                    add(buildJsonObject {
                        put("name", t.name)
                        put("shortHash", t.shortHash)
                    })
                }
            }
        }.toString()
    }

    private suspend fun remoteJson(repo: GitRepository): String {
        if (!repo.isRepo()) return jsonError("git_remote", "path is not a git repository")
        val remotes: List<GitRemote> = repo.remotes()
        return buildJsonObject {
            put("type", "git_remote")
            putJsonArray("remotes") {
                remotes.forEach { r ->
                    add(buildJsonObject {
                        put("name", r.name)
                        put("url", r.url)
                    })
                }
            }
        }.toString()
    }

    private fun gitFileChange(path: String, statusCode: String) = buildJsonObject {
        put("path", path)
        put("status", statusCode)
    }
}