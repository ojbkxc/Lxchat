package com.lxseek.chat.viewmodel

import com.lxseek.chat.membership.LocalMembershipProvider
import com.lxseek.chat.membership.MembershipStatus
import com.lxseek.chat.membership.RedemptionCodeValidator
import com.lxseek.chat.membership.RedemptionResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Membership API surface exposed by [ChatViewModel] without bloating the ViewModel file.
 *
 * Wraps a [LocalMembershipProvider] (persistence + apply/revoke) and a
 * [RedemptionCodeValidator] (signature/expiry/replay checks). [redeemCode] performs
 * full validation against the persisted used-nonce set and, on success, applies the
 * redemption so the status flow re-emits with the new tier.
 *
 * Kept intentionally small: the UI page ([com.lxseek.chat.ui.settings.SettingsMembershipPage])
 * collects [status] and calls [redeemCode]/[revokeMembership]; no other ViewModel state is touched.
 */
class MembershipViewModelApi(
    private val provider: LocalMembershipProvider,
    private val validator: RedemptionCodeValidator,
) {
    /** Hot membership status; backed by DataStore via the provider. */
    val status: StateFlow<MembershipStatus> = provider.status

    /**
     * Validate [code] against the persisted used-nonce set and apply it on success.
     * Returns the raw [RedemptionResult] so the UI can render the appropriate feedback
     * (Valid → success, Invalid → reason, Expired/AlreadyUsed → dedicated messages).
     */
    suspend fun redeemCode(code: String): RedemptionResult {
        val usedNonces = provider.redeemedNonces()
        val result = validator.validate(code, usedNonces)
        if (result is RedemptionResult.Valid) {
            provider.applyRedemption(
                tier = result.tier,
                durationDays = result.durationDays,
                nonce = result.nonce,
            )
        }
        return result
    }

    /** Revoke membership and clear all persisted state. */
    suspend fun revokeMembership() = provider.revoke()

    /** Re-hydrate status from DataStore (e.g. after external changes). */
    suspend fun refresh() = provider.refresh()

    /** Apply a cloud credential directly to DataStore (after activation/restore), then refresh. */
    suspend fun applyCredential(credential: com.lxseek.chat.membership.SignedCredential) =
        provider.applyCredential(credential)
}