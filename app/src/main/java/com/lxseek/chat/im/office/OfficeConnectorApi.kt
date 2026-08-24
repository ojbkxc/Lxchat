package com.lxseek.chat.im.office

import com.lxseek.chat.api.HttpClient
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * AI Office Connector 协议常量。与 dsh-im `src/channels/office/protocol.mjs` 对齐。
 *
 * 协议版本 `office-harness.v1`；本机（Lxchat App）主动连接公网 Office，无需公网 IP、
 * 端口转发或 WebSocket 服务。Office 通过固定 Hook 向本机下发任务，本机通过同一组 Hook
 * 回传状态与终态。
 */
object OfficeProtocol {
    /** 协议版本号，Heartbeat 响应必须携带此值。 */
    const val PROTOCOL_VERSION = "office-harness.v1"

    // ── 固定 Hook 路径（任务规范路径，复数 `jobs`，与 dsh-im protocol.mjs 对齐） ───
    /** Heartbeat：鉴权 + 能力握手。 */
    const val PATH_HEARTBEAT = "/api/harness/connector/heartbeat"
    /** SSE 下行流：Office → 本机事件流。 */
    const val PATH_STREAM = "/api/harness/connector/stream"
    /** 查询单个任务详情（GET）。 */
    const val PATH_JOB = "/api/harness/connector/jobs/%s"
    /** 任务领取（POST，90 秒租约）。 */
    const val PATH_JOB_ACCEPT = "/api/harness/connector/jobs/%s/accept"
    /** 租约续租（POST，每 30 秒）。 */
    const val PATH_JOB_RENEW = "/api/harness/connector/jobs/%s/renew"
    /** 进度回传（POST，增量文字 / 工具名 / 状态）。 */
    const val PATH_JOB_PROGRESS = "/api/harness/connector/jobs/%s/progress"
    /** 审批请求（POST，独立端点，不复用 progress）。 */
    const val PATH_JOB_APPROVAL = "/api/harness/connector/jobs/%s/approval"
    /** 终态写入（POST，只允许一次）。 */
    const val PATH_JOB_RESULT = "/api/harness/connector/jobs/%s/result"
    /** 失败上报（POST，独立端点，不复用 result）。 */
    const val PATH_JOB_FAIL = "/api/harness/connector/jobs/%s/fail"

    /** 租约时长（秒）。 */
    const val LEASE_SECONDS = 90
    /** 续租间隔（毫秒）。 */
    const val RENEW_INTERVAL_MS = 30_000L
    /** 心跳间隔默认值（毫秒）。 */
    const val HEARTBEAT_INTERVAL_MS = 30_000L

    /** 重连退避序列（毫秒），与 dsh-im `office-runtime.mjs` 一致。 */
    val RETRY_DELAYS_MS = longArrayOf(1_000, 3_000, 10_000, 30_000)

    /** 共享 JSON 实例（忽略未知键，向前兼容）。 */
    val json = Json { ignoreUnknownKeys = true }
}

/**
 * Office Connector 传输层异常。
 *
 * [code] 区分错误类型，与 dsh-im `office-transport.mjs` 的错误码对齐：
 * - `invalid-device-token`（401）：Device Token 被 Office 拒绝。
 * - `office-hook-unavailable`（404）：Hook 尚未就绪。
 * - `office-job-conflict`（409）：Job 已被领取、取消或结束。
 * - `office-protocol-mismatch`：协议版本不兼容或响应格式错误。
 * - `office-transport-failed`：其他传输失败。
 */
class OfficeTransportException(
    message: String,
    val code: String = "office-transport-failed",
    val httpStatus: Int? = null,
) : Exception(message)

/**
 * SSE 事件帧。`type` 优先取自 JSON `type` 字段，回退到 SSE `event:` 行。
 */
data class OfficeSseEvent(
    val id: String?,
    val type: String,
    val data: JsonObject,
)

/**
 * SSE 下行流句柄。调用 [readEvent] 阻塞读取下一帧；返回 null 表示流正常结束。
 * 使用完毕必须调用 [close] 释放底层连接。
 *
 * 帧解析遵循 W3C EventSource 规范子集：`event:` / `id:` / `data:` 行，空行分隔帧。
 * 与 dsh-im `office-transport.mjs` 的 `parseFrame` 行为一致。
 */
