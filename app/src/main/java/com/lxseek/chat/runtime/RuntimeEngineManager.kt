package com.lxseek.chat.runtime

import android.content.Context
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.model.ModelId
import com.lxseek.chat.plugin.Plugin
import com.lxseek.chat.plugin.PluginCategory
import com.lxseek.chat.plugin.PluginContext
import com.lxseek.chat.plugin.PluginManifest
import com.lxseek.chat.plugin.market.MarketInstallation
import com.lxseek.chat.plugin.market.MarketPluginKind
import com.lxseek.chat.plugin.market.MarketPluginMeta
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * 运行时引擎编排器：统一协调「下载/安装（自动匹配版本）→ 启动（版本二次校验）→ 创作 →
 * 空闲自动停止」的完整链路，并向 UI / 工具暴露状态。
 *
 * 安装状态以 market 的 RUNTIME 安装记录为唯一数据源；版本命中 / 约束判断由
 * [RuntimeVersion] 完成。Node / Python / ffmpeg 走同一套链路，仅引擎类型不同。
 */
class RuntimeEngineManager(
    private val context: Context,
    val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    val packageManager: RuntimePackageManager = RuntimePackageManager(context)
    val processManager: RuntimeProcessManager = RuntimeProcessManager(context, scope)

    /** 由 AppContainer 在 [com.lxseek.chat.plugin.market.PluginMarket] 创建后注入。 */
    @Volatile
    var market: com.lxseek.chat.plugin.market.PluginMarket? = null

    val toolProvider: RuntimeToolProvider by lazy { RuntimeToolProvider(this) }

    init {
        processManager.onStop { engineId -> removeEngineFiles(engineId) }
    }

    // ── 安装（自动匹配版本） ───────────────────────────────

    /** 安装结果：选定的版本与包装后的插件。 */
    data class InstallResult(val version: String, val plugin: Plugin)

    /**
     * 安装指定引擎。version 为空时按约束自动匹配（取候选清单中满足引擎 min_version 与本机
     * 已装版本之外的最高兼容版本，优先最高）；低于约束的版本会被拒绝并说明原因。
     * 下载/解压为阻塞操作，交由 [RuntimePackageManager.install] 切到 IO 线程执行。
     */
    suspend fun installRuntime(meta: MarketPluginMeta, requestedVersion: String?): InstallResult {
        val engineId = meta.id
        // 自动匹配：在 meta.versions（缺省为 [meta.version]）中选择满足 meta.minVersion 的最高版本。
        val min = Version.parse(meta.minVersion)
        val candidates = meta.versions.ifEmpty { if (meta.version.isBlank()) emptyList() else listOf(meta.version) }
        if (candidates.isEmpty()) throw IllegalStateException("Engine $engineId has no declared versions")
        val selected = requestedVersion?.let { req ->
            if (req !in candidates) throw IllegalArgumentException(
                "Version $req not in available versions ${candidates.joinToString("/")} for engine $engineId",
            )
            req
        } ?: candidates.filter { min == Version.NONE || Version.parse(it).satisfiesMin(min) }
            .maxByOrNull { Version.parse(it) }
            ?: throw IllegalStateException(
                "Engine $engineId has no candidate satisfying min version ${meta.minVersion ?: "unknown"}",
            )
        if (min != Version.NONE && !Version.parse(selected).satisfiesMin(min)) {
            throw IllegalStateException(
                "Version $selected below min version ${meta.minVersion} for engine $engineId",
            )
        }
        val url = meta.downloadUrl ?: throw IllegalArgumentException("Engine $engineId missing downloadUrl")
        val root = packageManager.install(engineId, selected, url)
        val manifest = packageManager.readManifest(engineId, selected)
            ?: throw IllegalStateException("Engine $engineId: cannot read manifest after install")
        return InstallResult(selected, RuntimeEnginePlugin(engineId, selected, manifest, processManager))
    }

    /** 重建已装引擎插件（启动时离线恢复，无需重新下载）。 */
    fun buildRuntimePlugin(installation: MarketInstallation): Plugin {
        val version = installation.version
        val manifest = packageManager.readManifest(installation.pluginId, version)
            ?: RuntimeManifest(id = installation.pluginId, type = runtimeTypeOf(installation), version = version)
        return RuntimeEnginePlugin(installation.pluginId, version, manifest, processManager)
    }

    /** 卸载引擎：先停进程，再删除文件。 */
    fun uninstallRuntime(engineId: String) {
        processManager.stop(engineId)
        packageManager.removeEngine(engineId)
    }

    /** 删除引擎文件（进程已停后的兜底清理）。 */
    private fun removeEngineFiles(engineId: String) {
        runCatching { packageManager.removeEngine(engineId) }
    }

    // ── 启停 / 状态 ────────────────────────────────────────

    /** 启动引擎；未安装时报错提示先 market_install；已运行则幂等。返回注入进程的模型环境变量。 */
    suspend fun start(engineId: String, requirement: RuntimeRequirement? = null): Map<String, String> =
        ensureStarted(engineId, null, requirement)

    /**
     * 确保引擎在运行（等价 runtime_start）。返回注入到进程的模型环境变量。
     * 未安装 / 版本不满足约束时抛异常并给出明确原因。
     */
    suspend fun ensureStarted(
        engineId: String,
        requestedVersion: String?,
        requirement: RuntimeRequirement?,
        requirementOverride: RuntimeRequirement? = null,
    ): Map<String, String> {
        val installation = installationOf(engineId)
            ?: throw IllegalStateException("Engine $engineId not installed. Please install via market_install first.")
        val version = installation.version
        val manifest = packageManager.readManifest(engineId, version)
            ?: throw IllegalStateException("Engine $engineId installed but local files missing, please reinstall")
        // 启动前二次校验版本（优先技能约束覆盖，否则用 manifest 自身声明）。
        versionRequirementCheck(engineId, version, requirementOverride ?: requirement)
        // 依赖引擎：自动确保已安装，并解析其安装根目录（供 {depRoot} 引用原生命令环境）。
        val depRoot = if (manifest.requiresEngine.isNullOrBlank()) {
            null
        } else {
            ensureDependencyRoot(manifest.requiresEngine!!)?.absolutePath
        }
        val root = packageManager.versionRoot(engineId, version)
        val command = buildCommand(manifest, root.absolutePath, depRoot)
        val env = injectedEnv(engineId, manifest, version, root.absolutePath, depRoot)
        if (processManager.isRunning(engineId)) {
            processManager.touch(engineId)
            return env
        }
        processManager.start(engineId, command, env, root)
        return env
    }

    /**
     * 确保依赖引擎（如 runtime-python）已安装，返回其安装根目录。未安装时自动从市场
     * 目录解析 meta 并按需下载安装；目录中不存在则返回 null（调用方决定是否报错）。
     *
     * 安装失败时抛出异常向上传播，由调用方 [ensureStarted] 的 UI 层 try-catch 处理，
     * 不在此处用 runCatching 静默吞错——否则用户看不到依赖引擎安装失败的根因。
     */
    suspend fun ensureDependencyRoot(requiresEngine: String): File? {
        val existing = installationOf(requiresEngine)
        if (existing != null) {
            return packageManager.versionRoot(requiresEngine, existing.version)
        }
        val meta = resolveCatalogMeta(requiresEngine) ?: return null
        val market = market ?: return null
        // Let exceptions propagate so the caller can surface the real failure reason
        // (network error, invalid package, missing manifest, etc.) instead of swallowing
        // them into a silent null that hides the root cause.
        market.installRuntimeInternal(meta, null)
        val installation = installationOf(requiresEngine) ?: return null
        return packageManager.versionRoot(requiresEngine, installation.version)
    }

    /** 停止引擎。 */
    fun stop(engineId: String): Boolean = processManager.stop(engineId)

    /** 引擎状态快照。 */
    fun status(engineId: String): RuntimeStatus {
        val installation = installationOf(engineId)
        val version = installation?.version
        val installedVersions = packageManager.installedVersions(engineId)
        return RuntimeStatus(
            engineId = engineId,
            installed = installation != null,
            installedVersion = version,
            installedVersions = installedVersions,
            running = processManager.isRunning(engineId),
        )
    }

    fun isRunning(engineId: String): Boolean = processManager.isRunning(engineId)

    // ── 查询 ───────────────────────────────────────────────

    /** 从 market 目录按 id 解析 RUNTIME 条目（供 market_install）。 */
    fun resolveCatalogMeta(engineId: String): MarketPluginMeta? =
        market?.catalog?.value?.firstOrNull { it.id == engineId && it.kind == MarketPluginKind.RUNTIME }

    /** 当前已安装的 RUNTIME 安装记录。 */
    fun installationOf(engineId: String): MarketInstallation? =
        market?.installations?.value?.firstOrNull {
            it.kind == MarketPluginKind.RUNTIME && it.pluginId == engineId
        }

    /** 已落盘的多版本。 */
    fun installedVersions(engineId: String): List<String> = packageManager.installedVersions(engineId)

    // ── 模型 Key 注入 ──────────────────────────────────────

    /** 读取用户默认模型服务的配置并构建注入进程的环境变量。未配置任何模型服务时返回空。 */
    suspend fun buildModelEnv(): Map<String, String> {
        val selected = settings.selectedModel.value
        if (selected.isBlank()) return emptyMap()
        val mid = ModelId.parse(selected)
        val provider = mid.providerName
        val apiKey = settings.awaitActiveKey(provider) ?: settings.resolveActiveKey(provider)
        if (apiKey.isNullOrBlank()) return emptyMap()
        val baseUrl = settings.providerBaseUrls.value[provider]
        val model = mid.modelName
        return buildMap {
            put("INKOS_LLM_PROVIDER", provider)
            put("INKOS_LLM_MODEL", model)
            put("INKOS_LLM_API_KEY", apiKey)
            baseUrl?.takeIf(String::isNotBlank)?.let { put("INKOS_LLM_BASE_URL", it) }
            // 通用变量备用。
            put("LCHAT_LLM_PROVIDER", provider)
            put("LCHAT_LLM_MODEL", model)
            put("LCHAT_LLM_API_KEY", apiKey)
            baseUrl?.takeIf(String::isNotBlank)?.let { put("LCHAT_LLM_BASE_URL", it) }
        }
    }

    // ── 内部 ───────────────────────────────────────────────

    private suspend fun injectedEnv(engineId: String, manifest: RuntimeManifest, version: String, root: String, depRoot: String?): Map<String, String> {
        val env = mutableMapOf<String, String>()
        env["RUNTIME_ROOT"] = root
        env["RUNTIME_ENGINE"] = engineId
        env["RUNTIME_VERSION"] = version
        if (!depRoot.isNullOrBlank()) {
            env["RUNTIME_DEPENDENCY_ROOT"] = depRoot
            // 简化脚本侧引用原生命令环境：注入 python 可执行路径。
            if (manifest.type.lowercase() == "python-webnovel" || manifest.type.lowercase() == "webnovel") {
                env["PYTHON_BIN"] = File(depRoot, "python").absolutePath
            }
        }
        env.putAll(buildModelEnv())
        // AGPL 合规声明随引擎包附带；此处向进程注入来源与许可证，便于引擎侧读取/展示。
        manifest.license?.let { env["RUNTIME_LICENSE"] = it }
        manifest.sourceUrl?.let { env["RUNTIME_SOURCE_URL"] = it }
        return env
    }

    private fun buildCommand(manifest: RuntimeManifest, root: String, depRoot: String? = null): List<String> {
        val template = manifest.startCommand
        if (template.isNotEmpty()) {
            return template.map { it.replace("{root}", root).replace("{depRoot}", depRoot ?: "") }
        }
        // 兜底：按类型推断可执行入口。
        return when (manifest.type.lowercase()) {
            "node" -> listOf(File(root, "node").absolutePath)
            "python" -> listOf(File(root, "python").absolutePath)
            "ffmpeg" -> listOf(File(root, "ffmpeg").absolutePath)
            else -> throw IllegalStateException("Unknown engine type: ${manifest.type}, cannot build start command")
        }
    }

    /** 校验引擎版本满足运行时约束（低于技能声明版本的组合无法启动）。 */
    private fun versionRequirementCheck(engineId: String, version: String, requirement: RuntimeRequirement?) {
        if (requirement == null) return
        val actual = Version.parse(version)
        val min = Version.parse(requirement.minVersion)
        if (min != Version.NONE && !actual.satisfiesMin(min)) {
            throw IllegalStateException(
                "Engine $engineId version $version below skill requirement ${requirement.runtime} >= ${requirement.minVersion}, cannot start",
            )
        }
    }

    private fun runtimeTypeOf(installation: MarketInstallation): String =
        installation.runtimeType.ifBlank { RuntimeEngineType.fromId(installation.pluginId) ?: "" }

    private companion object {
        const val TAG = "RuntimeEngineMgr"
    }
}

