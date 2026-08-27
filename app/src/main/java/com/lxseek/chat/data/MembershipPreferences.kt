package com.lxseek.chat.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Offline persistence for membership state.
 *
 * Raw fields are exposed here so the membership module can assemble its own
 * MembershipStatus snapshot without forcing the data module to depend on it.
 */
class MembershipPreferences(private val store: DataStore<Preferences>) {
    val tier: Flow<String> = store.data.map { it[MEMBERSHIP_TIER] ?: "Free" }
    val expiryTimestamp: Flow<Long?> = store.data.map { it[MEMBERSHIP_EXPIRY_TIMESTAMP] }
    val source: Flow<String> = store.data.map { it[MEMBERSHIP_SOURCE] ?: "" }
    val isActive: Flow<Boolean> = store.data.map { it[MEMBERSHIP_IS_ACTIVE] ?: false }
    val redeemedNonces: Flow<Set<String>> =
        store.data.map { it[MEMBERSHIP_REDEEMED_NONCES] ?: emptySet() }

    /** Persist the full membership status snapshot atomically. */
    suspend fun saveStatus(
        tier: String,
        expiryTimestamp: Long?,
        source: String,
        isActive: Boolean,
    ) {
        store.edit { prefs ->
            prefs[MEMBERSHIP_TIER] = tier
            if (expiryTimestamp == null) {
                prefs.remove(MEMBERSHIP_EXPIRY_TIMESTAMP)
            } else {
                prefs[MEMBERSHIP_EXPIRY_TIMESTAMP] = expiryTimestamp
            }
            prefs[MEMBERSHIP_SOURCE] = source
            prefs[MEMBERSHIP_IS_ACTIVE] = isActive
        }
    }

    /** Record a redeemed code nonce so it cannot be redeemed again. */
    suspend fun addRedeemedNonce(nonce: String) {
        if (nonce.isBlank()) return
        store.edit { prefs ->
            val current = prefs[MEMBERSHIP_REDEEMED_NONCES] ?: emptySet()
            prefs[MEMBERSHIP_REDEEMED_NONCES] = current + nonce
        }
    }

    /** Clear all membership state (e.g. on sign-out or manual revoke). */
    suspend fun clear() {
        store.edit { prefs ->
            prefs.remove(MEMBERSHIP_TIER)
            prefs.remove(MEMBERSHIP_EXPIRY_TIMESTAMP)
            prefs.remove(MEMBERSHIP_SOURCE)
            prefs.remove(MEMBERSHIP_IS_ACTIVE)
            prefs.remove(MEMBERSHIP_REDEEMED_NONCES)
        }
    }
}