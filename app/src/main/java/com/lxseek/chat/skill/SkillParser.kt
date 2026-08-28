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
 * `context`, `model`, `requires_membership`, `chained_to`, and the nested `parameters`
 * block. List-valued keys (`allowed-tools`, `paths`) accept either a comma-separated
 * string (optionally quoted) or a YAML block sequence (`- item` lines). Inline
 * comments after `#` are stripped from scalar values.
 *
 * The `parameters` key starts a nested block sequence whose items are maps
 * (`- name: ... \n   type: ...` etc.), parsed into [SkillParameter] entries.
 * The flat [parseFrontmatter] step intentionally skips this block; a dedicated
 * [parseParameters] pass handles it.
 *
 * Returns null when the document has no frontmatter or is missing the required
 * `name` / `description` fields, so a single malformed skill file never breaks the
 * whole registry.
 */
object SkillParser {

    private const val DELIMITER = "---"
    private const val PARAMETERS_KEY = "parameters"

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
        val parameters = parseParameters(frontmatterLines)
        val chainedTo = map["chained_to"]?.trim()?.takeIf { it.isNotEmpty() }

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
            parameters = parameters,
            chainedTo = chainedTo,
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

            // `parameters` 是嵌套块序列（每项是 map），由 parseParameters 单独处理；
            // 这里跳过整个块，避免把 "- name: x" 当成普通 list item 污染结果。
            if (key == PARAMETERS_KEY) {
                i = skipIndentedBlock(lines, i + 1)
                continue
            }

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

    /**
     * Parse the nested `parameters` block into [SkillParameter] entries. Each item
     * starts with `- ` and is followed by indented `key: value` attribute lines.
     * Tolerant of blank lines and inline `#` comments. Returns an empty list when
     * the block is absent or malformed, so legacy skills are unaffected.
     *
     * Supported attributes per item: `name`, `type`, `description`, `required`,
     * `default`, `enumValues` (also accepts snake_case `enum_values`). `enumValues`
     * accepts either an inline array `[a, b]` or a comma-separated string.
     */
    private fun parseParameters(lines: List<String>): List<SkillParameter> {
        val startIdx = lines.indexOfFirst { it.substringBefore(':').trim() == PARAMETERS_KEY }
        if (startIdx < 0) return emptyList()

        // 收集缩进块内容（去掉行首缩进）。
        val blockLines = mutableListOf<String>()
        var j = startIdx + 1
        while (j < lines.size) {
            val candidate = lines[j]
            if (candidate.isBlank()) {
                j++
                continue
            }
            if (candidate.startsWith(" ") || candidate.startsWith("\t")) {
                blockLines.add(candidate.trimStart())
                j++
            } else {
                break
            }
        }
        if (blockLines.isEmpty()) return emptyList()

        // 遍历块：每个 "- " 开始一个新参数；后续 "key: value" 行属于当前参数，
        // 直到下一个 "- " 为止。
        val parameters = mutableListOf<SkillParameter>()
        var current: MutableMap<String, String>? = null
        for (line in blockLines) {
            if (line.startsWith("- ")) {
                if (current != null) parameters.add(buildParameter(current))
                current = LinkedHashMap()
                val afterDash = line.removePrefix("- ").trim()
                applyAttribute(current, afterDash)
            } else if (current != null) {
                applyAttribute(current, line)
            }
        }
        if (current != null) parameters.add(buildParameter(current))

        // 只保留有非空 name 的参数，避免空行噪声产生空条目。
        return parameters.filter { it.name.isNotEmpty() }
    }

    /** Parse one `key: value` attribute line into [target], stripping comments/quotes. */
    private fun applyAttribute(target: MutableMap<String, String>, line: String) {
        val colon = line.indexOf(':')
        if (colon < 0) return
        val key = line.substring(0, colon).trim()
        if (key.isEmpty()) return
        val value = line.substring(colon + 1).trim().stripComment().unquote()
        target[key] = value
    }

    /** Build a [SkillParameter] from a parsed attribute map, tolerant of missing keys. */
    private fun buildParameter(map: Map<String, String>): SkillParameter {
        val enumRaw = map["enumValues"] ?: map["enum_values"] ?: ""
        return SkillParameter(
            name = map["name"]?.trim().orEmpty(),
            type = map["type"]?.trim()?.takeIf { it.isNotEmpty() } ?: "string",
            description = map["description"]?.trim().orEmpty(),
            required = parseBoolean(map["required"]),
            default = map["default"]?.trim()?.takeIf { it.isNotEmpty() },
            enumValues = parseInlineList(enumRaw),
        )
    }

    /**
     * Parse an inline list value such as `[fast, thorough]` or `fast, thorough`.
     * Surrounding brackets are optional; items are split on commas and unquoted.
     */
    private fun parseInlineList(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val cleaned = raw.trim().removeSurrounding("[", "]").trim()
        if (cleaned.isEmpty()) return emptyList()
        return cleaned.split(",")
            .map { it.trim().unquote().trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Skip the indented block starting at [from]: blank lines and lines beginning
     * with whitespace are consumed; the first non-blank, non-indented line stops the
     * skip. Returns the index of the next line to process in the outer loop.
     */
    private fun skipIndentedBlock(lines: List<String>, from: Int): Int {
        var j = from
        while (j < lines.size) {
            val candidate = lines[j]
            if (candidate.isBlank()) {
                j++
                continue
            }
            if (candidate.startsWith(" ") || candidate.startsWith("\t")) {
                j++
            } else {
                break
            }
        }
        return j
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