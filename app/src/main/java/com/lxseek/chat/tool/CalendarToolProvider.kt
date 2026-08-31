package com.lxseek.chat.tool

import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.Manifest
import android.database.Cursor
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.api.ToolFunction
import com.lxseek.chat.api.ToolParameters
import com.lxseek.chat.api.ToolProperty
import com.lxseek.chat.util.DebugLog
import com.lxseek.chat.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Calendar tool provider backed by the Android system `CalendarProvider` ContentProvider.
 *
 * Exposes read and CRUD tools over the user's on-device calendars and events using only the
 * platform `CalendarContract` API (zero external dependencies). All calls run on
 * [Dispatchers.IO]; permission failures degrade to a structured JSON error rather than throwing.
 *
 * Required manifest permissions (declared elsewhere, not by this file):
 * - `android.permission.READ_CALENDAR`  — needed by every read tool
 * - `android.permission.WRITE_CALENDAR` — needed by create / update / delete
 *
 * Time arguments accept either epoch milliseconds (numeric string) or an ISO-8601 instant such as
 * `2026-08-29T09:00:00Z` or `2026-08-29T09:00:00+08:00`; a bare local datetime is interpreted in
 * the device's default zone.
 */
class CalendarToolProvider(private val app: Application) : ToolProvider {

