package com.lxseek.chat.skill

import android.content.Context
import java.io.File

/**
 * 用户自建技能的持久化存储：把模型通过 `create_skill` / `update_skill` 沉淀出来的
 * SKILL.md 写到 `filesDir/skills_user/`，应用启动时再 [loadAll] 回 [SkillHost] 注册。
 *
 * 与市场安装的技能（PluginMarket 管理）分离：这里的文件完全由用户对话生成，
 * 支持删除/编辑，是「从对话沉淀技能（/learn）」能力的地基。
 */
class UserSkillStore(context: Context) {

    private val skillsDir: File =
        File(context.filesDir, "skills_user").also { it.mkdirs() }

    /** 该技能是否是用户自建（文件存在于此目录）。 */
    fun isUserSkill(name: String): Boolean = fileFor(name).exists()

    /** 保存一个技能为 SKILL.md（覆盖已存在同名文件）。返回写入的文件路径。 */
    fun save(skill: Skill): String {
        val file = fileFor(skill.name)
        file.writeText(renderSkillDoc(skill))
        return file.absolutePath
    }

    /** 删除用户技能文件；不存在时返回 false。 */
    fun delete(name: String): Boolean = fileFor(name).delete()

    /** 读取全部用户技能（每个文件解析为 [Skill]）。 */
    fun loadAll(): List<Skill> = skillsDir.listFiles()
        ?.filter { it.extension == "md" }
        ?.mapNotNull { SkillParser.parse(it.readText(), source = it.absolutePath) }
        ?.sortedBy { it.name }
        ?: emptyList()

    /** 列出技能目录下全部 .md 文件（含无法解析的），导出/REPLACE 导入用。 */
    fun listSkillFiles(): List<File> =
        skillsDir.listFiles()?.filter { it.isFile && it.extension == "md" } ?: emptyList()

    /** 删除目录下全部技能文件（REPLACE 导入前清场）。返回删除数量。 */
    fun deleteAll(): Int = listSkillFiles().count { it.delete() }

    /** 根据技能名定位文件（做路径净化，防止目录穿越）。 */
    fun fileFor(name: String): File {
        val sanitized = name.trim()
            .replace(Regex("""[/\\:]"""), "_")
            .takeIf { it.isNotEmpty() } ?: "untitled"
        return File(skillsDir, if (sanitized.endsWith(".md")) sanitized else "$sanitized.md")
    }

    /** 把 [Skill] 渲染回带 YAML frontmatter 的 SKILL.md 文本。 */
    private fun renderSkillDoc(skill: Skill): String = buildString {
        appendLine("---")
        appendLine("name: ${skill.name}")
        appendLine("description: ${skill.description}")
        skill.whenToUse?.takeIf { it.isNotBlank() }?.let { appendLine("when_to_use: $it") }
        if (skill.allowedTools.isNotEmpty()) {
            appendLine("allowed-tools: ${skill.allowedTools.joinToString(", ")}")
        }
        if (skill.paths.isNotEmpty()) {
            appendLine("paths: ${skill.paths.joinToString(", ")}")
        }
        skill.context?.takeIf { it.isNotBlank() }?.let { appendLine("context: $it") }
        skill.model?.takeIf { it.isNotBlank() }?.let { appendLine("model: $it") }
        if (skill.requiresMembership) appendLine("requires_membership: true")
        skill.chainedTo?.takeIf { it.isNotBlank() }?.let { appendLine("chained_to: $it") }
        if (skill.parameters.isNotEmpty()) {
            appendLine("parameters:")
            skill.parameters.forEach { param ->
                appendLine("  - name: ${param.name}")
                appendLine("    type: ${param.type}")
                if (param.description.isNotBlank()) appendLine("    description: ${param.description}")
                if (param.required) appendLine("    required: true")
                param.default?.let { appendLine("    default: $it") }
                if (param.enumValues.isNotEmpty()) {
                    appendLine("    enumValues: [${param.enumValues.joinToString(", ")}]")
                }
            }
        }
        appendLine("---")
        appendLine()
        append(skill.body.trim())
        appendLine()
    }
}
