package com.lxseek.chat.command

import com.lxseek.chat.viewmodel.ChatViewModel

/**
 * 可扩展的斜杠命令系统。
 *
 * 发送时若输入文本精确命中某条命令的 [Command.trigger]，就执行对应动作而不是当作普通消息发送。
 * 新增命令只需在 [all] 列表追加一条 [Command] 并在 [execute] 中补充分支即可。
 */
object SlashCommands {

    data class Command(
        val trigger: String,
        val label: String,
        val description: String,
    )

    /** 所有已注册命令，按 trigger 排序，保证提示顺序稳定。 */
    val all: List<Command> = listOf(
        Command("/help", "帮助", "显示所有可用斜杠命令"),
        Command("/new", "新对话", "立即创建并切换到新对话"),
        Command("/stop", "停止", "停止当前正在进行的生成"),
        Command("/share", "分享", "导出并分享当前对话"),
        Command("/fork", "复制分支", "从当前对话新建一个可继续的分支"),
    )

    /** 前缀过滤：输入为 "/" 时返回全部，否则返回 trigger 以输入开头的命令。 */
    fun filterByPrefix(input: String): List<Command> {
        val text = input.trim()
        if (text == "/") return all
        return all.filter { it.trigger.startsWith(text) }
    }

    /** 完全匹配：发送时调用，命中返回对应命令，否则 null。 */
    fun findExact(input: String): Command? =
        all.firstOrNull { input.trim() == it.trigger }

    /**
     * 尝试把 [text] 当作斜杠命令执行。
     * 返回 true 表示已消费输入（调用方应清空输入框）；false 表示不是命令，走正常发送。
     */
    suspend fun execute(text: String, viewModel: ChatViewModel): Boolean {
        val command = findExact(text) ?: return false
        when (command.trigger) {
            "/help" -> viewModel.emitSnackbar(
                all.joinToString("\n") { "${it.trigger} — ${it.description}" },
            )
            "/new" -> viewModel.createNewChat()
            "/stop" -> viewModel.stopGeneration()
            "/share" -> viewModel.shareConversation()
            "/fork" -> viewModel.forkConversationFrom(null)
        }
        return true
    }
}