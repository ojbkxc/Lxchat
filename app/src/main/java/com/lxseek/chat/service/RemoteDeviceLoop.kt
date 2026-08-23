package com.lxseek.chat.service

import android.content.Context
import android.os.Build
import android.util.Base64
import com.lxseek.chat.data.DeviceIdentityManager
import com.lxseek.chat.data.PeerInfo
import com.lxseek.chat.data.PeerTrustStore
import com.lxseek.chat.data.TrustResult
import com.lxseek.chat.util.DebugLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 默认凭据推导：用设备 host:port 构造无鉴权 Conch HTTP 后端。
 * SSH 后端需调用方通过 [RemoteDeviceLoop.credentialProvider] 注入凭据。
 */
private fun defaultCredentialProvider(device: DiscoveredDevice): RemoteConnectionConfig? {
    val host = device.host?.hostAddress ?: return null
    if (device.port <= 0) return null
    return RemoteConnectionConfig.Conch(
        serverUrl = "http://$host:${device.port}",
        apiKey = "",
    )
}

/**
 * 远程设备协作闭环：发现 → 认证 → 连接 → 执行 → 传输 → 结果回传。
 *
 * 整合四个前置模块：
 * - [LanDeviceDiscovery]（T14）— mDNS 局域网设备发现/注册
 * - [DeviceIdentityManager] + [PeerTrustStore]（T15）— Ed25519 设备身份 + TOFU 信任
 * - [PathSanitizer]（T13）— 路径净化（在 [RemoteDeviceSession] 内部使用）
 * - [ProgressThrottle]（T16）— 进度节流（在 [RemoteDeviceSession] 内部使用）
 *
 * 闭环流程
 * --------
 * 1. [start] 注册本设备 mDNS 服务（携带 deviceId / 公钥 / 协议）并开始发现
 * 2. [onDeviceFound] 收到对端设备 → 用 [PeerTrustStore.verifyPeer] 校验公钥
 *    - [TrustResult.New]       → TOFU 首次信任（[PeerTrustStore.trustOnFirstUse]）
 *    - [TrustResult.Trusted]   → 放行
 *    - [TrustResult.Untrusted] → 公钥不匹配（MITM 信号），拒绝并记录
 * 3. [openSession] 对已信任设备用 [credentialProvider] 取凭据，建立 [RemoteDeviceSession]
 * 4. [executeAiCommand] AI 生成命令 → 远程执行 → 返回 stdout
 * 5. [RemoteDeviceSession.transferFile] 文件传输（路径净化 + 进度节流）
 * 6. [stop] 断开所有会话 + 停止发现/注销服务
 *
 * 设备列表管理：[discoveredDevices] 维护已发现设备，[sessions] 维护已连接会话，
 * 均用 [ConcurrentHashMap] 保证发现线程与调用方线程的安全访问。
 *
 * @param context           Android 上下文
 * @param localBaseDir      本地文件传输信任基目录（transferFile 的 localPath 限于此目录内）
 * @param remoteBaseDir     远程工作目录（transferFile 的 remotePath 相对于此解析）
 * @param listenPort        本设备 mDNS 服务注册端口
 * @param credentialProvider 从已发现设备推导连接凭据；返回 null 表示无法连接此设备。
 *                           默认实现用 `http://host:port` 构造无鉴权 Conch 后端。
 */