    override fun toolDescriptors(ctx: GenerationContext): List<ToolDescriptor> =
        definitions(ctx).map { def ->
            val name = def.function.name
            ToolDescriptor(
                definition = def,
                riskLevel = risk(name),
                tier = tier(name),
                requiresApproval = name == "calendar_create_event" ||
                    name == "calendar_update_event" ||
                    name == "calendar_delete_event",
            )
        }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        fun prop(t: String, d: String) = ToolProperty(t, d)
        return listOf(
            tool(
                "calendar_list_calendars",
                "List every calendar account visible to the user on this device " +
                    "(id, display name, account, owner, visibility, color). Read-only.",
                emptyMap(), emptyList(),
            ),
            tool(
                "calendar_list_events",
                "List calendar events with optional filters. Returns id, calendar_id, title, " +
                    "start/end (epoch millis + ISO), all_day, location and status. " +
                    "Use calendar_get_event for attendees/description.",
                mapOf(
                    "calendar_id" to prop("integer", "Restrict to one calendar id (from calendar_list_calendars). Optional."),
                    "limit" to prop("integer", "Maximum events to return (1..500), default 50."),
                    "start_time" to prop("string", "Lower bound for event start (epoch millis or ISO-8601). Optional."),
                    "end_time" to prop("string", "Upper bound for event start (epoch millis or ISO-8601). Optional."),
                ),
                emptyList(),
            ),
            tool(
                "calendar_get_event",
                "Return full details for one event by id, including description and attendees.",
                mapOf("event_id" to prop("integer", "The CalendarContract.Events._ID to look up.")),
                listOf("event_id"),
            ),
            tool(
                "calendar_create_event",
                "Create a new calendar event. Requires WRITE_CALENDAR. " +
                    "If calendar_id is omitted the first writable calendar is used. " +
                    "attendees is an optional list of email strings.",
                mapOf(
                    "title" to prop("string", "Event title / summary."),
                    "start_time" to prop("string", "Event start (epoch millis or ISO-8601). Required."),
                    "end_time" to prop("string", "Event end (epoch millis or ISO-8601). Required unless all_day is true."),
                    "calendar_id" to prop("integer", "Target calendar id. Optional; defaults to the first writable calendar."),
                    "description" to prop("string", "Free-form description / notes."),
                    "location" to prop("string", "Event location text."),
                    "all_day" to prop("boolean", "True for an all-day event; start/end become dates."),
                    "attendees" to ToolProperty("array", "Optional list of attendee email strings.", items = prop("string", "Attendee email.")),
                ),
                listOf("title", "start_time"),
            ),
            tool(
                "calendar_update_event",
                "Update one or more fields of an existing event by id. Requires WRITE_CALENDAR. " +
                    "Only the supplied fields are changed.",
                mapOf(
                    "event_id" to prop("integer", "The event id to update."),
                    "title" to prop("string", "New title."),
                    "description" to prop("string", "New description."),
                    "location" to prop("string", "New location."),
                    "start_time" to prop("string", "New start (epoch millis or ISO-8601)."),
                    "end_time" to prop("string", "New end (epoch millis or ISO-8601)."),
                    "all_day" to prop("boolean", "New all-day flag."),
                ),
                listOf("event_id"),
            ),
            tool(
                "calendar_delete_event",
                "Delete an event by id. Requires WRITE_CALENDAR. Irreversible.",
                mapOf("event_id" to prop("integer", "The event id to delete.")),
                listOf("event_id"),
            ),
        )
    }

    override fun handles(name: String): Boolean = name.startsWith("calendar_")

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String = withContext(Dispatchers.IO) {
        try {
            when (name) {
                "calendar_list_calendars" -> listCalendars()
                "calendar_list_events" -> listEvents(arguments)
                "calendar_get_event" -> getEvent(arguments)
                "calendar_create_event" -> createEvent(arguments)
                "calendar_update_event" -> updateEvent(arguments)
                "calendar_delete_event" -> deleteEvent(arguments)
                else -> err("unknown_tool", "Unknown calendar tool: $name")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SecurityException) {
            DebugLog.e("CalendarTool", "calendar_$name permission denied", e)
            err("permission_denied", "Calendar permission not granted: ${e.message}")
        } catch (e: Exception) {
            DebugLog.e("CalendarTool", "calendar_$name failed", e)
            err("tool_error", e.message)
        }
    }

    // ── Risk / tier classification ────────────────────────────

    private fun risk(name: String): RiskLevel = when (name) {
        "calendar_list_calendars", "calendar_list_events", "calendar_get_event" -> RiskLevel.ReadOnly
        "calendar_create_event", "calendar_update_event" -> RiskLevel.HighRisk
        "calendar_delete_event" -> RiskLevel.Destructive
        else -> RiskLevel.ReadOnly
    }

    private fun tier(name: String): ToolTier = when (name) {
        "calendar_list_calendars", "calendar_list_events", "calendar_get_event" -> ToolTier.Extended
        else -> ToolTier.Dangerous
    }

    override fun requiresApprovalByDefault(name: String): Boolean =
        name == "calendar_create_event" || name == "calendar_update_event" || name == "calendar_delete_event"

    // ── Permission helpers ─────────────────────────────────────

    private fun hasPermission(permission: String): Boolean = checkPermission(app, permission)

    private fun requireRead(): String? =
        if (hasPermission(Manifest.permission.READ_CALENDAR)) null
        else "Reading calendars requires the READ_CALENDAR permission."

    private fun requireWrite(): String? =
        if (hasPermission(Manifest.permission.WRITE_CALENDAR)) null
        else "Modifying calendars requires the WRITE_CALENDAR permission."

    private val resolver get() = app.contentResolver

    // ── Tools: list calendars ─────────────────────────────────

    private fun listCalendars(): String {
        requireRead()?.let { return err("permission_denied", it) }
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.SYNC_EVENTS,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        return buildJsonObject {
            put("type", "calendar_list_calendars")
            putJsonArray("calendars") {
                resolver.query(
                    CalendarContract.Calendars.CONTENT_URI, projection, null, null,
                    "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
                )?.use { c ->
                    while (c.moveToNext()) {
                        add(buildJsonObject {
                            put("id", c.getLong(c.getColumnIndexOrThrow(CalendarContract.Calendars._ID)))
                            put("displayName", c.getStringOrEmpty(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME))
                            put("accountName", c.getStringOrEmpty(CalendarContract.Calendars.ACCOUNT_NAME))
                            put("accountType", c.getStringOrEmpty(CalendarContract.Calendars.ACCOUNT_TYPE))
                            c.getStringOrNull(CalendarContract.Calendars.OWNER_ACCOUNT)?.let { put("owner", it) }
                            put("visible", c.getIntOrZero(CalendarContract.Calendars.VISIBLE) == 1)
                            put("sync", c.getIntOrZero(CalendarContract.Calendars.SYNC_EVENTS) == 1)
                            c.getIntOrNull(CalendarContract.Calendars.CALENDAR_COLOR)?.let { put("color", it) }
                            put("accessLevel", c.getIntOrZero(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL))
                            put("writable", c.getIntOrZero(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL) >=
                                CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)
                        })
                    }
                }
            }
        }.toString()
    }

    // ── Tools: list events ────────────────────────────────────

    private fun listEvents(arguments: String): String {
        requireRead()?.let { return err("permission_denied", it) }
        val calendarId = argLong("calendar_id", arguments)
        val limit = (argInt("limit", arguments) ?: 50).coerceIn(1, 500)
        val startMillis = parseTime(argString("start_time", arguments))
        val endMillis = parseTime(argString("end_time", arguments))

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.STATUS,
            CalendarContract.Events.EVENT_TIMEZONE,
        )

        val (selection, args) = buildSelection(calendarId, startMillis, endMillis)
        return buildJsonObject {
            put("type", "calendar_list_events")
            put("limit", limit)
            putJsonArray("events") {
                resolver.query(
                    CalendarContract.Events.CONTENT_URI, projection, selection, args,
                    "${CalendarContract.Events.DTSTART} ASC",
                )?.use { c ->
                    var taken = 0
                    while (c.moveToNext() && taken < limit) {
                        taken++
                        add(eventRow(c))
                    }
                }
            }
        }.toString()
    }

    /** Build a SQL selection clause from the optional list_events filters. */
    private fun buildSelection(
        calendarId: Long?,
        startMillis: Long?,
        endMillis: Long?,
    ): Pair<String?, Array<String>?> {
        val parts = mutableListOf<String>()
        val bind = mutableListOf<String>()
        if (calendarId != null) {
            parts.add("${CalendarContract.Events.CALENDAR_ID} = ?")
            bind.add(calendarId.toString())
        }
        if (startMillis != null) {
            parts.add("${CalendarContract.Events.DTSTART} >= ?")
            bind.add(startMillis.toString())
        }
        if (endMillis != null) {
            parts.add("${CalendarContract.Events.DTSTART} <= ?")
            bind.add(endMillis.toString())
        }
        if (parts.isEmpty()) return null to null
        return parts.joinToString(" AND ") to bind.toTypedArray()
    }

    // ── Tools: get single event ───────────────────────────────

    private fun getEvent(arguments: String): String {
        requireRead()?.let { return err("permission_denied", it) }
        val eventId = argLong("event_id", arguments)
            ?: return err("missing_event_id", "event_id is required.")
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.STATUS,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.ORGANIZER,
            CalendarContract.Events.DURATION,
        )
        return resolver.query(uri, projection, null, null, null)?.use { c ->
            if (!c.moveToFirst()) err("not_found", "Event $eventId not found.")
            else buildJsonObject {
                put("type", "calendar_get_event")
                putEventFields(c)
                c.getStringOrNull(CalendarContract.Events.ORGANIZER)?.let { put("organizer", it) }
                c.getStringOrNull(CalendarContract.Events.DURATION)?.let { put("duration", it) }
                putJsonArray("attendees") { addAttendeesInto(eventId) }
            }.toString()
        } ?: err("not_found", "Event $eventId not found.")
    }

    /** Append attendee rows for [eventId] into the current JSON array. */
    private fun JsonArrayBuilder.addAttendeesInto(eventId: Long) {
        val projection = arrayOf(
            CalendarContract.Attendees.ATTENDEE_NAME,
            CalendarContract.Attendees.ATTENDEE_EMAIL,
            CalendarContract.Attendees.ATTENDEE_STATUS,
            CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,
        )
        resolver.query(
            CalendarContract.Attendees.CONTENT_URI, projection,
            "${CalendarContract.Attendees.EVENT_ID} = ?", arrayOf(eventId.toString()), null,
        )?.use { c ->
            while (c.moveToNext()) {
                add(buildJsonObject {
                    put("name", c.getStringOrEmpty(CalendarContract.Attendees.ATTENDEE_NAME))
                    put("email", c.getStringOrEmpty(CalendarContract.Attendees.ATTENDEE_EMAIL))
                    put("status", attendeeStatus(c.getIntOrZero(CalendarContract.Attendees.ATTENDEE_STATUS)))
                    put("relationship", attendeeRel(c.getIntOrZero(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP)))
                })
            }
        }
    }

    // ── Tools: create event ───────────────────────────────────

    private fun createEvent(arguments: String): String {
        requireWrite()?.let { return err("permission_denied", it) }
        val title = argString("title", arguments)
            ?: return err("missing_title", "title is required.")
        val allDay = argBool("all_day", arguments)
        val startMillis = parseTime(argString("start_time", arguments))
            ?: return err("missing_start", "start_time is required and must be epoch millis or ISO-8601.")
        val endMillis = parseTime(argString("end_time", arguments))
            ?: if (allDay == true) startMillis + 24L * 60 * 60 * 1000
            else return err("missing_end", "end_time is required for non all-day events.")

        val calendarId = argLong("calendar_id", arguments) ?: firstWritableCalendarId()
            ?: return err("no_calendar", "No writable calendar found. Create an account first.")

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, argString("description", arguments) ?: "")
            put(CalendarContract.Events.EVENT_LOCATION, argString("location", arguments) ?: "")
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.ALL_DAY, if (allDay == true) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
        }
        val eventUri = resolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return err("insert_failed", "CalendarProvider rejected the insert.")
        val newId = ContentUris.parseId(eventUri)

        // Best-effort attendee insertion; never fails the whole call.
        insertAttendees(newId, arguments)

        return buildJsonObject {
            put("type", "calendar_create_event")
            put("status", "created")
            put("event_id", newId)
            put("calendar_id", calendarId)
            put("uri", eventUri.toString())
        }.toString()
    }

    /** Parse the optional `attendees` JSON array and insert one Attendees row per email. */
    private fun insertAttendees(eventId: Long, arguments: String) {
        val arr = try {
            Json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject["attendees"]?.jsonArray
        } catch (_: Exception) {
            null
        } ?: return
        for (el in arr) {
            val email = (el as? JsonPrimitive)?.content?.trim().orEmpty()
            if (email.isEmpty()) continue
            val v = ContentValues().apply {
                put(CalendarContract.Attendees.EVENT_ID, eventId)
                put(CalendarContract.Attendees.ATTENDEE_EMAIL, email)
                put(CalendarContract.Attendees.ATTENDEE_NAME, email.substringBefore('@'))
                put(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, CalendarContract.Attendees.RELATIONSHIP_ATTENDEE)
                put(CalendarContract.Attendees.ATTENDEE_TYPE, CalendarContract.Attendees.TYPE_NONE)
                put(CalendarContract.Attendees.ATTENDEE_STATUS, 0)  // STATUS_NONE = 0
            }
            try {
                resolver.insert(CalendarContract.Attendees.CONTENT_URI, v)
            } catch (e: Exception) {
                DebugLog.w("CalendarTool", "Failed to insert attendee $email")
            }
        }
    }

    // ── Tools: update event ───────────────────────────────────

    private fun updateEvent(arguments: String): String {
        requireWrite()?.let { return err("permission_denied", it) }
        val eventId = argLong("event_id", arguments)
            ?: return err("missing_event_id", "event_id is required.")
        val values = ContentValues()
        argString("title", arguments)?.let { values.put(CalendarContract.Events.TITLE, it) }
        argString("description", arguments)?.let { values.put(CalendarContract.Events.DESCRIPTION, it) }
        argString("location", arguments)?.let { values.put(CalendarContract.Events.EVENT_LOCATION, it) }
        parseTime(argString("start_time", arguments))?.let { values.put(CalendarContract.Events.DTSTART, it) }
        parseTime(argString("end_time", arguments))?.let { values.put(CalendarContract.Events.DTEND, it) }
        argBool("all_day", arguments)?.let { values.put(CalendarContract.Events.ALL_DAY, if (it) 1 else 0) }
        if (values.size() == 0) return err("no_fields", "No updatable fields were supplied.")

        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val rows = resolver.update(uri, values, null, null)
        return if (rows > 0) {
            buildJsonObject {
                put("type", "calendar_update_event")
                put("status", "updated")
                put("event_id", eventId)
                put("rowsAffected", rows)
            }.toString()
        } else {
            err("not_found", "Event $eventId not found or not updatable.")
        }
    }

    // ── Tools: delete event ───────────────────────────────────

    private fun deleteEvent(arguments: String): String {
        requireWrite()?.let { return err("permission_denied", it) }
        val eventId = argLong("event_id", arguments)
            ?: return err("missing_event_id", "event_id is required.")
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val rows = resolver.delete(uri, null, null)
        return if (rows > 0) {
            buildJsonObject {
                put("type", "calendar_delete_event")
                put("status", "deleted")
                put("event_id", eventId)
                put("rowsAffected", rows)
            }.toString()
        } else {
            err("not_found", "Event $eventId not found or already deleted.")
        }
    }

    // ── Lookup helpers ────────────────────────────────────────

    /** Return the id of the first calendar the app can write to, or null if none. */
    private fun firstWritableCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        return resolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection,
            "${CalendarContract.Calendars.VISIBLE} = 1", null,
            "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
        )?.use { c ->
            var best: Long? = null
            while (c.moveToNext()) {
                val access = c.getIntOrZero(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
                if (access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                    best = c.getLong(c.getColumnIndexOrThrow(CalendarContract.Calendars._ID))
                    break
                }
            }
            best
        }
    }

    // ── JSON row builders ─────────────────────────────────────

    /** Compact event row used by calendar_list_events. */
    private fun eventRow(c: Cursor) = buildJsonObject {
        put("id", c.getLong(c.getColumnIndexOrThrow(CalendarContract.Events._ID)))
        put("calendar_id", c.getLong(c.getColumnIndexOrThrow(CalendarContract.Events.CALENDAR_ID)))
        put("title", c.getStringOrEmpty(CalendarContract.Events.TITLE))
        c.getStringOrNull(CalendarContract.Events.DESCRIPTION)?.let { put("description", it) }
        val start = c.getLongOrZero(CalendarContract.Events.DTSTART)
        val end = c.getLongOrZero(CalendarContract.Events.DTEND)
        put("start", start)
        put("end", end)
        put("start_iso", iso(start))
        put("end_iso", iso(end))
        put("all_day", c.getIntOrZero(CalendarContract.Events.ALL_DAY) == 1)
        c.getStringOrNull(CalendarContract.Events.EVENT_LOCATION)?.let { put("location", it) }
        put("status", eventStatus(c.getIntOrZero(CalendarContract.Events.STATUS)))
    }

    /** Full event fields used by calendar_get_event (without attendees). */
    private fun JsonObjectBuilder.putEventFields(c: Cursor) {
        put("id", c.getLong(c.getColumnIndexOrThrow(CalendarContract.Events._ID)))
        put("calendar_id", c.getLong(c.getColumnIndexOrThrow(CalendarContract.Events.CALENDAR_ID)))
        put("title", c.getStringOrEmpty(CalendarContract.Events.TITLE))
        put("description", c.getStringOrEmpty(CalendarContract.Events.DESCRIPTION))
        val start = c.getLongOrZero(CalendarContract.Events.DTSTART)
        val end = c.getLongOrZero(CalendarContract.Events.DTEND)
        put("start", start)
        put("end", end)
        put("start_iso", iso(start))
        put("end_iso", iso(end))
        put("all_day", c.getIntOrZero(CalendarContract.Events.ALL_DAY) == 1)
        put("location", c.getStringOrEmpty(CalendarContract.Events.EVENT_LOCATION))
        put("status", eventStatus(c.getIntOrZero(CalendarContract.Events.STATUS)))
        c.getStringOrNull(CalendarContract.Events.EVENT_TIMEZONE)?.let { put("timezone", it) }
    }

    // ── Enum decoders ─────────────────────────────────────────

    private fun eventStatus(v: Int): String = when (v) {
        CalendarContract.Events.STATUS_TENTATIVE -> "tentative"
        CalendarContract.Events.STATUS_CONFIRMED -> "confirmed"
        CalendarContract.Events.STATUS_CANCELED -> "canceled"
        else -> "unknown"
    }

    private fun attendeeStatus(v: Int): String = when (v) {
        CalendarContract.Attendees.ATTENDEE_STATUS_NONE -> "none"
        CalendarContract.Attendees.ATTENDEE_STATUS_INVITED -> "invited"
        CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED -> "accepted"
        CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED -> "declined"
        CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE -> "tentative"
        else -> "unknown"
    }

    private fun attendeeRel(v: Int): String = when (v) {
        CalendarContract.Attendees.RELATIONSHIP_NONE -> "none"
        CalendarContract.Attendees.RELATIONSHIP_ATTENDEE -> "attendee"
        CalendarContract.Attendees.RELATIONSHIP_ORGANIZER -> "organizer"
        CalendarContract.Attendees.RELATIONSHIP_PERFORMER -> "performer"
        CalendarContract.Attendees.RELATIONSHIP_SPEAKER -> "speaker"
        else -> "unknown"
    }

    // ── Time parsing ──────────────────────────────────────────

    /**
     * Parse a time argument into epoch milliseconds. Accepts a numeric epoch-millis string or an
     * ISO-8601 instant / offset / local datetime. Returns null when the value cannot be parsed.
     *
     * Uses [SimpleDateFormat] (not `java.time`) so it works on minSdk 24 without core library
     * desugaring. Each call builds its own formatter, so thread-safety is not a concern.
     */
    private fun parseTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        value.toLongOrNull()?.let { return it }
        // Try several ISO-8601 shapes, strictest first.
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
            "yyyy-MM-dd",
        )
        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                if (pattern.endsWith("'Z'")) sdf.timeZone = TimeZone.getTimeZone("UTC")
                if (!pattern.contains("XXX") && !pattern.endsWith("'Z'")) {
                    // No offset in the pattern: interpret bare local times in the device zone.
                    sdf.timeZone = TimeZone.getDefault()
                }
                sdf.isLenient = true
                val parsed = sdf.parse(value) ?: continue
                return parsed.time
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    /** Format epoch millis as an ISO-8601 UTC instant, or null for non-positive values. */
    private fun iso(millis: Long): String? = if (millis <= 0L) null
    else try {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        sdf.format(java.util.Date(millis))
    } catch (_: Exception) {
        null
    }

    // ── Cursor helpers ────────────────────────────────────────

    private fun Cursor.getStringOrNull(col: String): String? {
        val i = getColumnIndex(col)
        return if (i < 0 || isNull(i)) null else getString(i)
    }

    private fun Cursor.getStringOrEmpty(col: String): String = getStringOrNull(col).orEmpty()

    private fun Cursor.getLongOrZero(col: String): Long {
        val i = getColumnIndex(col)
        return if (i < 0 || isNull(i)) 0L else getLong(i)
    }

    private fun Cursor.getIntOrZero(col: String): Int {
        val i = getColumnIndex(col)
        return if (i < 0 || isNull(i)) 0 else getInt(i)
    }

    private fun Cursor.getIntOrNull(col: String): Int? {
        val i = getColumnIndex(col)
        return if (i < 0 || isNull(i)) null else getInt(i)
    }

    // ── Tool definition + error helpers ───────────────────────

    private fun err(code: String, message: String?): String = toolError("calendar_error", code, message)
}