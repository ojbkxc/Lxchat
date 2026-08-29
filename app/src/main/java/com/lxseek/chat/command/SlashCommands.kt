package com.lxseek.chat.command

import com.lxseek.chat.viewmodel.ChatViewModel

/**
 * Extensible slash-command system.
 *
 * When the user sends text that exactly matches a [Command.trigger] (or a trigger followed by
 * parameters for commands with [Command.hasParams]), the corresponding action is executed
 * instead of treating the input as a normal message.
 *
 * To add a command: append a [Command] to [all] and add a branch in [execute].
 */
object SlashCommands {

    data class Command(
        val trigger: String,
        val label: String,
        val description: String,
        /** True if this command accepts trailing parameters (e.g. "/model gpt-4"). */
        val hasParams: Boolean = false,
    )

    /** All registered commands, sorted by trigger for stable suggestion ordering. */
    val all: List<Command> = listOf(
        // ── Conversation ─────────────────────────────────────────
        Command("/new", "新对话", "立即创建并切换到新对话"),
        Command("/stop", "停止", "停止当前正在进行的生成"),
        Command("/clear", "清空", "清除当前对话消息（保留对话但不显示消息）"),
        Command("/search", "搜索", "搜索对话历史", hasParams = true),
        Command("/share", "分享", "导出并分享当前对话"),
        Command("/fork", "复制分支", "从当前对话新建一个可继续的分支"),
        // ── Configuration ────────────────────────────────────────
        Command("/model", "模型", "切换/选择模型（无参数时显示列表）", hasParams = true),
        Command("/pet", "宠物", "切换桌面宠物", hasParams = true),
        Command("/voice", "语音", "切换语音输入模式"),
        Command("/settings", "设置", "快速打开设置页面"),
        // ── Export / help ─────────────────────────────────────────
        Command("/export", "导出", "导出对话为文件（txt/md）"),
        Command("/help", "帮助", "显示所有可用斜杠命令"),
    )

    /**
     * Parse raw user input into a [Command] and its optional parameter string.
     *
     * Returns `null` when the input does not start with a known trigger.
     * Example: "/model gpt-4" → (Command("/model"), "gpt-4").
     */
    fun parseCommand(input: String): Pair<Command, String?>? {
        val text = input.trim()
        if (!text.startsWith("/")) return null
        // Split into the first token (trigger) and the remainder (parameters).
        val spaceIdx = text.indexOf(' ')
        if (spaceIdx < 0) {
            val cmd = all.firstOrNull { it.trigger == text } ?: return null
            return cmd to null
        }
        val trigger = text.substring(0, spaceIdx)
        val param = text.substring(spaceIdx + 1).trim()
        val cmd = all.firstOrNull { it.trigger == trigger } ?: return null
        return cmd to if (param.isEmpty()) null else param
    }

    /** Prefix filter: return all commands when input is "/", otherwise those whose trigger starts with the input. */
    fun filterByPrefix(input: String): List<Command> {
        val text = input.trim()
        if (text == "/") return all
        return all.filter { it.trigger.startsWith(text) }
    }

    /**
     * Match a full input string (possibly with parameters) to a command.
     * Replaces the old [findExact] to support parameterized commands like "/timer 10m".
     */
    fun findMatch(input: String): Command? = parseCommand(input)?.first

    /** Legacy exact-match kept for backward compatibility (no parameters). */
    fun findExact(input: String): Command? =
        all.firstOrNull { input.trim() == it.trigger }

    /**
     * Attempt to execute [text] as a slash command.
     * Returns `true` if the input was consumed (caller should clear the input field);
     * `false` if it is not a command and should be sent as a normal message.
     */
    suspend fun execute(text: String, viewModel: ChatViewModel): Boolean {
        val parsed = parseCommand(text) ?: return false
        val command = parsed.first
        val param = parsed.second
        when (command.trigger) {
            // ── Conversation ──────────────────────────────────────────
            "/new" -> viewModel.createNewChat()
            "/stop" -> viewModel.stopGeneration()
            "/clear" -> viewModel.clearConversation()
            "/search" -> viewModel.searchConversationHistory(param.orEmpty())
            "/share" -> viewModel.shareConversation()
            "/fork" -> viewModel.forkConversationFrom(null)
            // ── Configuration ─────────────────────────────────────────
            "/model" -> viewModel.switchModel(param)
            "/pet" -> viewModel.switchPet(param)
            "/voice" -> viewModel.toggleVoiceInput()
            "/settings" -> viewModel.openSettings()
            // ── Export / help ─────────────────────────────────────────
            "/export" -> viewModel.exportConversation()
            "/help" -> viewModel.emitSnackbar(
                all.joinToString("\n") {
                    val suffix = if (it.hasParams) " <…>" else ""
                    "${it.trigger}$suffix — ${it.description}"
                },
            )
        }
        return true
    }
}
