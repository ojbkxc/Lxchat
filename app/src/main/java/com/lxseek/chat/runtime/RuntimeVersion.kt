package com.lxseek.chat.runtime

/**
 * 轻量语义化版本比较（容忍非严格语义号，如 "22.5"、"5.0"、"3.12"）。
 *
 * 支持：
 * - 数字段数值比较；
 * - 预发布后缀（如 "-rc1"）视为低于对应正式版（RFC 语义化版本行为），
 *   且预发布内部也按数值比较；
 * - [Version.NONE] 表示无法解析。
 *
 * runtime_requirement / min_version 约束统一经由 [satisfiesMin] 判断。
 */
data class Version private constructor(
    val major: Long,
    val minor: Long,
    val patch: Long,
    val prerelease: List<Long>,
) : Comparable<Version> {

    override fun compareTo(other: Version): Int {
        var c = major.compareTo(other.major)
        if (c != 0) return c
        c = minor.compareTo(other.minor)
        if (c != 0) return c
        c = patch.compareTo(other.patch)
        if (c != 0) return c
        return comparePrerelease(prerelease, other.prerelease)
    }

    private fun comparePrerelease(a: List<Long>, b: List<Long>): Int {
        // 有预发布版本 < 无预发布版本（正式版）。
        if (a.isEmpty() && b.isEmpty()) return 0
        if (a.isEmpty()) return 1
        if (b.isEmpty()) return -1
        for (i in 0 until minOf(a.size, b.size)) {
            val c = a[i].compareTo(b[i])
            if (c != 0) return c
        }
        return a.size.compareTo(b.size)
    }

    /** 是否满足 `min` 的最低版本约束（this >= min）。 */
    fun satisfiesMin(min: Version): Boolean = this >= min

    companion object {
        /** 无法解析的版本（比较始终为最低）。 */
        val NONE: Version = Version(-1, -1, -1, emptyList())

        private val RE = Regex(
            "(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:[-+]([0-9A-Za-z.-]+))?",
        )

        fun parse(raw: String?): Version {
            val s = raw?.trim() ?: return NONE
            if (s.isEmpty()) return NONE
            val m = RE.find(s) ?: return NONE
            val major = m.groupValues[1].toLongOrNull() ?: return NONE
            val minor = m.groupValues[2].toLongOrNull() ?: 0L
            val patch = m.groupValues[3].toLongOrNull() ?: 0L
            val prerelease = parsePrerelease(m.groupValues[4])
            return Version(major, minor, patch, prerelease)
        }

        private fun parsePrerelease(s: String): List<Long> {
            if (s.isBlank()) return emptyList()
            // 仅保留数字段；非数字段忽略（保守处理，统一视为 0）。
            return s
                .split('.')
                .mapNotNull { it.toLongOrNull() }
        }
    }
}