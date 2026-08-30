package com.lxseek.chat.membership

import android.content.Context

/**
 * AI capability categories that can be bound to a specific model.
 * Each function may either follow the global main model or use an independent model.
 */
enum class FunctionType(val label: String, val desc: String) {
    CHAT("主对话", "主聊天模型，跟随主模型配置"),
    TITLE_GEN("标题生成", "为对话生成标题，建议用轻量模型"),
    SUMMARIZE("上下文总结", "长对话压缩，可用中等模型"),
    VISION("图片识别", "需要支持视觉能力的模型"),
    TRANSCRIPTION("语音转录", "语音转文字模型"),
}

/**
 * Binding for a single [FunctionType].
 * @property useGlobal true = follow the main model, false = use [model].
 * @property model independent model name, used only when [useGlobal] is false.
 */
data class FunctionModelBinding(
    val useGlobal: Boolean = true,
    val model: String = "",
)

/**
 * Persists per-function model bindings via SharedPreferences.
 * Each [FunctionType] stores a useGlobal flag and an optional model name.
 */
class FunctionModelConfigRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Load all function bindings. Missing entries fall back to defaults. */
    fun load(): Map<FunctionType, FunctionModelBinding> =
        FunctionType.values().associateWith { type ->
            FunctionModelBinding(
                useGlobal = prefs.getBoolean(keyUseGlobal(type), true),
                model = prefs.getString(keyModel(type), "").orEmpty(),
            )
        }

    /** Persist a single function binding. */
    fun setBinding(type: FunctionType, binding: FunctionModelBinding) {
        prefs.edit()
            .putBoolean(keyUseGlobal(type), binding.useGlobal)
            .putString(keyModel(type), binding.model)
            .apply()
    }

    /**
     * Resolve the effective model name for [type].
     * Returns [globalModel] when the binding follows the global model or has no model set;
     * otherwise returns the independently bound model name.
     */
    fun resolveConfig(type: FunctionType, globalModel: String): String {
        val binding = load()[type] ?: FunctionModelBinding()
        return if (binding.useGlobal || binding.model.isBlank()) globalModel else binding.model
    }

    private fun keyUseGlobal(type: FunctionType) = "${type.name}_useGlobal"
    private fun keyModel(type: FunctionType) = "${type.name}_model"

    companion object {
        private const val PREFS_NAME = "function_model_config"
    }
}