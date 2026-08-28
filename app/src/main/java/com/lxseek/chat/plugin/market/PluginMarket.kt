package com.lxseek.chat.plugin.market

import android.content.Context
import com.lxseek.chat.api.HttpClient
import com.lxseek.chat.data.McpServerConfig
import com.lxseek.chat.data.McpTransportType
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.plugin.Plugin
import com.lxseek.chat.plugin.PluginHost
import com.lxseek.chat.plugin.adapters.ToolPkgAdapter
import com.lxseek.chat.skill.Skill
import com.lxseek.chat.tool.ToolProvider
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * 插件市场服务：抓取/校验/合并市场索引，安装/卸载/启停插件，启动时离线恢复已装插件。
 *
 * 状态全部以 StateFlow 暴露给 UI；持久化经由 [SettingsRepository] 的原始 JSON 通道，
 * 数据层对市场模型不感知。
 *
 * 生命周期：
 * - 目录（[catalog]）由 [refreshCatalog] 拉取全部启用源后合并（按插件 id 去重，首个源优先）；
 * - 安装（[install]）把插件构建为 [MarketPlugin] 注册进 [PluginHost]，并持久化 [MarketInstallation]；
 * - 重启时 [restoreOnStartup] 仅凭持久化记录离线重建并注册，无需联网。
 */
