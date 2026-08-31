package com.lxseek.chat.membership

import android.content.Context
import org.json.JSONObject

/**
 * Persists the in-flight Yipay（易支付）order so the App can query its status later.
 *
 * The App has no backend server, so a DeepLink callback is the only push channel for
 * payment results. If the user closes the browser or the DeepLink fails to launch,
 * the callback is lost and the user has paid without getting membership. This store
 * keeps the last submitted order; [com.lxseek.chat.MainActivity] queries the gateway
 * on [onResume] as a fallback and activates membership once the order is paid.
 *
 * Backed by SharedPreferences (`yipay_pending_order` / key `pending`) holding a tiny
 * JSON blob. Only one pending order is tracked at a time — a new payment overwrites
 * the previous one. Orders older than [EXPIRY_MILLIS] are treated as expired.
 *
 * 修复 M1：有效期从 10 分钟延长到 24 小时——用户下单后可能较晚才回到 App
 * （浏览器停留、锁屏等），10 分钟会把"已支付但延迟回跳"的订单误判过期，
 * 导致已付款用户无法激活。过期清理见 [cleanupExpired]（启动时调用）。
 *
 * 安全（H3b）：回调处理时要求订单号必须存在于本存储（App 本地发起过）、
 * 未过期、未被消费（消费即 [clear]），金额与下单金额一致（见 [PlanCatalog.amountsMatch]）。
 */
class PendingOrderStore(context: Context) {

    /** A submitted but not-yet-confirmed Yipay order. */
    data class PendingOrder(
        val outTradeNo: String,
        /** 二元制：本地订单仅记录展示用的档位，恒为 Premium（付费）。 */
        val tier: MembershipTier,
        val amount: String,
        val timestamp: Long,
        /** 设备身份，激活时需要带给服务器。 */
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
                // parse 会把旧档位（Pro/Enterprise）归一化为 Premium（二元制兼容）。
                tier = MembershipTier.parse(json.getString(KEY_TIER)),
                amount = json.getString(KEY_AMOUNT),
                timestamp = json.getLong(KEY_TIMESTAMP),
                // 旧版本存储没有 deviceId 字段，容错读为空串。
                deviceId = json.optString(KEY_DEVICE_ID, ""),
            )
        } catch (_: Exception) {
            // 存储损坏（极少见）：按无待处理订单处理，下次支付会重新写入。
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

    /** M1：清理已过期的待处理订单（App 启动时调用，避免残留陈旧订单）。 */
    fun cleanupExpired() {
        if (get() != null && isExpired()) {
            clear()
        }
    }

    companion object {
        private const val PREFS_NAME = "yipay_pending_order"
        private const val KEY_PENDING = "pending"
        private const val KEY_OUT_TRADE_NO = "outTradeNo"
        private const val KEY_TIER = "tier"
        private const val KEY_AMOUNT = "amount"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_DEVICE_ID = "deviceId"

        /** M1：订单有效期 24 小时（原 10 分钟，见类注释）。 */
        private const val EXPIRY_MILLIS = 24L * 60L * 60L * 1000L
    }
}
