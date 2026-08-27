package com.lxseek.chat.plugin

import com.lxseek.chat.skill.SkillHost
import com.lxseek.chat.tool.ToolProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程级插件宿主。维护插件注册表、启用状态与聚合后的工具提供者列表。
 *
 * A [Plugin] is a multi-capability container: it may expose tools ([Plugin.toolProviders]),
 * skills ([Plugin.skills]) and a settings schema ([Plugin.settingsSchema]). This host
 * aggregates all three:
 * - tools → [toolProviders] (membership-wrapped at the outlet);
 * - skills → [skillHost] (registered/unregistered together with the plugin lifecycle);
 * - settings schema → consumed by the generic settings UI via [Plugin.settingsSchema].
 *
 * 生成管线只消费 [toolProviders] 聚合结果，因此：
 * - 新增插件 = register() 一个 Plugin，管线零改动；
 * - 会员门禁 = 在 [toolProviders] 出口按 manifest.requiresMembership 过滤，不侵入任何插件。
 */
class PluginHost(
    private val context: PluginContext,
) {
    data class PluginInfo(
        val manifest: PluginManifest,
        val enabled: Boolean,
    )

    private val registered = mutableMapOf<String, Plugin>()
    private val enabled = mutableMapOf<String, Boolean>()
    private val enabledProviders = mutableMapOf<String, List<ToolProvider>>()
    /** Tracks the skill names each plugin registered, so [setEnabled] can sync them. */
    private val pluginSkills = mutableMapOf<String, List<String>>()
    private val _plugins = MutableStateFlow<List<PluginInfo>>(emptyList())

    /** 当前插件列表快照（供设置页展示）。 */
    val plugins: StateFlow<List<PluginInfo>> = _plugins.asStateFlow()

    /** Aggregated skill host. Every enabled plugin's skills are registered here,
     *  enabling progressive disclosure + path-conditional activation across the
     *  whole plugin ecosystem. Exposed for the generation pipeline and settings UI. */
    val skillHost: SkillHost = SkillHost()

    fun register(plugin: Plugin, initiallyEnabled: Boolean = true) {
        val id = plugin.manifest.id
        if (registered.putIfAbsent(id, plugin) != null) return
        enabled[id] = initiallyEnabled

        // Register this plugin's skills with the shared SkillHost. The skill enable
        // state follows the plugin's enable state, so toggling a plugin on/off
        // transparently activates/deactivates all its skills.
        val skills = plugin.skills()
        pluginSkills[id] = skills.map { it.name }
        skills.forEach { skillHost.register(it, enabled = initiallyEnabled) }

        if (initiallyEnabled) {
            plugin.onEnable(context)
            enabledProviders[id] = plugin.toolProviders(context)
        }
        refresh()
    }

    fun setEnabled(id: String, on: Boolean) {
        val plugin = registered[id] ?: return
        if (enabled[id] == on) return
        enabled[id] = on

        // Sync skill enable state for this plugin's skills.
        pluginSkills[id]?.forEach { skillHost.setEnabled(it, on) }

        if (on) {
            plugin.onEnable(context)
            enabledProviders[id] = plugin.toolProviders(context)
        } else {
            plugin.onDisable()
            enabledProviders.remove(id)
        }
        refresh()
    }

    fun isEnabled(id: String): Boolean = enabled[id] ?: false

    /**
     * 卸载插件：移除注册、技能、启用状态与工具提供者，并回调 [Plugin.onDisable]。
     * 卸载后无法再通过 [setEnabled] 恢复，需重新 [register]。
     */
    fun unregister(id: String) {
        val plugin = registered.remove(id) ?: return
        enabled.remove(id)
        if (enabledProviders.remove(id) != null) plugin.onDisable()
        pluginSkills.remove(id)?.forEach { skillHost.unregister(it) }
        refresh()
    }

    /**
     * 聚合当前启用插件的全部工具提供者。
     *
     * 会员插件（manifest.requiresMembership = true）的工具被 [MembershipToolProvider] 包装，
     * 把插件级标记下沉到工具级 ToolDescriptor.requiresMembership，由 GenerationToolExecutor 的
     * 披露层（filterByMembership）与执行层（membershipCheck）统一门禁。
     */
    fun toolProviders(): List<ToolProvider> = enabledProviders.entries.flatMap { (id, providers) ->
        val plugin = registered[id]
        if (plugin?.manifest?.requiresMembership == true) {
            providers.map { MembershipToolProvider(it) }
        } else {
            providers
        }
    }

    private fun refresh() {
        _plugins.value = registered.values.map {
            PluginInfo(it.manifest, enabled[it.manifest.id] ?: false)
        }
    }
}
