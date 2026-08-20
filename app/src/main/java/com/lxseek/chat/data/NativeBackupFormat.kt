package com.lxseek.chat.data

import kotlinx.serialization.Serializable

internal object NativeBackupFormat {
    const val CURRENT_VERSION = 4
    const val MIN_SUPPORTED_VERSION = 1

    const val MANIFEST_ENTRY = "manifest.json"
    const val CONVERSATIONS_ENTRY = "conversations.json"
    const val SETTINGS_ENTRY = "settings.json"
    const val LEGACY_EXTRA_SETTINGS_ENTRY = "extra_settings.json"
    const val SECRETS_ENTRY = "api_keys.json"
    const val SYSTEM_PROMPTS_ENTRY = "system_prompts.json"
    const val CUSTOM_FONT_ENTRY = "custom_font/font"

    const val IMAGE_MEDIA_PREFIX = "media/images/"
    const val VIDEO_MEDIA_PREFIX = "media/videos/"
    const val DRAFT_MEDIA_PREFIX = "media/drafts/"

    fun isSupported(version: Int): Boolean =
        version in MIN_SUPPORTED_VERSION..CURRENT_VERSION
}

@Serializable
internal data class ShellDeviceSecrets(
    val apiKey: String = "",
    val sshPassword: String = "",
)

@Serializable
internal data class NativeBackupSecrets(
    val apiKeys: List<ApiKeyEntry> = emptyList(),
    val activeApiKeyIds: Map<String, String> = emptyMap(),
    val webSearchApiKeys: Map<String, String> = emptyMap(),
    val proxyPassword: String = "",
    val shellDevices: Map<String, ShellDeviceSecrets> = emptyMap(),
    /** v1-v3 compatibility only. v4 always keys shell credentials by stable device ID. */
    val shellApiKeys: Map<String, String> = emptyMap(),
    val embeddingApiKeys: Map<String, String> = emptyMap(),
    val mcpHeaders: Map<String, Map<String, String>> = emptyMap(),
)

internal fun ShellDeviceConfig.withoutSecrets(): ShellDeviceConfig =
    copy(apiKey = "", sshPassword = "")

internal fun McpServerConfig.withoutSecrets(): McpServerConfig =
    copy(headers = emptyMap())

internal fun EmbeddingModelConfig.asPortableRemoteConfig(): EmbeddingModelConfig? =
    takeIf { it.type == EmbeddingModelType.REMOTE }
        ?.copy(remoteApiKey = "", localFilePath = "")
