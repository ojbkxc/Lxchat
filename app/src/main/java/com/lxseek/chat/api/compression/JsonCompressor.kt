package com.lxseek.chat.api.compression

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Headroom SmartCrusher 思想的 JSON 压缩器（确定性 Kotlin 移植）。
 *
 * 上游对 JSON 数组做"schema 折叠 + 行采样 + 常量因子分解"并靠 CCR 取回原文。
 * LxChat 没有检索回路，因此移植的是它的无损核心：
 *
 * 1. **紧凑化**：去掉 pretty-print 的缩进与换行（单行渲染）；
 * 2. **null / 空值剥离**：递归丢弃 `"key": null`、`""`、`[]`、`{}` 字段，
 *    这是工具输出（API 响应、shell 结果）中最常见的体积浪费；
 * 3. **超长字符串截断**：单个字符串值超过阈值时保留头尾并标记省略长度，
 *    对应上游 opaque-blob offload 的内联变体；
 * 4. **同构数组折叠**：对象数组中全行共享的常量键值对提炼为头部 schema 行，
 *    行内只保留变化字段（上游 csv-schema 渲染）。
 *
 * 一切操作失败即返回原文。输出仍然是合法 JSON。
 */
object JsonCompressor {

    /** 单个字符串值超过此长度时截断（头尾保留）。 */
    internal const val MAX_STRING_VALUE_CHARS = 512

    /** 触发数组 schema 折叠的最少对象数。 */
    internal const val MIN_ITEMS_TO_FOLD = 5

    private val json = Json { prettyPrint = false }

    /** 快速探测：文本以 [ 或 { 开头且能被完整解析为 JSON。 */
    fun looksLikeJson(text: String): Boolean {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) return false
        // 全文必须整体是 JSON，而不是"夹着 JSON 的散文"。
        return try {
            json.parseToJsonElement(trimmed)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun compress(text: String): String {
        val element = try {
            json.parseToJsonElement(text)
        } catch (_: Exception) {
            return text
        }
        val compacted = compact(element)
        // 同构数组折叠（仅顶层对象数组）。
        val folded = foldHomogeneousArray(compacted)
        return render(folded)
    }

    // ── 递归紧凑化：去 null/空值、截断超长字符串 ──────────────────────────

    private fun compact(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> {
            val entries = element.entries
                .asSequence()
                .mapNotNull { (key, value) ->
                    val compacted = compact(value)
                    if (isEmptyValue(compacted)) null else key to compacted
                }
                .toList()
            JsonObject(entries.toMap())
        }
        is JsonArray -> JsonArray(element.map(::compact))
        is JsonPrimitive -> truncatePrimitive(element)
        else -> element
    }

    private fun isEmptyValue(value: JsonElement): Boolean = when (value) {
        is JsonNull -> true
        is JsonObject -> value.isEmpty()
        is JsonArray -> value.isEmpty()
        is JsonPrimitive ->
            value.isString && value.content.isBlank()
        else -> false
    }

    private fun truncatePrimitive(primitive: JsonPrimitive): JsonPrimitive {
        val content = primitive.content
        if (!primitive.isString || content.length <= MAX_STRING_VALUE_CHARS) return primitive
        val head = content.take(MAX_STRING_VALUE_CHARS / 2)
        val tail = content.takeLast(MAX_STRING_VALUE_CHARS / 4)
        return JsonPrimitive("$head…[+${content.length - head.length - tail.length} chars]…$tail")
    }

    // ── 同构数组折叠 ────────────────────────────────────────────────────

    /**
     * 对象数组中所有行共享的键值对提炼为头部一行 schema，行内只留变化字段。
     * 与上游 csv-schema 的区别：这里不丢行（无损采样换成无损折叠），行序保持不变。
     */
    private fun foldHomogeneousArray(element: JsonElement): JsonElement {
        val array = element as? JsonArray ?: return element
        val objects = array.filterIsInstance<JsonObject>()
        if (objects.size < MIN_ITEMS_TO_FOLD || objects.size != array.size) return element

        val commonEntries = objects[0].entries.filter { (key, value) ->
            val first = value.toString()
            objects.all { it[key]?.toString() == first }
        }
        if (commonEntries.isEmpty()) return element

        val commonKeys = commonEntries.associate { it.key to it.value }
        val rows = objects.map { obj ->
            JsonObject(obj.entries.filter { it.key !in commonKeys }.toMap())
        }
        val parts = mutableListOf<JsonElement>(JsonObject(commonKeys))
        parts.addAll(rows)
        return JsonArray(parts)
    }

    private fun render(element: JsonElement): String = element.toString()
}
