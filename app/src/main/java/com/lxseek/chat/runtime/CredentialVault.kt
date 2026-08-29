package com.lxseek.chat.runtime

import com.lxseek.chat.util.DebugLog
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory credential vault for API keys and other secrets.
 *
 * The vault keeps plaintext credentials in process memory (never written to disk
 * by this class) and exposes only masked representations to callers that do not
 * need the raw secret. A typical flow is:
 *
 *  1. On startup, a bootstrap loader calls [store] for each known credential.
 *  2. UI / listing layers use [list] / [listByProvider] / [retrieveMasked] which
 *     never return the plaintext.
 *  3. The networking layer calls [retrieve] right before issuing a request and
 *     drops the reference immediately afterwards.
 *
 * Masking policy ([mask]): keep the first 3 and last 4 characters of the
 * plaintext, replacing everything in between with `****`. Strings too short to
 * safely mask (≤ 7 chars) are fully redacted as `****`.
 *
 * Thread-safety: all accessors are synchronized through a [ConcurrentHashMap].
 *
 * Pure Kotlin only — zero external dependencies. Designed to be injected as a
 * process-scoped singleton via [com.lxseek.chat.di.AppContainer]. For at-rest
 * encryption, wrap this class with an EncryptedSharedPreferences-backed
 * implementation at the persistence boundary; the in-memory contract here is
 * intentionally storage-agnostic.
 */
class CredentialVault {

    /** Public, mask-safe view of a stored credential. Never contains plaintext. */
    data class StoredCredential(
        val id: String,
        val providerId: String,
        val keyName: String,
        val maskedValue: String,
        val createdAt: Long,
    )

    /** Internal record that retains the plaintext alongside the metadata. */
    private data class VaultEntry(
        val providerId: String,
        val keyName: String,
        val plaintext: String,
        val createdAt: Long,
    )

    private val entriesById = ConcurrentHashMap<String, VaultEntry>()

    // ── Write ──────────────────────────────────────────────────

    /**
     * Stores [plaintext] under [id], replacing any previous entry. The plaintext
     * is kept in memory only; callers that need to display the value should use
     * [retrieveMasked] instead of [retrieve].
     */
    fun store(id: String, providerId: String, keyName: String, plaintext: String) {
        entriesById[id] = VaultEntry(
            providerId = providerId,
            keyName = keyName,
            plaintext = plaintext,
            createdAt = System.currentTimeMillis(),
        )
        DebugLog.d(TAG, "store id=$id provider=$providerId name=$keyName (len=${plaintext.length})")
    }

    /** Removes the credential with [id]. Returns `true` when an entry was deleted. */
    fun remove(id: String): Boolean {
        val removed = entriesById.remove(id) != null
        if (removed) DebugLog.d(TAG, "remove id=$id")
        return removed
    }

    // ── Read ───────────────────────────────────────────────────

    /** Returns the plaintext for [id], or `null` when no such credential exists. */
    fun retrieve(id: String): String? = entriesById[id]?.plaintext

    /** Returns a mask-safe [StoredCredential] for [id], or `null` if absent. */
    fun retrieveMasked(id: String): StoredCredential? {
        val entry = entriesById[id] ?: return null
        return toStoredCredential(id, entry)
    }

    /** Lists all credentials in masked form. */
    fun list(): List<StoredCredential> =
        entriesById.entries.map { (id, entry) -> toStoredCredential(id, entry) }

    /** Lists all credentials for [providerId] in masked form. */
    fun listByProvider(providerId: String): List<StoredCredential> =
        entriesById.entries
            .filter { it.value.providerId == providerId }
            .map { (id, entry) -> toStoredCredential(id, entry) }

    // ── Masking ────────────────────────────────────────────────

    /**
     * Masks [plaintext] by keeping the first 3 and last 4 characters and replacing
     * the middle with `****`. Strings of length ≤ 7 are fully redacted as `****`
     * so we never leak a meaningful prefix + suffix for short secrets.
     *
     * Examples:
     *  - `sk-abcdef1234567890` -> `sk-****7890`
     *  - `1234567`            -> `****`
     *  - ``                   -> `****`
     */
    fun mask(plaintext: String): String {
        val len = plaintext.length
        if (len <= MASK_KEEP_PREFIX + MASK_KEEP_SUFFIX) return MASK_FILLER
        val prefix = plaintext.take(MASK_KEEP_PREFIX)
        val suffix = plaintext.takeLast(MASK_KEEP_SUFFIX)
        return "$prefix$MASK_FILLER$suffix"
    }

    // ── Internals ──────────────────────────────────────────────

    private fun toStoredCredential(id: String, entry: VaultEntry): StoredCredential =
        StoredCredential(
            id = id,
            providerId = entry.providerId,
            keyName = entry.keyName,
            maskedValue = mask(entry.plaintext),
            createdAt = entry.createdAt,
        )

    companion object {
        private const val TAG = "CredentialVault"

        /** Number of leading plaintext characters kept by [mask]. */
        const val MASK_KEEP_PREFIX = 3

        /** Number of trailing plaintext characters kept by [mask]. */
        const val MASK_KEEP_SUFFIX = 4

        /** Replacement string inserted between the kept prefix and suffix,
         *  and also used as the fully-redacted placeholder for short secrets. */
        const val MASK_FILLER = "****"
    }
}