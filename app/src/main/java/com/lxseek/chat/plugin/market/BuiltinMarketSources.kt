package com.lxseek.chat.plugin.market

import com.lxseek.chat.api.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.URLEncoder

/**
 * ClawHub 与 SkillHub 两个内置市场源的适配器。
 *
 * 二者都是 REST 分页 API（动态），与自定义源的静态 `MarketIndex` 清单格式不同，
 * 因此这里把它们的列表与 SKILL 正文拉取逻辑统一成可供 [PluginMarket] 使用的函数：
 * - 列表 → [MarketIndex]（仅首页一批，按下载量倒序，作为目录浏览）。
 * - SKILL 正文 → 返回可注册进 SkillHost 的 SKILL.md 文本。
 *
 * 上游接口（已对照 cc-haha 的 provider 校验过）：
 * - ClawHub (https://clawhub.ai)：`GET /api/v1/skills?limit=&sort=downloads`
 *   `→ {items:[{slug,displayName,summary,topics,stats,latestVersion}], nextCursor}`；
 *   `GET /api/v1/skills/{slug} → {skill:{description,…}}`，description 即 SKILL.md 全文。
 * - SkillHub (https://api.skillhub.cn)：`GET /api/skills?page=&pageSize=`
 *   `→ {code:0,data:{skills:[…],total}}`；
 *   `GET /api/v1/skills/{slug}/files` 列出文件、`GET /api/v1/skills/{slug}/file?path=` 取正文。
 */
object BuiltinMarketSources {

    const val PREFIX_CLAWHUB = "clawhub"
    const val PREFIX_SKILLHUB = "skillhub"

    private const val BASE_CLAWHUB = "https://clawhub.ai"
    private const val BASE_SKILLHUB = "https://api.skillhub.cn"
    private const val LIST_LIMIT = 50

    private val json = Json { ignoreUnknownKeys = true }

    /** 三个内置市场源预设，仅在源列表中缺该 kind 时注入；用户可停用（不可删除）。 */
    val BUILTIN_SOURCES: List<MarketSource> = listOf(
        MarketSource(id = "builtin-clawhub", name = "ClawHub 技能市场", indexUrl = "$BASE_CLAWHUB", kind = MarketSourceKind.CLAWHUB),
        MarketSource(id = "builtin-skillhub", name = "SkillHub 技能市场", indexUrl = "$BASE_SKILLHUB", kind = MarketSourceKind.SKILLHUB),
        MarketSource(id = "builtin-asset", name = "内置技能库", indexUrl = "assets://builtin-skills-index.json", kind = MarketSourceKind.BUILTIN_ASSET),
    )

    // ── Builtin RUNTIME engines ────────────────────────────────

    /**
     * Built-in RUNTIME engine catalog: the only true runtime engines are Node.js, Python and
     * ffmpeg. External market sources only carry SKILL entries today, so without these built-in
     * RUNTIME metas the install button on the runtime engines page stays disabled
     * (catalog.firstOrNull { it.id == engineId } always returns null).
     *
     * webnovel-writer and inkos are NOT independent runtimes — they are script applications that
     * depend on the Python / Node.js runtimes and are exposed as SKILL entries via
     * [fetchBuiltinSkillCatalog] instead. Their tool invocations still resolve engine ids via
     * [com.lxseek.chat.runtime.RuntimeEngineType] constants (NODE_INKOS / PYTHON_WEB_NOVEL).
     */
    fun fetchBuiltinRuntimeCatalog(): MarketIndex = MarketIndex(
        plugins = listOf(
            MarketPluginMeta(
                id = "runtime-node",
                name = "Node.js",
                version = "7.7.2",
                kind = MarketPluginKind.RUNTIME,
                description = "Node.js runtime",
                downloadUrl = "https://github.com/ojbkxc/lxchat-runtime/releases/download/node-v7.7.2/node-7.7.2-android-arm64.zip",
                runtimeType = "node",
                versions = listOf("7.7.2"),
                minVersion = "7.7.2",
            ),
            MarketPluginMeta(
                id = "runtime-python",
                name = "Python",
                version = "3.11.0",
                kind = MarketPluginKind.RUNTIME,
                description = "Python 3.11 runtime",
                downloadUrl = "https://github.com/ojbkxc/lxchat-runtime/releases/download/python-v3.11.0/python-3.11.0-android-arm64.zip",
                runtimeType = "python",
                versions = listOf("3.11.0"),
                minVersion = "3.11.0",
            ),
            MarketPluginMeta(
                id = "runtime-ffmpeg",
                name = "ffmpeg",
                version = "9.0.0",
                kind = MarketPluginKind.RUNTIME,
                description = "ffmpeg multimedia processing",
                downloadUrl = "https://github.com/ojbkxc/lxchat-runtime/releases/download/ffmpeg-v9.0.0/ffmpeg-9.0.0-android-arm64.zip",
                runtimeType = "ffmpeg",
                versions = listOf("9.0.0"),
                minVersion = "9.0.0",
            ),
        ),
    )

