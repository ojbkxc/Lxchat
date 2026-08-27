package com.lxseek.chat.plugin.market

import com.lxseek.chat.plugin.Plugin
import com.lxseek.chat.plugin.PluginCategory
import com.lxseek.chat.plugin.PluginContext
import com.lxseek.chat.plugin.PluginManifest
import com.lxseek.chat.skill.Skill
import com.lxseek.chat.tool.ToolProvider

/**
 * 市场插件的通用包装：按安装记录构建统一 [Plugin] 契约。
 *
 * - SKILL 插件：通过 [skills] 暴露技能；
 * - MCP 插件：通过 [providers] 暴露工具，[onEnable]/[onDisable] 桥接到
 *   [ScopedMcpToolProvider.start]/[close] 的生命周期。
 */
class MarketPlugin(
    private val installation: MarketInstallation,
    private val providers: List<ToolProvider> = emptyList(),
    private val skills: List<Skill> = emptyList(),
    private val onEnableAction: (() -> Unit)? = null,
    private val onDisableAction: (() -> Unit)? = null,
) : Plugin {
    override val manifest = PluginManifest(
        id = installation.pluginId,
        name = installation.name,
        version = installation.version,
        category = PluginCategory.External,
        description = installation.description,
        author = installation.author,
        requiresMembership = installation.requiresMembership,
        builtIn = false,
    )

    override fun toolProviders(context: PluginContext): List<ToolProvider> = providers

    override fun skills(): List<Skill> = skills

    override fun onEnable(context: PluginContext) {
        onEnableAction?.invoke()
    }

    override fun onDisable() {
        onDisableAction?.invoke()
    }
}
