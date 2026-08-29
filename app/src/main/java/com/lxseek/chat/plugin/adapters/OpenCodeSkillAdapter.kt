package com.lxseek.chat.plugin.adapters

import com.lxseek.chat.skill.Skill
import com.lxseek.chat.skill.SkillParser
import java.io.File

/**
 * Adapter for importing OpenCode `SKILL.md` files into the Skill system.
 *
 * OpenCode stores skills under `.opencode/skills/<name>/SKILL.md`. The file format is
 * Markdown with a YAML frontmatter block (delimited by `---`), identical to the format
 * parsed by [SkillParser]. This adapter walks a directory tree, finds every `SKILL.md`
 * file, and parses each into a [Skill]. Malformed files are skipped (the parser returns
 * null and IO errors are swallowed per file), so a single bad file never breaks the
 * whole import — the registry stays consistent and the good skills still load.
 *
 * The resulting [Skill] list is typically handed to
 * [com.lxseek.chat.skill.SkillHost.register] by the caller (or aggregated through a
 * plugin), keeping this adapter a pure format-to-model projection with no host side
 * effects.
 *
 * This mirrors [com.lxseek.chat.skill.adapters.DshSkillAdapter] but is scoped to the
 * OpenCode source convention so callers can distinguish import provenance.
 */
object OpenCodeSkillAdapter {

    /** Import all `SKILL.md` files from a directory tree (top-down walk). */
    fun importDirectory(dir: File): List<Skill> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.walkTopDown()
            .filter { it.isFile && it.name.equals("SKILL.md", ignoreCase = true) }
            .mapNotNull { file -> importFile(file) }
            .toList()
    }

    /** Import a single `SKILL.md` file. Returns null when parsing fails or IO errors occur. */
    fun importFile(file: File): Skill? {
        val content = runCatching { file.readText() }.getOrElse { return null }
        return SkillParser.parse(content, source = file.absolutePath)
    }
}