class RemoteDeviceLoop(
    private val context: Context,
    private val localBaseDir: File,
    private val remoteBaseDir: String = ".",
    private val listenPort: Int = 0,
    private val credentialProvider: (DiscoveredDevice) -> RemoteConnectionConfig? =
        ::defaultCredentialProvider,
) {
    companion object {
        private const val TAG = "RemoteDeviceLoop"

        /** mDNS TXT record 中携带对端 Ed25519 公钥（base64，44 字节）的键。 */
        const val KEY_PUBLIC_KEY = "pub_key"

        /** 本设备广播的协议列表。 */
        private const val SELF_PROTOCOLS = "conch"
        /** 本设备广播的协议版本。 */
        private const val SELF_PROTOCOL_VERSION = "1"

    }

    private val discovery = LanDeviceDiscovery(context)
    private val trustStore = PeerTrustStore(context)
    private val identity = DeviceIdentityManager.loadOrGenerate(context)

    /** deviceId → 已建立会话。 */
    private val sessions = ConcurrentHashMap<String, RemoteDeviceSession>()

    /** deviceId → 已发现（且通过信任校验）的设备。 */
    private val discoveredDevices = ConcurrentHashMap<String, DiscoveredDevice>()

    /** 本设备稳定 ID（Ed25519 公钥 SHA-256 hex）。 */
    val selfDeviceId: String get() = identity.deviceId

    /** 已信任对端列表（UI 展示用）。 */
    fun trustedPeers(): List<PeerInfo> = trustStore.list()

    /** 已发现设备列表。 */
    fun discoveredList(): List<DiscoveredDevice> = discoveredDevices.values.toList()

    /** 已连接会话对应的 deviceId 列表。 */
    fun connectedDeviceIds(): Set<String> = sessions.keys.toSet()

    /**
     * 启动：注册本设备 mDNS 服务 + 开始发现局域网设备。
     *
     * 本设备广播的 TXT record 携带 deviceId / 设备名 / 协议 / 公钥，供对端做 TOFU 校验。
     *
     * @return true 注册与发现均成功提交
     */
    fun start(): Boolean {
        val pubKeyB64 = Base64.encodeToString(identity.publicKey, Base64.NO_WRAP)
        val meta = mapOf(
            LanDeviceDiscovery.KEY_DEVICE_ID to identity.deviceId,
            LanDeviceDiscovery.KEY_DEVICE_NAME to Build.MODEL,
            LanDeviceDiscovery.KEY_PROTOCOLS to SELF_PROTOCOLS,
            LanDeviceDiscovery.KEY_PROTOCOL_VERSION to SELF_PROTOCOL_VERSION,
            KEY_PUBLIC_KEY to pubKeyB64,
        )
        val registered = discovery.registerService(Build.MODEL, listenPort, meta)
        val discovering = discovery.startDiscovery(
            onDeviceFound = { onDeviceFound(it) },
            onDeviceLost = { onDeviceLost(it) },
        )
        DebugLog.d(
            TAG,
            "start: registered=$registered discovering=$discovering selfId=${identity.deviceId} port=$listenPort",
        )
        return registered && discovering
    }

    /**
     * 发现设备回调：校验对端身份 + TOFU 信任。
     *
     * 流程：
     * 1. 从 metadata 取 deviceId 与公钥（缺失则忽略）
     * 2. [PeerTrustStore.verifyPeer] 校验公钥
     *    - New → [PeerTrustStore.trustOnFirstUse] 记录
     *    - Trusted → 放行
     *    - Untrusted → 公钥不匹配，MITM 信号，拒绝
     * 3. 通过校验的设备加入 [discoveredDevices]
     *
     * 线程：在 NSD 内部线程触发；[ConcurrentHashMap] 与 [PeerTrustStore] 内部锁保证安全。
     */
    fun onDeviceFound(device: DiscoveredDevice) {
        val peerDeviceId = device.metadata[LanDeviceDiscovery.KEY_DEVICE_ID]
        if (peerDeviceId == null) {
            DebugLog.w(TAG, "onDeviceFound: missing device_id in metadata, ignore (${device.name})")
            return
        }
        val peerPubKeyB64 = device.metadata[KEY_PUBLIC_KEY]
        if (peerPubKeyB64 == null) {
            DebugLog.w(TAG, "onDeviceFound: missing pub_key for $peerDeviceId, ignore")
            return
        }
        val peerPubKey = try {
            Base64.decode(peerPubKeyB64, Base64.NO_WRAP)
        } catch (e: Exception) {
            DebugLog.w(TAG, "onDeviceFound: bad pub_key encoding for $peerDeviceId", e)
            return
        }

        when (trustStore.verifyPeer(peerDeviceId, peerPubKey)) {
            TrustResult.New -> {
                val now = System.currentTimeMillis()
                val peerInfo = PeerInfo(
                    deviceId = peerDeviceId,
                    name = device.name,
                    publicKey = peerPubKey,
                    firstSeenAt = now,
                    lastSeenAt = now,
                )
                trustStore.trustOnFirstUse(peerDeviceId, peerInfo)
                DebugLog.i(TAG, "TOFU trusted new peer $peerDeviceId (${device.name})")
            }
            TrustResult.Trusted -> {
                DebugLog.d(TAG, "peer $peerDeviceId verified (${device.name})")
            }
            TrustResult.Untrusted -> {
                // 公钥与已 pin 的不一致 —— 中间人攻击信号，拒绝并记录
                DebugLog.e(TAG, "peer $peerDeviceId key mismatch — MITM suspected, ignoring (${device.name})")
                return
            }
        }
        discoveredDevices[peerDeviceId] = device
    }

    /**
     * 设备消失回调：从已发现列表移除。
     *
     * 注意：lost 事件只携带服务名（host/port/metadata 不可用），按 [DiscoveredDevice.name] 匹配。
     */
    fun onDeviceLost(device: DiscoveredDevice) {
        val iter = discoveredDevices.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.value.name == device.name) {
                val id = entry.key
                iter.remove()
                DebugLog.d(TAG, "device lost: $id (${device.name})")
                break
            }
        }
    }

    /**
     * 打开与已发现设备的会话。
     *
     * 前置：设备必须已通过 [onDeviceFound] 的信任校验（存在于 [PeerTrustStore]）。
     * 凭据由 [credentialProvider] 提供；SSH 后端需调用方注入 user/password/host-key。
     *
     * @return 已连接的 [RemoteDeviceSession]；未信任/无凭据/连接失败返回 null
     */
    suspend fun openSession(device: DiscoveredDevice): RemoteDeviceSession? {
        val peerDeviceId = device.metadata[LanDeviceDiscovery.KEY_DEVICE_ID]
            ?: run {
                DebugLog.w(TAG, "openSession: missing device_id in metadata")
                return null
            }
        // 必须已通过信任校验
        if (trustStore.get(peerDeviceId) == null) {
            DebugLog.w(TAG, "openSession: peer $peerDeviceId not in trust store, refusing")
            return null
        }
        val config = credentialProvider(device) ?: run {
            DebugLog.w(TAG, "openSession: no credentials available for $peerDeviceId (${device.name})")
            return null
        }
        val session = RemoteDeviceSession(config, localBaseDir, remoteBaseDir)
        val ok = session.connect(device)
        if (!ok) {
            DebugLog.w(TAG, "openSession: connect failed for $peerDeviceId: ${session.lastError}")
            return null
        }
        sessions[peerDeviceId] = session
        DebugLog.d(TAG, "session opened for $peerDeviceId (${device.name})")
        return session
    }

    /**
     * AI 生成命令 → 远程执行 → 返回结果。
     *
     * @param deviceId           目标设备 ID
     * @param aiGeneratedCommand AI 生成的命令字符串（调用方负责白名单/确认）
     * @return stdout；无会话/执行失败返回 null
     */
    suspend fun executeAiCommand(deviceId: String, aiGeneratedCommand: String): String? {
        val session = sessions[deviceId] ?: run {
            DebugLog.w(TAG, "executeAiCommand: no active session for $deviceId")
            return null
        }
        return try {
            session.executeCommand(aiGeneratedCommand)
        } catch (e: Exception) {
            DebugLog.e(TAG, "executeAiCommand failed for $deviceId", e)
            null
        }
    }

    /**
     * 向指定设备传输文件（便捷封装）。
     *
     * 路径净化与进度节流由 [RemoteDeviceSession.transferFile] 处理。
     *
     * @return true 传输成功；无会话/失败返回 false
     */
    suspend fun transferFile(
        deviceId: String,
        localPath: String,
        remotePath: String,
        onProgress: (Double) -> Unit,
    ): Boolean {
        val session = sessions[deviceId] ?: run {
            DebugLog.w(TAG, "transferFile: no active session for $deviceId")
            return false
        }
        return try {
            session.transferFile(localPath, remotePath, onProgress)
            true
        } catch (e: Exception) {
            DebugLog.e(TAG, "transferFile failed for $deviceId", e)
            false
        }
    }

    /** 获取指定设备的活跃会话（供调用方直接调用 transferFile 等）。 */
    fun sessionOf(deviceId: String): RemoteDeviceSession? = sessions[deviceId]

    /**
     * 移除对某设备的信任并断开其会话。
     *
     * 用于用户在 UI 上"忘记此设备"：清除 TOFU pin 后，下次该设备以新公钥出现时
     * 会被当作新设备重新走 TOFU。
     */
    fun forgetPeer(deviceId: String): Boolean {
        sessions.remove(deviceId)?.disconnect()
        return trustStore.removeTrust(deviceId)
    }

    /**
     * 停止：断开所有会话 + 停止发现 + 注销本设备服务。
     *
     * 安全可重入；在 Activity/Service `onDestroy` 调用以释放全部资源。
     */
    fun stop() {
        sessions.values.forEach { runCatching { it.disconnect() } }
        sessions.clear()
        discoveredDevices.clear()
        discovery.stopAll()
        DebugLog.d(TAG, "stopped: all sessions closed, discovery released")
    }
}