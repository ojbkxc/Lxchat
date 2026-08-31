package com.lxseek.chat.membership

/** Membership settings for UI display. */
data class MembershipSettings(
    val status: MembershipStatus,
    val redemptionCodeInput: String = "",
    val lastValidationResult: RedemptionResult? = null,
) {
    /** True when there is a non-blank code ready to be validated. */
    val canValidate: Boolean get() = redemptionCodeInput.isNotBlank()

    /** True when the last validation produced a [RedemptionResult.Valid]. */
    val isLastValidationValid: Boolean
        get() = lastValidationResult is RedemptionResult.Valid

    /** Human-readable summary of the current tier for UI headers（二元制）. */
    val tierLabel: String
        get() = if (status.tier == MembershipTier.Free) "Free" else "Paid"
}
