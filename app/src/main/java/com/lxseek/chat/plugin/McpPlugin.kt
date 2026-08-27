package com.lxseek.chat.plugin

import com.lxseek.chat.tool.McpToolProvider
import com.lxseek.chat.tool.ToolProvider

/** 将既有 MCP 能力包装为插件，纳入统一插件生态。 */
class McpPlugin(
    private val provider: McpToolProvider,
) : Plugin {
    override val manifest = PluginManifest(
        id = "builtin.mcp",
        name = "MCP 插件",
        version = "1.0",
        category = PluginCategory.Mcp,
        description = "通过 MCP 协议接入任意远程工具服务",
        builtIn = true,
    )

    override fun toolProviders(context: PluginContext): List<ToolProvider> = listOf(provider)
}
