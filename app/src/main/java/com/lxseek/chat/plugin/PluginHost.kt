package com.lxseek.chat.plugin

import com.lxseek.chat.tool.ToolProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程级插件宿主。维护插件注册表、启用状态与聚合后的工具提供者列表。
 *
 * 生成管线只消费 [toolProviders] 聚合结果，因此：
 * - 新增插件 = register() 一个 Plugin，管线零改动；
 * - 会员门禁 = 未来在 [toolProviders] 出口按 manifest.requiresMembership 过滤，不侵入任何插件。
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
    private val _plugins = MutableStateFlow<List<PluginInfo>>(emptyList())

    /** 当前插件列表快照（供设置页展示）。 */
    val plugins: StateFlow<List<PluginInfo>> = _plugins.asStateFlow()

    fun register(plugin: Plugin, initiallyEnabled: Boolean = true) {
        val id = plugin.manifest.id
        if (registered.putIfAbsent(id, plugin) != null) return
        enabled[id] = initiallyEnabled
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
