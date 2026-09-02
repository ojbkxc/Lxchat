package com.lxseek.chat.im.office

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lxseek.chat.util.DebugLog
import com.lxseek.chat.util.SecretCrypto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Office Connector 持久化配置。
 *
 * [deviceToken] 不包含在此结构中——它单独加密存储（见 [OfficeConnectorStore]），
 * 永不回传、永不日志输出。
 *
 * @param baseUrl Office origin（如 `https://office.example.com`）。
 * @param deviceId 本机设备标识。
 * @param maxConcurrency 最大并发任务数（1-4）。
 * @param heartbeatSeconds 心跳间隔（秒，10-300）。
 * @param workspaces 工作区映射：alias → 绝对路径。
 * @param instructionPresets 指令预设映射：alias → 预设文本（≤ 8000 字符）。
 * @param enabled 是否自动启动连接。
 */
@Serializable
data class OfficeConnectorSettings(
    val baseUrl: String = "",
    val deviceId: String = "",
    val maxConcurrency: Int = 1,
    val heartbeatSeconds: Int = 30,
    val workspaces: Map<String, String> = emptyMap(),
    val instructionPresets: Map<String, String> = emptyMap(),
    val enabled: Boolean = false,
) {
    /** 配置是否有效（baseUrl 和 deviceId 非空）。 */
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && deviceId.isNotBlank()
}

/**
 * Office Connector 配置存储。
 *
 * 使用独立的加密 DataStore（`office_connector`），与 IM 网关和核心设置隔离。
 * Device Token 通过 [SecretCrypto] 加密后存储，永不以明文形式落盘。
 *
 * 与 dsh-im `config-store.mjs` 的 `OfficeConfigStore` 类对齐。
 */
internal val Context.officeConnectorDataStore by preferencesDataStore(name = "office_connector")

private val OFFICE_CONFIG_JSON = stringPreferencesKey("office_config_json")
private val OFFICE_TOKEN = stringPreferencesKey("office_device_token") // encrypted

class OfficeConnectorStore(private val context: Context) {

    /** 当前持久化配置（解密后）。 */
    val settings: Flow<OfficeConnectorSettings> = context.officeConnectorDataStore.data.map { pref ->
        val jsonStr = SecretCrypto.decrypt(pref[OFFICE_CONFIG_JSON] ?: "{}")
        runCatching { json.decodeFromString<OfficeConnectorSettings>(jsonStr) }
            .getOrDefault(OfficeConnectorSettings())
    }

    /** 当前 Device Token（解密后）。空字符串表示未配置。 */
    val deviceToken: Flow<String> = context.officeConnectorDataStore.data.map { pref ->
        val raw = pref[OFFICE_TOKEN] ?: ""
        if (raw.isEmpty()) "" else SecretCrypto.decrypt(raw)
    }

    /** 保存配置和 Device Token（Token 加密后存储）。 */
    suspend fun save(settings: OfficeConnectorSettings, deviceToken: String) {
        context.officeConnectorDataStore.edit {
            it[OFFICE_CONFIG_JSON] = SecretCrypto.encrypt(json.encodeToString(settings))
            it[OFFICE_TOKEN] = if (deviceToken.isBlank()) "" else SecretCrypto.encrypt(deviceToken)
        }
    }

    /** 清除所有配置和 Token。 */
    suspend fun clear() {
        context.officeConnectorDataStore.edit {
            it.remove(OFFICE_CONFIG_JSON)
            it.remove(OFFICE_TOKEN)
        }
    }
}

/**
 * 连接状态。
 */
enum class OfficeConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
    RECONNECTING,
}

/**
 * 连接状态快照。通过 [OfficeConnectorService.status] 暴露为 [StateFlow]。
 */
data class OfficeConnectionStatus(
    val state: OfficeConnectionState = OfficeConnectionState.DISCONNECTED,
    val connected: Boolean = false,
    val startedAtMs: Long = 0L,
    val lastHeartbeatAtMs: Long = 0L,
    val lastEventAtMs: Long = 0L,
    val lastEventId: String? = null,
    val lastEventType: String? = null,
    val reconnects: Int = 0,
    val jobsOffered: Int = 0,
    val error: String? = null,
)

/**
 * Office Connector 连接管理服务。
 *
 * 管理连接生命周期：Heartbeat → SSE 下行流 → 事件分发 → 自动重连。
 *
 * **连接状态**：disconnected / connecting / connected / error / reconnecting。
 * **自动重连**：指数退避（1s → 3s → 10s → 30s），与 dsh-im `office-runtime.mjs` 一致。
 * **SSE 事件处理**：`job.available` → 交给 [OfficeJobExecutor] 领取 → 执行 → 回传。
 * **审批等待**：工具审批 / 补充问题 → Office 人工面板 → SSE `approval.reply` 回传。
 *
 * 与 dsh-im `office-runtime.mjs` 的 `OfficeRuntime` 类对齐。
 *
 * @param settings 连接器配置。
 * @param deviceToken Device Token（从安全存储解密后传入）。
 * @param scope 连接协程作用域。
 * @param createHarness Harness 会话工厂。
 */
