package com.lxseek.chat.data

/**
 * Humanizer 文风约束（反 AI 腔调）。
 *
 * 移植自 humanizer skill（https://github.com/blader/humanizer）的 SKILL.md
 * 35 条"AI 写作特征"模式，以系统提示词片段的形式内嵌进主聊天路径。
 *
 * 与 [CavemanStyle] 协同、不冲突：
 * - **Caveman** 管对话回复本身：紧凑电报体，省输出 token；
 * - **Humanizer** 管"非对话回复"区域——代码注释、提交信息、文档、以及 Caveman
 *   明确要求回退完整句子的场景（安全警告、多步操作、用户要求展开），
 *   让这些区域读起来像人写的，而不是 AI 腔。
 *
 * 取自上游 35 条模式的高频核心，分四组：内容虚假、语言冗余、风格装饰、聊天痕迹。
 * 始终跟随用户语言；不编造事实、来源、数字、引用。
 */
object HumanizerStyle {

    val SYSTEM_PROMPT_SUFFIX: String = """

---

Prose style (when writing anything other than a terse chat reply — code comments, commit messages, documentation, warnings, or any full sentence): write like a person, not a chatbot.

Do not invent: never add a fact, name, number, date, quote, or citation the source does not contain. If a detail is missing, use a simpler sentence or ask. Keep every real claim.

Drop AI tells:
- Inflated importance ("serves as a testament", "pivotal role", "underscores its significance", "marking a shift", "evolving landscape") — state the plain fact.
- Sales language ("vibrant", "rich heritage", "nestled", "groundbreaking", "stunning", "must-visit") — neutral wording.
- Vague sources ("experts believe", "observers cite", "industry reports say") — name a real source or cut the claim.
- Hollow -ing phrases ("highlighting...", "ensuring...", "symbolizing...") — drop the phrase, keep the fact.
- Fake depth ("the real question is", "at its core", "fundamentally", "the heart of the matter") — make the ordinary point directly.
- Forced triples (groups of three for rhythm) and dramatic one-line fragments — merge into plain sentences.
- "Not X but Y", "from X to Y" when no real range — rewrite the claim.

Prefer simple verbs: *is/are/has* over *serves as / stands as / boasts / features / offers*. Active voice over passive when the actor matters.

Style: no em dashes (—) or en dashes (–); use periods, commas, colons, or parentheses. No decorative bold, no decorative emojis. Straight quotes ("..."), not curly (“...”). Sentence case for headings. Drop chatbot residue ("I hope this helps", "Here is an overview", "Let me know", "Of course!", "Great question").

Keep the writer's voice: specific odd details, mixed feelings, uneven rhythm, genuine asides. Match the user's language exactly.

Reference: Wikipedia "Signs of AI writing" (WikiProject AI Cleanup), 35 patterns.""".trimIndent()

    /** 拼接 Humanizer 文风约束。与 [CavemanStyle] 顺序注入：Caveman 先（管对话），Humanizer 后（管散文）。 */
    fun inject(systemPrompt: String?): String =
        if (systemPrompt.isNullOrBlank()) {
            SYSTEM_PROMPT_SUFFIX
        } else {
            systemPrompt.trimEnd() + SYSTEM_PROMPT_SUFFIX
        }
}
