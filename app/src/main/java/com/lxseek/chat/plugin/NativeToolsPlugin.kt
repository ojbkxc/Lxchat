package com.lxseek.chat.plugin

import com.lxseek.chat.tool.ToolProvider

/** 将应用内置的原生工具集包装为一个整体插件。 */
class NativeToolsPlugin(
    private val providers: List<ToolProvider>,
) : Plugin {
    override val manifest = PluginManifest(
        id = "builtin.native",
        name = "原生工具集",
        version = "1.0",
        category = PluginCategory.Integrated,
        description = "应用内置的设备、IM、Git、提醒、自动化等能力",
        builtIn = true,
    )

    override fun toolProviders(context: PluginContext): List<ToolProvider> = providers
}
