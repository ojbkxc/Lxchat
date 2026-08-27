package com.lxseek.chat.skill

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

    private val registered = LinkedHashMap<String, Skill>()
    private val enabled = mutableMapOf<String, Boolean>()
    private val _skills = MutableStateFlow<List<SkillInfo>>(emptyList())

    /** Observable snapshot of the registry (for settings UI). */
    val skills: StateFlow<List<SkillInfo>> = _skills.asStateFlow()

    /** Register a skill. A duplicate name overwrites the previous entry. */
    fun register(skill: Skill, enabled: Boolean = true) {
        registered[skill.name] = skill
        this.enabled[skill.name] = enabled
        refresh()
    }

    /** Remove a skill by name. */
    fun unregister(name: String) {
        registered.remove(name)
        enabled.remove(name)
        refresh()
    }

    /** Toggle a skill on/off without removing it from the registry. */
    fun setEnabled(name: String, on: Boolean) {
        if (name !in registered) return
        enabled[name] = on
        refresh()
    }

    /** Look up a skill by name (regardless of enable state). */
    fun skill(name: String): Skill? = registered[name]

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
        return skill.paths.any { GlobMatcher.matches(it, currentPath) }
    }

    private fun refresh() {
        _skills.value = registered.values.map { SkillInfo(it, enabled[it.name] ?: false) }
    }
}

/**
 * Minimal glob matcher for skill `paths` patterns. Supports `*` (segment-spanning
 * wildcard within a path segment), `**` (recursive wildcard across segments), and
 * `?` (single char). Enough for the `src/**/*.ts` style patterns used in SKILL.md
 * without pulling in a filesystem-aware glob library.
 */
private object GlobMatcher {
    fun matches(pattern: String, path: String): Boolean {
        val p = pattern.trim().replace('\\', '/')
        val t = path.trim().replace('\\', '/')
        return matchSegments(p.split("/"), t.split("/"))
    }

    private fun matchSegments(pattern: List<String>, path: List<String>): Boolean {
        if (pattern.isEmpty()) return path.isEmpty()
        val head = pattern.first()
        val rest = pattern.drop(1)
        if (head == "**") {
            // ** matches zero or more path segments.
            if (rest.isEmpty()) return true
            // Try consuming 0..path.size leading segments.
            for (i in 0..path.size) {
                if (matchSegments(rest, path.drop(i))) return true
            }
            return false
        }
        if (path.isEmpty()) return false
        return matchSegment(head, path.first()) && matchSegments(rest, path.drop(1))
    }

    /** Match a single segment with `*` and `?` wildcards (no `/` inside a segment). */
    private fun matchSegment(pattern: String, text: String): Boolean {
        if (pattern.isEmpty()) return text.isEmpty()
        return when (pattern.first()) {
            '*' -> {
                // * matches zero or more chars within this segment.
                for (i in 0..text.length) {
                    if (matchSegment(pattern.drop(1), text.drop(i))) return true
                }
                false
            }
            '?' -> text.isNotEmpty() && matchSegment(pattern.drop(1), text.drop(1))
            else -> text.isNotEmpty() &&
                pattern.first() == text.first() &&
                matchSegment(pattern.drop(1), text.drop(1))
        }
    }
}