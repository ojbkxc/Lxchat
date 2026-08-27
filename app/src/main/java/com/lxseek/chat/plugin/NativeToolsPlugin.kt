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

    /** Settings schema for the native toolset: shell execution, sandbox isolation,
     *  confirmation gate, shared-storage access, and the automation/task tools.
     *  Keys mirror the DataStore preference keys so the generic settings UI can
     *  read/write the same persisted state as the hand-rolled settings pages. */
    override fun settingsSchema(): PluginSettingsSchema = PluginSettingsSchema(
        fields = listOf(
            SettingsField(
                key = "shell_enabled",
                label = "Shell",
                type = FieldType.SWITCH,
                defaultValue = "true",
                description = "Enable shell command execution",
            ),
            SettingsField(
                key = "sandbox_enabled",
                label = "Sandbox",
                type = FieldType.SWITCH,
                defaultValue = "false",
                description = "Run shell commands in an isolated sandbox",
            ),
            SettingsField(
                key = "shell_confirm_enabled",
                label = "Shell confirmation",
                type = FieldType.SWITCH,
                defaultValue = "true",
                description = "Require confirmation before running shell commands",
            ),
            SettingsField(
                key = "sandbox_shared_storage_enabled",
                label = "Sandbox shared storage",
                type = FieldType.SWITCH,
                defaultValue = "false",
                description = "Allow sandbox to access shared storage",
            ),
            SettingsField(
                key = "automation_tools_enabled",
                label = "Automation tools",
                type = FieldType.SWITCH,
                defaultValue = "true",
                description = "Enable automation/task tools",
            ),
        ),
    )
}
