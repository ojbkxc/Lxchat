package com.lxseek.chat.im

import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.flow.first

/**
 * IM 机器人命令执行结果。
 *
 * @property replyText       回复给用户的文本；空串表示不回复。
 * @property requiresNewSession  为 true 时，处理器会解除当前 IM 会话与 Lxchat
 *                               会话的绑定，使下一条普通消息开启新会话。
 *                               仅 `/new` 命令会置 true。
 * @property isSteer         为 true 时表示这是 `/steer` 补充指令，需要走正常的
 *                               AI 回复流程（将 [steerText] 作为用户消息发送）。
 * @property steerText       `/steer` 命令的补充指令文本，仅当 [isSteer] 为 true
 *                               时有效。
 */
data class CommandResult(
    val replyText: String,
    val requiresNewSession: Boolean = false,
    val isSteer: Boolean = false,
    val steerText: String? = null,
    /**
     * 媒体发送动作（`/sendimage` `/sendfile` `/forward`）。由
     * [com.lxseek.chat.im.ImPollingReceiver] 用当前渠道执行；[replyText] 留空。
     */
    val mediaAction: MediaAction? = null,
) {

    /** 媒体发送动作：来源是 URL 或已缓存媒体名。 */
    sealed class MediaAction {
        /** 下载 [url] 并作为图片发送。 */
        data class SendImage(val url: String) : MediaAction()

        /** 下载 [url] 并作为文件发送。 */
        data class SendFile(val url: String) : MediaAction()

        /** 转发已收到名为 [name] 的媒体；[name] 为空表示列出可选转发项。 */
        data class Forward(val name: String) : MediaAction()
    }

    companion object {
        /** 构造一个纯文本回复（不触发 AI，不解除绑定）。 */
        fun text(reply: String): CommandResult = CommandResult(replyText = reply)

        /** 构造一个 `/steer` 结果，[instruction] 将作为用户消息发送给当前会话。 */
        fun steer(instruction: String): CommandResult = CommandResult(
            replyText = "已提交补充指令，Agent 会在下一步读取。",
            isSteer = true,
            steerText = instruction,
        )

        /** 构造一个媒体发送命令。成功/失败提示由执行方回填。 */
        fun media(action: MediaAction): CommandResult =
            CommandResult(replyText = "", mediaAction = action)
    }
}

/**
 * IM 机器人命令处理器。
 *
 * 解析以 `/` 开头的 IM 消息为机器人命令，执行对应操作，返回 [CommandResult]。
 * 命令不会触发 AI 回复（除了 `/steer`），结果直接通过 IM 渠道回复给用户。
 *
 * 支持的命令：
 *  - `/help`         显示支持的命令列表
 *  - `/new`          解除当前聊天的会话绑定，下一条消息开启新会话
 *  - `/status`       检查当前机器人连接状态
 *  - `/models`       列出可用模型
 *  - `/model`        查看或切换当前模型
 *  - `/presetlist`   列出可用的 Agent Preset（System Prompt）
 *  - `/preset`       设置 Agent Preset（System Prompt）
 *  - `/stop`         停止当前任务
 *  - `/steer`        补充指令
 *  - `/compact`      压缩上下文
 *  - `/workspace`    切换工作区（Lxchat 中暂不支持，返回提示）
 *  - `/workspacelist` 列出工作区（Lxchat 中暂不支持，返回提示）
 *  - `/sessionlist`  列出会话
 *  - `/session`      绑定会话
 *
 * 命令名称不区分大小写，支持参数（如 `/model 2`、`/workspace /path/to/dir`）。
 * 未知命令返回帮助信息。
 *
 * @param conversationRepository  会话仓库，用于列出/绑定 Lxchat 会话。
 * @param settings                设置仓库，用于查询/设置模型与 System Prompt。
 * @param store                   IM 网关持久化仓库，用于读写运行时状态。
 * @param stopGeneration          停止指定 Lxchat 会话的当前生成；返回是否成功。
 *                                为 null 时 `/stop` 回退为提示信息。
 * @param compactContext          压缩指定 Lxchat 会话的上下文；返回结果描述文本。
 *                                为 null 时 `/compact` 回退为提示信息。
 */