    // ── Builtin SKILL plugins (webnovel-writer / inkos) ────────

    /**
     * Built-in SKILL catalog for webnovel-writer and inkos. Both are script applications that
     * depend on the Python / Node.js runtimes rather than being standalone runtime engines, so
     * they are classified as SKILL plugins and installed from the skill market instead of the
     * runtime engines page.
     *
     * Ids are kept aligned with [com.lxseek.chat.runtime.RuntimeEngineType] constants
     * (NODE_INKOS / PYTHON_WEB_NOVEL) so that the existing tool invocation logic in
     * RuntimeToolProvider (novelInkos / webNovel) keeps resolving them via ensureStarted.
     * downloadUrl is retained for the future SKILL package download flow.
     */
    fun fetchBuiltinSkillCatalog(): MarketIndex = MarketIndex(
        plugins = listOf(
            MarketPluginMeta(
                id = "runtime-node-inkos",
                name = "inkos",
                version = "7.7.2",
                kind = MarketPluginKind.SKILL,
                description = "inkos web novel writing skill (requires Node.js runtime >= 22.5)",
                downloadUrl = "https://github.com/ojbkxc/lxchat-runtime/releases/download/node-v7.7.2/node-7.7.2-android-arm64.zip",
                runtimeType = "node",
                versions = listOf("7.7.2"),
                minVersion = "7.7.2",
            ),
            MarketPluginMeta(
                id = "runtime-python-webnovel",
                name = "webnovel-writer",
                version = "1.0.0",
                kind = MarketPluginKind.SKILL,
                description = "webnovel-writer web novel writing skill (GPL-3.0, requires Python runtime >= 3.10)",
                downloadUrl = "https://github.com/ojbkxc/lxchat-runtime/releases/download/webnovel-v1.0.0/webnovel-1.0.0-android-arm64.zip",
                runtimeType = "python-webnovel",
                versions = listOf("1.0.0"),
                minVersion = "1.0.0",
            ),
        ),
    )

    // ── ClawHub ─────────────────────────────────────────────────

    @Serializable
    private data class ClawhubListResponse(
        val items: List<ClawhubListItem>? = null,
        val nextCursor: String? = null,
    )

    @Serializable
    private data class ClawhubListItem(
        val slug: String? = null,
        val displayName: String? = null,
        val summary: String? = null,
        val description: String? = null,
    )

    @Serializable
    private data class ClawhubDetailResponse(
        val skill: ClawhubListItem? = null,
    )

    suspend fun fetchClawhubCatalog(): MarketIndex {
        val url = "$BASE_CLAWHUB/api/v1/skills?limit=$LIST_LIMIT&sort=downloads"
        val body = httpJson(url)
        val parsed = json.decodeFromString<ClawhubListResponse>(body)
        val plugins = (parsed.items ?: emptyList())
            .filter { !it.slug.isNullOrBlank() }
            .map { it.toMeta() }
        return MarketIndex(plugins = plugins)
    }

    suspend fun fetchClawhubSkillBody(pluginId: String): String {
        val slug = slugAfterPrefix(pluginId, PREFIX_CLAWHUB)
        val url = "$BASE_CLAWHUB/api/v1/skills/${encode(slug)}"
        // 与 SkillHub 路径一致：404 等网络异常时返回空串，避免向上抛 IOException 中断安装。
        val parsed = runCatching {
            json.decodeFromString<ClawhubDetailResponse>(httpJson(url))
        }.getOrNull() ?: return ""
        return parsed.skill?.description?.takeIf { it.isNotBlank() }
            ?: parsed.skill?.summary ?: ""
    }

    // ── SkillHub ────────────────────────────────────────────────

    @Serializable
    private data class SkillhubEnvelope<T>(
        val code: Int = -1,
        val data: T? = null,
    )

