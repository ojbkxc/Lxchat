package com.lxseek.chat.data

import kotlin.math.exp
import kotlin.math.log
import kotlin.math.max

/**
 * 记忆重要性评分器：结合类型权重、时间衰减与访问频率，输出一个综合分数。
 *
 * 评分公式：
 * ```
 * score = typeWeight * exp(-daysSinceLastAccess / 30.0) * (1 + ln(1 + accessCount) * 0.1)
 * ```
 *
 * - 类型权重：preference=1.0, skill=0.9, fact=0.8, contact=0.7, event=0.6, other=0.5
 * - 时间衰减：30 天半衰期，越久未访问分越低
 * - 访问频率加成：访问越多分越高（对数增长，避免高频项无限放大）
 *
 * 该评分器不依赖额外持久化字段：类型与初始评分以 `[type:xxx][score:0.xx]` 前缀编码进
 * [MemoryManager] 的 description 元数据；访问时间退化为文件 lastModified，访问次数以
 * description 中可选的 `[access:n]` 标签承载（缺失视为 0），保证向后兼容旧记忆。
 */
object MemoryImportanceScorer {

    /** 时间衰减半衰期（天）。 */
    private const val DECAY_HALF_LIFE_DAYS = 30.0
    private const val MILLIS_PER_DAY = 86_400_000.0
    /** 访问频率加成系数。 */
    private const val FREQUENCY_FACTOR = 0.1

    /**
     * 记忆分类。[weight] 为类型权重，[filePrefix] 为创建文件名时的分类前缀。
     */
    enum class Category(val weight: Double, val filePrefix: String) {
        PREFERENCE(1.0, "pref"),
        SKILL(0.9, "skill"),
        FACT(0.8, "fact"),
        CONTACT(0.7, "contact"),
        EVENT(0.6, "event"),
        OTHER(0.5, "misc");

        companion object {
            /** 从字符串解析分类，未匹配返回 [OTHER]。大小写不敏感。 */
            fun fromString(raw: String?): Category {
                if (raw.isNullOrBlank()) return OTHER
                val key = raw.trim().lowercase()
                return values().firstOrNull { it.name.lowercase() == key } ?: OTHER
            }
        }
    }

    /**
     * 单条记忆的评分输入视图。
     *
     * @property category      记忆分类
     * @property lastAccessTime 最近一次访问时间（epoch millis）
     * @property accessCount   累计访问次数
     */
    data class MemoryEntry(
        val category: Category,
        val lastAccessTime: Long,
        val accessCount: Int,
    )

    /**
     * 计算重要性分数。越高越重要。
     *
     * @param memory 记忆条目
     * @param now    当前时间戳（epoch millis），用于计算时间衰减
     */
    fun score(memory: MemoryEntry, now: Long): Double {
        val typeWeight = memory.category.weight
        val daysSince = max(0.0, (now - memory.lastAccessTime) / MILLIS_PER_DAY)
        val decay = exp(-daysSince / DECAY_HALF_LIFE_DAYS)
        val frequencyBoost = 1.0 + log(1.0 + memory.accessCount) * FREQUENCY_FACTOR
        return typeWeight * decay * frequencyBoost
    }

    // ── description 标签编解码 ──────────────────────────────────
    // 格式：[type:preference][score:0.80][access:3]
    // type 与 score 由 AutoMemoryExtractor 写入；access 为可选的运行时累加标签。

    private val TYPE_TAG = Regex("""\[type:([a-zA-Z]+)]""")
    private val SCORE_TAG = Regex("""\[score:([0-9.]+)]""")
    private val ACCESS_TAG = Regex("""\[access:(\d+)]""")
    private val ALL_TAGS = Regex("""\[(?:type|score|access):[^\]]*]""")

    /** 把分类、初始评分与（可选）访问次数编码为 description 标签前缀。 */
    fun encodeDescription(category: Category, score: Double, accessCount: Int = 0): String {
        val scoreStr = "%.2f".format(score.coerceIn(0.0, 2.0))
        val accessTag = if (accessCount > 0) "[access:$accessCount]" else ""
        return "[type:${category.name.lowercase()}][score:$scoreStr]$accessTag"
    }

    /** 从 description 解析分类。缺失返回 [Category.OTHER]。 */
    fun parseCategory(description: String): Category {
        val match = TYPE_TAG.find(description) ?: return Category.OTHER
        return Category.fromString(match.groupValues[1])
    }

    /** 从 description 解析已写入的初始评分。缺失返回 null。 */
    fun parseScore(description: String): Double? =
        SCORE_TAG.find(description)?.groupValues?.get(1)?.toDoubleOrNull()

    /** 从 description 解析访问次数。缺失返回 0。 */
    fun parseAccessCount(description: String): Int =
        ACCESS_TAG.find(description)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** 去除所有标签前缀，返回纯描述文本（向后兼容旧记忆）。 */
    fun stripTags(description: String): String =
        description.replace(ALL_TAGS, "").trim()
}