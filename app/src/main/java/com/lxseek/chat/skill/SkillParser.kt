package com.lxseek.chat.skill

/**
 * Parses a SKILL.md document (YAML frontmatter between `---` delimiters + Markdown body)
 * into a [Skill].
 *
 * The frontmatter is a flat `key: value` YAML subset. No external YAML dependency is
 * required: the format in practice is simple enough that a focused parser is both
 * smaller and more robust to malformed input than a general-purpose library.
 *
 * Supported keys: `name`, `description`, `when_to_use`, `allowed-tools`, `paths`,
 * `context`, `model`, `requires_membership`. List-valued keys (`allowed-tools`,
 * `paths`) accept either a comma-separated string (optionally quoted) or a YAML
 * block sequence (`- item` lines). Inline comments after `#` are stripped from
 * scalar values.
 *
 * Returns null when the document has no frontmatter or is missing the required
 * `name` / `description` fields, so a single malformed skill file never breaks the
 * whole registry.
 */
object SkillParser {

    private const val DELIMITER = "---"

    /** Parse a SKILL.md document. Returns null on malformed/missing frontmatter. */
    fun parse(content: String, source: String = ""): Skill? {
        val raw = content.replace("\r\n", "\n").replace("\r", "\n")
        val lines = raw.split("\n")

        // Frontmatter must start at the first non-blank line with `---`.
        val startIdx = lines.indexOfFirst { it.isNotBlank() }
        if (startIdx < 0 || lines[startIdx].trim() != DELIMITER) return null

        // Find the closing `---`. Everything between is frontmatter; the rest is body.
        val closeIdx = (startIdx + 1 until lines.size)
            .firstOrNull { lines[it].trim() == DELIMITER }
            ?: return null

        val frontmatterLines = lines.subList(startIdx + 1, closeIdx)
        val body = lines.subList(closeIdx + 1, lines.size)
            .joinToString("\n")
            .trimLeadingNewlines()

        val map = parseFrontmatter(frontmatterLines)
        val name = map["name"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val description = map["description"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        return Skill(
            name = name,
            description = description,
            whenToUse = map["when_to_use"]?.trim()?.takeIf { it.isNotEmpty() },
            allowedTools = parseList(map["allowed-tools"]),
            paths = parseList(map["paths"]),
            context = map["context"]?.trim()?.takeIf { it.isNotEmpty() },
            model = map["model"]?.trim()?.takeIf { it.isNotEmpty() },
            body = body,
            source = source,
            requiresMembership = parseBoolean(map["requires_membership"]),
        )
    }

    /**
     * Parse a flat `key: value` frontmatter block. Supports YAML block sequences
     * (`- item` lines indented under a key) and inline `#` comments on scalar lines.
     */
    private fun parseFrontmatter(lines: List<String>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            // Blank line or full-line comment.
            if (line.isBlank() || line.trimStart().startsWith("#")) {
                i++
                continue
            }
            val colon = line.indexOf(':')
            if (colon < 0) {
                i++
                continue
            }
            val key = line.substring(0, colon).trim()
            var value = line.substring(colon + 1).trim()

            if (value.isEmpty()) {
                // Possibly a YAML block sequence: collect following indented `- item` lines.
                val items = mutableListOf<String>()
                var j = i + 1
                while (j < lines.size) {
                    val candidate = lines[j]
                    val trimmed = candidate.trimStart()
                    if (trimmed.startsWith("- ")) {
                        items.add(trimmed.removePrefix("- ").trim().stripComment())
                        j++
                    } else if (candidate.isBlank()) {
                        j++
                    } else {
                        break
                    }
                }
                if (items.isNotEmpty()) {
                    result[key] = items.joinToString(", ")
                    i = j
                    continue
                }
                result[key] = ""
                i++
                continue
            }

            result[key] = value.stripComment()
            i++
        }
        return result
    }

    /** Parse a comma-separated list value, stripping surrounding quotes from each item. */
    private fun parseList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(",")
            .map { it.trim().unquote().trim() }
            .filter { it.isNotEmpty() }
    }

    /** Strip a trailing inline ` # comment` (with a space before #) from a scalar value. */
    private fun String.stripComment(): String {
        val hashIdx = indexOf(" #")
        return if (hashIdx >= 0) substring(0, hashIdx).trim() else trim()
    }

    /** Remove a single layer of surrounding single or double quotes. */
    private fun String.unquote(): String {
        if (length >= 2) {
            val first = first()
            val last = last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return substring(1, length - 1)
            }
        }
        return this
    }

    private fun parseBoolean(raw: String?): Boolean =
        raw?.trim()?.lowercase()?.let { it == "true" || it == "yes" || it == "1" } ?: false

    private fun String.trimLeadingNewlines(): String {
        var idx = 0
        while (idx < length && (this[idx] == '\n' || this[idx] == '\r')) idx++
        return substring(idx)
    }
}