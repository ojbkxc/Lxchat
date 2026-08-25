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
}