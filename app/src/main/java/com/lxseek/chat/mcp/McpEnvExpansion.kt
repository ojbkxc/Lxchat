package com.lxseek.chat.mcp

import com.lxseek.chat.data.McpServerConfig

/**
 * 在 MCP 远程服务器配置中展开环境变量，对齐 cc-haha 的 `envExpansion.ts`。
 *
 * 支持 `${VAR}` 与 `${VAR:-default}` 两种写法：变量值取自该服务器配置的 `env` 映射，
 * 未命中时回退到系统环境变量；两者都缺失且未提供默认值则记为缺失（missing），
 * 调用方应拒绝建立连接，而不是带着未解析占位符去请求。
 *
 * 适用范围与 cc-haha 一致：远程服务器（http/sse）只展开 `url` 与每个 header 的值。
 */
object McpEnvExpansion {

    private val ENV_VAR_REGEX = Regex("\\$\\{([^}]+)}")

    data class ExpandedString(
        val expanded: String,
        val missingVars: List<String>,
    )

    data class ExpandedConfig(
        val config: McpServerConfig,
        val missingVars: List<String>,
    )

    /**
     * 解析单个 `${...}` 内部内容：按 `:-` 切出变量名与默认值（split 限 2 段，
     * 默认值中的 `:` 会被保留，与 cc-haha 一致）。有值返回值，否则返回默认值。
     */
    private fun resolve(content: String, env: Map<String, String>): String? {
        val sep = content.indexOf(":-")
        val varName = if (sep >= 0) content.substring(0, sep) else content
        val defaultValue = if (sep >= 0) content.substring(sep + 2) else null
        val value = env[varName]?.takeIf(String::isNotBlank)
            ?: System.getenv(varName)?.takeIf(String::isNotBlank)
        return value ?: defaultValue
    }

    /** 展开单条字符串中的全部变量引用。 */
    fun expandInString(value: String, env: Map<String, String>): ExpandedString {
        val missing = mutableListOf<String>()
        val expanded = ENV_VAR_REGEX.replace(value) { match ->
            val content = match.groupValues[1]
            val resolved = resolve(content, env)
            if (resolved != null) {
                resolved
            } else {
                missing += content.substringBefore(":-")
                match.value // 保留原占位符，便于调试缺失项
            }
        }
        return ExpandedString(expanded, missing.distinct())
    }

    /** 展开服务器配置的 `url` 与每个 header 值，收集缺失变量。 */
    fun expand(config: McpServerConfig): ExpandedConfig {
        val env = config.env
        val missing = mutableListOf<String>()
        fun expand(value: String): String {
            val result = expandInString(value, env)
            missing += result.missingVars
            return result.expanded
        }
        return ExpandedConfig(
            config = config.copy(
                url = expand(config.url),
                headers = config.headers.mapValues { (_, value) -> expand(value) },
            ),
            missingVars = missing.distinct(),
        )
    }
}
