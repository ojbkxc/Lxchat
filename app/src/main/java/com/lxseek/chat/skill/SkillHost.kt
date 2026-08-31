package com.lxseek.chat.skill

import com.lxseek.chat.util.PortableGlobMatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-level Skill host. Maintains the skill registry, enable state, and the
 * two token-saving disclosure strategies from the extension-platform plan:
 *
 * 1. **Progressive disclosure** — [summaries] returns only `name + description`
 *    directory entries to the model. The full Markdown body is loaded on demand
 *    only when a skill is activated (called), so the system prompt stays small.
 *
 * 2. **Conditional activation via `paths`** — [activeSkills] returns only the
 *    skills whose `paths` glob patterns match the current file-path context,
 *    so skills for unrelated files never consume tool slots or tokens.
 *
 * Membership gating is applied at both layers: non-members never see
 * `requiresMembership` skills in the directory, and never receive them as
 * active (callable) skills. The execution-layer gate lives in
 * [SkillToolProvider] / [SkillMembershipDecorator].
 */
class SkillHost {

    /** A registered skill plus its enable flag. */
    data class SkillInfo(val skill: Skill, val enabled: Boolean)

    /** Per-skill usage statistics, used by the curator to spot skills nobody uses. */
    data class SkillUsage(
        val name: String,
        val callCount: Int,
        val firstSeenAt: Long,
        val lastUsedAt: Long,
    )

    private val registered = LinkedHashMap<String, Skill>()
    private val enabled = mutableMapOf<String, Boolean>()
    private val callCount = mutableMapOf<String, Int>()
    private val firstSeenAt = mutableMapOf<String, Long>()
    private val lastUsedAt = mutableMapOf<String, Long>()
    private val _skills = MutableStateFlow<List<SkillInfo>>(emptyList())

    /** Observable snapshot of the registry (for settings UI). */
    val skills: StateFlow<List<SkillInfo>> = _skills.asStateFlow()

    /** Register a skill. A duplicate name overwrites the previous entry. */
    fun register(skill: Skill, enabled: Boolean = true) {
        synchronized(this) {
            registered[skill.name] = skill
            this.enabled[skill.name] = enabled
            // Preserve usage history across re-registrations (e.g. app restart reload).
            if (skill.name !in firstSeenAt) firstSeenAt[skill.name] = System.currentTimeMillis()
            refresh()
        }
    }

    /** Remove a skill by name. */
    fun unregister(name: String) {
        synchronized(this) {
            registered.remove(name)
            enabled.remove(name)
            callCount.remove(name)
            firstSeenAt.remove(name)
            lastUsedAt.remove(name)
            refresh()
        }
    }

    /** Toggle a skill on/off without removing it from the registry. */
    fun setEnabled(name: String, on: Boolean) {
        if (name !in registered) return
        enabled[name] = on
        refresh()
    }

    /** Look up a skill by name (regardless of enable state). */
    fun skill(name: String): Skill? = registered[name]

    /** Record one successful invocation of a skill (drives curator + journey). */
    fun recordUsage(name: String) {
        synchronized(this) {
            if (name !in registered) return
            val now = System.currentTimeMillis()
            callCount[name] = (callCount[name] ?: 0) + 1
            lastUsedAt[name] = now
        }
    }

    /** Snapshot of usage statistics for all registered skills (curator + journey data). */
    fun usageSnapshot(): List<SkillUsage> = synchronized(this) {
        registered.keys.map { name ->
            SkillUsage(
                name = name,
                callCount = callCount[name] ?: 0,
                firstSeenAt = firstSeenAt[name] ?: 0L,
                lastUsedAt = lastUsedAt[name] ?: 0L,
            )
        }
    }

    // ── Progressive disclosure ──────────────────────────────────────────────

    /**
     * Progressive disclosure: return skill summaries (name + description only) for
     * the directory. The full body is intentionally excluded — it is loaded on
     * demand only when the skill is activated.
     *
     * Membership-gated skills are hidden from non-members even at the directory
     * layer (saves tokens + UX). Disabled skills are excluded.
     */
    fun summaries(hasMembership: Boolean): List<Skill> {
        return registered.values
            .filter { (enabled[it.name] ?: false) && visibleByMembership(it, hasMembership) }
            .map { it.copy(body = "") } // strip body for the directory entry
    }

