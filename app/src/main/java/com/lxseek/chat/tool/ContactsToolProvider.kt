package com.lxseek.chat.tool

import android.app.Application
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
import android.provider.ContactsContract
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * On-device contacts (address book) tools backed by Android's [ContactsContract]
 * ContentProvider. Exposes CRUD plus search so the model can read and manage the
 * user's contacts without any external dependency.
 *
 * Permissions:
 * - Read tools (contact_list / contact_get / contact_search) require READ_CONTACTS.
 * - Write tools (contact_create / contact_update / contact_delete) require WRITE_CONTACTS.
 *
 * Every permission-dependent call degrades to a structured JSON error (never crashes)
 * when the grant is missing. This provider is intentionally self-contained: it is not
 * registered in NativeToolsPlugin or the manifest — wire it up explicitly when the
 * product decides to expose contacts to the model.
 */
class ContactsToolProvider(private val app: Application) : ToolProvider {

    override fun toolDescriptors(ctx: GenerationContext): List<ToolDescriptor> =
        definitions(ctx).map { def ->
            val name = def.function.name
            ToolDescriptor(
                definition = def,
                riskLevel = risk(name),
                tier = tier(name),
                // All mutations touch the user's address book, so force explicit approval.
                requiresApproval = name == "contact_create" ||
                    name == "contact_update" || name == "contact_delete",
            )
        }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        fun prop(t: String, d: String) = ToolProperty(t, d)
        return listOf(
            tool(
                "contact_list",
                "List contacts from the device address book. Returns id, name, phone, email and " +
                    "photo_uri for each contact, sorted by display name. Supports limit/offset paging.",
                mapOf(
                    "limit" to prop("integer", "Maximum number of contacts to return (1..500), default 50."),
                    "offset" to prop("integer", "Number of contacts to skip for paging, default 0."),
                ),
                emptyList(),
            ),
            tool(
                "contact_get",
                "Return full detail for one contact by id: display name, all phone numbers, all " +
                    "emails and photo uri.",
                mapOf("id" to prop("integer", "The contact id from contact_list/contact_search.")),
                listOf("id"),
            ),
            tool(
                "contact_search",
                "Search contacts by name substring or phone number substring. Returns matching " +
                    "contacts with id, name, phone, email and photo_uri.",
                mapOf(
                    "query" to prop("string", "Substring to match against contact name or phone number."),
                    "limit" to prop("integer", "Maximum number of matches to return (1..500), default 50."),
                ),
                listOf("query"),
            ),
            tool(
                "contact_create",
                "Create a new contact with a display name and optional phone and email. Requires " +
                    "the WRITE_CONTACTS permission.",
                mapOf(
                    "name" to prop("string", "Display name of the new contact."),
                    "phone" to prop("string", "Optional phone number."),
                    "email" to prop("string", "Optional email address."),
                ),
                listOf("name"),
            ),
            tool(
                "contact_update",
                "Update an existing contact's display name, phone or email by id. Only the fields " +
                    "provided are changed. Phone/email update the primary entry, or insert one if " +
                    "none exists. Requires the WRITE_CONTACTS permission.",
                mapOf(
                    "id" to prop("integer", "The contact id to update."),
                    "name" to prop("string", "New display name (optional)."),
                    "phone" to prop("string", "New primary phone number (optional)."),
                    "email" to prop("string", "New primary email address (optional)."),
                ),
                listOf("id"),
            ),
            tool(
                "contact_delete",
                "Delete a contact by id. This is irreversible. Requires the WRITE_CONTACTS permission.",
                mapOf("id" to prop("integer", "The contact id to delete.")),
                listOf("id"),
            ),
        )
    }

    override fun handles(name: String): Boolean = name.startsWith("contact_")

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String = withContext(Dispatchers.IO) {
        try {
            when (name) {
                "contact_list" -> list(arguments)
                "contact_get" -> get(arguments)
                "contact_search" -> search(arguments)
                "contact_create" -> create(arguments)
                "contact_update" -> update(arguments)
                "contact_delete" -> delete(arguments)
                else -> err("unknown_tool", "Unknown contacts tool: $name")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e("ContactsTool", "contact_$name failed", e)
            err("tool_error", e.message)
        }
    }

    // ── Read tools ───────────────────────────────────────────

    private fun list(arguments: String): String {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            return err("permission_denied", "Reading contacts needs the READ_CONTACTS permission.")
        }
        val limit = (argInt("limit", arguments) ?: 50).coerceIn(1, 500)
        val offset = (argInt("offset", arguments) ?: 0).coerceAtLeast(0)
        val cr = app.contentResolver
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME,
            ContactsContract.Contacts.PHOTO_URI,
        )
        val page = mutableListOf<ContactSummary>()
        cr.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            null, null,
            "${ContactsContract.Contacts.DISPLAY_NAME} ASC",
        )?.use { c ->
            var skipped = 0
            while (c.moveToNext()) {
                if (skipped < offset) { skipped++; continue }
                if (page.size >= limit) break
                val id = c.getLong(0)
                val name = c.getString(1) ?: continue
                page.add(ContactSummary(id, name, c.getString(2)))
            }
        }
        val ids = page.map { it.id }.toSet()
        val phones = loadPhones(cr, ids)
        val emails = loadEmails(cr, ids)
        return buildJsonObject {
            put("type", "contact_list")
            put("count", page.size)
            put("limit", limit)
            put("offset", offset)
            put("contacts", buildJsonArray {
                page.forEach { cs ->
                    add(buildJsonObject {
                        put("id", cs.id)
                        put("name", cs.name)
                        put("phone", phones[cs.id]?.firstOrNull().orEmpty())
                        put("phones", buildJsonArray {
                            phones[cs.id]?.forEach { add(JsonPrimitive(it)) }
                        })
                        put("email", emails[cs.id]?.firstOrNull().orEmpty())
                        put("emails", buildJsonArray {
                            emails[cs.id]?.forEach { add(JsonPrimitive(it)) }
                        })
                        put("photo_uri", cs.photoUri.orEmpty())
                    })
                }
            })
        }.toString()
    }

    private fun get(arguments: String): String {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            return err("permission_denied", "Reading contacts needs the READ_CONTACTS permission.")
        }
        val id = argInt("id", arguments)?.toLong()
            ?: return err("no_id", "Missing contact id.")
        val cr = app.contentResolver
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)
        var name: String? = null
        var photoUri: String? = null
        cr.query(
            uri,
            arrayOf(
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Contacts.PHOTO_URI,
            ),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                photoUri = c.getString(1)
            }
        }
        if (name == null) return err("not_found", "No contact with id $id.")
        val phones = loadPhones(cr, setOf(id))[id] ?: emptyList()
        val emails = loadEmails(cr, setOf(id))[id] ?: emptyList()
        return buildJsonObject {
            put("type", "contact_get")
            put("id", id)
            put("name", name)
            put("phones", buildJsonArray { phones.forEach { add(JsonPrimitive(it)) } })
            put("emails", buildJsonArray { emails.forEach { add(JsonPrimitive(it)) } })
            put("photo_uri", photoUri.orEmpty())
        }.toString()
    }

    private fun search(arguments: String): String {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            return err("permission_denied", "Reading contacts needs the READ_CONTACTS permission.")
        }
        val query = argString("query", arguments)?.trim()
            ?: return err("no_query", "Missing query.")
        if (query.isEmpty()) return err("no_query", "Query must not be empty.")
        val limit = (argInt("limit", arguments) ?: 50).coerceIn(1, 500)
        val cr = app.contentResolver
        val needle = "%$query%"
        // Collect matching contact ids, de-duplicated and insertion-ordered.
        val idSet = linkedSetOf<Long>()
        // Match by display name.
        cr.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID),
            "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?",
            arrayOf(needle),
            "${ContactsContract.Contacts.DISPLAY_NAME} ASC",
        )?.use { c ->
            while (c.moveToNext() && idSet.size < limit) idSet.add(c.getLong(0))
        }
        // Match by phone number digit substring.
        if (idSet.size < limit) {
            cr.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID),
                "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?",
                arrayOf(needle),
                null,
            )?.use { c ->
                while (c.moveToNext() && idSet.size < limit) idSet.add(c.getLong(0))
            }
        }
        val contacts = idSet.mapNotNull { fetchSummary(cr, it) }
        val phones = loadPhones(cr, idSet)
        val emails = loadEmails(cr, idSet)
        return buildJsonObject {
            put("type", "contact_search")
            put("query", query)
            put("count", contacts.size)
            put("contacts", buildJsonArray {
                contacts.forEach { cs ->
                    add(buildJsonObject {
                        put("id", cs.id)
                        put("name", cs.name)
                        put("phone", phones[cs.id]?.firstOrNull().orEmpty())
                        put("email", emails[cs.id]?.firstOrNull().orEmpty())
                        put("photo_uri", cs.photoUri.orEmpty())
                    })
                }
            })
        }.toString()
    }

    // ── Write tools ──────────────────────────────────────────

    private fun create(arguments: String): String {
        if (!hasPermission(Manifest.permission.WRITE_CONTACTS)) {
            return err("permission_denied", "Creating contacts needs the WRITE_CONTACTS permission.")
        }
        val name = argString("name", arguments)?.trim()
            ?: return err("no_name", "Missing contact name.")
        if (name.isEmpty()) return err("no_name", "Contact name must not be empty.")
        val phone = argString("phone", arguments)?.trim()?.takeIf { it.isNotEmpty() }
        val email = argString("email", arguments)?.trim()?.takeIf { it.isNotEmpty() }
        val cr = app.contentResolver
        val ops = ArrayList<ContentProviderOperation>()
        // 1. Insert a raw contact on the local (null) account.
        ops.add(ContentProviderOperation
            .newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
            .build())
        // 2. Display name.
        ops.add(ContentProviderOperation
            .newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE,
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            .build())
        // 3. Optional phone.
        if (phone != null) {
            ops.add(ContentProviderOperation
                .newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build())
        }
        // 4. Optional email.
        if (email != null) {
            ops.add(ContentProviderOperation
                .newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                .withValue(ContactsContract.CommonDataKinds.Email.TYPE,
                    ContactsContract.CommonDataKinds.Email.TYPE_HOME)
                .build())
        }
        val results = cr.applyBatch(ContactsContract.AUTHORITY, ops)
        // Resolve the aggregate contact id from the freshly created raw contact.
        val rawContactId = results[0].uri?.lastPathSegment?.toLongOrNull()
        val contactId = rawContactId?.let { resolveContactId(cr, it) }
        return buildJsonObject {
            put("type", "contact_create")
            put("status", "ok")
            put("name", name)
            if (phone != null) put("phone", phone)
            if (email != null) put("email", email)
            if (contactId != null) put("id", contactId)
        }.toString()
    }

    private fun update(arguments: String): String {
        if (!hasPermission(Manifest.permission.WRITE_CONTACTS)) {
            return err("permission_denied", "Updating contacts needs the WRITE_CONTACTS permission.")
        }
        val id = argInt("id", arguments)?.toLong()
            ?: return err("no_id", "Missing contact id.")
        val newName = argString("name", arguments)?.trim()?.takeIf { it.isNotEmpty() }
        val newPhone = argString("phone", arguments)?.trim()?.takeIf { it.isNotEmpty() }
        val newEmail = argString("email", arguments)?.trim()?.takeIf { it.isNotEmpty() }
        if (newName == null && newPhone == null && newEmail == null) {
            return err("no_fields", "No fields to update; provide name, phone or email.")
        }
        val cr = app.contentResolver
        // Verify the contact exists and pick its raw contact row to mutate.
        val rawContactId = findRawContactId(cr, id)
            ?: return err("not_found", "No contact with id $id.")
        val ops = ArrayList<ContentProviderOperation>()
        if (newName != null) {
            ops.add(ContentProviderOperation
                .newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection(
                    "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND " +
                        "${ContactsContract.Data.MIMETYPE} = ?",
                    arrayOf(rawContactId.toString(),
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE),
                )
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, newName)
                .build())
        }
        if (newPhone != null) {
            upsertData(
                ops, rawContactId,
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Phone.NUMBER, newPhone,
                ContactsContract.CommonDataKinds.Phone.TYPE to
                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
            )
        }
        if (newEmail != null) {
            upsertData(
                ops, rawContactId,
                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Email.ADDRESS, newEmail,
                ContactsContract.CommonDataKinds.Email.TYPE to
                    ContactsContract.CommonDataKinds.Email.TYPE_HOME,
            )
        }
        cr.applyBatch(ContactsContract.AUTHORITY, ops)
        return buildJsonObject {
            put("type", "contact_update")
            put("status", "ok")
            put("id", id)
            if (newName != null) put("name", newName)
            if (newPhone != null) put("phone", newPhone)
            if (newEmail != null) put("email", newEmail)
        }.toString()
    }

    private fun delete(arguments: String): String {
        if (!hasPermission(Manifest.permission.WRITE_CONTACTS)) {
            return err("permission_denied", "Deleting contacts needs the WRITE_CONTACTS permission.")
        }
        val id = argInt("id", arguments)?.toLong()
            ?: return err("no_id", "Missing contact id.")
        val cr = app.contentResolver
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)
        val deleted = cr.delete(uri, null, null)
        return if (deleted > 0) {
            buildJsonObject {
                put("type", "contact_delete")
                put("status", "ok")
                put("id", id)
                put("deleted", deleted)
            }.toString()
        } else {
            err("not_found", "No contact with id $id.")
        }
    }

    // ── ContactsContract helpers ─────────────────────────────

    /** Lightweight row used while paging/searching before phones/emails are joined. */
    private data class ContactSummary(val id: Long, val name: String, val photoUri: String?)

    /** Load all phone numbers grouped by contact id for the given contact id set. */
    private fun loadPhones(cr: ContentResolver, ids: Set<Long>): Map<Long, List<String>> {
        if (ids.isEmpty()) return emptyMap()
        val result = mutableMapOf<Long, MutableList<String>>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val selection =
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} IN (${ids.joinToString(",")})"
        cr.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection, selection, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val cid = c.getLong(0)
                val num = c.getString(1) ?: continue
                result.getOrPut(cid) { mutableListOf() }.add(num)
            }
        }
        return result
    }

    /** Load all email addresses grouped by contact id for the given contact id set. */
    private fun loadEmails(cr: ContentResolver, ids: Set<Long>): Map<Long, List<String>> {
        if (ids.isEmpty()) return emptyMap()
        val result = mutableMapOf<Long, MutableList<String>>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Email.CONTACT_ID,
            ContactsContract.CommonDataKinds.Email.ADDRESS,
        )
        val selection =
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} IN (${ids.joinToString(",")})"
        cr.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            projection, selection, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val cid = c.getLong(0)
                val addr = c.getString(1) ?: continue
                result.getOrPut(cid) { mutableListOf() }.add(addr)
            }
        }
        return result
    }

    /** Fetch the id/name/photo summary for a single contact id, or null if it no longer exists. */
    private fun fetchSummary(cr: ContentResolver, id: Long): ContactSummary? {
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)
        cr.query(
            uri,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Contacts.PHOTO_URI,
            ),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(1) ?: return null
                return ContactSummary(c.getLong(0), name, c.getString(2))
            }
        }
        return null
    }

    /** Map a raw contact id to its aggregate contact id. */
    private fun resolveContactId(cr: ContentResolver, rawContactId: Long): Long? {
        cr.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.CONTACT_ID),
            "${ContactsContract.RawContacts._ID} = ?",
            arrayOf(rawContactId.toString()),
            null,
        )?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return null
    }

    /** Find the first raw contact id backing the given aggregate contact id, or null. */
    private fun findRawContactId(cr: ContentResolver, contactId: Long): Long? {
        cr.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null,
        )?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return null
    }

    /**
     * Update the first existing Data row of [mimeType] for [rawContactId], or insert a new one
     * if none exists. [extra] carries an extra column/value pair (e.g. Phone.TYPE) applied to
     * both the update and the insert paths.
     */
    private fun upsertData(
        ops: ArrayList<ContentProviderOperation>,
        rawContactId: Long,
        mimeType: String,
        valueColumn: String,
        value: String,
        extra: Pair<String, Int>,
    ) {
        val cr = app.contentResolver
        val existingId = cr.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID),
            "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(rawContactId.toString(), mimeType),
            null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

        if (existingId != null) {
            ops.add(ContentProviderOperation
                .newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data._ID} = ?", arrayOf(existingId.toString()))
                .withValue(valueColumn, value)
                .withValue(extra.first, extra.second)
                .build())
        } else {
            ops.add(ContentProviderOperation
                .newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                .withValue(ContactsContract.Data.MIMETYPE, mimeType)
                .withValue(valueColumn, value)
                .withValue(extra.first, extra.second)
                .build())
        }
    }

    // ── Generic helpers ──────────────────────────────────────

    private fun tool(
        name: String,
        description: String,
        properties: Map<String, ToolProperty>,
        required: List<String>,
    ) = ToolDefinition(function = ToolFunction(
        name = name,
        description = description,
        parameters = ToolParameters(properties = properties, required = required),
    ))

    private fun risk(name: String): RiskLevel = when (name) {
        "contact_create", "contact_update" -> RiskLevel.Moderate
        "contact_delete" -> RiskLevel.HighRisk
        else -> RiskLevel.ReadOnly
    }

    private fun tier(name: String): ToolTier = when (name) {
        "contact_list", "contact_get", "contact_search" -> ToolTier.Extended
        else -> ToolTier.Dangerous
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    private fun argString(key: String, arguments: String): String? {
        val stripped = arguments.ifBlank { "{}" }
        return try {
            val el = Json.decodeFromString<Map<String, JsonPrimitive>>(stripped)[key]
            val v = el?.content ?: return null
            if (v == "null") null else v
        } catch (_: Exception) {
            null
        }
    }

    private fun argInt(key: String, arguments: String): Int? {
        val stripped = arguments.ifBlank { "{}" }
        return try {
            Json.decodeFromString<Map<String, JsonPrimitive>>(stripped)[key]?.content?.toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun err(code: String, message: String?): String = buildJsonObject {
        put("type", "contact_error")
        put("error", code)
        if (!message.isNullOrBlank()) put("message", message)
    }.toString()
}