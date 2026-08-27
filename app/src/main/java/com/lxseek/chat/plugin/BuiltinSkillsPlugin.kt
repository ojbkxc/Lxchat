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
            name = "refactor_advisor",
            description = "Suggest refactoring improvements",
            whenToUse = "When refactoring",
            body = "# Refactor Advisor\n\nSuggest refactoring improvements for better code structure.",
        ),
    )
}