    // ── Conditional activation via paths ────────────────────────────────────

    /**
     * Return the skills active for the given file-path context: enabled, membership-
     * visible, and whose `paths` patterns match [currentPath] (an empty `paths`
     * list means the skill is always active regardless of path).
     *
     * @param currentPath   The current file path the model is working on, or null
     *                       when no path context is available. Skills with non-empty
     *                       `paths` are deactivated when [currentPath] is null.
     * @param hasMembership  Whether the current user has an active membership.
     */
    fun activeSkills(currentPath: String?, hasMembership: Boolean): List<Skill> {
        return registered.values.filter { skill ->
            (enabled[skill.name] ?: false) &&
                visibleByMembership(skill, hasMembership) &&
                matchesPaths(skill, currentPath)
        }
    }

    // ── Skill composition (chaining) ─────────────────────────────────────────

    /**
     * Chain two skills so that [skillA]'s output is fed into [skillB]. Sets
     * `chainedTo` on skill A to point at skill B. Returns false if either skill
     * is not registered (the registry is left unchanged on failure). Self-chaining
     * (A == B) is rejected to avoid trivial cycles.
     *
     * The link is observable via [resolveChain] and surfaces to the model as a
     * `--- Next Skill ---` trailer in the executed body (see [SkillToolProvider]).
     */
    fun chain(skillA: String, skillB: String): Boolean {
        if (skillA == skillB) return false
        val a = registered[skillA] ?: return false
        if (registered[skillB] == null) return false
        registered[skillA] = a.copy(chainedTo = skillB)
        refresh()
        return true
    }

    /**
     * Resolve the chain starting at [startSkill]: returns the ordered list of
     * skills `[startSkill, next, next.next, ...]` following `chainedTo` links.
     * Cycle-safe: a visited set stops traversal once a skill repeats, so a
     * misconfigured cycle (A → B → A) returns `[A, B]` rather than looping forever.
     * Returns an empty list when [startSkill] is not registered.
     */
    fun resolveChain(startSkill: String): List<Skill> {
        val start = registered[startSkill] ?: return emptyList()
        val result = mutableListOf<Skill>()
        val visited = mutableSetOf<String>()
        var current: Skill = start
        while (current.name !in visited) {
            visited.add(current.name)
            result.add(current)
            val nextName = current.chainedTo ?: break
            current = registered[nextName] ?: break
        }
        return result
    }

    /** Membership visibility check: members see everything; non-members skip gated skills. */
    private fun visibleByMembership(skill: Skill, hasMembership: Boolean): Boolean =
        hasMembership || !skill.requiresMembership

    /**
     * Path matching: a skill with no `paths` is always active. A skill with `paths`
     * is active only when [currentPath] is non-null and matches at least one pattern.
     */
    private fun matchesPaths(skill: Skill, currentPath: String?): Boolean {
        if (skill.paths.isEmpty()) return true
        if (currentPath == null) return false
        // 统一委托 PortableGlobMatcher（原私有 GlobMatcher 已删除，语义保持一致）：
        // `*` 段内、`**` 段边界跨段、`?` 单字符的匹配行为两者完全相同；旧实现对
        // pattern/path 的 trim 在此是冗余的——SkillParser.parseList 已对每个 path 模式
        // trim 过，currentPath 也来自 trim 过的文件路径上下文。仅剩的理论边缘差异是：
        // ① 段内出现 `**`（如 `a**b`，这里按 `.*` 可跨段匹配）；② 字符类 `[...]`
        // （旧实现视为字面量）；③ 模式以 `**` 结尾时对目录本身的匹配（如 `src/**`，
        // 旧实现 `**` 可匹配零段因而匹配 "src"，新实现要求尾分隔符不匹配），而
        // SKILL.md 的 paths 实际只有 `src/**/*.ts`、`*.md` 这类标准段边界写法，
        // 不会用到这些边缘形式，替换是安全的。
        return skill.paths.any { PortableGlobMatcher.matches(it, currentPath) }
    }

    private fun refresh() {
        _skills.value = registered.values.map { SkillInfo(it, enabled[it.name] ?: false) }
    }
}
