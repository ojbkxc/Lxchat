package com.lxseek.chat.plugin.market

import com.lxseek.chat.data.McpTransportType
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 插件市场数据模型。
 *
 * 三层模型：
 * - [MarketSource]：用户添加的市场源（本地持久化，指向一个 [MarketIndex] JSON）。
 * - [MarketIndex] / [MarketPluginMeta]：从源抓取的在线目录（浏览列表）。
 * - [MarketInstallation]：已安装插件记录（本地持久化，含重建运行时所需的全部字段，
 *   因此应用重启后无需联网即可恢复已安装插件）。
 */
enum class MarketPluginKind {
    /** 技能插件：拉取 SKILL.md 正文注册进 SkillHost。 */
    SKILL,

    /** MCP 插件：按 serverUrl/transport 建立单服务连接暴露工具。 */
    MCP,

    /** ToolPkg 插件：下载 .toolpkg ZIP，经 ToolPkgAdapter 解析为 Plugin。 */
    TOOLPKG,
}

/** 用户添加的市场源。id 在创建时生成，安装记录通过 sourceId 关联回来源。 */
@Serializable
data class MarketSource(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val indexUrl: String,
    val enabled: Boolean = true,
)

/** 市场索引：一个源 URL 返回的 JSON 根对象。 */
@Serializable
data class MarketIndex(
    val schema: String = "1.0",
    val generatedAt: Long = 0,
    val plugins: List<MarketPluginMeta> = emptyList(),
)

/** 目录中的单个插件条目（来自市场索引）。 */
@Serializable
data class MarketPluginMeta(
    val id: String,
    val name: String,
    val version: String,
    val kind: MarketPluginKind = MarketPluginKind.SKILL,
    val author: String? = null,
    val description: String? = null,
    val iconUrl: String? = null,
    val homepage: String? = null,
    /** SKILL 插件：SKILL.md 正文的下载地址。 */
    val manifestUrl: String? = null,
    /** MCP 插件：服务端点。 */
    val serverUrl: String? = null,
    val serverTransport: McpTransportType? = null,
    /** MCP 插件：附加请求头（如 Authorization: Bearer <token>），可让用户在软件内配置凭据。 */
    val headers: Map<String, String> = emptyMap(),
    /** TOOLPKG 插件：.toolpkg ZIP 的下载地址。 */
    val downloadUrl: String? = null,
    val requiresMembership: Boolean = false,
    /** 合并目录时由市场服务填充：本条来自哪个源。 */
    val sourceId: String = "",
)

/**
 * 已安装插件记录。持久化全部字段以支持离线重建：
 * SKILL 插件把拉取到的正文存进 [content]；MCP 插件存 [serverUrl]/[serverTransport]。
 */
@Serializable
data class MarketInstallation(
    val pluginId: String,
    val sourceId: String,
    val name: String,
    val version: String,
    val kind: MarketPluginKind,
    val description: String? = null,
    val author: String? = null,
    val requiresMembership: Boolean = false,
    /** SKILL 插件：SKILL.md 正文。 */
    val content: String = "",
    /** MCP 插件：服务端点。 */
    val serverUrl: String = "",
    val serverTransport: McpTransportType = McpTransportType.STREAMABLE_HTTP,
    /** MCP 插件：附加请求头（离线恢复时凭此重建认证连接）。 */
    val headers: Map<String, String> = emptyMap(),
    /** TOOLPKG 插件：下载源地址与本地落盘路径（离线恢复时凭 localPath 重建）。 */
    val downloadUrl: String = "",
    val localPath: String = "",
    val installedAt: Long = System.currentTimeMillis(),
    val enabled: Boolean = true,
)
