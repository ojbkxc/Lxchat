package com.lxseek.chat.mcp

import com.lxseek.chat.api.HttpClient
import com.lxseek.chat.data.McpOAuthConfig
import com.lxseek.chat.data.McpOAuthTokens
import com.lxseek.chat.data.repository.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toFormUrlEncodedBody
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * OAuth 2.0 support for official remote MCP servers (RFC 9728 resource metadata →
 * RFC 8414 authorization server metadata → dynamic client registration → PKCE →
 * token exchange / refresh), mirroring the flow cc-haha uses for GitHub, Slack, etc.
 *
 * All HTTP calls here are blocking OkHttp calls; callers must run them on a
 * background dispatcher (the flow manager does this via [Dispatchers.IO]).
 */
internal class McpOAuthClient {
    private val json = Json { ignoreUnknownKeys = true }

    /** RFC 8414 authorization server metadata (the fields LxChat needs). */
    internal class Metadata(
        val issuer: String,
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
        val registrationEndpoint: String?,
        val revocationEndpoint: String?,
    )

    internal class ClientInfo(
        val clientId: String,
        val clientSecret: String = "",
    )

    internal class TokenResult(
        val accessToken: String,
        val refreshToken: String?,
        val expiresInSeconds: Long?,
        val scope: String?,
    )

