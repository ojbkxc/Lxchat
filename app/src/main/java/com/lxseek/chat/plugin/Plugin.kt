package com.lxseek.chat.plugin

import com.lxseek.chat.tool.ToolProvider

/** 插件归属分类。 */
enum class PluginCategory {
    /** 应用内置能力（原生工具集）。 */
    Integrated,

    /** MCP 协议插件。 */
    Mcp,

    /** 第三方市场插件（Claude 插件、Operit 等）。 */
    External,
}

/** 插件元数据：身份、展示信息与能力门禁标记。 */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val category: PluginCategory,
    val description: String? = null,
    val author: String? = null,
    /** true 表示该插件属于会员（高级）能力，由能力门禁层拦截。 */
    val requiresMembership: Boolean = false,
    /** true 表示随 APK 内置，false 表示外部/动态加载。 */
    val builtIn: Boolean = true,
    /** 设置项开关 key，用于持久化启用状态（null 表示始终启用）。 */
    val preferenceKey: String? = null,
)

/**
 * 统一插件契约。任何能力（原生工具、MCP 服务、Claude 插件、第三方市场插件）都收敛为
 * 一个 [Plugin]，由 [PluginHost] 统一管理生命周期与启用状态。
 *
 * 会员体系落地时，能力门禁（CapabilityGate）只需在 [PluginHost] 出口按
 * [PluginManifest.requiresMembership] 做拦截，无需改动任何单个插件实现——
 * 这正是"不影响现有架构"的关键。
 */
interface Plugin {
    val manifest: PluginManifest

    /** 该插件暴露给生成管线的工具提供者。 */
    fun toolProviders(context: PluginContext): List<ToolProvider> = emptyList()

    /** Settings schema for auto-generating settings UI. Override to provide fields.
     *  Returns null when the plugin has no configurable settings (default). */
    fun settingsSchema(): PluginSettingsSchema? = null

    fun onEnable(context: PluginContext) {}

    fun onDisable() {}
}
