package com.lxseek.chat.tool

import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.automation.TaskManager
import com.lxseek.chat.viewmodel.GenerationContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Lets the agent turn natural-language reminder requests ("在 30 分钟后提醒我喝水", "每天早上 8 点叫我起床")
 * into a persisted background Task with a sparing 5-field cron schedule.
 *
 * Instead of re-implementing an LLM classifier inside the provider, the parsing LLM itself resolves
 * the user's intent into a strict structured JSON first (mirroring the reference python bot's
 * reminder parser), which is far more robust than asking the model to directly produce a 5-field cron.
 * This provider only validates the structured fields and translates them to a cron expression that is
 * executed through the existing [TaskManager] so the reminder runs headlessly like any other Task.
 */
class ReminderToolProvider(
    private val taskManager: TaskManager,
    private val isCurrentlyEnabled: suspend () -> Boolean = { true },
) : ToolProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override fun handles(name: String): Boolean = name == SCHEDULE_REMINDER

    override fun riskLevel(name: String): RiskLevel = RiskLevel.Moderate

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> = listOf(
        ToolDefinition(
            function = ToolFunction(
                name = SCHEDULE_REMINDER,
                description = "Schedule a one-shot or recurring reminder. Resolve the user's natural-language " +
                    "reminder request into structured fields first: type is exactly \"recurring\" for a repeating " +
                    "daily reminder, \"one_off_short\" when the target is within 10 minutes from now, or \"one_off_long\" " +
                    "otherwise. For recurring provide the 24h \"HH:MM\" time. For one-off " +
                    "reminders provide the absolute future \"YYYY-MM-DD HH:MM\" (never a relative duration). " +
                    "message is the reminder content. Returns the created Task. Do not invent times; infer them " +
                    "from the request and the current time.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "type" to ToolProperty(
                            type = "string",
                            description = "One of: recurring (daily repeat), one_off_short (within 10 min), one_off_long (later today/future).",
                        ),
                        "time" to ToolProperty(
                            type = "string",
                            description = "\"HH:MM\" for recurring, or absolute \"YYYY-MM-DD HH:MM\" for one-off reminders.",
                        ),
                        "message" to ToolProperty(
                            type = "string",
                            description = "The reminder/reminder content the user wants to be reminded of.",
                        ),
                    ),
                    required = listOf("type", "time", "message"),
                ),
            ),
        ),
    )

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (name != SCHEDULE_REMINDER) return error("Unknown reminder tool: $name")
        if (!isCurrentlyEnabled()) return error("Reminder tool is disabled")
        return try {
            val args = json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject
            val type = args.string("type")?.trim()?.lowercase().orEmpty()
            val time = args.string("time")?.trim().orEmpty()
            val message = args.string("message")?.trim().orEmpty()
            when {
                message.isEmpty() -> return error("message is required")
                time.isEmpty() -> return error("time is required")
            }
            when (type) {
                "recurring" -> scheduleRecurring(time, message)
                "one_off_short", "one_off_long" -> scheduleOneOff(time, type, message)
                else -> error("type must be one of: recurring, one_off_short, one_off_long")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error(e.localizedMessage?.takeIf { it.isNotBlank() } ?: "Invalid reminder arguments")
        }
    }

    /** Daily-repeating reminder: "HH:MM" -> a sparing 5-field cron "M H * * *". */
    private suspend fun scheduleRecurring(time: String, message: String): String {
        val hm = Regex("""^(\d{1,2}):(\d{2})$""").matchEntire(time.trim())
        if (hm == null) return error("recurring time must be 24h \"HH:MM\" (e.g. 08:00)")
        val hour = hm.groupValues[1].toInt()
        val minute = hm.groupValues[2].toInt()
        if (hour !in 0..23 || minute !in 0..59) return error("recurring time out of range: $time")
        val cron = "$minute $hour * * *"
        val task = taskManager.createTask(
            name = "Reminder · ${message.take(24)}",
            prompt = "Reminder: $message. Deliver this reminder clearly to the user.",
            cronExpr = cron,
            modelId = null,
        )
        return buildJsonObject {
            put("type", SCHEDULE_REMINDER)
            put("cron", cron)
            put("message", message)
            put("task_id", task.id)
            put("enabled", task.enabled)
        }.toString()
    }

    /** One-shot reminder: a true one-time task via [TaskManager.createTask] runAt, so it never repeats. */
    private suspend fun scheduleOneOff(time: String, type: String, message: String): String {
        val dt = parseDateTime(time)
            ?: return error("one-off time must be absolute \"YYYY-MM-DD HH:MM\", e.g. 2024-05-29 14:30")
        val now = LocalDateTime.now()
        if (type == "one_off_short") {
            val diffMin = TimeUnit.MILLISECONDS.toMinutes(
                java.time.Duration.between(now, dt).toMillis(),
            )
            if (diffMin < 0 || diffMin > 10) {
                return error("one_off_short must target within 10 minutes from now; got $diffMin min")
            }
        } else if (dt <= now) {
            return error("one_off_long target time must be in the future: $time")
        }
        val runAt = dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (runAt <= System.currentTimeMillis()) {
            return error("one-off target time is already past: $time")
        }
        val task = taskManager.createTask(
            name = "Reminder · ${message.take(24)}",
            prompt = "Reminder: $message. Deliver this reminder clearly to the user.",
            cronExpr = "",
            modelId = null,
            runAt = runAt,
        )
        return buildJsonObject {
            put("type", SCHEDULE_REMINDER)
            put("run_at", runAt)
            put("message", message)
            put("task_id", task.id)
            put("enabled", task.enabled)
        }.toString()
    }

    private fun parseDateTime(s: String): LocalDateTime? = runCatching {
        LocalDateTime.parse(s.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun error(message: String): String = "Error: $message"

    private companion object {
        const val SCHEDULE_REMINDER = "schedule_reminder"
    }
}