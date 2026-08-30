package com.lxseek.chat.openai

import android.content.Context
import com.lxseek.chat.api.oauth.parseJwtEmail
import com.lxseek.chat.util.DebugLog
import org.json.JSONObject
import java.io.File

/** 已持久化的 ChatGPT(OpenAI) OAuth token 快照。 */
internal data class StoredOpenAITokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Long?,
    val email: String?,
    /** ChatGPT account id extracted from the JWT claims (chatgpt_account_id /
     *  organizations[0].id). Sent as the `ChatGPT-Account-Id` header on Codex
     *  Responses API calls; null when the token did not carry it. */
    val accountId: String?,
)

/**
 * 把 ChatGPT(OpenAI) OAuth token 存到 App 私有目录 `filesDir/openai_oauth.json`。
 *
 * 不新增任何依赖:用 Android 内置的 [File] + [org.json] 读写。相比把整套
 * token 塞进 DataStore/SettingsManager,私有文件让本特性完全自包含,并且
 * [Context.MODE_PRIVATE] 权限默认即仅当前 App 可读。
 */
internal class OpenAIXTokenStore(private val context: Context) {
    private val file: File by lazy { File(context.filesDir, FILE_NAME) }

    fun load(): StoredOpenAITokens? {
        return try {
            if (!file.exists()) return null
            val json = JSONObject(file.readText())
            val access = json.optString("accessToken").takeIf { it.isNotBlank() } ?: return null
            val refresh = json.optString("refreshToken").takeIf { it.isNotBlank() }
            val expiresAt = if (json.has("expiresAt")) json.optLong("expiresAt") else null
            val email = json.optString("email").takeIf { it.isNotBlank() }
            val accountId = json.optString("accountId").takeIf { it.isNotBlank() }
            StoredOpenAITokens(
                accessToken = access,
                refreshToken = refresh,
                expiresAt = expiresAt,
                email = email,
                accountId = accountId,
            )
        } catch (e: Exception) {
            DebugLog.e(TAG, "load failed", e)
            null
        }
    }

    fun save(tokens: StoredOpenAITokens) {
        try {
            val json = JSONObject().apply {
                put("accessToken", tokens.accessToken)
                if (!tokens.refreshToken.isNullOrBlank()) put("refreshToken", tokens.refreshToken)
                tokens.expiresAt?.let { put("expiresAt", it) }
                if (!tokens.email.isNullOrBlank()) put("email", tokens.email)
                if (!tokens.accountId.isNullOrBlank()) put("accountId", tokens.accountId)
            }
            // 原子写:先写临时文件再 rename,避免读到写一半的残缺 JSON。
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) {
                // rename 失败(罕见的 FS 限制)时退化为直接覆盖。
                file.writeText(json.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "save failed", e)
        }
    }

    fun delete() {
        try {
            file.delete()
        } catch (e: Exception) {
            DebugLog.e(TAG, "delete failed", e)
        }
    }

    /** 若 access token 尚未过期,直接返回;否则用 refresh token 刷新并落盘。 */
    fun ensureFresh(): StoredOpenAITokens? {
        val current = load() ?: return null
        val exp = current.expiresAt
        if (exp == null) return current
        if (exp - System.currentTimeMillis() > OpenAIXOAuthConstants.TOKEN_EXPIRY_SKEW_MS) {
            return current
        }
        val refresh = current.refreshToken ?: return null
        return try {
            val raw = refreshOpenAITokens(refresh)
            val updated = parseTokenResponse(raw, current)
            save(updated)
            updated
        } catch (e: Exception) {
            DebugLog.e(TAG, "refresh failed", e)
            null
        }
    }

    /** 把 token 端点返回的 JSON 解析为 [[StoredOpenAITokens]]。 */
    internal fun parseTokenResponse(raw: String, fallback: StoredOpenAITokens? = null): StoredOpenAITokens {
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
                ?: System.currentTimeMillis() + OpenAIXOAuthConstants.TOKEN_LIFETIME_DEFAULT_SECONDS * 1000L
        }
        val email = parseJwtEmail(json, fallback?.email)
        val accountId = extractOpenAIAccountId(json, fallback?.accountId)
        return StoredOpenAITokens(
            accessToken = access,
            refreshToken = refresh,
            expiresAt = expiresAt,
            email = email,
            accountId = accountId,
        )
    }

    /**
     * Extract the ChatGPT account id from the JWT claims of the token endpoint response.
     *
     * Mirrors cc-haha-main `extractOpenAIAccountId`: prefers `id_token`, falls back to
     * `access_token`, and reads `chatgpt_account_id` (top-level or nested under
     * `https://api.openai.com/auth`) then `organizations[0].id`. Returns [fallback]
     * when no account id can be determined.
     */
    private fun extractOpenAIAccountId(json: JSONObject, fallback: String?): String? {
        val jwt = json.optString("id_token").takeIf { it.isNotBlank() }
            ?: json.optString("access_token").takeIf { it.isNotBlank() }
            ?: return fallback
        val parts = jwt.split('.')
        if (parts.size < 2) return fallback
        val payload = parts[1]
        return runCatching {
            val decoded = android.util.Base64.decode(
                payload.replace('-', '+').replace('_', '/')
                    .padEnd((payload.length + 3) / 4 * 4, '='),
                android.util.Base64.DEFAULT,
            )
            val claims = JSONObject(String(decoded, Charsets.UTF_8))
            // 1) top-level chatgpt_account_id
            claims.optString("chatgpt_account_id").takeIf { it.isNotBlank() }
                // 2) https://api.openai.com/auth -> chatgpt_account_id
                ?: claims.optJSONObject("https://api.openai.com/auth")
                    ?.optString("chatgpt_account_id")?.takeIf { it.isNotBlank() }
                // 3) organizations[0].id
                ?: claims.optJSONArray("organizations")
                    ?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: fallback
    }

    private companion object {
        const val FILE_NAME = "openai_oauth.json"
        const val TAG = "OpenAIXTokenStore"
    }
}