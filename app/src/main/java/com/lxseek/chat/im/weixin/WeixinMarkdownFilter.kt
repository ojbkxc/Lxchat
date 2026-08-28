package com.lxseek.chat.im.weixin

/**
 * Strips Markdown syntax that WeChat cannot render, converting it to plain text.
 * WeChat displays plain text only — raw Markdown characters like **, ##, []()
 * appear as literal text to the recipient, which is ugly and confusing.
 *
 * Based on SpenserCai/weixin-agent-sdk-rs findings: WeChat does not support
 * CJK italics, image links, or most Markdown formatting.
 */
object WeixinMarkdownFilter {

    /**
     * Convert Markdown text to WeChat-friendly plain text.
     * Preserves content while removing formatting markers.
     */
    fun strip(markdown: String): String {
        var text = markdown

        // Markdown tables (| a | b |) → mobile-friendly bullet lines.
        // WeChat renders raw pipe tables as messy text, so collapse them into
        // a first-column label plus "• column: value" bullets (borrowed from
        // cc-haha's convertMarkdownTablesToBullets). Code-fenced tables are kept.
        text = convertTablesToBullets(text)

        // Code blocks (```lang ... ```) → keep content, remove fences
        text = text.replace(Regex("```[a-zA-Z]*\\n?([\\s\\S]*?)```")) { m ->
            m.groupValues[1].trim()
        }

        // Inline code: `code` → code
        text = text.replace(Regex("`([^`]+)`"), "$1")

        // Bold: **text** or __text__ → text
        text = text.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        text = text.replace(Regex("__([^_]+?)__"), "$1")

        // Italic: *text* or _text_ → text (careful not to match ** or list items)
        text = text.replace(Regex("(?<!\\*)\\*([^*\\n]+?)\\*(?!\\*)"), "$1")
        text = text.replace(Regex("(?<!_)_([^_\\n]+?)_(?!_)"), "$1")

        // Strikethrough: ~~text~~ → text
        text = text.replace(Regex("~~(.+?)~~"), "$1")

        // Headers: #/##/###... → just text
        text = text.replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")

        // Links: [text](url) → text
        text = text.replace(Regex("\\[([^]]+)]\\(([^)]+)\\)"), "$1")

        // Images: ![alt](url) → [alt] (or empty if no alt)
        text = text.replace(Regex("!\\[([^]]*)]\\([^)]+\\)")) { m ->
            val alt = m.groupValues[1]
            if (alt.isNotBlank()) "[$alt]" else ""
        }

        // Blockquotes: > text → text
        text = text.replace(Regex("^\\s*>\\s?", RegexOption.MULTILINE), "")

        // Unordered list markers: - / * / + → • (bullet)
        text = text.replace(Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE), "• ")

        // Ordered list markers: keep the number, remove the dot-space
        // 1. text → 1. text (keep as-is, WeChat renders this fine)

        // Horizontal rules: --- or *** → —
        text = text.replace(Regex("^\\s*[-*]{3,}\\s*$", RegexOption.MULTILINE), "—")

        // Clean up: collapse 3+ consecutive newlines to 2
        text = text.replace(Regex("\\n{3,}"), "\n\n")

        return text.trim()
    }

    // ── Markdown 表格 → bullet 列表（手机友好） ─────────────────

    /** Whether [line] opens or closes a ```/~~~ code fence. */
    private fun isFenceMarker(line: String): Boolean = Regex("^\\s*(```|~~~)").containsMatchIn(line)

    /** Whether [line] looks like a pipe-table row (2+ cells). */
    private fun isTableRow(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.contains('|')) return false
        return splitTableRow(trimmed).size >= 2
    }

    /** Whether [line] is a pipe-table divider (`|---|---|`). */
    private fun isTableDivider(line: String): Boolean {
        val cells = splitTableRow(line.trim())
        if (cells.size < 2) return false
        return cells.all { Regex("^:?-{3,}:?$").matches(it.trim()) }
    }

    private fun splitTableRow(line: String): List<String> {
        val trimmed = line.trim()
        val inner = if (trimmed.startsWith("|")) trimmed.substring(1) else trimmed
        val withoutTrailingPipe = if (inner.endsWith("|")) inner.dropLast(1) else inner
        return withoutTrailingPipe.split("|").map { it.trim() }
    }

    private fun renderTableAsBullets(headers: List<String>, rows: List<List<String>>): String {
        if (headers.isEmpty() || rows.isEmpty()) return ""
        val output = mutableListOf<String>()
        for (row in rows) {
            if (row.all { it.isBlank() }) continue
            val label = row.getOrNull(0).orEmpty()
            if (label.isNotBlank()) output.add(label)
            val colCount = maxOf(headers.size, row.size)
            for (i in 1 until colCount) {
                val value = row.getOrNull(i).orEmpty()
                if (value.isBlank()) continue
                val header = headers.getOrNull(i).orEmpty()
                output.add("• " + (if (header.isNotBlank()) "$header: " else "列${i + 1}: ") + value)
            }
            if (output.lastOrNull()?.isNotBlank() == true) output.add("")
        }
        while (output.lastOrNull()?.isEmpty() == true) output.removeAt(output.lastIndex)
        return output.joinToString("\n")
    }

    /**
     * Convert GitHub-flavored pipe tables into mobile-friendly bullet lists,
     * preserving code-fenced content untouched.
     */
    private fun convertTablesToBullets(markdown: String): String {
        val lines = markdown.split("\n")
        val output = mutableListOf<String>()
        var inFence = false
        var i = 0
        while (i < lines.size) {
            val headerLine = lines[i]
            if (isFenceMarker(headerLine)) {
                inFence = !inFence
                output.add(headerLine)
                i++
                continue
            }
            val dividerLine = lines.getOrNull(i + 1).orEmpty()
            if (!inFence && isTableRow(headerLine) && isTableDivider(dividerLine)) {
                val headers = splitTableRow(headerLine)
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && isTableRow(lines[i])) {
                    rows.add(splitTableRow(lines[i]))
                    i++
                }
                val rendered = renderTableAsBullets(headers, rows)
                if (rendered.isNotBlank()) output.add(rendered)
                continue
            }
            output.add(headerLine)
            i++
        }
        return output.joinToString("\n")
    }
}