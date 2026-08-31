package com.lxseek.chat.api.oauth

import android.content.Context
import com.lxseek.chat.util.DebugLog
import org.json.JSONObject
import java.io.File

/**
 * token 端点响应的解析结果：内存中的完整 token（含 access token）。
 * 只在登录/刷新的瞬间存在，不整体落盘——见 [StoredOAuthTokens] 的说明。
 */
data class ParsedOAuthTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Long?,
    val email: String?,
    val accountId: String?,
)

/**
 * OAuth 会话需要持久化的元数据（refresh token、过期时间、账号标识）。
 *
 * M4 修复：access token 明文不再落盘本文件——它的唯一持久层是
 * SettingsRepository 的活动 API Key（Provider 请求路径本来就从那里取值），
 * 登录/刷新后由 Manager 负责同步过去。旧版文件里的 `accessToken` 字段在
 * 读取时被忽略，向后兼容。
 */
data class StoredOAuthTokens(
    val refreshToken: String?,
    val expiresAt: Long?,
    val email: String?,
    /** Provider 专属账号标识（ChatGPT 的 chatgpt_account_id；Grok 恒为 null）。 */
    val accountId: String? = null,
)

fun ParsedOAuthTokens.toStored(): StoredOAuthTokens = StoredOAuthTokens(
    refreshToken = refreshToken,
    expiresAt = expiresAt,
    email = email,
    accountId = accountId,
)

/**
 * Provider 无关的 OAuth token 元数据存储（R1 重构），落到 App 私有目录
 * `filesDir/<fileName>`。
 *
 * 不新增任何依赖：用 Android 内置的 [File] + [org.json] 读写。相比把整套
 * token 塞进 DataStore/SettingsManager，私有文件让 OAuth 特性完全自包含，
 * 并且 [Context.MODE_PRIVATE] 权限默认即仅当前 App 可读。
 *
 * H2 修复：[save] 不再吞异常——落盘失败向上抛，由调用方决定刷新结果是否作废，
 * 杜绝「内存认为已保存、磁盘实际失败」的不一致。并发保护（检查-刷新-落盘的
 * 互斥）在 [BaseXOAuthManager] 的刷新临界区内完成。
 */
abstract class BaseXTokenStore(
    context: Context,
    protected val config: OAuthProviderConfig,
) {
    private val appContext = context.applicationContext

    /** 存储文件名（含扩展名），如 `openai_oauth.json`。 */
    protected abstract val fileName: String

    /** 日志 TAG。 */
    protected abstract val tag: String

    private val file: File by lazy { File(appContext.filesDir, fileName) }

    /** 读取持久化的会话元数据；未登录/文件缺失/无有效字段时返回 null。 */
    fun load(): StoredOAuthTokens? = try {
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        val refresh = json.optString("refreshToken").takeIf { it.isNotBlank() }
        val expiresAt = if (json.has("expiresAt")) json.optLong("expiresAt") else null
        // 旧版迁移：既无 refresh 元数据也无过期时间的文件视为未登录。
        if (refresh == null && expiresAt == null) return null
        StoredOAuthTokens(
            refreshToken = refresh,
            expiresAt = expiresAt,
            email = json.optString("email").takeIf { it.isNotBlank() },
            accountId = json.optString("accountId").takeIf { it.isNotBlank() },
        )
    } catch (e: Exception) {
        DebugLog.e(tag, "load failed", e)
        null
    }

    /**
     * 原子写入元数据：先写临时文件再 rename，避免读到写一半的残缺 JSON。
     * H2：任何失败都会抛出（记日志后重新抛出），不吞。
     */
    fun save(tokens: StoredOAuthTokens) {
        try {
            val json = JSONObject().apply {
                if (!tokens.refreshToken.isNullOrBlank()) put("refreshToken", tokens.refreshToken)
                tokens.expiresAt?.let { put("expiresAt", it) }
                if (!tokens.email.isNullOrBlank()) put("email", tokens.email)
                if (!tokens.accountId.isNullOrBlank()) put("accountId", tokens.accountId)
            }
            val tmp = File(appContext.filesDir, "$fileName.tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) {
                // rename 失败（罕见的 FS 限制）时退化为直接覆盖。
                file.writeText(json.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            DebugLog.e(tag, "save failed", e)
            throw e
        }
    }

    fun delete() {
        try {
            file.delete()
        } catch (e: Exception) {
            DebugLog.e(tag, "delete failed", e)
        }
    }

    /**
     * 把 token 端点返回的 JSON 解析为 [ParsedOAuthTokens]。
     *
     * @param fallback 刷新场景下传入磁盘上的旧元数据，响应缺 `refresh_token` /
     *   `expires_in` 时沿用旧值。
     */
    fun parseTokenResponse(raw: String, fallback: StoredOAuthTokens? = null): ParsedOAuthTokens {
        val json = JSONObject(raw)
        val access = json.optString("access_token").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("token response lacks access_token")
        val refresh = json.optString("refresh_token").takeIf { it.isNotBlank() }
            ?: fallback?.refreshToken
        val expiresIn = json.optLong("expires_in", -1L)
        val expiresAt = if (expiresIn > 0) {
            System.currentTimeMillis() + expiresIn * 1000L
        } else {
            fallback?.expiresAt
                ?: System.currentTimeMillis() + config.tokenLifetimeDefaultSeconds * 1000L
        }
        val email = parseJwtEmail(json, fallback?.email)
        val accountId = parseProviderClaims(json) ?: fallback?.accountId
        return ParsedOAuthTokens(
            accessToken = access,
            refreshToken = refresh,
            expiresAt = expiresAt,
            email = email,
            accountId = accountId,
        )
    }

    /**
     * 解析响应中的 Provider 专属账号标识（如 ChatGPT 的 chatgpt_account_id）。
     * 默认无；需要时由子类覆写。
     */
    protected open fun parseProviderClaims(json: JSONObject): String? = null
}