/**
 * 引擎插件包装：主要职责是驱动进程生命周期——onDisable 时强制停止对应引擎进程，卸载时不留残留。
 * 引擎工具由常驻的 [RuntimeToolProvider] 动态披露，不在此重复注册。
 */
class RuntimeEnginePlugin(
    private val engineId: String,
    private val version: String,
    private val runtimeManifest: RuntimeManifest,
    private val processManager: RuntimeProcessManager,
) : Plugin {
    override val manifest: PluginManifest = PluginManifest(
        id = engineId,
        name = engineDisplayName(engineId),
        version = version,
        category = PluginCategory.External,
        description = "运行时引擎：${runtimeManifest.type}${runtimeManifest.binarySource?.let { "（$it）" }.orEmpty()}",
        builtIn = false,
    )

    override fun onEnable(context: PluginContext) {
        // 引擎仅在 AI 调用工具时按需启动，不常驻。
    }

    override fun onDisable() {
        processManager.stop(engineId)
    }

    companion object {
        /** 引擎展示名（含文献/来源附注）。 */
        fun engineDisplayName(engineId: String): String = when (engineId) {
            "runtime-node" -> "Node.js"
            RuntimeEngineType.NODE_INKOS -> "Node + inkos"
            RuntimeEngineType.PYTHON_WEB_NOVEL -> "webnovel-writer"
            "runtime-python" -> "Python"
            "runtime-ffmpeg" -> "ffmpeg"
            else -> engineId
        }
    }
}