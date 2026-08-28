package com.lxseek.chat.data

import com.lxseek.chat.util.Constants
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ApiKeyEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val key: String,
    val provider: String = Constants.PROVIDER_GOOGLE
)

@Serializable
data class ShellDeviceConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val type: String = "conch",          // "conch" | "ssh"
    // Conch fields (type=conch)
    val serverUrl: String = "",
    val apiKey: String = "",
    val conchPublicKey: String = "",
    // SSH fields (type=ssh)
    val sshHost: String = "",
    val sshPort: Int = 22,
    val sshUser: String = "root",
    val sshPassword: String = "",
    // Pinned SSH host key (base64 of the server public-key blob). Blank = not yet
    // pinned (trust-on-first-use); once set, connections must match or are rejected.
    val sshHostKey: String = ""
)

@Serializable
enum class McpTransportType {
    @SerialName("streamable_http")
    STREAMABLE_HTTP,

    @SerialName("sse")
    SSE,
}

@Serializable
data class McpServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val url: String = "",
    val transport: McpTransportType = McpTransportType.STREAMABLE_HTTP,
    val headers: Map<String, String> = emptyMap(),
    /** Raw MCP tool names disabled for this server. New tools stay enabled by default. */
    val disabledTools: Set<String> = emptySet(),
    /** OAuth 2.0 (RFC 9728 + PKCE) settings for official remote MCP servers. */
    val oauth: McpOAuthConfig = McpOAuthConfig(),
)

/** OAuth configuration for a single MCP server (official remote servers, e.g. GitHub, Slack). */
@Serializable
data class McpOAuthConfig(
    /** True to use OAuth (PKCE) for this server instead of only static headers. */
    val enabled: Boolean = false,
    /** Pre-configured client_id. Blank = dynamic client registration (DCR). */
    val clientId: String = "",
    /** Override for authorization server metadata discovery (must be https). */
    val authServerMetadataUrl: String = "",
    /** Space-separated scopes. Blank = server-provided scopes. */
    val scope: String = "",
)

/** OAuth token credentials for a single MCP server, stored separately from the (non-secret) config. */
@Serializable
data class McpOAuthTokens(
    val serverId: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    /** Absolute epoch millis when the access token expires. */
    val expiresAt: Long = 0L,
    val scope: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
)

@Serializable
data class SystemPromptEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String = "",
    val systemItems: List<PromptTemplateItem> = emptyList(),
    val userPrependItems: List<PromptTemplateItem> = emptyList(),
    val userPostpendItems: List<PromptTemplateItem> = emptyList()
) {
    val resolvedSystemItems: List<PromptTemplateItem>
        get() = if (systemItems.isNotEmpty()) systemItems
        else if (content.isNotBlank()) listOf(PromptTemplateItem(type = PromptItemType.CUSTOM, value = content))
        else emptyList()
}

@Serializable
data class ConversationSettings(
    /** Provider-visible conversation token budget. Values <=100 are legacy message windows. */
    val contextWindow: Int? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val codeExecutionEnabled: Boolean? = null,
    val googleSearchEnabled: Boolean? = null,
    val thinkingEnabled: Boolean? = null,
    val thinkingLevel: String? = null,
    val thinkingBudgetEnabled: Boolean? = null,
    val thinkingBudgetTokens: Int? = null,
    val openAiServiceTierEnabled: Boolean? = null,
    val openAiServiceTier: String? = null,
    val webSearchEnabled: Boolean? = null,
    val shellEnabled: Boolean? = null
) {
    fun isAllNull() = contextWindow == null && temperature == null && maxTokens == null && topP == null
        && frequencyPenalty == null && presencePenalty == null
        && codeExecutionEnabled == null && googleSearchEnabled == null && thinkingEnabled == null
        && thinkingLevel == null && thinkingBudgetEnabled == null && thinkingBudgetTokens == null
        && openAiServiceTierEnabled == null && openAiServiceTier == null
        && webSearchEnabled == null && shellEnabled == null
}
