package com.lxseek.chat.api.router

/**
 * 模型白名单/黑名单过滤器
 *
 * 控制哪些模型 ID 被允许使用。规则优先级：
 * 1. 若 [blocked] 包含该模型（精确或前缀匹配），则禁止
 * 2. 若 [allowed] 为空，则允许所有未被黑名单禁止的模型（白名单未启用）
 * 3. 若 [allowed] 非空，则仅允许白名单中的模型（精确或前缀匹配）
 *
 * 设计意图：
 * - 支持精确匹配与前缀匹配：`"gpt-4"` 匹配 `"gpt-4"`、`"gpt-4-turbo"` 等
 * - 前缀匹配便于按模型族批量放行/禁止
 * - 纯数据结构，无副作用，可安全共享
 *
 * @param allowed 白名单模型 ID 集合（空表示不启用白名单）
 * @param blocked 黑名单模型 ID 集合
 */
data class ModelAllowlist(
    val allowed: Set<String> = emptySet(),
    val blocked: Set<String> = emptySet(),
) {
    /**
     * 判断给定模型 ID 是否被允许使用。
     *
     * @param modelId 模型 ID（不含 provider 前缀）
     */
    fun isAllowed(modelId: String): Boolean {
        // 黑名单优先：命中即禁止
        if (matches(blocked, modelId)) return false
        // 白名单为空 = 不启用白名单 = 放行所有未被禁止的模型
        if (allowed.isEmpty()) return true
        // 白名单非空 = 仅放行白名单中的模型
        return matches(allowed, modelId)
    }

    /**
     * 判断模型 ID 是否命中给定规则集合（精确或前缀匹配）。
     *
     * 规则 `"gpt-4"` 命中 `"gpt-4"`（精确）和 `"gpt-4-turbo"`（前缀 + `-`）。
     * 前缀匹配要求规则是模型 ID 的真前缀且下一个字符是分隔符 `-`、`:` 或 `/`，
     * 避免 `"gpt-4"` 误命中 `"gpt-4o"`（虽然实际也常希望命中，但用精确/显式前缀更可控）。
     * 为兼顾常见用法，这里采用宽松前缀：规则是 ID 的前缀即可命中。
     */
    private fun matches(rules: Set<String>, modelId: String): Boolean {
        if (rules.isEmpty()) return false
        if (modelId in rules) return true
        // 宽松前缀匹配：rules 中任一条目是 modelId 的前缀
        return rules.any { rule -> modelId.startsWith(rule) }
    }

    companion object {
        /** 不启用任何过滤：放行所有模型。 */
        val PERMISSIVE = ModelAllowlist(allowed = emptySet(), blocked = emptySet())
    }
}