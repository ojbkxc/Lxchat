package com.lxseek.chat.ui.chat.bottombar

/**
 * 输入框 `@` 工具提及目录。
 *
 * 这里维护一份面向用户的“能力清单”，供小白在输入框里用 `@` 直接点名工具，而无需记住
 * 工具的真实注册名。每条 [ToolMention.name] 对应后端工具注册名，点击提及后插入的也是这个
 * 名字；[label] 与 [description] 仅用于面板展示，用大白话描述能力，避免术语堆砌。
 */
object ToolMentions {

    /** 工具所属分组：决定了面板里的分组标题与排序。 */
    enum class Group {
        FILE, WEB, MEMORY, SEARCH, SHELL, TASK, IMAGE, AGENT, SYSTEM,
    }

    data class Mention(
        val name: String,
        val label: String,
        val description: String,
        val group: Group,
    )

    val all: List<Mention> = listOf(
        // —— 文件 ——
        Mention("file_read", "读文件", "读取本地文件内容", Group.FILE),
        Mention("file_write", "写文件", "新建或覆盖一个文件", Group.FILE),
        Mention("file_edit", "改文件", "在文件里做局部修改", Group.FILE),
        Mention("file_glob", "找文件", "按名称模式查找文件", Group.FILE),
        Mention("file_grep", "搜文本", "在文件里搜索内容", Group.FILE),
        // —— 网页 ——
        Mention("web_search", "联网搜索", "搜索互联网上的最新信息", Group.WEB),
        Mention("web_fetch", "打开网页", "抓取并阅读一个网页", Group.WEB),
        // —— 记忆 ——
        Mention("read_memory_file", "读记忆", "读取已保存的记忆", Group.MEMORY),
        Mention("create_memory_file", "存记忆", "把内容存成新记忆", Group.MEMORY),
        Mention("edit_memory_file", "改记忆", "修改已保存的记忆", Group.MEMORY),
        // —— 会话检索 ——
        Mention("search_conversations", "搜历史", "在过往对话里查找内容", Group.SEARCH),
        Mention("list_conversations", "列对话", "列出历史对话", Group.SEARCH),
        // —— Shell ——
        Mention("execute_shell_command", "执行命令", "在设备上运行一条命令", Group.SHELL),
        Mention("list_shells", "看设备", "查看可用的执行设备", Group.SHELL),
        // —— 任务 ——
        Mention("create_task", "建任务", "新建一个待办任务", Group.TASK),
        Mention("list_tasks", "看任务", "列出当前待办任务", Group.TASK),
        // —— 图像 ——
        Mention("view_image", "看图", "查看一张图片", Group.IMAGE),
        Mention("generate_image", "画图", "根据描述生成图片", Group.IMAGE),
        // —— Android UI 控制（文本模型适配）——
        Mention("android_read_ui", "读屏幕", "读取当前屏幕上的可点内容", Group.AGENT),
        Mention("android_click", "点按", "点击屏幕上的某个元素", Group.AGENT),
        Mention("android_input", "输入", "向输入框填入文字", Group.AGENT),
        Mention("android_swipe", "滑动", "在屏幕上滑动", Group.AGENT),
        // —— 系统 ——
        Mention("system_clean", "清理系统", "扫描并清理系统垃圾", Group.SYSTEM),
    )

    /** 前缀过滤：[query] 为 `@` 后面的已输入内容，匹配 name 或 label 或 description。 */
    fun filter(query: String): List<Mention> {
        val q = query.trim()
        if (q.isEmpty()) return all
        return all.filter {
            it.name.contains(q, ignoreCase = true) ||
                it.label.contains(q) ||
                it.description.contains(q)
        }
    }
}