    @Serializable
    private data class SkillhubListData(
        val skills: List<SkillhubListItem>? = null,
        val total: Int = 0,
    )

    @Serializable
    private data class SkillhubListItem(
        val slug: String? = null,
        val name: String? = null,
        val displayName: String? = null,
        val summary: String? = null,
        val summary_zh: String? = null,
        val description: String? = null,
        val description_zh: String? = null,
        val iconUrl: String? = null,
        val version: String? = null,
        val updated_at: Long? = null,
    )

    @Serializable
    private data class SkillhubFilesResponse(
        val files: List<SkillhubFileEntry>? = null,
    )

    @Serializable
    private data class SkillhubFileEntry(
        val path: String? = null,
    )

    suspend fun fetchSkillhubCatalog(): MarketIndex {
        val url = "$BASE_SKILLHUB/api/skills?page=1&pageSize=$LIST_LIMIT"
        val parsed = json.decodeFromString<SkillhubEnvelope<SkillhubListData>>(httpJson(url))
        val plugins = (parsed.data?.skills ?: emptyList())
            .filter { !it.slug.isNullOrBlank() }
            .map { it.toMeta() }
        return MarketIndex(plugins = plugins)
    }

    suspend fun fetchSkillhubSkillBody(pluginId: String): String {
        val slug = slugAfterPrefix(pluginId, PREFIX_SKILLHUB)
        var body = ""
        val files = runCatching {
            json.decodeFromString<SkillhubFilesResponse>(httpJson("$BASE_SKILLHUB/api/v1/skills/${encode(slug)}/files"))
        }.getOrNull()
        if (files?.files?.any { it.path == "SKILL.md" } == true) {
            body = runCatching { httpText("$BASE_SKILLHUB/api/v1/skills/${encode(slug)}/file?path=${encode("SKILL.md")}") }
                .getOrNull()
                ?.trim() ?: ""
        }
        if (body.isNotBlank()) return body
        // 正文缺失时回退到详情字段摘要，保证至少有一条可用的技能描述。
        // 详情接口直接返回 {skill:{…}, owner, latestVersion, securityReports}（根级，非 envelope）。
        val detail = runCatching {
            json.decodeFromString<JsonObject>(httpJson("$BASE_SKILLHUB/api/v1/skills/${encode(slug)}"))
        }.getOrNull()
        val skill = detail?.get("skill") as? JsonObject
        return skill?.firstNonBlank("description_zh", "description", "summary_zh", "summary").orEmpty()
    }

    // ── 映射与工具 ──────────────────────────────────────────────

    private fun ClawhubListItem.toMeta(): MarketPluginMeta = MarketPluginMeta(
        id = "$PREFIX_CLAWHUB:$slug",
        name = displayName ?: slug ?: "",
        version = "1.0.0",
        kind = MarketPluginKind.SKILL,
        description = summary ?: "",
        iconUrl = null,
        homepage = "$BASE_CLAWHUB/$slug",
    )

    private fun SkillhubListItem.toMeta(): MarketPluginMeta = MarketPluginMeta(
        id = "$PREFIX_SKILLHUB:$slug",
        name = name ?: displayName ?: slug ?: "",
        version = version ?: "1.0.0",
        kind = MarketPluginKind.SKILL,
        description = description_zh ?: description ?: summary_zh ?: summary ?: "",
        iconUrl = iconUrl,
        homepage = "$BASE_SKILLHUB/skill/$slug",
    )

    /** 从 `prefix:slug` 形式的目录 id 提取 slug；非法用 raw 兜底。 */
    private fun slugAfterPrefix(pluginId: String, prefix: String): String {
        val prefixWithColon = "$prefix:"
        return if (pluginId.startsWith(prefixWithColon)) {
            pluginId.removePrefix(prefixWithColon)
        } else {
            pluginId
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** 按顺序返回 obj 中第一个非空字符串字段值。 */
    private fun JsonObject.firstNonBlank(vararg keys: String): String? {
        for (key in keys) {
            // 字段可能是 JsonObject/JsonArray，用 as? 安全转换避免 ClassCastException。
            val text = (this[key] as? JsonPrimitive)?.contentOrNull
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private suspend fun httpJson(url: String): String = withContext(Dispatchers.IO) {
        val response = HttpClient.getTextResponse(url)
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        response.body
    }

    private suspend fun httpText(url: String): String = withContext(Dispatchers.IO) {
        val response = HttpClient.getTextResponse(url)
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        response.body
    }
}