    /**
     * Discovers the authorization server metadata for an MCP server.
     *
     * Order (matching cc-haha / RFC 9728):
     * 1. If [configuredMetadataUrl] is set, fetch it directly (must be https).
     * 2. Probe `/.well-known/oauth-protected-resource`, read `authorization_servers[0]`,
     *    then RFC 8414 discovery against that URL.
     * 3. Fallback: RFC 8414 discovery directly against the server URL.
     */
    fun discoverMetadata(serverUrl: String, configuredMetadataUrl: String): Metadata {
        if (configuredMetadataUrl.isNotBlank()) {
            require(configuredMetadataUrl.startsWith("https://", ignoreCase = true)) {
                "authServerMetadataUrl must use https:// (got: $configuredMetadataUrl)"
            }
            return fetch8414(configuredMetadataUrl)
        }
        val base = trimTrailingSlash(serverUrl)
        val protectedResource = try {
            val response = HttpClient.getTextResponse("$base/.well-known/oauth-protected-resource")
            if (response.isSuccessful && response.body.isNotBlank()) {
                runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
        val authorizationServers = protectedResource?.get("authorization_servers") as? JsonArray
        val asUrl = authorizationServers
            ?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf(String::isNotBlank)
        if (asUrl != null) {
            return fetch8414(asUrl)
        }
        return fetch8414(base)
    }

    private fun fetch8414(url: String): Metadata {
        val trimmed = trimTrailingSlash(url)
        val candidates = linkedSetOf<String>()
        candidates.add("$trimmed/.well-known/oauth-authorization-server")
        candidates.add(trimmed)
        var lastError: Exception? = null
        for (candidate in candidates) {
            try {
                val response = HttpClient.getTextResponse(
                    candidate,
                    mapOf("Accept" to "application/json"),
                )
                if (!response.isSuccessful) {
                    lastError = IOException("HTTP ${response.code} fetching $candidate")
                    continue
                }
                val obj = json.parseToJsonElement(response.body).jsonObject
                val authorizationEndpoint =
                    (obj["authorization_endpoint"] as? JsonPrimitive)?.contentOrNull
                        ?.takeIf(String::isNotBlank)
                val tokenEndpoint =
                    (obj["token_endpoint"] as? JsonPrimitive)?.contentOrNull
                        ?.takeIf(String::isNotBlank)
                if (authorizationEndpoint != null && tokenEndpoint != null) {
                    return Metadata(
                        issuer = (obj["issuer"] as? JsonPrimitive)?.contentOrNull ?: candidate,
                        authorizationEndpoint = authorizationEndpoint,
                        tokenEndpoint = tokenEndpoint,
                        registrationEndpoint =
                            (obj["registration_endpoint"] as? JsonPrimitive)?.contentOrNull,
                        revocationEndpoint =
                            (obj["revocation_endpoint"] as? JsonPrimitive)?.contentOrNull,
                    )
                }
                lastError = IOException("Invalid authorization server metadata at $candidate")
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("No authorization server metadata found for $url")
    }

    /** RFC 7591 dynamic client registration for a public client (no secret). */
    fun registerClient(metadata: Metadata, redirectUri: String): ClientInfo {
        val registrationEndpoint = metadata.registrationEndpoint
            ?: throw IOException(
                "Authorization server does not advertise a registration endpoint; " +
                    "configure a client_id manually",
            )
        val clientMetadata = buildJsonObject {
            put("client_name", "LxChat")
            put("redirect_uris", buildJsonArray { add(redirectUri) })
            put("grant_types", buildJsonArray { add("authorization_code"); add("refresh_token") })
            put("response_types", buildJsonArray { add("code") })
            put("token_endpoint_auth_method", "none")
        }
        val response = HttpClient.postTextResponse(
            registrationEndpoint,
            clientMetadata.toString(),
            mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
        )
        if (!response.isSuccessful) {
            throw IOException(
                "Dynamic client registration failed: HTTP ${response.code} " +
                    response.body.take(512),
            )
        }
        val obj = json.parseToJsonElement(response.body).jsonObject
        val clientId = (obj["client_id"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: throw IOException("Client registration response missing client_id")
        return ClientInfo(
            clientId = clientId,
            clientSecret = (obj["client_secret"] as? JsonPrimitive)?.contentOrNull ?: "",
        )
    }

    fun buildAuthorizationUrl(
        metadata: Metadata,
        clientId: String,
        redirectUri: String,
        scope: String,
        codeChallenge: String,
        state: String,
    ): String {
        val builder = metadata.authorizationEndpoint.toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("code_challenge", codeChallenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
        if (scope.isNotBlank()) {
            builder.addQueryParameter("scope", scope)
        }
        return builder.build().toString()
    }

    /** Exchanges the authorization code for tokens (PKCE S256). */
    fun exchangeCode(
        metadata: Metadata,
        clientId: String,
        code: String,
        codeVerifier: String,
        redirectUri: String,
    ): TokenResult {
        val form = linkedMapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to clientId,
            "code_verifier" to codeVerifier,
        )
        return tokenRequest(metadata.tokenEndpoint, form)
    }

    /** Refreshes an access token using the stored refresh token (RFC 6749 §6). */
    fun refreshTokens(metadata: Metadata, clientId: String, refreshToken: String): TokenResult {
        val form = linkedMapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to clientId,
        )
        return tokenRequest(metadata.tokenEndpoint, form)
    }

    private fun tokenRequest(tokenEndpoint: String, form: Map<String, String>): TokenResult {
        val body = form.toFormUrlEncodedBody()
        val request = Request.Builder()
            .url(tokenEndpoint)
            .header("Accept", "application/json")
            .post(body)
            .build()
        val (code, responseBody) = HttpClient.client.newCall(request).execute().use { response ->
            response.code to response.body.string()
        }
        if (code !in 200..299) {
            throw IOException("OAuth token request failed: HTTP $code ${responseBody.take(512)}")
        }
        val obj = json.parseToJsonElement(responseBody).jsonObject
        val accessToken = (obj["access_token"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: throw IOException("Token response missing access_token")
        return TokenResult(
            accessToken = accessToken,
            refreshToken = (obj["refresh_token"] as? JsonPrimitive)?.contentOrNull,
            expiresInSeconds = (obj["expires_in"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
            scope = (obj["scope"] as? JsonPrimitive)?.contentOrNull,
        )
    }

    fun newCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun newState(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun trimTrailingSlash(value: String): String {
        var end = value.length
        while (end > 0 && value[end - 1] == '/') end--
        return value.substring(0, end)
    }
}

/** Read/write access to the persisted OAuth tokens for a single MCP server. */
internal class McpOAuthTokenStore(
    private val settings: SettingsRepository,
) {
    fun tokens(serverId: String): McpOAuthTokens? = settings.mcpOAuthTokens.value[serverId]

    suspend fun save(tokens: McpOAuthTokens) {
        settings.saveMcpOAuthToken(tokens)
    }

    suspend fun clear(serverId: String) {
        settings.clearMcpOAuthToken(serverId)
    }
}

/** Supplies an `Authorization` header value for MCP transport requests. */
internal interface McpAuthHeaderProvider {
    /** Returns e.g. `Bearer <token>`, or null when no usable token is available. */
    suspend fun authorizationHeader(): String?
}

/**
 * Resolves an `Authorization: Bearer` header from the persisted OAuth token,
 * proactively refreshing when the access token is expiring. Refresh calls are
 * deduplicated so concurrent transport requests only trigger one exchange.
 */
internal class McpOAuthAuthHeaderProvider(
    private val serverId: String,
    private val serverUrl: String,
    private val oauth: McpOAuthConfig,
    private val tokenStore: McpOAuthTokenStore,
    private val client: McpOAuthClient,
) : McpAuthHeaderProvider {
    companion object {
        /** Refresh proactively 5 minutes before expiry (mirrors cc-haha). */
        private const val REFRESH_EARLY_MILLIS = 5L * 60L * 1_000L
        private const val INVALID_GRANT = "invalid_grant"
    }

    private val refreshMutex = Mutex()
    private var cachedMetadata: McpOAuthClient.Metadata? = null

    override suspend fun authorizationHeader(): String? {
        val current = tokenStore.tokens(serverId) ?: return null
        if (current.accessToken.isBlank()) return null
        val expiresInMillis = current.expiresAt - System.currentTimeMillis()
        if (expiresInMillis > REFRESH_EARLY_MILLIS) {
            return "Bearer ${current.accessToken}"
        }
        if (current.refreshToken.isBlank()) return null
        return withContext(Dispatchers.IO) {
            refreshMutex.withLock { doRefresh() }
        }?.accessToken?.let { "Bearer $it" }
    }

    private suspend fun doRefresh(): McpOAuthTokens? {
        val current = tokenStore.tokens(serverId) ?: return null
        if (current.refreshToken.isBlank()) return null
        return try {
            val metadata = cachedMetadata ?: client.discoverMetadata(
                serverUrl,
                oauth.authServerMetadataUrl,
            ).also { cachedMetadata = it }
            val clientId = current.clientId.ifBlank { oauth.clientId.trim() }
            val result = client.refreshTokens(metadata, clientId, current.refreshToken)
            val expiresAt = if (result.expiresInSeconds != null) {
                System.currentTimeMillis() + result.expiresInSeconds * 1_000L
            } else {
                0L
            }
            val updated = current.copy(
                accessToken = result.accessToken,
                refreshToken = result.refreshToken ?: current.refreshToken,
                expiresAt = expiresAt,
                scope = result.scope ?: current.scope,
            )
            tokenStore.save(updated)
            updated
        } catch (e: Exception) {
            // Revoked/expired refresh token: drop credentials so the transport returns
            // no header → 401 → NEEDS_AUTH, prompting the user to re-authorize.
            if (e.message?.contains(INVALID_GRANT, ignoreCase = true) == true) {
                cachedMetadata = null
                tokenStore.clear(serverId)
            }
            null
        }
    }
}

/**
 * Coordinates the interactive OAuth consent flow for a remote MCP server.
 *
 * [start] performs discovery + dynamic client registration + PKCE and returns the
 * authorization URL to open in a browser. The flow completes when [complete] is
 * called with the callback URL (delivered by the app's custom-scheme deep link, or
 * pasted manually by the user). [awaitTokens] suspends until that happens.
 */
internal class McpOAuthFlowManager(
    private val tokenStore: McpOAuthTokenStore,
    private val client: McpOAuthClient,
) {
    internal class Pending(
        val serverId: String,
        val redirectUri: String,
        val codeVerifier: String,
        val state: String,
        val clientId: String,
        val clientSecret: String,
        val scope: String,
        val metadata: McpOAuthClient.Metadata,
        val deferred: CompletableDeferred<McpOAuthTokens>,
    )

    private val pendings = ConcurrentHashMap<String, Pending>()

    /**
     * Discovers metadata, registers a client (or uses a configured client_id), generates
     * the PKCE verifier/challenge and returns the authorization URL to open. The pending
     * flow stays registered until [complete] or [cancel].
     */
    suspend fun start(
        serverId: String,
        serverUrl: String,
        oauth: McpOAuthConfig,
        redirectUri: String,
    ): String = withContext(Dispatchers.IO) {
        val metadata = client.discoverMetadata(serverUrl, oauth.authServerMetadataUrl)
        val clientInfo = if (oauth.clientId.isNotBlank()) {
            McpOAuthClient.ClientInfo(clientId = oauth.clientId.trim())
        } else {
            client.registerClient(metadata, redirectUri)
        }
        val verifier = client.newCodeVerifier()
        val challenge = client.codeChallenge(verifier)
        // Embed the server id in the state so the deep-link handler can route the
        // callback back to the right pending flow (opaque to the authorization server).
        val state = "$serverId::${client.newState()}"
        pendings.remove(serverId)?.deferred?.cancel()
        pendings[serverId] = Pending(
            serverId = serverId,
            redirectUri = redirectUri,
            codeVerifier = verifier,
            state = state,
            clientId = clientInfo.clientId,
            clientSecret = clientInfo.clientSecret,
            scope = oauth.scope,
            metadata = metadata,
            deferred = CompletableDeferred(),
        )
        client.buildAuthorizationUrl(
            metadata = metadata,
            clientId = clientInfo.clientId,
            redirectUri = redirectUri,
            scope = oauth.scope,
            codeChallenge = challenge,
            state = state,
        )
    }

    fun pending(serverId: String): Pending? = pendings[serverId]

    /** The authorization URL of an in-progress flow (for copy / manual fallback). */
    fun authorizationUrl(serverId: String): String? = pending(serverId)?.let { p ->
        client.buildAuthorizationUrl(
            metadata = p.metadata,
            clientId = p.clientId,
            redirectUri = p.redirectUri,
            scope = p.scope,
            codeChallenge = client.codeChallenge(p.codeVerifier),
            state = p.state,
        )
    }

    /** Suspends until the flow started by [start] completes with the saved tokens. */
    suspend fun awaitTokens(serverId: String): McpOAuthTokens {
        val deferred = pendings[serverId]?.deferred
            ?: throw IOException("OAuth flow was not started for this server")
        return deferred.await()
    }

    /**
     * Completes the pending flow with the authorization server's callback URL, validates
     * the CSRF state, exchanges the code and persists the tokens.
     */
    suspend fun complete(serverId: String, callbackUrl: String): McpOAuthTokens {
        val pending = pendings.remove(serverId)
            ?: throw IOException("No pending OAuth flow for this server")
        return withContext(Dispatchers.IO) {
            val params = parseCallbackParams(callbackUrl)
            params["error"]?.takeIf(String::isNotBlank)?.let { error ->
                throw IOException("OAuth error: $error")
            }
            if (params["state"] != pending.state) {
                throw IOException("OAuth state mismatch - possible CSRF attack")
            }
            val code = params["code"]?.takeIf(String::isNotBlank)
                ?: throw IOException("Callback URL does not contain an authorization code")
            val result = client.exchangeCode(
                metadata = pending.metadata,
                clientId = pending.clientId,
                code = code,
                codeVerifier = pending.codeVerifier,
                redirectUri = pending.redirectUri,
            )
            val expiresAt = if (result.expiresInSeconds != null) {
                System.currentTimeMillis() + result.expiresInSeconds * 1_000L
            } else {
                0L
            }
            val tokens = McpOAuthTokens(
                serverId = serverId,
                accessToken = result.accessToken,
                refreshToken = result.refreshToken ?: "",
                expiresAt = expiresAt,
                scope = result.scope ?: pending.scope,
                clientId = pending.clientId,
                clientSecret = pending.clientSecret,
            )
            tokenStore.save(tokens)
            pending.deferred.complete(tokens)
            tokens
        }
    }

    fun cancel(serverId: String) {
        pendings.remove(serverId)?.deferred?.cancel()
    }

    /** Parses `?code=&state=&error=` from a redirect URI / callback URL. */
    private fun parseCallbackParams(callbackUrl: String): Map<String, String> {
        val query = callbackUrl.substringAfter('?', missingDelimiterValue = "")
            .ifBlank { return emptyMap() }
        val params = linkedMapOf<String, String>()
        query.split('&').forEach { pair ->
            val (rawKey, rawValue) = pair.split('=', limit = 2).let {
                it[0] to it.getOrNull(1).orEmpty()
            }
            val key = decodeFormComponent(rawKey)
            val value = decodeFormComponent(rawValue)
            if (key.isNotBlank()) params[key] = value
        }
        return params
    }

    private fun decodeFormComponent(value: String): String = try {
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    } catch (e: Exception) {
        value
    }
}
