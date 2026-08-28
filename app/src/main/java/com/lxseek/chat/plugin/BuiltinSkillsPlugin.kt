package com.lxseek.chat.plugin

import com.lxseek.chat.skill.Skill

/**
 * Built-in skills plugin. Registers a small set of canonical skill templates that
 * validate the Skill skeleton end-to-end: the [PluginHost] aggregates them into its
 * [SkillHost] on register, the generation pipeline discovers them via progressive
 * disclosure, and the settings UI lists them as built-in (non-removable) entries.
 *
 * These are intentionally lightweight (body is a short Markdown stub) — they exist
 * to prove the plugin → skillHost → disclosure wiring, not to ship production-grade
 * prompts. Real skill content arrives via the dsh/Operit adapters (task 21).
 */
class BuiltinSkillsPlugin : Plugin {
    override val manifest = PluginManifest(
        id = "builtin_skills",
        name = "Built-in Skills",
        version = "1.0.0",
        category = PluginCategory.Integrated,
        description = "Built-in skill templates",
        builtIn = true,
    )

    override fun skills(): List<Skill> = listOf(
        Skill(
            name = "code_review",
            description = "Review code for bugs and improvements",
            whenToUse = "When reviewing code",
            body = "# Code Review\n\nReview the code for bugs, style issues, and improvements.",
        ),
        Skill(
            name = "debug_helper",
            description = "Help debug issues by analyzing error messages",
            whenToUse = "When debugging errors",
            body = "# Debug Helper\n\nAnalyze error messages and suggest fixes.",
        ),
        Skill(
            name = "test_writer",
            description = "Generate unit tests for given code",
            whenToUse = "When writing tests",
            body = "# Test Writer\n\nGenerate comprehensive unit tests.",
        ),
        Skill(
            name = "doc_generator",
            description = "Generate documentation from code",
            whenToUse = "When writing docs",
            body = "# Doc Generator\n\nGenerate documentation from code structure.",
        ),
        Skill(
            name = "wechat_helper",
            description = "Use the agent to operate WeChat: check capabilities, list conversations, " +
                "send text/files, open a chat with a contact. Oriented to the user's own WeChat.",
            whenToUse = "When the user asks anything in WeChat: sending a message/file to a contact, " +
                "opening a chat, checking whether WeChat actions are supported.",
            body = """
                # 微信助手

                目标: 通过已接入的 IM iLink 通道 + 无障碍桥，帮用户在自己的微信里完成操作。
                每步失败都要向用户明确说明原因，不要神秘报错。

                执行顺序:
                1. 先调用 `wechat_capabilities` 判断当前能力(发文本/图片/文件/typing/转发属于基线，
                   需先扫码绑定 iLink; 撤回/群管理/朋友圈/支付被如实标记为"未确认/受限")。
                2. 发消息/长文本 → `im_conversations` 拿 conversationId，再用 `im_send` 或 `im_send_multi`。
                3. iLink 未绑定或需要 UI 兜底 → `android_open_app`(weixin) 打开微信，配合
                   `android_read_ui` / `android_click` / `android_input` 完成。
                4. "打开与{联系人}的聊天" → 用 `wechat_open_chat`(需无障碍桥)。

                约束: 撤回/群管理/朋友圈/支付当前能力未知或受限，不要假设可做；
                支付、收款码等安全敏感操作默认需用户明确确认。
            """.trimIndent(),
        ),
    )
}