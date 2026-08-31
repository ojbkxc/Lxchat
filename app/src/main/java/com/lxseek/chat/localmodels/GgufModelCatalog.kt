package com.lxseek.chat.localmodels

/**
 * Built-in catalog of recommended GGUF models for on-device download.
 * URLs point to hf-mirror.com (HuggingFace mirror, direct in mainland China).
 * Every URL has been verified to exist on 2026-08-31.
 */
data class GgufCatalogEntry(
    val id: String,
    val displayName: String,
    val description: String,
    val url: String,
    val sizeBytes: Long,
    val minRamGb: Int,
    val tags: List<String> = emptyList(),
    val recommendedContext: Int = 2048,
)

object GgufModelCatalog {
    val entries: List<GgufCatalogEntry> = listOf(
        GgufCatalogEntry(
            id = "qwen3-0.6b-q8",
            displayName = "Qwen3 0.6B",
            description = "通义千问 0.6B，超轻量，中文友好",
            url = "https://hf-mirror.com/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf",
            sizeBytes = 639L * 1024 * 1024,
            minRamGb = 2,
            tags = listOf("中文", "轻量"),
            recommendedContext = 4096,
        ),
        GgufCatalogEntry(
            id = "qwen3-1.7b-q4km",
            displayName = "Qwen3 1.7B",
            description = "通义千问 1.7B，中文能力强，推荐",
            url = "https://hf-mirror.com/Qwen/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
            sizeBytes = 1_100L * 1024 * 1024,
            minRamGb = 4,
            tags = listOf("中文", "推荐"),
            recommendedContext = 4096,
        ),
        GgufCatalogEntry(
            id = "qwen3-4b-q4km",
            displayName = "Qwen3 4B",
            description = "通义千问 4B，中文能力很强，需大内存",
            url = "https://hf-mirror.com/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf",
            sizeBytes = 2_500L * 1024 * 1024,
            minRamGb = 8,
            tags = listOf("中文", "大模型"),
            recommendedContext = 2048,
        ),
        GgufCatalogEntry(
            id = "llama3.2-1b-q4km",
            displayName = "Llama 3.2 1B",
            description = "Meta Llama 3.2 1B，英文能力强",
            url = "https://hf-mirror.com/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            sizeBytes = 808L * 1024 * 1024,
            minRamGb = 4,
            tags = listOf("英文", "轻量"),
            recommendedContext = 4096,
        ),
        GgufCatalogEntry(
            id = "llama3.2-3b-q4km",
            displayName = "Llama 3.2 3B",
            description = "Meta Llama 3.2 3B，英文能力强，需大内存",
            url = "https://hf-mirror.com/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            sizeBytes = 2_020L * 1024 * 1024,
            minRamGb = 8,
            tags = listOf("英文", "大模型"),
            recommendedContext = 2048,
        ),
    )

    fun byId(id: String): GgufCatalogEntry? = entries.firstOrNull { it.id == id }
}