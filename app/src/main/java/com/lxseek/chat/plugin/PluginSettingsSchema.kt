package com.lxseek.chat.plugin

import kotlinx.serialization.Serializable

/**
 * Schema describing a plugin's settings fields, used to auto-generate UI.
 * Eliminates the need for hardcoded settings pages per plugin.
 *
 * A [Plugin] returns this from [Plugin.settingsSchema]; the generic
 * [com.lxseek.chat.ui.settings.SettingsPluginPage] renders the fields
 * without any plugin-specific Compose code.
 */
@Serializable
data class PluginSettingsSchema(
    val fields: List<SettingsField>
)

@Serializable
data class SettingsField(
    /** Settings map key used to persist this value. */
    val key: String,
    /** Human-readable display label. */
    val label: String,
    /** Widget type to render. */
    val type: FieldType,
    /** Default value as a string (parsed by the consumer). */
    val defaultValue: String? = null,
    /** Optional help text shown below the field. */
    val description: String? = null,
    /** Options for [FieldType.DROPDOWN]. */
    val options: List<String> = emptyList(),
    /** True if the field must be non-empty before the plugin can enable. */
    val required: Boolean = false,
    /** Placeholder text for input fields. */
    val placeholder: String? = null,
)

/** Widget types supported by the generic settings renderer. */
enum class FieldType {
    /** Boolean toggle switch. */
    SWITCH,
    /** Single-line text input. */
    TEXT_INPUT,
    /** Masked text input for secrets. */
    PASSWORD,
    /** Single-select dropdown from [SettingsField.options]. */
    DROPDOWN,
    /** Numeric input. */
    NUMBER,
    /** Range slider (min/max derived from options or separate fields). */
    SLIDER,
}