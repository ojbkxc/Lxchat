package com.lxseek.chat.tool

import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.data.ActivityJournal
import com.lxseek.chat.data.MemoryImportanceScorer
import com.lxseek.chat.data.MemoryManager
import com.lxseek.chat.util.DebugLog
import com.lxseek.chat.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class MemoryToolProvider(
    private val memoryManager: MemoryManager,
    private val scorer: MemoryScorer = MemoryScorer,
    private val journal: ActivityJournal? = null,
) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.accessSavedMemories && !ctx.accessActiveMemory) return emptyList()
        val tools = mutableListOf<ToolDefinition>()
        if (ctx.accessSavedMemories) {
            tools.addAll(
                listOf(
                    ToolDefinition(
                        function = ToolFunction(
                            name = "list_memory_files",
                            description = "List all files in the memory database with their names and descriptions.",
                            parameters = ToolParameters(properties = emptyMap())
                        )
                    ),
                    ToolDefinition(
                        function = ToolFunction(
                            name = "read_memory_file",
                            description = "Read the content of one or more files from the memory database.",
                            parameters = ToolParameters(
                                properties = mapOf(
                                    "name" to ToolProperty("string", "The file name to read."),
                                    "names" to ToolProperty(
                                        "array",
                                        "Multiple file names to read in one call.",
                                        items = ToolProperty("string", "A file name.")
                                    )
                                ),
                                required = emptyList()
                            )
                        )
                    ),
                    ToolDefinition(
                        function = ToolFunction(
                            name = "create_memory_file",
                            description = "Create a new file in the memory database with the given content and optional description.",
                            parameters = ToolParameters(
                                properties = mapOf(
                                    "name" to ToolProperty("string", "The file name to create (e.g., 'notes.md')."),
                                    "content" to ToolProperty("string", "The markdown content for the file."),
                                    "description" to ToolProperty(
                                        "string",
                                        "A short description of what this file contains (optional)."
                                    )
                                ),
                                required = listOf("name", "content")
                            )
                        )
                    ),
                    ToolDefinition(
                        function = ToolFunction(
                            name = "edit_memory_file",
                            description = "Edit, rename, or update a memory file. Use 'old_string'+'new_string' for precise replacement (must match once), or 'content' for full rewrite (mutually exclusive).",
                            parameters = ToolParameters(
                                properties = mapOf(
                                    "name" to ToolProperty("string", "The current file name to edit."),
                                    "content" to ToolProperty(
                                        "string",
                                        "The new markdown content (full rewrite). Omit to keep existing content. Mutually exclusive with 'old_string'."
                                    ),
                                    "old_string" to ToolProperty(
                                        "string",
                                        "Exact string to find and replace. Must match exactly once in the file. Mutually exclusive with 'content'."
                                    ),
                                    "new_string" to ToolProperty(
                                        "string",
                                        "Replacement string for old_string. Pass empty string to delete the matched text. Required when old_string is provided."
                                    ),
                                    "new_name" to ToolProperty("string", "New file name to rename to. Omit to keep existing name."),
                                    "description" to ToolProperty(
                                        "string",
                                        "A short description of the file contents. Omit to keep existing description. Pass empty string to remove."
                                    )
                                ),
                                required = listOf("name")
                            )
                        )
                    ),
                    ToolDefinition(
                        function = ToolFunction(
                            name = "delete_memory_file",
                            description = "Delete a file from the memory database.",
                            parameters = ToolParameters(
                                properties = mapOf("name" to ToolProperty("string", "The file name to delete.")),
                                required = listOf("name")
                            )
                        )
                    ),
                    ToolDefinition(
                        function = ToolFunction(
                            name = "cleanup_memories",
                            description = "Trim stale memories: delete files not modified for max_age_days whose importance is below min_importance (0-60, higher = more important). Use this to keep the memory database compact. Destructive; requires approval.",
                            parameters = ToolParameters(
                                properties = mapOf(
                                    "max_age_days" to ToolProperty("integer", "Delete files untouched for at least this many days. Default 30."),
                                    "min_importance" to ToolProperty("integer", "Only delete files with importance below this threshold (0-60). Default 30. Pass -1 to ignore importance and delete by age alone.")
                                ),
                                required = emptyList()
                            )
                        )
                    )
                )
            )
        }
        if (ctx.accessActiveMemory) {
            tools.add(
                ToolDefinition(
                    function = ToolFunction(
                            name = "update_active_memory",
                            description = "Update active memory. Modes: replace (default), append, prepend, or patch (old_string→new_string).",
                        parameters = ToolParameters(
                            properties = mapOf(
                                "content" to ToolProperty("string", "The content to write (for replace/append/prepend modes)."),
                                "mode" to ToolProperty(
                                    "string",
                                    "One of: replace, append, prepend, patch. Default is replace."
                                ),
                                "old_string" to ToolProperty(
                                    "string",
                                    "Exact string to find and replace in the active memory. Required for patch mode. Must match exactly once."
                                ),
                                "new_string" to ToolProperty(
                                    "string",
                                    "Replacement string for old_string in patch mode. Pass empty string to delete the matched text."
                                )
                            ),
                            required = listOf("content")
                        )
                    )
                )
            )
        }
        return tools
    }

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String = withContext(Dispatchers.IO) {
        val argsStr = arguments.ifBlank { "{}" }
        val args =
            Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        fun arg(key: String): String =
            (args[key] as? JsonPrimitive)?.content ?: ""

        when (name) {
            "list_memory_files" -> {
                val files = memoryManager.listFiles()
                if (files.isEmpty()) {
                    buildJsonObject {
                        put("type", "list_memory_files")
                        putJsonArray("files") {}
                    }.toString()
                } else {
                    val now = System.currentTimeMillis()
                    val ranked = scorer.rankOrdered(
                        files.map { MemoryScorer.Entry(it.name, it.description) }
                    )
                    buildJsonObject {
                        put("type", "list_memory_files")
                        putJsonArray("files") {
                            ranked.forEach { f ->
                                // 从 description 标签解析分类与初始评分，向后兼容旧记忆。
                                val category = MemoryImportanceScorer.parseCategory(f.description)
                                val importance = MemoryImportanceScorer.parseScore(f.description)
                                    ?: (scorer.score(f.name, f.description, f.content) / 60.0)
                                val info = files.firstOrNull { it.name == f.name }
                                val modifiedAt = info?.modifiedAt ?: 0L
                                val ageDays = if (modifiedAt > 0L) ((now - modifiedAt) / 86_400_000L).toInt() else -1
                                add(
                                    buildJsonObject {
                                        put("name", f.name)
                                        put("description", f.description)
                                        put("priority", scorer.score(f.name, f.description, f.content))
                                        put("category", category.name.lowercase())
                                        put("importance", importance)
                                        put("age_days", ageDays)
                                        if (ageDays > 1) {
                                            put(
                                                "stale_warning",
                                                "This memory is $ageDays days old; it is a point-in-time observation and may be outdated."
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }.toString()
                }
            }

            "read_memory_file" -> {
                val singleName = arg("name")
                val namesArray = args["names"] as? JsonArray
                if (namesArray != null && namesArray.isNotEmpty()) {
                    val names = namesArray.map {
                        (it as? JsonPrimitive)?.content ?: ""
                    }.filter { it.isNotEmpty() }
                    names.joinToString("\n\n") { name ->
                        "--- $name ---\n${memoryManager.readFile(name)}"
                    }
                } else if (singleName.isNotEmpty()) {
                    memoryManager.readFile(singleName)
                } else {
                    "Error: No file name provided. Use 'name' for a single file or 'names' for multiple files."
                }
            }

            "create_memory_file" -> {
                val result = memoryManager.createFile(
                    arg("name"),
                    arg("content"),
                    arg("description")
                )
                journal?.record(ActivityJournal.Kind.MEMORY, "create_memory_file", arg("name"), result)
                result
            }

            "edit_memory_file" -> {
                val editContent = arg("content").ifBlank { null }
                val oldStr = arg("old_string").ifBlank { null }
                val newStr = arg("new_string")
                val newName = arg("new_name").ifBlank { null }
                val descArg = arg("description")
                val desc = if (args.containsKey("description")) descArg else null
                if (editContent != null && oldStr != null) {
                    "Error: 'content' and 'old_string' are mutually exclusive. Use one or the other."
                } else if (oldStr != null && !args.containsKey("new_string")) {
                    "Error: 'old_string' requires 'new_string' (pass empty string to delete)."
                } else if (editContent == null && oldStr == null && newName == null && desc == null) {
                    "Error: At least 'content', 'old_string', 'new_name', or 'description' must be provided."
                } else {
                    val result = memoryManager.editFile(
                        arg("name"),
                        editContent,
                        newName,
                        desc,
                        oldStr,
                        newStr
                    )
                    journal?.record(ActivityJournal.Kind.MEMORY, "edit_memory_file", arg("name"), result)
                    result
                }
            }

            "delete_memory_file" -> {
                val name = arg("name")
                val result = memoryManager.deleteFile(name)
                journal?.record(ActivityJournal.Kind.MEMORY, "delete_memory_file", name, result)
                result
            }

            "cleanup_memories" -> {
                val maxAgeDays = (args["max_age_days"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 30
                val minImportance = (args["min_importance"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 30
                val removed = memoryManager.cleanupMemories(maxAgeDays, minImportance)
                journal?.record(
                    ActivityJournal.Kind.MEMORY,
                    "cleanup_memories",
                    "removed ${removed.size} stale memories (age>${maxAgeDays}d, importance<$minImportance)",
                    removed.joinToString(", ").take(200),
                )
                if (removed.isEmpty()) {
                    buildJsonObject {
                        put("type", "cleanup_memories")
                        put("status", "noop")
                        put("note", "No memory files older than $maxAgeDays days with importance below $minImportance. Nothing deleted.")
                    }.toString()
                } else {
                    buildJsonObject {
                        put("type", "cleanup_memories")
                        put("status", "deleted")
                        put("removed_count", removed.size)
                        putJsonArray("removed") {
                            removed.forEach { add(JsonPrimitive(it)) }
                        }
                        put("note", "Report the removed files to the user.")
                    }.toString()
                }
            }

            "update_active_memory" -> {
                val mode = arg("mode").ifBlank { "replace" }
                val oldStr = arg("old_string").ifBlank { null }
                val newStr = arg("new_string").ifBlank { null }
                if (mode == "patch" && oldStr == null) {
                    "Error: 'old_string' is required for patch mode."
                } else {
                    val result = memoryManager.updateActiveMemory(arg("content"), mode, oldStr, newStr)
                    journal?.record(ActivityJournal.Kind.MEMORY, "update_active_memory", mode, result)
                    result
                }
            }

            else -> "Unknown tool: $name"
        }
    }

    override fun handles(name: String): Boolean = name in setOf(
        "list_memory_files",
        "read_memory_file",
        "create_memory_file",
        "edit_memory_file",
        "delete_memory_file",
        "cleanup_memories",
        "update_active_memory"
    )

    override fun riskLevel(name: String): RiskLevel = when (name) {
        "list_memory_files", "read_memory_file" -> RiskLevel.ReadOnly
        "create_memory_file", "edit_memory_file", "update_active_memory" -> RiskLevel.LowRisk
        "delete_memory_file", "cleanup_memories" -> RiskLevel.HighRisk
        else -> RiskLevel.ReadOnly
    }

    /** 写审批模式：创建/编辑/删除/精简记忆都需要用户确认（Hermes 式写审批）。 */
    override fun requiresApprovalByDefault(name: String): Boolean = name in setOf(
        "create_memory_file",
        "edit_memory_file",
        "delete_memory_file",
        "cleanup_memories",
    )

    override fun toolDescriptors(ctx: GenerationContext): List<ToolDescriptor> {
        val summaries = mapOf(
            "list_memory_files" to "List memory files.",
            "read_memory_file" to "Read memory file(s).",
            "create_memory_file" to "Create a memory file.",
            "edit_memory_file" to "Edit/rename a memory file.",
            "delete_memory_file" to "Delete a memory file.",
            "cleanup_memories" to "Trim stale memories.",
            "update_active_memory" to "Update active memory.",
        )
        return super.toolDescriptors(ctx).map { d ->
            d.copy(
                summary = summaries[d.definition.function.name] ?: d.summary,
                requiresApproval = requiresApprovalByDefault(d.definition.function.name),
            )
        }
    }
}
