package com.lxseek.chat.api.router

import com.lxseek.chat.util.DebugLog

/**
 * 基于任务复杂度的模型路由器
 *
 * 根据 [ComplexityLevel] 选择更合适的模型：
 * - [ComplexityLevel.Simple] → 优先选小模型（省 token）
 * - [ComplexityLevel.Medium] → 用主模型（返回 null）
 * - [ComplexityLevel.Complex] → 优先选大模型（更强能力）
 *
 * 模型选择规则（按优先级）：
 * 1. 用户显式配置的 [simpleTaskModel] / [complexTaskModel]（非空时直接采用）；
 * 2. 启发式匹配：若主模型已是目标类型（名字含小/大模型标记），无需切换，返回 null；
 * 3. 启发式推导：按主模型所属家族映射到对应的小/大模型变体；
 * 4. 找不到匹配时回退到主模型（返回 null）。
 *
 * @param simpleTaskModel 简单任务用的小模型（null 或空 = 未配置，走启发式）
 * @param complexTaskModel 复杂任务用的大模型（null 或空 = 未配置，走启发式）
 */
class ComplexityRouter(
    private val simpleTaskModel: String? = null,
    private val complexTaskModel: String? = null,
) {
    private companion object {
        const val TAG = "ComplexityRouter"

        /** 小模型名字标记：模型名包含这些子串视为小模型。 */
        val SMALL_MARKERS = listOf("mini", "flash", "haiku", "small", "lite", "nano")

        /** 大模型名字标记：模型名包含这些子串视为大模型。 */
        val LARGE_MARKERS = listOf("pro", "o1", "o3", "reasoner", "max", "opus", "ultra")

        /**
         * 模型家族映射表：按主模型名前缀匹配，映射到对应的小/大模型变体。
         *
         * 顺序敏感：更具体的前缀（如 claude-3-opus）需排在更宽泛的前缀（如 claude-3）之前。
         */
        val MODEL_FAMILIES = listOf(
            ModelFamily("gpt-4o", smallVariant = "gpt-4o-mini", largeVariant = "gpt-4o"),
            ModelFamily("gpt-4", smallVariant = "gpt-4o-mini", largeVariant = "gpt-4o"),
            ModelFamily("claude-3-5-sonnet", smallVariant = "claude-3-5-haiku", largeVariant = "claude-3-5-sonnet"),
            ModelFamily("claude-3-opus", smallVariant = "claude-3-haiku", largeVariant = "claude-3-opus"),
            ModelFamily("claude-3-sonnet", smallVariant = "claude-3-haiku", largeVariant = "claude-3-opus"),
            ModelFamily("claude-3", smallVariant = "claude-3-haiku", largeVariant = "claude-3-opus"),
            ModelFamily("gemini-1.5", smallVariant = "gemini-1.5-flash", largeVariant = "gemini-1.5-pro"),
            ModelFamily("gemini-2", smallVariant = "gemini-2.0-flash", largeVariant = "gemini-2.0-pro"),
            ModelFamily("gemini", smallVariant = "gemini-1.5-flash", largeVariant = "gemini-1.5-pro"),
            ModelFamily("deepseek", smallVariant = "deepseek-chat", largeVariant = "deepseek-reasoner"),
            ModelFamily("qwen", smallVariant = "qwen-turbo", largeVariant = "qwen-max"),
            ModelFamily("glm-4", smallVariant = "glm-4-flash", largeVariant = "glm-4"),
            ModelFamily("llama", smallVariant = "llama-3.1-8b", largeVariant = "llama-3.1-70b"),
        )
    }

    /**
     * 模型家族：主模型名前缀 → 小/大模型变体。
     *
     * @param prefix 主模型名前缀（大小写不敏感）
     * @param smallVariant 该家族的小模型变体
     * @param largeVariant 该家族的大模型变体
     */
    private data class ModelFamily(
        val prefix: String,
        val smallVariant: String,
        val largeVariant: String,
    )

    /**
     * 根据复杂度选择模型。
     *
     * @param complexity 任务复杂度
     * @param primaryModel 当前主模型 ID
     * @return 选中的模型 ID；null 表示用主模型（无需切换）
     */
    fun routeFor(complexity: ComplexityLevel, primaryModel: String): String? {
        val result = when (complexity) {
            ComplexityLevel.Medium -> null

            ComplexityLevel.Simple -> {
                // 优先用配置的小模型
                simpleTaskModel?.takeIf { it.isNotBlank() }
                    ?: heuristicSmallModel(primaryModel)
            }

            ComplexityLevel.Complex -> {
                // 优先用配置的大模型
                complexTaskModel?.takeIf { it.isNotBlank() }
                    ?: heuristicLargeModel(primaryModel)
            }
        }

        DebugLog.d(
            TAG,
            "复杂度=$complexity, 主模型=$primaryModel, 选中=${result ?: primaryModel}(未切换)",
        )
        return result
    }

    /**
     * 启发式选择小模型。
     *
     * 1. 若主模型已是小模型（含小模型标记）→ null（已合适，无需切换）；
     * 2. 否则按家族映射查小模型变体；
     * 3. 找不到 → null（回退主模型）。
     */
    private fun heuristicSmallModel(primaryModel: String): String? {
        if (isSmallModel(primaryModel)) return null
        return findFamily(primaryModel)?.smallVariant
    }

    /**
     * 启发式选择大模型。
     *
     * 1. 若主模型已是大模型（含大模型标记）→ null（已合适，无需切换）；
     * 2. 否则按家族映射查大模型变体；
     * 3. 找不到 → null（回退主模型）。
     */
    private fun heuristicLargeModel(primaryModel: String): String? {
        if (isLargeModel(primaryModel)) return null
        return findFamily(primaryModel)?.largeVariant
    }

    /** 判断模型名是否含小模型标记。 */
    private fun isSmallModel(modelId: String): Boolean =
        SMALL_MARKERS.any { modelId.contains(it, ignoreCase = true) }

    /** 判断模型名是否含大模型标记。 */
    private fun isLargeModel(modelId: String): Boolean =
        LARGE_MARKERS.any { modelId.contains(it, ignoreCase = true) }

    /** 按模型名前缀查找家族映射（首个匹配项）。 */
    private fun findFamily(modelId: String): ModelFamily? =
        MODEL_FAMILIES.firstOrNull { modelId.contains(it.prefix, ignoreCase = true) }
}