class OfficeSseStream(
    private val response: Response,
) {
    private val source = response.body?.source()
        ?: throw IOException("AI Office stream returned no body")

    /**
     * 读取并解析下一个 SSE 事件。返回 null 当流正常结束；抛 [OfficeTransportException]
     * 当读取失败或数据不是合法 JSON 对象。
     *
     * 阻塞调用，调用方应在 `Dispatchers.IO` 上执行。
     */
    fun readEvent(): OfficeSseEvent? {
        val frame = mutableListOf<String>()
        while (true) {
            val line = try {
                source.readUtf8Line()
            } catch (e: IOException) {
                throw OfficeTransportException(
                    "AI Office SSE stream read failed: ${e.message}",
                    "office-transport-failed",
                )
            }
            if (line == null) {
                // 流结束；处理残余帧（无尾随空行的情况）
                return if (frame.isEmpty()) null else parseFrame(frame)
            }
            if (line.isEmpty()) {
                // 帧分隔符（空行）
                val event = parseFrame(frame)
                frame.clear()
                if (event != null) return event
                // 空帧（如心跳注释）继续累积
            } else {
                frame.add(line)
            }
        }
    }

    /** 关闭底层响应，释放连接。 */
    fun close() = runCatching { response.close() }

    private fun parseFrame(lines: List<String>): OfficeSseEvent? {
        var type = "message"
        var id: String? = null
        val data = StringBuilder()
        for (line in lines) {
            when {
                line.startsWith("event:") ->
                    type = line.substring(6).trim().ifEmpty { "message" }
                line.startsWith("id:") ->
                    id = line.substring(3).trim().ifEmpty { null }
                line.startsWith("data:") -> {
                    data.append(line.substring(5).trimStart())
                    data.append('\n')
                }
            }
        }
        if (data.isEmpty()) return null
        val jsonStr = data.toString().trimEnd('\n')
        val value = try {
            OfficeProtocol.json.parseToJsonElement(jsonStr)
        } catch (e: Exception) {
            throw OfficeTransportException(
                "AI Office SSE returned invalid JSON",
                "office-protocol-mismatch",
            )
        }
        val obj = value as? JsonObject ?: throw OfficeTransportException(
            "AI Office SSE data is not a JSON object",
            "office-protocol-mismatch",
        )
        // type 优先取自 JSON body，回退到 SSE event: 行
        val resolvedType = obj["type"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: type
        return OfficeSseEvent(id, resolvedType, obj)
    }
}

/**
 * AI Office Connector 协议客户端（传输层）。
 *
 * 封装 Heartbeat、SSE 下行流、任务查询、任务领取、租约续租、进度回传、审批请求、
 * 终态写入、失败上报九个固定 Hook。复用 Lxchat 已有的 [HttpClient]（共享 OkHttp 连接池、
 * 代理、超时）和 [DebugLog]。
 *
 * 鉴权通过 `Authorization: Bearer <deviceToken>` + `x-harness-device-id` 头完成；
 * 租约操作额外携带 `x-harness-lease-token` 头。Device Token 只在本类内部使用，
 * 不通过日志或回调回传。
 *
 * 与 dsh-im `office-transport.mjs` 的 `OfficeTransport` 类对齐。
 *
 * @param baseUrl Office origin（如 `https://office.example.com`），HTTPS 必需（loopback 例外）。
 * @param deviceId 本机设备标识。
 * @param deviceToken Device Token（从安全存储解密后传入），长度 ≥ 32。
 */
class OfficeConnectorApi(
    val baseUrl: String,
    val deviceId: String,
    val deviceToken: String,
) {
    /** 规范化为 origin（去掉路径、query、fragment）。 */
    private val base: String = normalizeOrigin(baseUrl)

    init {
        require(baseUrl.isNotBlank()) { "Office baseUrl must not be blank" }
        require(deviceId.isNotBlank()) { "Office deviceId must not be blank" }
        require(deviceToken.length >= 32) { "Device Token must contain at least 32 characters" }
        // HTTPS 必需（loopback 例外），与 dsh-im normalizeOfficeBaseUrl 一致
        val scheme = try { java.net.URI(baseUrl).scheme?.lowercase() } catch (_: Exception) { null }
        val host = try { java.net.URI(baseUrl).host?.lowercase() } catch (_: Exception) { null }
        val isLoopback = host == "localhost" || host == "127.0.0.1" || host == "[::1]"
        require(scheme == "https" || isLoopback) {
            "AI Office URL must use HTTPS (HTTP is allowed only for loopback testing)"
        }
    }

    /** 构建鉴权 headers。 */
    private fun authHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> =
        buildMap {
            put("authorization", "Bearer $deviceToken")
            put("x-harness-device-id", deviceId)
            putAll(extra)
        }

    /** POST JSON 并解析响应为 JsonObject。 */
    private suspend fun postJson(
        path: String,
        payload: JsonObject,
        leaseToken: String? = null,
    ): JsonObject = withContext(Dispatchers.IO) {
        val url = base + path
        val headers = authHeaders(
            buildMap {
                put("accept", "application/json")
                put("content-type", "application/json")
                if (leaseToken != null) put("x-harness-lease-token", leaseToken)
            }
        )
        val response = try {
            HttpClient.postTextResponse(url, payload.toString(), headers)
        } catch (e: Exception) {
            throw OfficeTransportException(
                "AI Office POST $path could not be completed: ${e.message}",
            )
        }
        if (!response.isSuccessful) {
            throw transportError("POST $path", response.code)
        }
        parseJsonObject(response.body)
    }

    /** GET JSON 并解析响应为 JsonObject。 */
    private suspend fun getJson(
        path: String,
        leaseToken: String? = null,
    ): JsonObject = withContext(Dispatchers.IO) {
        val url = base + path
        val headers = authHeaders(
            buildMap {
                put("accept", "application/json")
                if (leaseToken != null) put("x-harness-lease-token", leaseToken)
            }
        )
        val response = try {
            HttpClient.getTextResponse(url, headers)
        } catch (e: Exception) {
            throw OfficeTransportException(
                "AI Office GET $path could not be completed: ${e.message}",
            )
        }
        if (!response.isSuccessful) {
            throw transportError("GET $path", response.code)
        }
        parseJsonObject(response.body)
    }

    /** 根据 HTTP 状态码构建对应的 [OfficeTransportException]。 */
    private fun transportError(operation: String, status: Int): OfficeTransportException {
        val code = when (status) {
            401 -> "invalid-device-token"
            404 -> "office-hook-unavailable"
            409 -> "office-job-conflict"
            else -> "office-transport-failed"
        }
        return OfficeTransportException("AI Office $operation failed: HTTP $status", code, status)
    }

    /** 解析响应体为 JsonObject。 */
    private fun parseJsonObject(body: String): JsonObject =
        try {
            OfficeProtocol.json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw OfficeTransportException(
                "AI Office returned invalid JSON: ${e.message}",
                "office-protocol-mismatch",
            )
        }

    // ── 公开 API ─────────────────────────────────────────────────────────

    /**
     * Heartbeat：`POST /api/harness/connector/heartbeat`。
     *
     * 完成鉴权和能力握手。响应必须是 `{"ok":true,"protocolVersion":"office-harness.v1"}`，
     * 否则抛 [OfficeTransportException]（`office-protocol-mismatch`）。
     *
     * @param capabilities 能力声明（protocolVersion、deviceId、workspaces、instructionPresets、maxConcurrency）。
     * @return Office 心跳响应（可能包含待领取的 Job 列表）。
     */
    suspend fun heartbeat(capabilities: JsonObject): JsonObject {
        val resp = postJson(OfficeProtocol.PATH_HEARTBEAT, capabilities)
        val ok = resp["ok"]?.let { (it as? JsonPrimitive)?.boolean } ?: false
        val proto = resp["protocolVersion"]?.let { (it as? JsonPrimitive)?.contentOrNull }
        if (!ok || proto != OfficeProtocol.PROTOCOL_VERSION) {
            throw OfficeTransportException(
                "AI Office heartbeat protocol mismatch (expected ${OfficeProtocol.PROTOCOL_VERSION})",
                "office-protocol-mismatch",
            )
        }
        return resp
    }

    /**
     * 打开 SSE 下行流：`GET /api/harness/connector/stream`。
     *
     * 返回 [OfficeSseStream] 供调用方逐帧读取。调用方负责在结束时调用 [OfficeSseStream.close]。
     *
     * @param lastEventId 上次接收的事件 id，用于断点续传；null 表示从头开始。
     */
    suspend fun openStream(lastEventId: String?): OfficeSseStream = withContext(Dispatchers.IO) {
        val url = base + OfficeProtocol.PATH_STREAM
        val headers = authHeaders(mapOf("accept" to "text/event-stream"))
        val requestBuilder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        if (lastEventId != null) requestBuilder.addHeader("last-event-id", lastEventId)
        val call = HttpClient.client.newCall(requestBuilder.build())
        val response = call.execute()
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw transportError("stream", code)
        }
        val contentType = response.header("content-type")?.lowercase() ?: ""
        if (!contentType.contains("text/event-stream")) {
            response.close()
            throw OfficeTransportException(
                "AI Office stream did not return text/event-stream",
                "office-protocol-mismatch",
            )
        }
        OfficeSseStream(response)
    }

    /**
     * 查询任务详情：`GET /api/harness/connector/jobs/:id`。
     *
     * 返回包含 `job` 字段的响应对象。用于领取前的两步流程第一步，
     * 以及续租时轮询审批状态。
     */
    suspend fun getJob(jobId: String): JsonObject =
        getJson(OfficeProtocol.PATH_JOB.format(jobId))

    /**
     * 任务领取：`POST /api/harness/connector/jobs/:id/accept`。
     *
     * 确认领取任务并获得 90 秒租约。返回包含 `leaseToken` 的响应对象。
     * 必须先调用 [getJob] 查询任务详情（两步 get+accept 流程）。
     */
    suspend fun acceptJob(jobId: String): JsonObject =
        postJson(OfficeProtocol.PATH_JOB_ACCEPT.format(jobId), buildJsonObject {})

    /**
     * 租约续租：`POST /api/harness/connector/jobs/:id/renew`。
     *
     * 每 30 秒调用一次以保持租约。需携带 [leaseToken]。
     */
    suspend fun renewJob(jobId: String, leaseToken: String): JsonObject =
        postJson(OfficeProtocol.PATH_JOB_RENEW.format(jobId), buildJsonObject {}, leaseToken)

    /**
     * 进度回传：`POST /api/harness/connector/jobs/:id/progress`。
     *
     * 增量回传执行状态（文字增量、工具名、状态消息）。需携带 [leaseToken]。
     */
    suspend fun postProgress(jobId: String, leaseToken: String, body: JsonObject): JsonObject =
        postJson(OfficeProtocol.PATH_JOB_PROGRESS.format(jobId), body, leaseToken)

    /**
     * 审批请求：`POST /api/harness/connector/jobs/:id/approval`。
     *
     * 独立端点，不复用 [postProgress]。向 Office 人工面板请求审批（工具批准 / 补充问题）。
     * 需携带 [leaseToken]。
     */
    suspend fun requestApproval(jobId: String, leaseToken: String, body: JsonObject): JsonObject =
        postJson(OfficeProtocol.PATH_JOB_APPROVAL.format(jobId), body, leaseToken)

    /**
     * 终态写入：`POST /api/harness/connector/jobs/:id/result`。
     *
     * 完成任务，写入最终结果。只允许调用一次；重复调用会被 Office 拒绝（409）。
     * 需携带 [leaseToken]。
     */
    suspend fun completeJob(jobId: String, leaseToken: String, body: JsonObject): JsonObject =
        postJson(OfficeProtocol.PATH_JOB_RESULT.format(jobId), body, leaseToken)

    /**
     * 失败上报：`POST /api/harness/connector/jobs/:id/fail`。
     *
     * 独立端点，不复用 [completeJob]。上报任务执行失败。需携带 [leaseToken]。
     */
    suspend fun failJob(jobId: String, leaseToken: String, body: JsonObject): JsonObject =
        postJson(OfficeProtocol.PATH_JOB_FAIL.format(jobId), body, leaseToken)

    companion object {
        /**
         * 从 Office Base URL 自动展示全部固定 Hook URL。
         *
         * 返回 `Map<hookName, url>`，其中 `:id` 保留为模板占位符。
         */
        fun hookUrls(baseUrl: String): Map<String, String> {
            val base = normalizeOrigin(baseUrl)
            return linkedMapOf(
                "heartbeat" to (base + OfficeProtocol.PATH_HEARTBEAT),
                "stream" to (base + OfficeProtocol.PATH_STREAM),
                "job" to (base + OfficeProtocol.PATH_JOB.format(":id")),
                "job_accept" to (base + OfficeProtocol.PATH_JOB_ACCEPT.format(":id")),
                "job_renew" to (base + OfficeProtocol.PATH_JOB_RENEW.format(":id")),
                "job_progress" to (base + OfficeProtocol.PATH_JOB_PROGRESS.format(":id")),
                "job_approval" to (base + OfficeProtocol.PATH_JOB_APPROVAL.format(":id")),
                "job_result" to (base + OfficeProtocol.PATH_JOB_RESULT.format(":id")),
                "job_fail" to (base + OfficeProtocol.PATH_JOB_FAIL.format(":id")),
            )
        }

        /** 规范化 URL 为 origin（scheme://host[:port]），去掉路径、query、fragment。 */
        private fun normalizeOrigin(url: String): String {
            val uri = try { java.net.URI(url) } catch (_: Exception) { return url.trimEnd('/') }
            val scheme = uri.scheme ?: return url.trimEnd('/')
            val host = uri.host ?: return url.trimEnd('/')
            val port = uri.port
            return if (port != -1) "$scheme://$host:$port" else "$scheme://$host"
        }
    }
}