package buildlogic

internal const val KOTLIN_SOURCE_MAX_LINES = 999

/** Immutable ceiling for the one migration baseline authorized on 2026-08-09. */
internal val INITIAL_KOTLIN_SOURCE_BASELINE_CAPS = mapOf(
    "app/src/fdroid/java/com/lxseek/chat/sandbox/ProotSandboxManager.kt" to 1153,
    "app/src/main/java/com/lxseek/chat/MainActivity.kt" to 1138,
    "app/src/main/java/com/lxseek/chat/api/anthropic/AnthropicProvider.kt" to 1037,
    "app/src/main/java/com/lxseek/chat/data/DataImporter.kt" to 1509,
    "app/src/main/java/com/lxseek/chat/data/SettingsManager.kt" to 1369,
    "app/src/main/java/com/lxseek/chat/data/local/ChatDatabase.kt" to 1587,
    "app/src/main/java/com/lxseek/chat/model/RunLifecycle.kt" to 1389,
    "app/src/main/java/com/lxseek/chat/tool/ShellToolProvider.kt" to 1514,
    "app/src/main/java/com/lxseek/chat/ui/chat/ChatApp.kt" to 2313,
    "app/src/main/java/com/lxseek/chat/ui/chat/MessageList.kt" to 1181,
    "app/src/main/java/com/lxseek/chat/ui/settings/SettingsModelsPage.kt" to 1004,
    "app/src/main/java/com/lxseek/chat/ui/tasks/TasksScreen.kt" to 1712,
    "app/src/main/java/com/lxseek/chat/viewmodel/ChatViewModel.kt" to 1897,
    "app/src/main/java/com/lxseek/chat/viewmodel/ConversationGenerationState.kt" to 1080,
    "app/src/main/java/com/lxseek/chat/viewmodel/GenerationManager.kt" to 1689,
    "app/src/main/java/com/lxseek/chat/viewmodel/MessageGenerationController.kt" to 2108,
    "app/src/test/java/com/lxseek/chat/model/ConversationRuntimeReducerTest.kt" to 1433,
    "app/src/test/java/com/lxseek/chat/ui/chat/MessageListLayoutTest.kt" to 1256,
    "app/src/test/java/com/lxseek/chat/viewmodel/ConversationGenerationStateTest.kt" to 1145,
)

internal enum class KotlinSourceSizeViolationReason {
    INVALID_BASELINE,
    MISSING_BASELINE_SOURCE,
    STALE_BASELINE,
    BASELINE_GROWTH,
    NEW_OVERSIZED_SOURCE,
}

internal data class KotlinSourceSizeViolation(
    val path: String,
    val currentLines: Int,
    val allowedLines: Int,
    val reason: KotlinSourceSizeViolationReason,
) {
    fun report(): String =
        "$path: $currentLines lines (allowed $allowedLines; ${reason.name.lowercase()})"
}

/** Pure, platform-independent policy used by the Gradle task and focused tests. */
internal object KotlinSourceSizePolicy {
    private val excludedSegments = setOf(
        ".build-proot",
        ".git",
        ".gradle",
        ".harness",
        ".idea",
        ".kotlin",
        ".claude",
        ".gemini",
        "build",
        "cache",
        "caches",
        "generated",
        "site",
        "thirdparty",
    )

    fun countPhysicalLines(text: String): Int {
        if (text.isEmpty()) return 0
        var lines = 0
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '\r' -> {
                    lines += 1
                    if (index + 1 < text.length && text[index + 1] == '\n') index += 1
                }
                '\n' -> lines += 1
            }
            index += 1
        }
        if (text.last() != '\r' && text.last() != '\n') lines += 1
        return lines
    }

    fun normalizePath(path: String): String = path.replace('\\', '/').trimStart('/')

    fun isExcluded(path: String): Boolean =
        normalizePath(path).split('/').any { segment -> segment in excludedSegments }

    fun parseBaseline(text: String): Map<String, Int> {
        val entries = linkedMapOf<String, Int>()
        text.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
            val separator = line.indexOf('=')
            require(separator > 0 && separator < line.lastIndex) {
                "Invalid Kotlin source baseline entry at line ${index + 1}: $rawLine"
            }
            val path = normalizePath(line.substring(0, separator).trim())
            val lines = line.substring(separator + 1).trim().toIntOrNull()
                ?: error("Invalid Kotlin source line count at line ${index + 1}: $rawLine")
            require(entries.put(path, lines) == null) {
                "Duplicate Kotlin source baseline path at line ${index + 1}: $path"
            }
        }
        return entries
    }

    fun evaluate(
        currentLines: Map<String, Int>,
        baselineLines: Map<String, Int>,
        maximumLines: Int = KOTLIN_SOURCE_MAX_LINES,
        allowedBaselineCaps: Map<String, Int> = baselineLines,
    ): List<KotlinSourceSizeViolation> {
        val normalizedCurrent = currentLines.entries.associate { (path, lines) ->
            normalizePath(path) to lines
        }
        val normalizedBaseline = baselineLines.entries.associate { (path, lines) ->
            normalizePath(path) to lines
        }
        val normalizedAllowedCaps = allowedBaselineCaps.entries.associate { (path, lines) ->
            normalizePath(path) to lines
        }
        val violations = mutableListOf<KotlinSourceSizeViolation>()

        normalizedBaseline.toSortedMap().forEach { (path, recordedLines) ->
            val current = normalizedCurrent[path]
            val frozenCap = normalizedAllowedCaps[path]
            when {
                frozenCap == null -> violations += KotlinSourceSizeViolation(
                    path,
                    current ?: 0,
                    maximumLines,
                    KotlinSourceSizeViolationReason.INVALID_BASELINE,
                )
                recordedLines > frozenCap -> violations += KotlinSourceSizeViolation(
                    path,
                    current ?: 0,
                    frozenCap,
                    KotlinSourceSizeViolationReason.INVALID_BASELINE,
                )
                recordedLines <= maximumLines -> violations += KotlinSourceSizeViolation(
                    path,
                    current ?: 0,
                    maximumLines,
                    KotlinSourceSizeViolationReason.INVALID_BASELINE,
                )
                current == null -> violations += KotlinSourceSizeViolation(
                    path,
                    0,
                    maximumLines,
                    KotlinSourceSizeViolationReason.MISSING_BASELINE_SOURCE,
                )
                current <= maximumLines -> violations += KotlinSourceSizeViolation(
                    path,
                    current,
                    maximumLines,
                    KotlinSourceSizeViolationReason.STALE_BASELINE,
                )
                current > recordedLines -> violations += KotlinSourceSizeViolation(
                    path,
                    current,
                    recordedLines,
                    KotlinSourceSizeViolationReason.BASELINE_GROWTH,
                )
            }
        }

        normalizedCurrent.toSortedMap().forEach { (path, current) ->
            if (current > maximumLines && path !in normalizedBaseline) {
                violations += KotlinSourceSizeViolation(
                    path,
                    current,
                    maximumLines,
                    KotlinSourceSizeViolationReason.NEW_OVERSIZED_SOURCE,
                )
            }
        }
        return violations.sortedWith(compareBy(KotlinSourceSizeViolation::path, KotlinSourceSizeViolation::reason))
    }
}
