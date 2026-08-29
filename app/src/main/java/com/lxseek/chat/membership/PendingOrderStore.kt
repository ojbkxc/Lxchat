package com.lxseek.chat.membership

import android.content.Context
import org.json.JSONObject

/**
 * Persists the in-flight Yipay (鏄撴敮浠? order so the App can query its status later.
 *
 * The App has no backend server, so a DeepLink callback is the only push channel for
 * payment results. If the user closes the browser or the DeepLink fails to launch,
 * the callback is lost and the user has paid without getting membership. This store
 * keeps the last submitted order; [com.lxseek.chat.MainActivity] queries the gateway
 * on [onResume] as a fallback and activates membership once the order is paid.
 *
 * Backed by SharedPreferences (`yipay_pending_order` / key `pending`) holding a tiny
 * JSON blob. Only one pending order is tracked at a time 鈥?a new payment overwrites
 * the previous one. Orders older than [EXPIRY_MILLIS] are treated as expired.
 */
class PendingOrderStore(context: Context) {

    /** A submitted but not-yet-confirmed Yipay order. */
    data class PendingOrder(
        val outTradeNo: String,
        val tier: MembershipTier,
        val amount: String,
        val timestamp: Long,
        /** 璁惧韬唤璇侊紝婵€娲绘椂闇€瑕佸甫缁欐湇鍔″櫒銆?*/
        val deviceId: String = "",
    )

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Persist [order] as the current pending order (overwrites any previous one). */
    fun save(order: PendingOrder) {
        val json = JSONObject().apply {
            put(KEY_OUT_TRADE_NO, order.outTradeNo)
            put(KEY_TIER, order.tier.name)
            put(KEY_AMOUNT, order.amount)
            put(KEY_TIMESTAMP, order.timestamp)
            put(KEY_DEVICE_ID, order.deviceId)
        }
        prefs.edit().putString(KEY_PENDING, json.toString()).apply()
    }

    /** The current pending order, or null if none stored. */
    fun get(): PendingOrder? {
        val raw = prefs.getString(KEY_PENDING, null) ?: return null
        return try {
            val json = JSONObject(raw)
            PendingOrder(
                outTradeNo = json.getString(KEY_OUT_TRADE_NO),
                tier = MembershipTier.parse(json.getString(KEY_TIER)),
                amount = json.getString(KEY_AMOUNT),
                timestamp = json.getLong(KEY_TIMESTAMP),
                // 鏃х増鏈瓨鍌ㄦ病鏈?deviceId 瀛楁锛屽閿欒鍙栦负绌轰覆銆?                deviceId = json.optString(KEY_DEVICE_ID, ""),
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Drop the pending order (called after success, expiry, or overwrite). */
    fun clear() {
        prefs.edit().remove(KEY_PENDING).apply()
    }

    /** True if the stored order is older than [EXPIRY_MILLIS] (or absent). */
    fun isExpired(): Boolean {
        val order = get() ?: return true
        return System.currentTimeMillis() - order.timestamp > EXPIRY_MILLIS
    }

    companion object {
        private const val PREFS_NAME = "yipay_pending_order"
        private const val KEY_PENDING = "pending"
        private const val KEY_OUT_TRADE_NO = "outTradeNo"
        private const val KEY_TIER = "tier"
        private const val KEY_AMOUNT = "amount"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_DEVICE_ID = "deviceId"

        /** Orders are considered stale 10 minutes after submission. */
        private const val EXPIRY_MILLIS = 10L * 60L * 1000L
    }
}
