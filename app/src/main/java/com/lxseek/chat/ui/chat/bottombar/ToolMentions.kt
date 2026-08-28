package com.lxseek.chat.ui.chat.bottombar

import androidx.annotation.StringRes
import com.lxseek.chat.R

/**
 * 输入框 `@` 工具提及目录。
 *
 * 这里维护一份面向用户的“能力清单”，供小白在输入框里用 `@` 直接点名工具，而无需记住
 * 工具的真实注册名。每条 [ToolMention.name] 对应后端工具注册名，点击提及后插入的也是这个
 * 名字；[labelRes] 与 [descriptionRes] 仅用于面板展示，用大白话描述能力，避免术语堆砌，
 * 以资源形式提供以支持中英双语。
 */
object ToolMentions {

    /** 工具所属分组：决定了面板里的分组标题与排序。 */
    enum class Group {
        FILE, WEB, MEMORY, SEARCH, SHELL, TASK, IMAGE, AGENT, SYSTEM,
    }

    data class Mention(
        val name: String,
        @StringRes val labelRes: Int,
        @StringRes val descriptionRes: Int,
        val group: Group,
    )

    val all: List<Mention> = listOf(
        // —— 文件 ——
        Mention("file_read", R.string.tm_file_read_label, R.string.tm_file_read_desc, Group.FILE),
        Mention("file_write", R.string.tm_file_write_label, R.string.tm_file_write_desc, Group.FILE),
        Mention("file_edit", R.string.tm_file_edit_label, R.string.tm_file_edit_desc, Group.FILE),
        Mention("file_glob", R.string.tm_file_glob_label, R.string.tm_file_glob_desc, Group.FILE),
        Mention("file_grep", R.string.tm_file_grep_label, R.string.tm_file_grep_desc, Group.FILE),
        // —— 网页 ——
        Mention("web_search", R.string.tm_web_search_label, R.string.tm_web_search_desc, Group.WEB),
        Mention("web_fetch", R.string.tm_web_fetch_label, R.string.tm_web_fetch_desc, Group.WEB),
        // —— 记忆 ——
        Mention("read_memory_file", R.string.tm_memory_read_label, R.string.tm_memory_read_desc, Group.MEMORY),
        Mention("create_memory_file", R.string.tm_memory_create_label, R.string.tm_memory_create_desc, Group.MEMORY),
        Mention("edit_memory_file", R.string.tm_memory_edit_label, R.string.tm_memory_edit_desc, Group.MEMORY),
        // —— 会话检索 ——
        Mention("search_conversations", R.string.tm_search_hist_label, R.string.tm_search_hist_desc, Group.SEARCH),
        Mention("list_conversations", R.string.tm_search_list_label, R.string.tm_search_list_desc, Group.SEARCH),
        // —— Shell ——
        Mention("execute_shell_command", R.string.tm_shell_exec_label, R.string.tm_shell_exec_desc, Group.SHELL),
        Mention("list_shells", R.string.tm_shell_devices_label, R.string.tm_shell_devices_desc, Group.SHELL),
        // —— 任务 ——
        Mention("create_task", R.string.tm_task_create_label, R.string.tm_task_create_desc, Group.TASK),
        Mention("list_tasks", R.string.tm_task_list_label, R.string.tm_task_list_desc, Group.TASK),
        // —— 图像 ——
        Mention("view_image", R.string.tm_image_view_label, R.string.tm_image_view_desc, Group.IMAGE),
        Mention("generate_image", R.string.tm_image_gen_label, R.string.tm_image_gen_desc, Group.IMAGE),
        // —— Android UI 控制（文本模型适配）——
        Mention("android_read_ui", R.string.tm_agent_read_label, R.string.tm_agent_read_desc, Group.AGENT),
        Mention("android_click", R.string.tm_agent_click_label, R.string.tm_agent_click_desc, Group.AGENT),
        Mention("android_input", R.string.tm_agent_input_label, R.string.tm_agent_input_desc, Group.AGENT),
        Mention("android_swipe", R.string.tm_agent_swipe_label, R.string.tm_agent_swipe_desc, Group.AGENT),
        // —— 系统 ——
        Mention("system_clean", R.string.tm_system_clean_label, R.string.tm_system_clean_desc, Group.SYSTEM),
    )

    /** 分组标题的资源 id。 */
    @StringRes
    fun groupTitleRes(group: Group): Int = when (group) {
        Group.FILE -> R.string.tm_group_file
        Group.WEB -> R.string.tm_group_web
        Group.MEMORY -> R.string.tm_group_memory
        Group.SEARCH -> R.string.tm_group_search
        Group.SHELL -> R.string.tm_group_shell
        Group.TASK -> R.string.tm_group_task
        Group.IMAGE -> R.string.tm_group_image
        Group.AGENT -> R.string.tm_group_agent
        Group.SYSTEM -> R.string.tm_group_system
    }
}