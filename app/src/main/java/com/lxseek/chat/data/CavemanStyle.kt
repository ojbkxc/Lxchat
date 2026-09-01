package com.lxseek.chat.data

/**
 * Caveman 输出压缩（输出侧省 token）。
 *
 * 移植自 caveman skill（https://github.com/JuliusBrussee/caveman）的
 * SKILL.md 核心规则，以系统提示词片段的形式内嵌进主聊天路径，
 * 让模型用紧凑电报式风格回答：技术实质全保留，只有客套话消失。
 *
 * 上游实测 full 档可省约 70% 输出 token（69 → 19）。
 * 这里取 full 档（默认档）规则，中英双语各一份——按用户当前
 * 会话语言由模型自行匹配，规则本身明确要求"跟随用户语言"。
 */
object CavemanStyle {

    /**
     * 注入到主聊天系统提示词末尾的压缩风格指令。
     *
     * 设计要点（与上游 SKILL.md 逐条对应）：
     * - 去冠词/填充词/寒暄/模糊限制语；短词替代长词；允许句子片段；
     * - 技术术语、代码块、命令、错误原文、数字与单位逐字保留；
     * - 不为"显得像电报体"而额外加词——只压缩、不膨胀；
     * - 回复语言始终跟随用户语言（中文用户得到中文电报体）；
     * - 安全警告、不可逆操作确认、多步操作顺序说明自动回退完整句子
     *   （上游 Auto-Clarity 规则）；
     * - 仅作用于对话回复；写代码、注释、提交信息、文档时恢复正常文体
     *   （上游 Boundaries 规则）。
     */
    val SYSTEM_PROMPT_SUFFIX: String = """

---

Response style (token-saving mode, active by default):

Answer in tight telegraphic style. Keep every technical fact: code, commands, API names, error strings, numbers, units stay verbatim. Drop filler only: articles, pleasantries ("sure", "of course"), hedging ("it seems", "basically"), redundant restatement. Fragments OK. Short synonyms over long ones. Never add words to sound terse — compression only, never grow output.

Tool calls: fire directly, no preamble or progress narration between calls.

Drop telegraphic style when: writing code/comments/commit messages/documents (normal prose there), issuing security or irreversible-action warnings, explaining multi-step sequences where omitted conjunctions risk misread, or when the user asks to clarify. Resume after.

Always reply in the user's language. Compress the style, never the language or technical content.""".trimIndent()

    /** 拼接 Caveman 风格指令到用户系统提示词。空系统提示词时也能独立生效。 */
    fun inject(systemPrompt: String?): String =
        if (systemPrompt.isNullOrBlank()) {
            SYSTEM_PROMPT_SUFFIX
        } else {
            systemPrompt.trimEnd() + SYSTEM_PROMPT_SUFFIX
        }
}