class OfficeConnectorService(
    val settings: OfficeConnectorSettings,
    private val deviceToken: String,
    private val scope: CoroutineScope,
    private val createHarness: (workspaceAlias: String) -> OfficeHarnessSession,
) {
    private val api = OfficeConnectorApi(settings.baseUrl, settings.deviceId, deviceToken)
    private val jobExecutor = OfficeJobExecutor(settings, api, scope, createHarness)

    private val _status = MutableStateFlow(OfficeConnectionStatus())
    /** 连接状态流。 */
    val status: StateFlow<OfficeConnectionStatus> = _status

    @Volatile private var runJob: Job? = null

    /** 当前执行器状态（任务计数等）。 */
    val executorStatus: StateFlow<OfficeJobExecutorStatus> get() = jobExecutor.status

    /**
     * 启动连接循环。如果已在运行则无操作。
     * 立即返回；连接在后台协程中异步建立。
     */
    fun start() {
        if (runJob?.isActive == true) return
        _status.value = _status.value.copy(
            state = OfficeConnectionState.CONNECTING,
            startedAtMs = System.currentTimeMillis(),
            error = null,
        )
        runJob = scope.launch { runLoop() }
    }

    /**
     * 停止连接，取消所有协程和活跃任务。
     */
    fun stop() {
        runJob?.cancel()
        runJob = null
        jobExecutor.close()
        _status.value = _status.value.copy(
            state = OfficeConnectionState.DISCONNECTED,
            connected = false,
        )
    }

    /**
     * 主连接循环：Heartbeat → 并行（SSE 流 + 心跳循环），带退避重连。
     *
     * 任一子流（SSE 或心跳）异常时取消另一个，等待退避后重连。
     */
    private suspend fun runLoop() {
        var attempt = 0
        while (scope.isActive) {
            try {
                // 1. 初始 Heartbeat（鉴权 + 能力握手）
                val capabilities = buildCapabilities()
                api.heartbeat(capabilities)
                _status.value = _status.value.copy(
                    state = OfficeConnectionState.CONNECTED,
                    connected = true,
                    lastHeartbeatAtMs = System.currentTimeMillis(),
                    error = null,
                )
                attempt = 0

                // 2. 并行运行 SSE 流 + 心跳循环
                coroutineScope {
                    launch { runStream() }
                    launch { runHeartbeat(capabilities) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!scope.isActive) break
                DebugLog.w("OfficeConnector", "connection error")
                val delayIdx = attempt.coerceAtMost(OfficeProtocol.RETRY_DELAYS_MS.size - 1)
                val delayMs = OfficeProtocol.RETRY_DELAYS_MS[delayIdx]
                attempt++
                _status.value = _status.value.copy(
                    state = OfficeConnectionState.RECONNECTING,
                    connected = false,
                    reconnects = _status.value.reconnects + 1,
                    error = e.message,
                )
                try {
                    delay(delayMs)
                } catch (ce: CancellationException) {
                    throw ce
                }
            }
        }
        _status.value = _status.value.copy(
            state = OfficeConnectionState.DISCONNECTED,
            connected = false,
        )
    }

    /**
     * SSE 事件循环。从 Office 读取事件帧并分发给 [OfficeJobExecutor]。
     * 流结束（null）时抛异常以触发重连。
     */
    private suspend fun runStream() {
        val stream = api.openStream(_status.value.lastEventId)
        try {
            while (scope.isActive) {
                val event = withContext(Dispatchers.IO) { stream.readEvent() }
                    ?: throw OfficeTransportException("AI Office SSE stream ended")
                _status.value = _status.value.copy(
                    lastEventAtMs = System.currentTimeMillis(),
                    lastEventId = event.id ?: _status.value.lastEventId,
                    lastEventType = event.type,
                )
                if (event.type == "job.available") {
                    _status.value = _status.value.copy(
                        jobsOffered = _status.value.jobsOffered + 1,
                    )
                }
                jobExecutor.handleEvent(event)
            }
        } finally {
            stream.close()
        }
    }

    /**
     * 心跳循环。定期发送 Heartbeat 保持连接活跃。
     */
    private suspend fun runHeartbeat(capabilities: JsonObject) {
        while (scope.isActive) {
            delay(settings.heartbeatSeconds * 1000L)
            api.heartbeat(capabilities)
            _status.value = _status.value.copy(
                lastHeartbeatAtMs = System.currentTimeMillis(),
            )
        }
    }

    /**
     * 构建能力声明 JSON（发送给 Office 作为 Heartbeat payload）。
     *
     * `workspaces` 和 `instructionPresets` 使用 JSON 数组格式（与 dsh-im
     * `office-runtime.mjs` 的 `capabilities()` 对齐，返回 `Object.keys(...)` 数组），
     * 而非逗号分隔字符串。
     */
    private fun buildCapabilities(): JsonObject = buildJsonObject {
        put("protocolVersion", OfficeProtocol.PROTOCOL_VERSION)
        put("deviceId", api.deviceId)
        put("maxConcurrency", settings.maxConcurrency)
        put("workspaces", buildJsonArray {
            settings.workspaces.keys.forEach { add(it) }
        })
        put("instructionPresets", buildJsonArray {
            settings.instructionPresets.keys.forEach { add(it) }
        })
    }
}