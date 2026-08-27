package com.lxseek.chat.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.lxseek.chat.util.DebugLog
import java.net.InetAddress
import java.util.concurrent.Executors

/**
 * 一个被发现的局域网 Lxchat/Conch 设备。
 *
 * [metadata] 携带设备 ID、设备名、支持的协议（conch/file-transfer）等键值对，
 * 对应 HyX Rust beacon 中的 `device_id` / `device_name` / `cert_fingerprint` 字段。
 *
 * @property name    mDNS 服务名（通常是设备展示名）
 * @property host    解析出的设备 IP；[stopDiscovery] 触发的 lost 回调中为 null
 * @property port    服务端口；lost 回调中为 0
 * @property metadata TXT record 键值对；lost 回调中为空
 */
data class DiscoveredDevice(
    val name: String,
    val host: InetAddress?,
    val port: Int,
    val metadata: Map<String, String>,
)

/**
 * 用 Android 原生 NSD（Network Service Discovery）API 实现 mDNS 服务发现，
 * 发现同局域网内的 Conch/Lxchat 设备。
 *
 * 设计参考 HyX `core/src/discovery.rs` 的 `DiscoveryManager`：
 * - [registerService] 把本设备注册为 mDNS 服务（对应 Rust 的 broadcaster 任务）
 * - [startDiscovery]  发现局域网内其他设备（对应 Rust 的 receiver 任务）
 * - [unregisterService] / [stopDiscovery] 对应 Rust 的 `stop`
 *
 * 与 Rust 自定义 UDP beacon 不同，Android NSD 走系统 mDNS 守护进程，
 * 不需要自己维护 TTL 清理任务——系统会自动管理服务上线/下线事件，
 * 因此 Rust 的 cleanup 任务在此无对应物。
 *
 * 生命周期：注册/注销配对、发现/停止配对，所有方法安全可重入。
 * [stopAll] 在 Activity/Service `onDestroy` 调用以释放全部资源。
 *
 * 线程安全：listener 引用用 `@Volatile` 保护，resolve 串行化见 [resolveExecutor]。
 */
class LanDeviceDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "LanDeviceDiscovery"

        /** mDNS 服务类型：`_lxchat._tcp.`（结尾的点表示 DNS 根，避免本地搜索域拼接） */
        const val SERVICE_TYPE = "_lxchat._tcp."

        // ---- 元数据 attribute keys（DNS TXT record 键，对应 HyX beacon 字段）----
        /** 设备 ID（对应 HyX `beacon.device_id`） */
        const val KEY_DEVICE_ID = "device_id"
        /** 设备名（对应 HyX `beacon.device_name`） */
        const val KEY_DEVICE_NAME = "device_name"
        /** 支持的协议列表，逗号分隔，如 `"conch,file-transfer"` */
        const val KEY_PROTOCOLS = "protocols"
        /** 协议版本（对应 HyX `PROTOCOL_VERSION`） */
        const val KEY_PROTOCOL_VERSION = "proto_ver"

        /** DNS TXT record 单个 value 最大 255 字节 */
        private const val MAX_ATTR_VALUE_BYTES = 255
    }

    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: error("NsdManager unavailable on this device")

    @Volatile private var registrationListener: NsdManager.RegistrationListener? = null
    @Volatile private var discoveryListener: NsdManager.DiscoveryListener? = null

    /** 注册成功后系统可能改名（重名时加后缀），这里保存实际生效名。 */
    @Volatile var registeredServiceName: String? = null
        private set

    /**
     * 串行执行 [resolveService]：Android NSD 在部分 OEM 实现上同时只允许一个
     * `ResolveListener` 活跃，并发 resolve 会触发 `FAILURE_ALREADY_ACTIVE`。
     * daemon 线程，不阻止 JVM 退出；无需显式 shutdown。
     */
    private val resolveExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lxchat-nsd-resolve").apply { isDaemon = true }
    }

    /** 是否有注册或发现活动正在进行。 */
    fun isActive(): Boolean = registrationListener != null || discoveryListener != null

    /**
     * 把本设备注册为 mDNS 服务。
     *
     * @param name     服务名（通常是设备展示名）
     * @param port     服务端口
     * @param metadata 元数据：设备 ID、设备名、支持的协议等。value 会被截断到 255 字节。
     * @return `true` 注册请求已提交（异步结果通过 [DebugLog] 输出）
     */
    fun registerService(name: String, port: Int, metadata: Map<String, String>): Boolean {
        // 防止重复注册泄漏 listener
        unregisterService()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            this.port = port
            metadata.forEach { (k, v) -> setAttribute(k, truncateAttributeValue(v)) }
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredServiceName = info.serviceName
                DebugLog.d(
                    TAG,
                    "onServiceRegistered: name=${info.serviceName} type=${info.serviceType} port=${info.port}",
                )
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                DebugLog.e(TAG, "onRegistrationFailed: errorCode=$errorCode name=${info.serviceName}")
                registrationListener = null
                // 失败时尝试清理（listener 可能未真正注册，吞掉 IllegalArgumentException）
                safeUnregister(this)
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                DebugLog.d(TAG, "onServiceUnregistered: name=${info.serviceName}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                DebugLog.e(TAG, "onUnregistrationFailed: errorCode=$errorCode name=${info.serviceName}")
            }
        }

        return try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            registrationListener = listener
            DebugLog.d(TAG, "registerService: name=$name port=$port metadata=$metadata")
            true
        } catch (e: Exception) {
            DebugLog.e(TAG, "registerService threw", e)
            false
        }
    }

    /**
     * 注销本设备的 mDNS 服务。安全可重入；未注册时为 no-op。
     */
    fun unregisterService() {
        val listener = registrationListener ?: return
        registrationListener = null
        registeredServiceName = null
        safeUnregister(listener)
    }

    /**
     * 开始发现局域网内的 Lxchat/Conch 设备。
     *
     * @param onDeviceFound 发现新设备时回调（在 NSD 内部线程触发）
     * @param onDeviceLost  设备消失时回调；注意 [DiscoveredDevice.host]/[port] 此时不可用，
     *                       上层应按 [DiscoveredDevice.name] 匹配之前 found 的设备。
     * @return `true` 发现请求已提交
     */
    fun startDiscovery(
        onDeviceFound: (DiscoveredDevice) -> Unit,
        onDeviceLost: (DiscoveredDevice) -> Unit,
    ): Boolean {
        // 防止重复发现泄漏 listener
        stopDiscovery()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                DebugLog.d(TAG, "onDiscoveryStarted: $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                DebugLog.d(TAG, "onDiscoveryStopped: $serviceType")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                DebugLog.d(TAG, "onServiceFound: name=${info.serviceName} type=${info.serviceType}")
                // 串行 resolve，拿到 host/port/metadata
                resolveExecutor.execute { resolveService(info, onDeviceFound) }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                DebugLog.d(TAG, "onServiceLost: name=${info.serviceName}")
                // lost 只能拿到 name，host/port/metadata 不可用
                onDeviceLost(
                    DiscoveredDevice(
                        name = info.serviceName,
                        host = null,
                        port = 0,
                        metadata = emptyMap(),
                    )
                )
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                DebugLog.e(TAG, "onStartDiscoveryFailed: errorCode=$errorCode type=$serviceType")
                // 探活失败意味着本 listener 从未被 NsdManager 成功注册，此时调用
                // stopServiceDiscovery(listener) 会抛 IllegalArgumentException("listener not registered")
                // 并导致崩溃（issue #6，Android 12）。正确做法是仅重置状态，让上层稍后重新尝试。
                discoveryListener = null
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                DebugLog.e(TAG, "onStopDiscoveryFailed: errorCode=$errorCode type=$serviceType")
            }
        }

        return try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            discoveryListener = listener
            DebugLog.d(TAG, "startDiscovery: type=$SERVICE_TYPE")
            true
        } catch (e: Exception) {
            DebugLog.e(TAG, "startDiscovery threw", e)
            false
        }
    }

    /**
     * 停止发现。安全可重入；未启动时为 no-op。
     */
    fun stopDiscovery() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        safeStopDiscovery(listener)
    }

    /**
     * 停止所有活动：停止发现 + 注销服务。
     *
     * 在 Activity/Service `onDestroy` 调用以避免 listener 泄漏。可重入。
     * resolve 线程为 daemon，随 JVM 退出自动清理，无需显式 shutdown。
     */
    fun stopAll() {
        stopDiscovery()
        unregisterService()
        DebugLog.d(TAG, "stopAll: discovery and registration released")
    }

    /**
     * Resolve 一个被发现的服务，拿到 host/port/metadata。
     * 必须在 [resolveExecutor] 上调用（串行，避免 OEM 的 FAILURE_ALREADY_ACTIVE）。
     */
    private fun resolveService(
        info: NsdServiceInfo,
        onDeviceFound: (DiscoveredDevice) -> Unit,
    ) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val device = DiscoveredDevice(
                    name = resolved.serviceName,
                    host = resolved.host,
                    port = resolved.port,
                    metadata = readAttributes(resolved),
                )
                DebugLog.d(
                    TAG,
                    "onServiceResolved: name=${device.name} host=${device.host} port=${device.port} metadata=${device.metadata}",
                )
                onDeviceFound(device)
            }

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                DebugLog.e(TAG, "onResolveFailed: errorCode=$errorCode name=${info.serviceName}")
            }
        }

        try {
            nsdManager.resolveService(info, resolveListener)
        } catch (e: Exception) {
            DebugLog.e(TAG, "resolveService threw", e)
        }
    }

    /**
     * 读取 `NsdServiceInfo.attributes`（`Map<String, ByteArray>`）转为 `Map<String, String>`。
     */
    private fun readAttributes(info: NsdServiceInfo): Map<String, String> {
        val attrs = info.attributes ?: return emptyMap()
        val result = HashMap<String, String>(attrs.size)
        attrs.forEach { (k, v) -> result[k] = String(v, Charsets.UTF_8) }
        return result
    }

    /**
     * 截断 TXT record value 到 [MAX_ATTR_VALUE_BYTES] 字节，避免注册失败。
     * 按字节截断后回退到 UTF-8 字符边界，去掉可能产生的 U+FFFD 替换符。
     */
    private fun truncateAttributeValue(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_ATTR_VALUE_BYTES) return value
        val decoded = String(bytes.copyOf(MAX_ATTR_VALUE_BYTES), Charsets.UTF_8)
        return decoded.removeSuffix("\uFFFD")
    }

    private fun safeUnregister(listener: NsdManager.RegistrationListener) {
        try {
            nsdManager.unregisterService(listener)
        } catch (e: IllegalArgumentException) {
            // listener 未注册或已注销，忽略
        }
    }

    private fun safeStopDiscovery(listener: NsdManager.DiscoveryListener) {
        try {
            nsdManager.stopServiceDiscovery(listener)
        } catch (e: IllegalArgumentException) {
            // listener 未注册或已停止，忽略
        }
    }
}