class PluginMarket(
    private val context: Context,
    private val settings: SettingsRepository,
    private val host: PluginHost,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _sources = MutableStateFlow<List<MarketSource>>(emptyList())
    /** 用户添加的市场源列表。 */
    val sources: StateFlow<List<MarketSource>> = _sources.asStateFlow()

    private val _installations = MutableStateFlow<List<MarketInstallation>>(emptyList())
    /** 已安装插件记录列表。 */
    val installations: StateFlow<List<MarketInstallation>> = _installations.asStateFlow()

    private val _catalog = MutableStateFlow<List<MarketPluginMeta>>(emptyList())
    /** 合并后的在线目录（浏览列表）。 */
    val catalog: StateFlow<List<MarketPluginMeta>> = _catalog.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _lastRefreshError = MutableStateFlow<String?>(null)
    val lastRefreshError: StateFlow<String?> = _lastRefreshError.asStateFlow()

    init {
        scope.launch {
            settings.marketSourcesRaw.collect { raw ->
                val decoded = decodeList<MarketSource>(raw)
                // 内置源始终可用：旧记录未含时自动补入（可停用；删除后下次启动恢复）。
                var merged = if (decoded.isEmpty()) listOf(DEFAULT_SOURCE) else decoded
                for (builtin in BuiltinMarketSources.BUILTIN_SOURCES) {
                    if (merged.none { it.kind == builtin.kind }) {
                        merged = merged + builtin
                    }
                }
                _sources.value = merged
                if (merged != decoded) persistSources(merged)
            }
        }
        scope.launch {
            settings.marketInstalledRaw.collect { raw -> _installations.value = decodeList(raw) }
        }
        scope.launch {
            // Await the persisted value (the eager StateFlow default "" must not be trusted),
            // then restore every enabled installation offline.
            val installed = decodeList<MarketInstallation>(settings.getMarketInstalledJson())
            _installations.value = installed
            installed.filter { it.enabled }.forEach { registerRuntime(it) }
        }
    }

    // ── 目录抓取与合并 ─────────────────────────────────────────

    /** 抓取全部启用源的市场索引并合并为目录。 */
    suspend fun refreshCatalog() {
        _refreshing.value = true
        _lastRefreshError.value = null
        try {
            val enabled = _sources.value.filter { it.enabled }
            if (enabled.isEmpty()) {
                _catalog.value = emptyList()
                return
            }
            val merged = LinkedHashMap<String, MarketPluginMeta>()
            enabled.forEach { source ->
                val index = runCatching {
                    when (source.kind) {
                        MarketSourceKind.MARKET -> fetchIndex(source.indexUrl)
                        MarketSourceKind.CLAWHUB -> BuiltinMarketSources.fetchClawhubCatalog()
                        MarketSourceKind.SKILLHUB -> BuiltinMarketSources.fetchSkillhubCatalog()
                    }
                }
                index.exceptionOrNull()?.let { e ->
                    _lastRefreshError.value = "源「${source.name}」加载失败：${e.message}"
                }
                index.getOrNull()?.plugins?.forEach { meta ->
                    if (meta.id.isNotBlank() && meta.id !in merged) {
                        merged[meta.id] = meta.copy(sourceId = source.id)
                    }
                }
            }
            _catalog.value = merged.values.toList()
        } finally {
            _refreshing.value = false
        }
    }

    private suspend fun fetchIndex(url: String): MarketIndex = withContext(Dispatchers.IO) {
        val response = HttpClient.getTextResponse(url)
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        json.decodeFromString<MarketIndex>(response.body)
    }

    // ── 安装 / 卸载 / 启停 ─────────────────────────────────────

    /** 安装目录条目：拉取技能正文（SKILL）或建立连接配置（MCP），注册进宿主并持久化。 */
    suspend fun install(meta: MarketPluginMeta) {
        if (_installations.value.any { it.pluginId == meta.id }) {
            throw IllegalArgumentException("该插件已安装")
        }
        val installation = when (meta.kind) {
            MarketPluginKind.SKILL -> {
                val content = fetchSkillContent(meta)
                if (content.isBlank()) throw IllegalArgumentException("技能正文为空")
                MarketInstallation(
                    pluginId = meta.id,
                    sourceId = meta.sourceId,
                    name = meta.name,
                    version = meta.version,
                    kind = meta.kind,
                    description = meta.description,
                    author = meta.author,
                    requiresMembership = meta.requiresMembership,
                    content = content,
                )
            }
            MarketPluginKind.MCP -> {
                val serverUrl = meta.serverUrl
                    ?: throw IllegalArgumentException("MCP 插件缺少 serverUrl")
                MarketInstallation(
                    pluginId = meta.id,
                    sourceId = meta.sourceId,
                    name = meta.name,
                    version = meta.version,
                    kind = meta.kind,
                    description = meta.description,
                    author = meta.author,
                    requiresMembership = meta.requiresMembership,
                    serverUrl = serverUrl,
                    serverTransport = meta.serverTransport ?: McpTransportType.STREAMABLE_HTTP,
                    headers = meta.headers,
                )
            }
            MarketPluginKind.TOOLPKG -> {
                val downloadUrl = meta.downloadUrl
                    ?: throw IllegalArgumentException("ToolPkg 插件缺少 downloadUrl")
                val localPath = downloadToolpkg(meta.id, downloadUrl)
                // 安装前校验包结构：坏包立即报错并清理下载文件，不落安装记录。
                if (ToolPkgAdapter().adapt(File(localPath), meta.id) == null) {
                    File(localPath).delete()
                    throw IllegalArgumentException("ToolPkg 包结构无效或文件损坏")
                }
                MarketInstallation(
                    pluginId = meta.id,
                    sourceId = meta.sourceId,
                    name = meta.name,
                    version = meta.version,
                    kind = meta.kind,
                    description = meta.description,
                    author = meta.author,
                    requiresMembership = meta.requiresMembership,
                    downloadUrl = downloadUrl,
                    localPath = localPath,
                )
            }
        }
        host.register(buildPlugin(installation), initiallyEnabled = true)
        val updated = _installations.value + installation
        _installations.value = updated
        persistInstallations(updated)
    }

    /** 卸载插件：从宿主移除、删除安装记录，并清理 ToolPkg 本地文件。 */
    suspend fun uninstall(pluginId: String) {
        val removed = _installations.value.firstOrNull { it.pluginId == pluginId }
        host.unregister(pluginId)
        val updated = _installations.value.filterNot { it.pluginId == pluginId }
        _installations.value = updated
        persistInstallations(updated)
        if (removed?.kind == MarketPluginKind.TOOLPKG && removed.localPath.isNotBlank()) {
            runCatching { File(removed.localPath).delete() }
        }
    }

    /**
     * 修改已安装 MCP 插件的服务器地址与请求头：重建运行时并同步持久化记录。
     * 目录默认地址（如 127.0.0.1）在设备上不可达，用户需改为实际服务地址；
     * ModelScope 等服务还需要在此配置认证凭据（Authorization 等请求头）。
     */
    fun updateMcpConfig(
        pluginId: String,
        url: String,
        headers: Map<String, String>,
    ) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        val current = _installations.value.firstOrNull { it.pluginId == pluginId } ?: return
        if (current.kind != MarketPluginKind.MCP) return
        if (current.serverUrl == trimmed && current.headers == headers) return
        host.unregister(pluginId)
        val updated = current.copy(serverUrl = trimmed, headers = headers)
        val updatedList = _installations.value.map {
            if (it.pluginId == pluginId) updated else it
        }
        _installations.value = updatedList
        persistInstallations(updatedList)
        if (updated.enabled) host.register(buildPlugin(updated), initiallyEnabled = true)
    }

    /** 启停已安装插件，同步宿主与持久化记录。 */
    fun setEnabled(pluginId: String, enabled: Boolean) {
        if (_installations.value.none { it.pluginId == pluginId }) return
        host.setEnabled(pluginId, enabled)
        val updated = _installations.value.map {
            if (it.pluginId == pluginId) it.copy(enabled = enabled) else it
        }
        _installations.value = updated
        persistInstallations(updated)
    }

    // ── 市场源管理 ─────────────────────────────────────────────

    /** 添加市场源；相同 indexUrl 视为同一源，覆盖旧记录。 */
    fun addSource(source: MarketSource) {
        val updated = _sources.value.filterNot { it.indexUrl == source.indexUrl } + source
        _sources.value = updated
        persistSources(updated)
    }

    fun removeSource(sourceId: String) {
        val updated = _sources.value.filterNot { it.id == sourceId }
        _sources.value = updated
        persistSources(updated)
    }

    fun setSourceEnabled(sourceId: String, enabled: Boolean) {
        val updated = _sources.value.map {
            if (it.id == sourceId) it.copy(enabled = enabled) else it
        }
        _sources.value = updated
        persistSources(updated)
    }

    // ── 内部工具 ───────────────────────────────────────────────

    private suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        val response = HttpClient.getTextResponse(url)
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        response.body
    }

    /**
     * 拉取 SKILL 插件正文。对 ClawHub / SkillHub 内置源走各自的适配器；自定义源沿用
     * `manifestUrl`（SKILL.md 正文直接地址）的单文件下载。根据来源判定，规避静态清单
     * 在分页 API 源上不可用的问题。
     */
    private suspend fun fetchSkillContent(meta: MarketPluginMeta): String {
        val source = _sources.value.firstOrNull { it.id == meta.sourceId }
        return when (source?.kind) {
            MarketSourceKind.CLAWHUB -> BuiltinMarketSources.fetchClawhubSkillBody(meta.id)
            MarketSourceKind.SKILLHUB -> BuiltinMarketSources.fetchSkillhubSkillBody(meta.id)
            else -> {
                val manifestUrl = meta.manifestUrl
                    ?: throw IllegalArgumentException("技能插件缺少 manifestUrl")
                fetchText(manifestUrl)
            }
        }
    }

    /** 启动时按持久化记录重建插件并注册（离线可用）；单个坏记录不阻断整体恢复。 */
    private fun registerRuntime(installation: MarketInstallation) {
        runCatching { host.register(buildPlugin(installation), initiallyEnabled = true) }
            .onFailure {
                DebugLog.e(
                    "PluginMarket",
                    "恢复插件失败 id=${installation.pluginId} kind=${installation.kind}: ${it.message}",
                )
            }
    }

    private fun buildPlugin(installation: MarketInstallation): Plugin {
        val providers = mutableListOf<ToolProvider>()
        val skills = mutableListOf<Skill>()
        var onEnable: (() -> Unit)? = null
        var onDisable: (() -> Unit)? = null
        when (installation.kind) {
            MarketPluginKind.SKILL -> skills += Skill(
                name = skillNameFor(installation.pluginId),
                description = installation.description ?: installation.name,
                whenToUse = installation.description,
                body = installation.content,
                source = installation.sourceId,
                requiresMembership = installation.requiresMembership,
            )
            MarketPluginKind.MCP -> {
                val provider = ScopedMcpToolProvider(
                    context = context,
                    pluginId = installation.pluginId,
                    config = McpServerConfig(
                        id = installation.pluginId,
                        name = installation.name,
                        enabled = true,
                        url = installation.serverUrl,
                        transport = installation.serverTransport,
                        headers = installation.headers,
                    ),
                    scope = scope,
                )
                providers += provider
                onEnable = provider::start
                onDisable = provider::close
            }
            MarketPluginKind.TOOLPKG -> {
                val file = File(installation.localPath)
                return ToolPkgAdapter().adapt(file, installation.pluginId)
                    ?: throw IllegalStateException("ToolPkg 解析失败或本地文件缺失")
            }
        }
        return MarketPlugin(
            installation = installation,
            providers = providers,
            skills = skills,
            onEnableAction = onEnable,
            onDisableAction = onDisable,
        )
    }

    /** 下载 .toolpkg 到 filesDir 并返回本地路径（filesDir 不会被系统清理，可离线恢复）。 */
    private suspend fun downloadToolpkg(pluginId: String, url: String): String =
        withContext(Dispatchers.IO) {
            val bytes = HttpClient.getBytes(url)
                ?: throw IOException("ToolPkg 下载失败：HTTP 错误或连接中断")
            if (bytes.isEmpty()) throw IOException("ToolPkg 下载内容为空")
            val dir = File(context.filesDir, "market/toolpkg").apply { mkdirs() }
            val file = File(dir, "${safeFileName(pluginId)}.toolpkg")
            file.writeBytes(bytes)
            file.absolutePath
        }

    /** 把任意插件 id 归一化为安全文件名。 */
    private fun safeFileName(id: String): String =
        id.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
            .joinToString("")
            .trim('_', '-')
            .ifBlank { "plugin" }
            .take(64)

    /** 由插件 id 派生稳定的技能名（目录去重保证 id 唯一）。 */
    private fun skillNameFor(pluginId: String): String {
        val cleaned = pluginId
            .map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
            .joinToString("")
            .trim('_', '-')
            .ifBlank { "plugin" }
            .take(48)
        return "market_$cleaned"
    }

    private fun persistSources(list: List<MarketSource>) {
        scope.launch { settings.saveMarketSources(json.encodeToString(list)) }
    }

    private fun persistInstallations(list: List<MarketInstallation>) {
        scope.launch { settings.saveMarketInstalled(json.encodeToString(list)) }
    }

    private inline fun <reified T> decodeList(raw: String): List<T> =
        if (raw.isBlank()) emptyList()
        else runCatching { json.decodeFromString<List<T>>(raw) }.getOrDefault(emptyList())

    companion object {
        /** 官方默认市场源：首次启动无源时自动注册，用户可删除或停用。 */
        val DEFAULT_SOURCE = MarketSource(
            name = "官方源",
            indexUrl = "https://raw.githubusercontent.com/ojbkxc/Lxchat/main/market/index.json",
        )
    }
}
