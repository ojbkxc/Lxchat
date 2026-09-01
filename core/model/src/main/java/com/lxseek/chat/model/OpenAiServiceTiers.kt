package com.lxseek.chat.model

object OpenAiServiceTiers {
    const val AUTO = "auto"
    const val DEFAULT = "default"
    const val FLEX = "flex"
    const val FAST = "fast"

    val values = listOf(AUTO, DEFAULT, FLEX, FAST)

    fun normalize(value: String?): String =
        value?.trim()?.lowercase()?.takeIf { it in values } ?: AUTO

    fun indexForTier(value: String?): Int = values.indexOf(normalize(value))

    fun tierForIndex(index: Int): String = values[index.coerceIn(values.indices)]

    fun requestValue(enabled: Boolean, value: String?): String? =
        normalize(value).takeIf { enabled }
}