class ImCommandProcessor(
    private val conversationRepository: ConversationRepository,
    private val settings: SettingsRepository,
    private val store: ImGatewayStore,
    private val stopGeneration: (suspend (lxchatConversationId: String) -> Boolean)? = null,
    private val compactContext: (suspend (lxchatConversationId: String) -> String)? = null,
) {

    // ── 公开入口 ──────────────────────────────────────────────

    /**
     * 判断 [text] 是否是一条机器人命令（以 `/` 开头，且不是纯 `/`）。
     */
    fun isCommand(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.startsWith("/") && trimmed.length > 1
    }

    /**
     * 解析并执行 [text] 中的机器人命令。
     *
     * @param text              原始消息文本。
     * @param channelKey        IM 渠道 key（[ImGatewayConfig.effectiveChannelId]）。
     * @param imConversationId  IM 侧会话 id（用于查找/解除绑定）。
     * @return                  命令执行结果；如果 [text] 不是命令则返回 null。
     */
    suspend fun process(
        text: String,
        channelKey: String,
        imConversationId: String,
    ): CommandResult? {
        if (!isCommand(text)) return null
        val parsed = parse(text) ?: return CommandResult.text(helpText())
        DebugLog.d(TAG, "command: /${parsed.name} args=${parsed.args}")
        return dispatch(parsed, channelKey, imConversationId)
    }

    // ── 命令解析 ──────────────────────────────────────────────

    /**
     * 解析后的命令。
     *
     * @property name  小写命令名（不含 `/`）。
     * @property args  原始参数字符串（已 trim，可能为空）。
     */
    private data class ParsedCommand(val name: String, val args: String)

    /**
     * 将 [text] 解析为 [ParsedCommand]。
     *
     * 规则：
     *  - 去除前导/尾随空白。
     *  - 以 `/` 开头，紧随的 token 为命令名（转小写）。
     *  - 其余部分为参数（原样保留，仅 trim）。
     *  - 仅 `/` 或空白返回 null。
     */
    private fun parse(text: String): ParsedCommand? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/") || trimmed.length <= 1) return null
        // 去掉前导 '/'
        val body = trimmed.substring(1)
        // 按第一个空白拆分命令名和参数
        val spaceIdx = body.indexOfFirst { it.isWhitespace() }
        return if (spaceIdx < 0) {
            ParsedCommand(name = body.lowercase(), args = "")
        } else {
            ParsedCommand(
                name = body.substring(0, spaceIdx).lowercase(),
                args = body.substring(spaceIdx).trim(),
            )
        }
    }

    // ── 命令路由 ──────────────────────────────────────────────

    private suspend fun dispatch(
        cmd: ParsedCommand,
        channelKey: String,
        imConversationId: String,
    ): CommandResult = when (cmd.name) {
        "help" -> cmdHelp()
        "new" -> cmdNew(channelKey, imConversationId)
        "status" -> cmdStatus(channelKey)
        "models" -> cmdModels()
        "model" -> cmdModel(cmd.args, channelKey)
        "presetlist" -> cmdPresetList()
        "preset" -> cmdPreset(cmd.args)
        "stop" -> cmdStop(channelKey, imConversationId)
        "steer" -> cmdSteer(cmd.args)
        "compact" -> cmdCompact(channelKey, imConversationId)
        "workspace" -> cmdWorkspace(cmd.args)
        "workspacelist" -> cmdWorkspaceList()
        "sessionlist" -> cmdSessionList()
        "session" -> cmdSession(cmd.args, channelKey, imConversationId)
        "ai" -> cmdAi(cmd.args, channelKey, imConversationId)
        "sendimage" -> cmdSendImage(cmd.args)
        "sendfile" -> cmdSendFile(cmd.args)
        "forward" -> cmdForward(cmd.args)
        else -> {
            DebugLog.d(TAG, "unknown command: /${cmd.name}")
            CommandResult.text("未知命令：/${cmd.name}\n\n${helpText()}")
        }
    }

    // ── 各命令实现 ────────────────────────────────────────────

    /** `/help` — 显示支持的命令列表。 */
    private fun cmdHelp(): CommandResult = CommandResult.text(helpText())

    /**
     * `/new` — 解除当前 IM 会话与 Lxchat 会话的绑定。
     *
     * 下一条普通消息到达时，[ImPollingReceiver] 会创建新的 Lxchat 会话。
     */
    private suspend fun cmdNew(channelKey: String, imConversationId: String): CommandResult {
        val state = channelState(channelKey)
        val bound = state.conversationBindings[imConversationId]
        if (bound == null) {
            return CommandResult.text("当前聊天没有绑定的会话，直接发送消息即可开启新会话。")
        }
        store.updateChannelState(channelKey) { s ->
            s.copy(conversationBindings = s.conversationBindings - imConversationId)
        }
        DebugLog.i(TAG, "/new: unbound IM conv=$imConversationId from Lxchat conv=$bound")
        return CommandResult(
            replyText = "已解除当前会话绑定。下一条消息将开启新会话。",
            requiresNewSession = true,
        )
    }

    /** `/status` — 检查当前机器人连接状态。 */
    private suspend fun cmdStatus(channelKey: String): CommandResult {
        val config = configForChannel(channelKey)
        val state = channelState(channelKey)
        val lines = mutableListOf<String>()
        lines += "机器人状态："
        if (config == null) {
            lines += "- 配置：未找到"
        } else {
            lines += "- 平台：${config.platform}"
            lines += "- 启用：${if (config.enabled) "是" else "否"}"
            lines += "- 已配置：${if (config.isConfigured) "是" else "否"}"
            lines += "- Base URL：${config.baseUrl.ifBlank { "（空）" }}"
            lines += "- 自动回复模型：${config.autoReplyModel.ifBlank { "跟随默认" }}"
            lines += "- Agent Preset：${config.agentPreset.ifBlank { "跟随默认" }}"
        }
        val bindingCount = state.conversationBindings.size
        lines += "- 绑定会话数：$bindingCount"
        lines += "- 已记录消息数：${state.seenMessageIds.size}"
        return CommandResult.text(lines.joinToString("\n"))
    }

    /** `/models` — 列出可用模型。 */
    private suspend fun cmdModels(): CommandResult {
        val available = settings.availableModels.value
        val selected = settings.selectedModel.value
        if (available.isEmpty()) {
            return CommandResult.text("当前没有可用模型。\n\n请在设置中配置模型提供方。")
        }
        val lines = mutableListOf<String>()
        lines += "可用模型："
        var index = 0
        for ((provider, models) in available) {
            if (models.isEmpty()) continue
            lines += ""
            lines += provider
            for (model in models) {
                index++
                val marker = if (model == selected) "（当前）" else ""
                lines += "$index. $model$marker"
            }
        }
        if (index == 0) {
            return CommandResult.text("当前没有可用模型。\n\n请在设置中配置模型提供方。")
        }
        lines += ""
        lines += "切换模型：/model <序号>"
        return CommandResult.text(lines.joinToString("\n"))
    }

    /**
     * `/model` — 查看或切换当前模型。
     *
     * - 无参数：显示当前模型（含本机器人的自动回复模型，如有设置）。
     * - 数字参数：按 `/models` 列表序号切换。
     * - 其他参数：按模型 id 切换。
     *
     * 切换时除全局默认模型（selectedModel）外，还会把目标写入当前渠道的
     * autoReplyModel：IM 自动回复的模型解析优先级是
     * autoReplyModel > 会话 modelId > 全局默认（见 TaskExecutionEngine），
     * 只写全局默认时，已绑定会话可能仍用旧模型。
     */
    private suspend fun cmdModel(args: String, channelKey: String): CommandResult {
        val available = settings.availableModels.value
        val selected = settings.selectedModel.value
        if (args.isBlank()) {
            val autoReply = configForChannel(channelKey)?.autoReplyModel.orEmpty()
            val lines = mutableListOf<String>()
            lines += "当前模型："
            lines += selected.ifBlank { "（未选择）" }
            if (autoReply.isNotBlank()) {
                lines += "自动回复模型：$autoReply"
            }
            lines += ""
            lines += "查看全部模型：/models"
            lines += "切换模型：/model <序号>"
            return CommandResult.text(lines.joinToString("\n"))
        }
        // 构建扁平的模型列表（与 /models 显示顺序一致）
        val flat = available.values.flatten()
        val target = resolveModelTarget(args, flat)
        if (target == null) {
            return CommandResult.text(
                "模型序号或 ID 无效：$args\n\n请发送 /models 查看可用模型。"
            )
        }
        settings.setSelectedModel(target)
        // IM auto-replies resolve the model as autoReplyModel > conversation.modelId
        // > selectedModel (see TaskExecutionEngine.runOnceLocked), so updating only
        // the global default would not affect bound conversations that already pin
        // a model. Also write the target into this channel's autoReplyModel (the
        // highest priority) so the switch takes effect on the very next message.
        val appliedToChannel = updateChannelAutoReplyModel(channelKey, target)
        DebugLog.i(TAG, "/model: switched to $target (autoReplyModel applied=$appliedToChannel)")
        return if (appliedToChannel) {
            CommandResult.text(
                "模型已切换为：\n$target\n\n已更新本机器人的自动回复模型，后续消息将使用该模型。"
            )
        } else {
            CommandResult.text(
                "模型已切换为：\n$target\n\n已更新全局默认模型，后续消息将按全局默认模型回复。"
            )
        }
    }

    /**
     * 将参数解析为目标模型 id。
     *
     * - 纯数字：按 1-based 序号在 [flatList] 中查找。
     * - 其他：直接作为模型 id，需在 [flatList] 中存在。
     */
    private fun resolveModelTarget(args: String, flatList: List<String>): String? {
        val trimmed = args.trim()
        val asIndex = trimmed.toIntOrNull()
        if (asIndex != null) {
            if (asIndex < 1 || asIndex > flatList.size) return null
            return flatList[asIndex - 1]
        }
        return flatList.firstOrNull { it == trimmed }
    }

    /** `/presetlist` — 列出可用的 Agent Preset（System Prompt）。 */
    private suspend fun cmdPresetList(): CommandResult {
        val prompts = settings.systemPrompts.value
        val activeId = settings.activeSystemPromptId.value
        if (prompts.isEmpty()) {
            return CommandResult.text("当前没有可用的 Agent Preset。")
        }
        val lines = mutableListOf<String>()
        lines += "可用 Agent Preset（${prompts.size}）："
        prompts.forEachIndexed { idx, prompt ->
            val marker = if (prompt.id == activeId) "（当前）" else ""
            lines += "${idx + 1}. ${prompt.title}$marker"
            lines += "   ID: ${prompt.id}"
        }
        lines += ""
        lines += "选择：/preset <序号或 ID>"
        return CommandResult.text(lines.joinToString("\n"))
    }

    /**
     * `/preset` — 设置 Agent Preset（System Prompt）。
     *
     * - 无参数：显示当前设置。
     * - 数字参数：按 `/presetlist` 序号选择。
     * - 其他参数：按 System Prompt ID 选择。
     */
    private suspend fun cmdPreset(args: String): CommandResult {
        val prompts = settings.systemPrompts.value
        val activeId = settings.activeSystemPromptId.value
        if (args.isBlank()) {
            val current = prompts.firstOrNull { it.id == activeId }
            val lines = mutableListOf<String>()
            lines += "当前 Agent Preset："
            lines += current?.title ?: "（未设置）"
            if (current != null) lines += "ID: ${current.id}"
            lines += ""
            lines += "查看可用项：/presetlist"
            lines += "选择：/preset <序号或 ID>"
            return CommandResult.text(lines.joinToString("\n"))
        }
        if (prompts.isEmpty()) {
            return CommandResult.text("当前没有可用的 Agent Preset。")
        }
        val targetId = resolvePresetTarget(args, prompts)
        if (targetId == null) {
            return CommandResult.text(
                "Agent Preset 序号或 ID 无效：$args\n\n请发送 /presetlist 查看可用项。"
            )
        }
        settings.setActiveSystemPrompt(targetId)
        val title = prompts.firstOrNull { it.id == targetId }?.title ?: targetId
        DebugLog.i(TAG, "/preset: switched to $title ($targetId)")
        return CommandResult.text(
            "Agent Preset 已设置为：\n$title\n\n后续新会话将使用该 Preset。"
        )
    }

    /** 将参数解析为目标 System Prompt ID。 */
    private fun resolvePresetTarget(
        args: String,
        prompts: List<com.lxseek.chat.data.SystemPromptEntry>,
    ): String? {
        val trimmed = args.trim()
        val asIndex = trimmed.toIntOrNull()
        if (asIndex != null) {
            if (asIndex < 1 || asIndex > prompts.size) return null
            return prompts[asIndex - 1].id
        }
        return prompts.firstOrNull { it.id == trimmed }?.id
    }

    /**
     * `/stop` — 停止当前会话的生成任务。
     *
     * 需要 [stopGeneration] 回调；未注入时返回提示信息。
     */
    private suspend fun cmdStop(channelKey: String, imConversationId: String): CommandResult {
        val lxchatConvId = boundLxchatConversation(channelKey, imConversationId)
        if (lxchatConvId == null) {
            return CommandResult.text("当前聊天没有正在运行的任务。")
        }
        val stop = stopGeneration
        if (stop == null) {
            return CommandResult.text("当前机器人暂不支持停止任务。")
        }
        val ok = try {
            stop(lxchatConvId)
        } catch (e: Exception) {
            DebugLog.e(TAG, "/stop failed", e)
            false
        }
        return if (ok) {
            CommandResult.text("已请求停止当前任务。")
        } else {
            CommandResult.text("当前聊天没有正在运行的任务。")
        }
    }

    /**
     * `/steer` — 补充指令。
     *
     * 将指令文本作为用户消息发送给当前会话，触发 AI 回复。
     * 无参数时返回用法提示。
     */
    private fun cmdSteer(args: String): CommandResult {
        val instruction = args.trim()
        if (instruction.isEmpty()) {
            return CommandResult.text("用法：/steer <补充指令>")
        }
        return CommandResult.steer(instruction)
    }

    /**
     * `/compact` — 压缩上下文。
     *
     * 需要 [compactContext] 回调；未注入时返回提示信息。
     */
    private suspend fun cmdCompact(channelKey: String, imConversationId: String): CommandResult {
        val lxchatConvId = boundLxchatConversation(channelKey, imConversationId)
        if (lxchatConvId == null) {
            return CommandResult.text("当前聊天还没有可压缩的会话，请先发送一条消息。")
        }
        val compact = compactContext
        if (compact == null) {
            return CommandResult.text("当前机器人暂不支持上下文压缩。")
        }
        val result = try {
            compact(lxchatConvId)
        } catch (e: Exception) {
            DebugLog.e(TAG, "/compact failed", e)
            "上下文压缩失败，请稍后重试。"
        }
        return CommandResult.text(result)
    }

    /**
     * `/workspace` — 切换工作区。
     *
     * Lxchat 是单工作区应用，暂不支持多工作区切换。
     */
    private fun cmdWorkspace(args: String): CommandResult {
        if (args.isBlank()) {
            return CommandResult.text("用法：/workspace <工作区路径>\n\n提示：Lxchat 当前为单工作区模式。")
        }
        return CommandResult.text("Lxchat 当前为单工作区模式，暂不支持切换工作区。")
    }

    /** `/workspacelist` — 列出工作区。 */
    private fun cmdWorkspaceList(): CommandResult {
        return CommandResult.text("Lxchat 当前为单工作区模式，仅有一个默认工作区。")
    }

    /**
     * `/sessionlist` — 列出 Lxchat 会话。
     *
     * 列出当前所有 IM 渠道中已绑定的会话，供 `/session` 绑定参考。
     * Lxchat 的全部历史会话可在应用内 UI 查看。
     */
    private suspend fun cmdSessionList(): CommandResult {
        // ConversationRepository 没有公开的 "list all" 方法；
        // 我们通过 ImRuntimeState 中的绑定来展示已绑定的会话。
        // 同时提示用户可以使用应用内 UI 查看全部会话。
        val multiState = store.multiRuntimeState.first()
        val legacyState = store.runtimeState.first()
        val allBindings = mutableMapOf<String, String>()
        multiState.values.forEach { state ->
            state.conversationBindings.forEach { (imId, lxId) ->
                allBindings[imId] = lxId
            }
        }
        legacyState.conversationBindings.forEach { (imId, lxId) ->
            allBindings.putIfAbsent(imId, lxId)
        }
        val lines = mutableListOf<String>()
        if (allBindings.isEmpty()) {
            lines += "当前没有已绑定的会话。"
            lines += ""
            lines += "提示：发送普通消息会自动创建新会话。"
            lines += "在 Lxchat 应用内可查看全部历史会话。"
        } else {
            lines += "已绑定的会话（${allBindings.size}）："
            allBindings.entries.forEachIndexed { idx, (imId, lxId) ->
                lines += "${idx + 1}. IM: $imId"
                lines += "   Lxchat: $lxId"
            }
            lines += ""
            lines += "绑定用法：/session <Lxchat 会话 ID>"
        }
        return CommandResult.text(lines.joinToString("\n"))
    }

    /**
     * `/session` — 绑定会话。
     *
     * 将当前 IM 会话绑定到指定的 Lxchat 会话。
     * 参数为 Lxchat 会话 ID（UUID 格式）。
     */
    private suspend fun cmdSession(
        args: String,
        channelKey: String,
        imConversationId: String,
    ): CommandResult {
        val sessionId = args.trim()
        if (sessionId.isEmpty()) {
            return CommandResult.text("用法：/session <Lxchat 会话 ID>")
        }
        // 验证会话是否存在
        val conv = conversationRepository.getConversation(sessionId)
        if (conv == null) {
            return CommandResult.text("未找到该会话：$sessionId\n\n请确认会话 ID 是否正确。")
        }
        // 更新绑定
        store.updateChannelState(channelKey) { s ->
            s.copy(conversationBindings = s.conversationBindings + (imConversationId to sessionId))
        }
        DebugLog.i(TAG, "/session: bound IM conv=$imConversationId to Lxchat conv=$sessionId")
        val title = conv.title
        return CommandResult.text(
            "当前聊天已绑定会话：\n标题：$title\nID：$sessionId"
        )
    }

    // ── 辅助方法 ──────────────────────────────────────────────

    /**
     * `/ai` — 查看/开启/关闭当前好友的自动 AI 回复。
     *
     * 对齐 Zyn-iLink 的 is_ai_enabled_for_user。关闭后该好友的普通消息不再触发
     * AI 回复，但命令（含本命令）仍会被响应，便于随时恢复。
     */
    private suspend fun cmdAi(
        args: String,
        channelKey: String,
        imConversationId: String,
    ): CommandResult {
        val disabled = channelState(channelKey).aiDisabledContacts.contains(imConversationId)
        val arg = args.trim().lowercase()
        return when (arg) {
            "" -> CommandResult.text(
                if (disabled) "当前好友已关闭自动回复。（/ai on 恢复）"
                else "当前好友自动回复：开启。（/ai off 关闭）",
            )
            "on", "enable" -> {
                store.updateChannelState(channelKey) { s ->
                    s.copy(aiDisabledContacts = s.aiDisabledContacts - imConversationId)
                }
                DebugLog.i(TAG, "/ai on: enabled auto-reply for IM conv=$imConversationId")
                CommandResult.text("已开启当前好友的自动 AI 回复。")
            }
            "off", "disable" -> {
                store.updateChannelState(channelKey) { s ->
                    s.copy(aiDisabledContacts = s.aiDisabledContacts + imConversationId)
                }
                DebugLog.i(TAG, "/ai off: disabled auto-reply for IM conv=$imConversationId")
                CommandResult.text("已关闭当前好友的自动 AI 回复。想恢复请输入 /ai on。")
            }
            else -> CommandResult.text("用法：/ai [on|off|status]")
        }
    }

    /**
     * `/sendimage <URL>` — 下载 URL 中的图片并发送到当前会话。
     * 仅微信 iLink 渠道支持；其余渠道由执行方回提示。
     */
    private fun cmdSendImage(args: String): CommandResult {
        val url = args.trim()
        if (url.isEmpty()) return CommandResult.text("用法：/sendimage <图片URL>")
        // 仅允许 https 直接下载；其余由执行方统一处理。
        return CommandResult.media(CommandResult.MediaAction.SendImage(url))
    }

    /**
     * `/sendfile <URL>` — 下载 URL 中的文件并发送到当前会话。
     * 仅微信 iLink 渠道支持；其余渠道由执行方回提示。
     */
    private fun cmdSendFile(args: String): CommandResult {
        val url = args.trim()
        if (url.isEmpty()) return CommandResult.text("用法：/sendfile <文件URL>")
        return CommandResult.media(CommandResult.MediaAction.SendFile(url))
    }

    /**
     * `/forward [名称]` — 转发之前收到的媒体到当前会话。
     * 无参数时列出可选转发项；仅微信 iLink 渠道支持。
     */
    private fun cmdForward(args: String): CommandResult {
        val name = args.trim()
        return CommandResult.media(CommandResult.MediaAction.Forward(name))
    }

    /** 读取 [channelKey] 的运行时状态（多渠道优先，回退到 legacy）。 */
    private suspend fun channelState(channelKey: String): ImRuntimeState {
        val multi = store.multiRuntimeState.first()
        multi[channelKey]?.let { return it }
        val legacy = store.runtimeState.first()
        return if (legacy.channelId.isBlank() && (legacy.platform == channelKey || legacy.platform.isBlank())) {
            legacy
        } else {
            ImRuntimeState(channelId = channelKey, platform = channelKey)
        }
    }

    /** 解析 [channelKey] 对应的 [ImGatewayConfig]（多渠道优先，回退到 legacy）。 */
    private suspend fun configForChannel(channelKey: String): ImGatewayConfig? {
        val multi = store.multiConfig.first()
        multi.all.firstOrNull { it.effectiveChannelId == channelKey }?.let { return it }
        val legacy = store.config.first()
        return if (legacy.effectiveChannelId == channelKey) legacy else null
    }

    /**
     * Write [model] into [channelKey]'s autoReplyModel, mirroring
     * [configForChannel]'s read priority (multi-config first, legacy fallback).
     * Returns false when no persisted config backs this channel.
     */
    private suspend fun updateChannelAutoReplyModel(channelKey: String, model: String): Boolean {
        val multi = store.multiConfig.first()
        multi.all.firstOrNull { it.effectiveChannelId == channelKey }?.let { bot ->
            if (bot.autoReplyModel != model) {
                store.upsertBot(bot.copy(autoReplyModel = model))
            }
            return true
        }
        val legacy = store.config.first()
        if (legacy.effectiveChannelId == channelKey) {
            if (legacy.autoReplyModel != model) {
                store.save(legacy.copy(autoReplyModel = model))
            }
            return true
        }
        return false
    }

    /** 查找 IM 会话绑定的 Lxchat 会话 ID。 */
    private suspend fun boundLxchatConversation(
        channelKey: String,
        imConversationId: String,
    ): String? = channelState(channelKey).conversationBindings[imConversationId]

    /** 生成 `/help` 帮助文本。 */
    private fun helpText(): String = HELP_TEXT

    private companion object {
        const val TAG = "ImCmd"


        val HELP_TEXT = buildString {
            appendLine("支持的命令：")
            appendLine()
            appendLine("/help                显示本帮助信息")
            appendLine("/new                 解除当前会话绑定，下一条消息开启新会话")
            appendLine("/status              查看机器人连接状态")
            appendLine("/models              列出可用模型")
            appendLine("/model [序号|ID]     查看或切换当前模型")
            appendLine("/presetlist          列出可用的 Agent Preset")
            appendLine("/preset [序号|ID]    查看或设置 Agent Preset")
            appendLine("/stop                停止当前任务")
            appendLine("/steer <指令>        向当前任务补充指令")
            appendLine("/compact             压缩当前会话上下文")
            appendLine("/workspace <路径>    切换工作区（暂不支持）")
            appendLine("/workspacelist       列出工作区（暂不支持）")
            appendLine("/sessionlist         列出已绑定的会话")
            appendLine("/session <会话ID>    绑定到指定 Lxchat 会话")
            appendLine("/ai [on|off|status]  查看或开启/关闭当前好友的自动回复")
            appendLine("/sendimage <URL>    下载图片并发送（微信）")
            appendLine("/sendfile <URL>     下载文件并发送（微信）")
            appendLine("/forward [名称]     转发已收到的媒体（微信）")
            appendLine()
            appendLine("命令名称不区分大小写。")
        }.trimEnd()
    }
}