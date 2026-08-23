package com.lxseek.chat.data

import android.content.Context
import android.util.Base64
import com.lxseek.chat.util.DebugLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * 已知对端信息。公钥为 32 字节 Ed25519 公钥。
 *
 * @param deviceId    对端设备 ID（公钥 SHA-256 hex）
 * @param name        人类可读显示名（best effort，可为空）
 * @param publicKey   对端 Ed25519 公钥
 * @param firstSeenAt 首次信任时间（毫秒）
 * @param lastSeenAt  最近一次成功连接时间（毫秒）
 */
data class PeerInfo(
    val deviceId: String,
    val name: String,
    val publicKey: ByteArray,
    val firstSeenAt: Long,
    val lastSeenAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerInfo) return false
        return deviceId == other.deviceId &&
            name == other.name &&
            publicKey.contentEquals(other.publicKey) &&
            firstSeenAt == other.firstSeenAt &&
            lastSeenAt == other.lastSeenAt
    }

    override fun hashCode(): Int {
        var r = deviceId.hashCode()
        r = 31 * r + name.hashCode()
        r = 31 * r + publicKey.contentHashCode()
        r = 31 * r + firstSeenAt.hashCode()
        r = 31 * r + lastSeenAt.hashCode()
        return r
    }

    override fun toString(): String =
        "PeerInfo(deviceId=$deviceId, name=$name, firstSeenAt=$firstSeenAt, lastSeenAt=$lastSeenAt)"
}

/**
 * 对端校验结果。
 *
 * - [New]      首次见到此设备，未在信任存储中
 * - [Trusted]  已知设备且公钥匹配
 * - [Untrusted] 已知设备但公钥不匹配 —— MITM 信号，上层应中止连接
 */
enum class TrustResult { New, Trusted, Untrusted }

/**
 * TOFU（Trust On First Use）信任存储。
 *
 * 首次连接信任对端公钥并记录；后续连接校验公钥是否与记录一致。公钥不匹配是
 * 中间人攻击信号，[verifyPeer] 返回 [TrustResult.Untrusted] 让上层中止。
 *
 * 持久化到 SharedPreferences（JSON 编码），同步 API 匹配 HyX `KnownPeers` 的
 * 同步 `Mutex + fs` 语义。每次修改立即 flush，崩溃不丢信任状态。
 *
 * 对应 HyX `core/src/known_peers.rs`。Rust 用 `BTreeMap<fingerprint_hex, PeerRecord>`；
 * 这里用 `Map<deviceId, PeerInfo>`，deviceId 是公钥 SHA-256 hex，等价且更易调试。
 */
class PeerTrustStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()
    private var peers: MutableMap<String, PeerInfo> = loadAll()

    /** 已信任设备数。 */
    val size: Int get() = synchronized(lock) { peers.size }

    /** 获取已知设备；不存在返回 null。 */
    fun get(deviceId: String): PeerInfo? = synchronized(lock) { peers[deviceId] }

    /** 列出所有已知设备（UI 显示用）。 */
    fun list(): List<PeerInfo> = synchronized(lock) { peers.values.toList() }

    /**
     * TOFU 首次信任：记录设备。
     *
     * - 设备未知：插入并返回 [TrustResult.New]
     * - 设备已知：刷新 lastSeenAt（和 name 若非空），**不覆盖公钥**（TOFU pin），
     *   返回 [TrustResult.Trusted]
     *
     * @param deviceId  对端设备 ID
     * @param peerInfo  对端信息（publicKey/name 来自首次握手）
     */
    fun trustOnFirstUse(deviceId: String, peerInfo: PeerInfo): TrustResult = synchronized(lock) {
        val now = System.currentTimeMillis()
        val existing = peers[deviceId]
        if (existing == null) {
            peers[deviceId] = peerInfo.copy(firstSeenAt = now, lastSeenAt = now)
            persist()
            DebugLog.d(TAG, "TOFU pinned new peer $deviceId")
            TrustResult.New
        } else {
            // 已存在：保持原公钥（TOFU pin），只更新 name 和 lastSeenAt
            peers[deviceId] = existing.copy(
                name = if (peerInfo.name.isNotEmpty()) peerInfo.name else existing.name,
                lastSeenAt = now
            )
            persist()
            TrustResult.Trusted
        }
    }

    /**
     * 校验对端公钥是否与已信任的一致。
     *
     * - 设备未知：返回 [TrustResult.New]（调用方可决定是否 [trustOnFirstUse]）
     * - 已知且公钥匹配：刷新 lastSeenAt，返回 [TrustResult.Trusted]
     * - 已知但公钥不匹配：返回 [TrustResult.Untrusted]（不更新存储，MITM 信号）
     *
     * @param deviceId  对端声称的设备 ID
     * @param publicKey 对端本次实际呈现的公钥
     */
    fun verifyPeer(deviceId: String, publicKey: ByteArray): TrustResult = synchronized(lock) {
        val existing = peers[deviceId]
        if (existing == null) {
            TrustResult.New
        } else if (existing.publicKey.contentEquals(publicKey)) {
            peers[deviceId] = existing.copy(lastSeenAt = System.currentTimeMillis())
            persist()
            TrustResult.Trusted
        } else {
            DebugLog.w(TAG, "peer $deviceId key mismatch — possible MITM")
            TrustResult.Untrusted
        }
    }

    /**
     * 移除对某设备的信任。
     * @return true 若存在并被移除。
     */
    fun removeTrust(deviceId: String): Boolean = synchronized(lock) {
        val removed = peers.remove(deviceId) != null
        if (removed) persist()
        removed
    }

    /** 清空所有信任记录。 */
    fun clear() = synchronized(lock) {
        if (peers.isNotEmpty()) {
            peers.clear()
            persist()
        }
    }

    /** 序列化全量到 SharedPreferences（JSON）。 */
    private fun persist() {
        val arr = JSONArray()
        peers.values.forEach { p ->
            arr.put(JSONObject().apply {
                put("deviceId", p.deviceId)
                put("name", p.name)
                put("publicKey", Base64.encodeToString(p.publicKey, Base64.NO_WRAP))
                put("firstSeenAt", p.firstSeenAt)
                put("lastSeenAt", p.lastSeenAt)
            })
        }
        val json = JSONObject().put("peers", arr).toString()
        prefs.edit().putString(KEY_PEERS, json).apply()
    }

    /** 从 SharedPreferences 反序列化。损坏时返回空集（不抛异常）。 */
    private fun loadAll(): MutableMap<String, PeerInfo> {
        val map = LinkedHashMap<String, PeerInfo>()
        val json = prefs.getString(KEY_PEERS, null) ?: return map
        return try {
            val arr = JSONObject(json).getJSONArray("peers")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getString("deviceId")
                map[id] = PeerInfo(
                    deviceId = id,
                    name = o.optString("name", ""),
                    publicKey = Base64.decode(o.getString("publicKey"), Base64.NO_WRAP),
                    firstSeenAt = o.getLong("firstSeenAt"),
                    lastSeenAt = o.getLong("lastSeenAt")
                )
            }
            map
        } catch (e: Exception) {
            DebugLog.e(TAG, "load failed; starting empty", e)
            map
        }
    }

    companion object {
        private const val TAG = "PeerTrustStore"
        private const val PREFS = "lxchat_peer_trust"
        private const val KEY_PEERS = "peers_json"
    }
}