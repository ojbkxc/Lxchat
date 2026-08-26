package com.lxseek.chat.im.weixin

import com.lxseek.chat.api.HttpClient
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

// ── JSON 导航辅助：用 `as?` 安全转型，单字段类型不符不会让整条消息解析失败 ──
private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull
private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull

/**
 * P1-5: 响应体日志脱敏。含 token/ticket/aeskey 等敏感字段的响应只打印长度，
 * 不打印 body 内容，避免 bot_token/context_token 等泄露到 logcat
 * （参考 weixin-ClawBot-API bot.py:371-397 不明文打印 token）。
 */
private fun maskResponseBody(body: String): String {
    val sensitiveKeys = listOf(
        "\"bot_token\"", "\"context_token\"", "\"typing_ticket\"",
        "\"aeskey\"", "\"qrcode\"", "\"qrcode_img_content\"",
    )
    if (sensitiveKeys.any { body.contains(it) }) {
        return "[sensitive body masked, len=${body.length}]"
    }
    return body.take(500)
}

/** 微信 iLink 协议错误。code 与 dsh-im/weixin-api.mjs 的 WeixinApiError 对齐。 */
class WeixinApiError(
    val code: String,
    message: String,
    cause: Throwable? = null,
    val status: Int? = null,
) : RuntimeException(message, cause)

/**
 * 微信 iLink bot 协议适配器（Kotlin 重写 dsh-im/src/channels/weixin/weixin-api.mjs）。
 *
 * 直连腾讯官方 ilinkai.weixin.qq.com，无需任何外部网关。提供：
 *  - 扫码绑定：[beginLogin] / [pollLogin]
 *  - 长轮询收消息：[getUpdates]
 *  - 发文本：[sendText]
 *  - 启停通知：[notifyStart] / [notifyStop]
 *  - 图片解密：[decryptWeixinImage] / [parseWeixinImageAesKey] / [weixinImageDownloadUrl]
 *  - 文本提取：[extractWeixinText] / [weixinMessageId] / [splitWeixinText]
 *
 * 协议版本 2.4.6，iLink-App-Id "bot"，AES-128-ECB 图片解密。
 */
class WeixinIlinkApi(
    private val client: OkHttpClient = HttpClient.client,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /** beginLogin 返回：二维码令牌 + 图片 URL。 */
    data class BeginLoginResult(
        val qrcode: String,
        val qrcodeUrl: String,
    )

    /** pollLogin 返回。 */
    data class LoginStatus(
        val status: String,
        /** 当 status=confirmed 时携带的 token / base_url。 */
        val token: String? = null,
        val baseUrl: String? = null,
        /** 远端 bot id（ilink_bot_id），confirmed 后用于自回复防护/账号切换检测/多账号管理。
         *  参考weixin-ClawBot-API bot.py:1236-1255 登录后保存 ilink_bot_id。 */
        val botId: String? = null,
        /** 远端 user id（ilink_user_id），可选，部分流程用于会话定位。 */
        val userId: String? = null,
        /** 原始响应，便于取额外字段（如 verify_code 提示）。 */
        val raw: JsonObject,
    )

    /** getUpdates 返回。 */
    data class Updates(
        val ret: Int,
        val msgs: List<JsonObject>,
        val getUpdatesBuf: String,
        val raw: JsonObject,
        /** Server-suggested timeout (ms) for the next getUpdates long-poll. 0 = use default. */
        val longpollingTimeoutMs: Long = 0L,
    )

    /** 图片引用（懒加载解密）。 */
    interface WeixinImageRef {
        val name: String
        suspend fun load(maxBytes: Long = DEFAULT_IMAGE_MAX_BYTES): ByteArray
    }

    /** 文件引用（懒加载解密）。文件与图片共用 AES-128-ECB 下载+解密链路。 */
    interface WeixinFileRef {
        val name: String
        suspend fun load(maxBytes: Long = DEFAULT_IMAGE_MAX_BYTES): ByteArray
    }

    /** 媒体消息种类。 */
    enum class WeixinMediaKind { IMAGE, FILE }

    /** 待发送的媒体规格。 */
    data class WeixinMediaSpec(
        val kind: WeixinMediaKind,
        val rawBytes: ByteArray,
        val fileName: String = "file",
        /** 图片缩略图（发送方已压缩为 JPEG 字节）；图片建议提供，文件忽略。 */
        val thumbBytes: ByteArray? = null,
    )

    // ── 公开 API ─────────────────────────────────────────────────────────

    /** 申请扫码绑定二维码。返回二维码令牌 + 图片 URL。 */
    suspend fun beginLogin(
        localTokens: List<String> = emptyList(),
        botType: String = DEFAULT_BOT_TYPE,
    ): BeginLoginResult = withContext(Dispatchers.IO) {
        DebugLog.d("WeixinIlinkApi", "beginLogin: 请求 get_bot_qrcode, botType=$botType")
        val tokens = localTokens
            .mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
            .distinct()
            .takeLast(10)
        val body = buildJsonObject {
            putJsonArray("local_token_list") { tokens.forEach { add(JsonPrimitive(it)) } }
        }.toString()
        val response = requestJson(
            method = "POST",
            baseUrl = WEIXIN_QR_BASE_URL,
            endpoint = "ilink/bot/get_bot_qrcode?bot_type=${urlEncode(botType)}",
            body = body,
            token = null,
            timeoutMs = 10_000L,
            authenticated = true,
        )
        val qrcode = response["qrcode"].str()?.takeIf { it.isNotBlank() }
            ?: throw WeixinApiError("invalid-qr", "微信服务没有返回二维码令牌。")
        val imgContent = response["qrcode_img_content"].str()
            ?: throw WeixinApiError("invalid-qr", "微信服务没有返回扫码地址。")
        val qrcodeUrl = normalizeWeixinQrUrl(imgContent)
        DebugLog.d(
            "WeixinIlinkApi",
            "beginLogin: 响应 qrcode=${qrcode.take(10)}... imgContent=${imgContent.take(50)}... url=$qrcodeUrl",
        )
        BeginLoginResult(qrcode = qrcode, qrcodeUrl = qrcodeUrl)
    }

    /** 轮询扫码状态（长轮询 35s）。 */
    suspend fun pollLogin(
        qrcode: String,
        baseUrl: String = WEIXIN_QR_BASE_URL,
        verifyCode: String? = null,
    ): LoginStatus = withContext(Dispatchers.IO) {
        val qr = qrcode.trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("qrcode is required")
        var endpoint = "ilink/bot/get_qrcode_status?qrcode=${urlEncode(qr)}"
        val vc = verifyCode?.trim()?.takeIf { it.isNotEmpty() }
        if (vc != null) endpoint += "&verify_code=${urlEncode(vc)}"
        // H9: 长轮询 35s 超时是正常控制流（服务端在 35s 内无状态变化），应视为 wait 状态
        // 继续轮询，而非抛错导致绑定直接失败。
        // 参考weixin-ClawBot-API bot.py:1031-1032 `if status.get("_timeout"): return {"status": "wait"}`。
        val response = try {
            requestJson(
                method = "GET",
                baseUrl = baseUrl,
                endpoint = endpoint,
                body = null,
                token = null,
                timeoutMs = DEFAULT_LONG_POLL_TIMEOUT_MS,
                authenticated = false,
            )
        } catch (e: WeixinApiError) {
            if (e.code == "timeout") {
                return@withContext LoginStatus(
                    status = "wait",
                    token = null,
                    baseUrl = null,
                    botId = null,
                    userId = null,
                    raw = buildJsonObject {},
                )
            } else throw e
        }
        val status = response["status"].str()?.takeIf { it in LOGIN_STATUSES }
            ?: throw WeixinApiError("invalid-login-status", "微信服务返回了无法识别的扫码状态。")
        LoginStatus(
            status = status,
            token = response["bot_token"].str(),
            baseUrl = response["baseurl"].str(),
            // G1: 解析 ilink_bot_id / ilink_user_id，供绑定后写入 ImGatewayConfig.botId，
            // 使自回复防护第一道、账号切换检测、多账号管理生效。
            botId = response["ilink_bot_id"].str(),
            userId = response["ilink_user_id"].str(),
            raw = response,
        )
    }

    /** 长轮询拉取新消息。超时按"无新消息"处理，返回空列表。 */
    suspend fun getUpdates(
        baseUrl: String,
        token: String,
        getUpdatesBuf: String = "",
        timeoutMs: Long = DEFAULT_LONG_POLL_TIMEOUT_MS,
    ): Updates = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("get_updates_buf", getUpdatesBuf)
            put("base_info", baseInfoJson())
        }.toString()
        val response = try {
            requestJson(
                method = "POST",
                baseUrl = baseUrl,
                endpoint = "ilink/bot/getupdates",
                body = body,
                token = token,
                timeoutMs = timeoutMs,
                authenticated = true,
            )
        } catch (e: WeixinApiError) {
            if (e.code == "timeout") {
                return@withContext Updates(
                    ret = 0,
                    msgs = emptyList(),
                    getUpdatesBuf = getUpdatesBuf,
                    raw = buildJsonObject {},
                )
            } else throw e
        }
        Updates(
            ret = response["ret"].int() ?: 0,
            msgs = response["msgs"].arr()?.mapNotNull { it.obj() } ?: emptyList(),
            getUpdatesBuf = response["get_updates_buf"].str() ?: getUpdatesBuf,
            raw = response,
            longpollingTimeoutMs = response["longpolling_timeout_ms"].long() ?: 0L,
        )
    }

    /**
     * 发送文本消息。
     *
     * 返回响应里携带的新 `context_token`（若服务端下发了），否则返回 null。
     * 按好友会话的 context_token 在每次 sendmessage 后都可能刷新，调用方应回写，
     * 否则后续回复可能被服务端静默丢弃（对齐 weixin_client.py send_message 的
     * _extract_response_context_token 回写逻辑）。
     */
    suspend fun sendText(
        baseUrl: String,
        token: String,
        toUserId: String,
        text: String,
        contextToken: String? = null,
        runId: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        val recipient = toUserId.trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("toUserId is required")
        val content = text.trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("text is required")
        val clientId = "lxchat-weixin-${UUID.randomUUID()}"
        val body = buildJsonObject {
            putJsonObject("msg") {
                put("from_user_id", "")
                put("to_user_id", recipient)
                put("client_id", clientId)
                put("message_type", 2)
                put("message_state", 2)
                putJsonArray("item_list") {
                    add(buildJsonObject {
                        put("type", 1)
                        putJsonObject("text_item") { put("text", content) }
                    })
                }
                contextToken?.trim()?.takeIf { it.isNotEmpty() }?.let { put("context_token", it) }
                runId?.trim()?.takeIf { it.isNotEmpty() }?.let { put("run_id", it) }
            }
            put("base_info", baseInfoJson())
        }.toString()
        val response = requestJson(
            method = "POST",
            baseUrl = baseUrl,
            endpoint = "ilink/bot/sendmessage",
            body = body,
            token = token,
            timeoutMs = DEFAULT_TIMEOUT_MS,
            authenticated = true,
        )
        // P2-2: 同时校验 ret 和 errcode，防止 ret=0/null 但 errcode=-14 被当成功
        // （参考 weixin-ClawBot-API bot.py:435-448 ensure_business_success）
        val ret = response["ret"].long()
        val errcode = response["errcode"].long()
        val ok = (ret == null || ret == 0L) && (errcode == null || errcode == 0L)
        if (!ok) {
            throw WeixinApiError("send-rejected", "微信服务拒绝了回复消息（ret=$ret errcode=$errcode）。")
        }
        // sendmessage 后 context_token 可能更新，返回新 token 供调用方回写。
        extractContextToken(response)
    }

    /** 发送媒体消息（图片/文件）：AES-128-ECB 加密 → getuploadurl → CDN 上传 → sendmessage。
     *  返回响应里下发的新 `context_token`（无则 null）。失败抛 [WeixinApiError]。 */
    suspend fun sendMediaItem(
        baseUrl: String,
        token: String,
        toUserId: String,
        spec: WeixinMediaSpec,
        contextToken: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        val recipient = toUserId.trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("toUserId is required")
        if (spec.rawBytes.isEmpty()) throw IllegalArgumentException("media bytes are empty")
        val rawBytes = spec.rawBytes
        val mediaType = if (spec.kind == WeixinMediaKind.IMAGE) WEIXIN_MEDIA_TYPE_IMAGE else WEIXIN_MEDIA_TYPE_FILE

        // 1) 随机 AES-128 密钥并加密明文（PKCS7 padding），与解密链路对称。
        val aesKey = ByteArray(16).also { SECURE_RANDOM.nextBytes(it) }
        val aesKeyHex = aesKey.toHexString()
        val encrypted = encryptWeixinMedia(rawBytes, aesKey)
        val filekey = ByteArray(16).also { SECURE_RANDOM.nextBytes(it) }.toHexString()
        val rawMd5 = md5Hex(rawBytes)

        // 2) 图片需要缩略图（发送方预先压缩为 JPEG 字节）。
        var thumb: ThumbInfo? = null
        if (spec.kind == WeixinMediaKind.IMAGE && spec.thumbBytes.orEmpty().isNotEmpty()) {
            val tKey = ByteArray(16).also { SECURE_RANDOM.nextBytes(it) }
            val tEncrypted = encryptWeixinMedia(spec.thumbBytes!!, tKey)
            thumb = ThumbInfo(
                encrypted = tEncrypted,
                aesKeyHex = tKey.toHexString(),
                rawMd5 = md5Hex(spec.thumbBytes!!),
                rawSize = spec.thumbBytes!!.size,
            )
        }

        // 3) getuploadurl 获取上传凭证。
        val uploadBody = buildJsonObject {
            put("filekey", filekey)
            put("media_type", mediaType)
            put("to_user_id", recipient)
            put("rawsize", rawBytes.size)
            put("rawfilemd5", rawMd5)
            put("filesize", encrypted.size)
            put("aeskey", aesKeyHex)
            if (thumb != null) {
                put("thumb_rawsize", thumb!!.rawSize)
                put("thumb_rawfilemd5", thumb!!.rawMd5)
                put("thumb_filesize", thumb!!.encrypted.size)
                put("no_need_thumb", false)
            } else {
                put("no_need_thumb", true)
            }
            put("base_info", baseInfoJson())
        }.toString()
        val uploadResp = requestJson(
            method = "POST",
            baseUrl = baseUrl,
            endpoint = "ilink/bot/getuploadurl",
            body = uploadBody,
            token = token,
            timeoutMs = DEFAULT_TIMEOUT_MS,
            authenticated = true,
        )
        val uploadParam = uploadResp["upload_param"].str()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw WeixinApiError("upload-rejected", "微信服务没有返回媒体上传凭证。")
        val aesKeyB64 = Base64.encodeToString(aesKeyHex.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        // 4) 上传密文到 CDN，得到 media 对象。
        val media = uploadCdn(uploadParam, filekey, encrypted, aesKeyB64)

        // 5) 构造 item_list 并 sendmessage。
        val thumbUploadParam = uploadResp["thumb_upload_param"].str()
        val itemList = ArrayList<JsonElement>(1).apply {
            if (spec.kind == WeixinMediaKind.IMAGE) {
                add(buildJsonObject {
                    put("type", WEIXIN_MSG_ITEM_IMAGE)
                    putJsonObject("image_item") {
                        putJsonObject("media", media)
                        put("mid_size", encrypted.size)
                        if (thumb != null && !thumbUploadParam.isNullOrEmpty()) {
                            val thumbAesKeyB64 = Base64.encodeToString(
                                thumb!!.aesKeyHex.toByteArray(Charsets.UTF_8), Base64.NO_WRAP,
                            )
                            val thumbMedia = uploadCdn(
                                thumbUploadParam, "$filekey_thumb", thumb!!.encrypted, thumbAesKeyB64,
                            )
                            putJsonObject("thumb_media", thumbMedia)
                            put("thumb_size", thumb!!.encrypted.size)
                        }
                    }
                })
            } else {
                add(buildJsonObject {
                    put("type", WEIXIN_MSG_ITEM_FILE)
                    putJsonObject("file_item") {
                        putJsonObject("media", media)
                        put("file_name", spec.fileName.ifBlank { "file" })
                        put("len", rawBytes.size)
                    }
                })
            }
        }
        val clientId = "lxchat-weixin-${UUID.randomUUID()}"
        val msgBody = buildJsonObject {
            putJsonObject("msg") {
                put("from_user_id", "")
                put("to_user_id", recipient)
                put("client_id", clientId)
                put("message_type", 2)
                put("message_state", 2)
                put("item_list", JsonArray(itemList))
                contextToken?.trim()?.takeIf { it.isNotEmpty() }?.let { put("context_token", it) }
            }
            put("base_info", baseInfoJson())
        }.toString()
        val sendResp = requestJson(
            method = "POST",
            baseUrl = baseUrl,
            endpoint = "ilink/bot/sendmessage",
            body = msgBody,
            token = token,
            timeoutMs = DEFAULT_TIMEOUT_MS,
            authenticated = true,
        )
        val ret = sendResp["ret"].long()
        val errcode = sendResp["errcode"].long()
        val ok = (ret == null || ret == 0L) && (errcode == null || errcode == 0L)
        if (!ok) {
            throw WeixinApiError("send-rejected", "微信服务拒绝了媒体消息（ret=$ret errcode=$errcode）。")
        }
        extractContextToken(sendResp)
    }

    // ── 媒体上传/加密辅助（对齐 weixin_client.py _prepare_weixin_upload） ──

    /** AES-128-ECB + PKCS7 加密（与解密/ECB/NoPadding 互补：先 PKCS7 填充到 16 字节整数倍）。 */
    fun encryptWeixinMedia(rawBytes: ByteArray, key: ByteArray): ByteArray {
        require(key.size == 16) { "AES key must be 16 bytes" }
        require(rawBytes.isNotEmpty()) { "media bytes empty" }
        return try {
            val paddedLength = ((rawBytes.size / 16) + 1) * 16
            val padded = rawBytes.copyOf(paddedLength)
            val padLen = paddedLength - rawBytes.size
            for (i in rawBytes.size until paddedLength) padded[i] = padLen.toByte()
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            cipher.doFinal(padded)
        } catch (e: Exception) {
            throw WeixinApiError("media-encryption-failed", "微信媒体加密失败。", e)
        }
    }

    /** 上传 AES 密文到微信 CDN，返回构造好的 media 对象（encrypt_query_param + aes_key + encrypt_type）。 */
    private suspend fun uploadCdn(
        uploadParam: String,
        filekey: String,
        encrypted: ByteArray,
        aesKeyB64: String,
    ): JsonObject {
        val param = uploadParam.trim().takeIf { it.isNotEmpty() }
            ?: throw WeixinApiError("upload-rejected", "微信媒体上传凭证为空。")
        val key = filekey.trim().takeIf { it.isNotEmpty() }
            ?: throw WeixinApiError("upload-rejected", "微信媒体 filekey 为空。")
        val url = "$WEIXIN_CDN_BASE_URL/upload?encrypted_query_param=${urlEncode(param)}&filekey=${urlEncode(key)}"
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull()
        if (host != WEIXIN_CDN_HOST) {
            throw WeixinApiError("untrusted-upload-url", "微信媒体上传地址不受信任。")
        }
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .post(encrypted.toRequestBody(OCTET_STREAM_MEDIA))
                .build()
            try {
                val response = client.newCall(request).execute()
                response.use {
                    if (!it.isSuccessful) {
                        val err = it.header("x-error-message") ?: "HTTP ${it.code}"
                        throw WeixinApiError("cdn-upload-failed", "微信 CDN 上传失败：$err", status = it.code)
                    }
                    val encryptedParam = it.header("x-encrypted-param")
                        ?.trim()?.takeIf { s -> s.isNotEmpty() }
                        ?: throw WeixinApiError("cdn-upload-missing-param", "微信 CDN 上传缺少 x-encrypted-param 响应头。")
                    buildJsonObject {
                        put("encrypt_query_param", encryptedParam)
                        put("aes_key", aesKeyB64)
                        put("encrypt_type", 1)
                    }
                }
            } catch (e: WeixinApiError) {
                throw e
            } catch (e: IOException) {
                throw WeixinApiError("network-error", "上传微信媒体时网络错误。", e)
            } catch (e: Exception) {
                throw WeixinApiError("network-error", "上传微信媒体失败。", e)
            }
        }
    }

    /** 从 sendmessage 响应中提取新下发的 context_token（顺带检查 msg/message/data/result 嵌套）。 */
    private fun extractContextToken(response: JsonObject): String? {
        response["context_token"].str()?.let { return it }
        for (key in listOf("msg", "message", "data", "result")) {
            response[key].obj()?.get("context_token")?.str()?.let { return it }
        }
        return null
    }

    /** 通知微信服务开始接收消息。 */
    suspend fun notifyStart(baseUrl: String, token: String): JsonObject = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("base_info", baseInfoJson()) }.toString()
        val response = requestJson(
            method = "POST",
            baseUrl = baseUrl,
            endpoint = "ilink/bot/msg/notifystart",
            body = body,
            token = token,
            timeoutMs = 10_000L,
            authenticated = true,
        )
        val ret = response["ret"].int()
        if (ret != null && ret != 0) {
            throw WeixinApiError("start-rejected", "微信账号连接启动失败。")
        }
        response
    }

    /** 通知微信服务停止接收消息。 */
    suspend fun notifyStop(baseUrl: String, token: String): JsonObject = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("base_info", baseInfoJson()) }.toString()
        requestJson(
            method = "POST",
            baseUrl = baseUrl,
            endpoint = "ilink/bot/msg/notifystop",
            body = body,
            token = token,
            timeoutMs = 10_000L,
            authenticated = true,
        )
    }

    /**
     * 获取用户的输入状态凭证 typing_ticket（供 [sendTyping] 使用）。
     *
     * 对齐 weixin-ClawBot-API bot.py 的 `ilink/bot/getconfig`：按用户缓存、失败不阻断文字回复。
     * 返回 null 表示当前拿不到 ticket（无 context_token / 服务拒绝 / 网络错误），调用方应静默忽略。
     */
    suspend fun getConfig(
        baseUrl: String,
        token: String,
        userId: String,
        contextToken: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        val recipient = userId.trim().takeIf { it.isNotEmpty() } ?: return@withContext null
        val body = buildJsonObject {
            put("ilink_user_id", recipient)
            contextToken?.trim()?.takeIf { it.isNotEmpty() }?.let { put("context_token", it) }
            put("base_info", baseInfoJson())
        }.toString()
        try {
            val response = requestJson(
                method = "POST",
                baseUrl = baseUrl,
                endpoint = "ilink/bot/getconfig",
                body = body,
                token = token,
                timeoutMs = 10_000L,
                authenticated = true,
            )
            val ret = response["ret"].int()
            if (ret != null && ret != 0) {
                DebugLog.w("WeixinIlinkApi", "getconfig rejected ret=$ret")
                return@withContext null
            }
            response["typing_ticket"].str()?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            DebugLog.e("WeixinIlinkApi", "getconfig failed", e)
            null
        }
    }

    /**
     * 发送微信"正在输入"状态：status=1 表示生成中，status=2 表示完成。
     * 尽力而为，失败不抛（输入状态只是反馈，不该影响文字回复）。
     * 对齐 weixin-ClawBot-API bot.py 的 `ilink/bot/sendtyping`。
     */
    suspend fun sendTyping(
        baseUrl: String,
        token: String,
        userId: String,
        typingTicket: String,
        status: Int,
    ) = withContext(Dispatchers.IO) {
        val recipient = userId.trim().takeIf { it.isNotEmpty() } ?: return@withContext
        val ticket = typingTicket.trim().takeIf { it.isNotEmpty() } ?: return@withContext
        val body = buildJsonObject {
            put("ilink_user_id", recipient)
            put("typing_ticket", ticket)
            put("status", status)
            put("base_info", baseInfoJson())
        }.toString()
        try {
            val response = requestJson(
                method = "POST",
                baseUrl = baseUrl,
                endpoint = "ilink/bot/sendtyping",
                body = body,
                token = token,
                timeoutMs = 10_000L,
                authenticated = true,
            )
            val ret = response["ret"].int()
            if (ret != null && ret != 0) {
                DebugLog.w("WeixinIlinkApi", "sendTyping rejected ret=$ret")
            }
        } catch (e: Exception) {
            DebugLog.e("WeixinIlinkApi", "sendTyping failed", e)
        }
    }

    /** 提取消息中的图片引用（懒加载解密）。 */
    fun extractWeixinImages(message: JsonObject): List<WeixinImageRef> {
        val itemList = message["item_list"].arr() ?: return emptyList()
        val images = ArrayList<WeixinImageRef>()
        for (item in itemList) {
            val imageItem = item.obj()?.get("image_item").obj() ?: continue
            val index = images.size
            images.add(object : WeixinImageRef {
                override val name: String = if (index == 0) "image" else "image-${index + 1}"
                override suspend fun load(maxBytes: Long): ByteArray = withContext(Dispatchers.IO) {
                    val key = parseWeixinImageAesKey(imageItem)
                    val media = imageItem["media"].obj()
                        ?: throw WeixinApiError("missing-image-url", "微信图片没有可用的下载地址。")
                    val url = weixinImageDownloadUrl(media)
                    val ciphertext = fetchImageBytes(url, maxBytes + 16)
                    decryptWeixinImage(ciphertext, key)
                }
            })
        }
        return images
    }

    /** 入站图片引用（[extractWeixinImages] 的别名，对齐 mjs 命名）。 */
    fun inboundImages(message: JsonObject): List<WeixinImageRef> = extractWeixinImages(message)

    /** 提取消息中的文件引用（懒加载解密）。文件与图片共用 AES 解密，供文本类文件内容识别。 */
    fun inboundFiles(message: JsonObject): List<WeixinFileRef> {
        val itemList = message["item_list"].arr() ?: return emptyList()
        val files = ArrayList<WeixinFileRef>()
        for (item in itemList) {
            val fileItem = item.obj()?.get("file_item").obj() ?: continue
            val filename = fileItem["filename"].str().orEmpty().ifEmpty { "file" }
            val index = files.size
            files.add(object : WeixinFileRef {
                override val name: String = if (index == 0) filename else "$filename-${index + 1}"
                override suspend fun load(maxBytes: Long): ByteArray = withContext(Dispatchers.IO) {
                    val key = parseWeixinImageAesKey(fileItem)
                    val media = fileItem["media"].obj()
                        ?: throw WeixinApiError("missing-file-url", "微信文件没有可用的下载地址。")
                    val url = weixinImageDownloadUrl(media)
                    val ciphertext = fetchImageBytes(url, maxBytes + 16)
                    decryptWeixinImage(ciphertext, key)
                }
            })
        }
        return files
    }

    // ── HTTP 请求 ───────────────────────────────────────────────────────

    private data class RawResponse(val code: Int, val body: String)

    private suspend fun requestJson(
        method: String,
        baseUrl: String,
        endpoint: String,
        body: String?,
        token: String?,
        timeoutMs: Long,
        authenticated: Boolean,
    ): JsonObject {
        val trustedBase = normalizeWeixinApiBaseUrl(baseUrl)
        val url = trustedBase + endpoint
        // 二次校验最终 URL 的 host（防 endpoint 注入）
        val finalHost = runCatching { URI(url).host?.lowercase() }.getOrNull()
        if (finalHost == null || !isWeixinHost(finalHost)) {
            throw WeixinApiError("untrusted-endpoint", "拒绝访问不受信任的微信服务地址。")
        }
        val headers = if (authenticated) authenticatedHeaders(token) else commonHeaders()
        DebugLog.d("WeixinIlinkApi", "requestJson: $method $url (authenticated=$authenticated) body=${body?.take(300)}")
        try {
            val raw = executeCall(method, url, headers, body, timeoutMs)
            // P1-5: 响应体脱敏，含 token 等敏感字段时只打印长度不打印 body
            DebugLog.d("WeixinIlinkApi", "requestJson: 响应 code=${raw.code} len=${raw.body.length} body=${maskResponseBody(raw.body)}")
            if (raw.code !in 200..299) {
                throw WeixinApiError(
                    code = "http-error",
                    message = "微信服务请求失败（HTTP ${raw.code}）。",
                    status = raw.code,
                )
            }
            val el = json.parseToJsonElement(raw.body)
            return el.obj() ?: throw WeixinApiError("invalid-response", "微信服务返回了非对象响应。")
        } catch (e: WeixinApiError) {
            throw e
        } catch (e: InterruptedIOException) {
            DebugLog.e("WeixinIlinkApi", "requestJson: 超时 $method $url", e)
            throw WeixinApiError("timeout", "微信服务请求超时。", e)
        } catch (e: IOException) {
            DebugLog.e("WeixinIlinkApi", "requestJson: 网络错误 $method $url", e)
            throw WeixinApiError("network-error", "暂时无法访问微信服务。", e)
        } catch (e: Exception) {
            DebugLog.e("WeixinIlinkApi", "requestJson: 异常 $method $url", e)
            throw WeixinApiError("network-error", "暂时无法访问微信服务。", e)
        }
    }

    /** 用 OkHttp 异步 enqueue + suspendCancellableCoroutine，协程取消时立即 cancel call。 */
    private suspend fun executeCall(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMs: Long,
    ): RawResponse = suspendCancellableCoroutine { cont ->
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post((body ?: "").toRequestBody(JSON_MEDIA))
            else -> {
                cont.resumeWithException(IllegalArgumentException("method=$method"))
                return@suspendCancellableCoroutine
            }
        }
        val request = builder.build()
        val call = client.newCall(request)
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        cont.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : okhttp3.Callback {
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    response.use {
                        val text = it.body?.string() ?: ""
                        if (cont.isActive) cont.resume(RawResponse(it.code, text))
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        })
    }

    private suspend fun fetchImageBytes(url: String, maxBytes: Long): ByteArray = withContext(Dispatchers.IO) {
        val bytes = HttpClient.getBytes(url)
            ?: throw WeixinApiError("image-download-failed", "微信图片下载失败。")
        if (bytes.size.toLong() > maxBytes) {
            throw WeixinApiError("image-too-large", "微信图片过大。")
        }
        bytes
    }

    // ── 头部 / base_info ─────────────────────────────────────────────────

    private fun commonHeaders(): MutableMap<String, String> = mutableMapOf(
        "iLink-App-Id" to ILINK_APP_ID,
        "iLink-App-ClientVersion" to ILINK_CLIENT_VERSION.toString(),
    )

    private fun authenticatedHeaders(token: String?): MutableMap<String, String> {
        val headers = commonHeaders().apply {
            put("Content-Type", "application/json")
            put("AuthorizationType", "ilink_bot_token")
            put("X-WECHAT-UIN", randomWechatUin())
        }
        val t = token?.trim()?.takeIf { it.isNotEmpty() }
        if (t != null) headers["Authorization"] = "Bearer $t"
        return headers
    }

    private fun randomWechatUin(): String {
        val bytes = ByteArray(4)
        SECURE_RANDOM.nextBytes(bytes)
        val value = ((bytes[0].toLong() and 0xFF) shl 24) or
                    ((bytes[1].toLong() and 0xFF) shl 16) or
                    ((bytes[2].toLong() and 0xFF) shl 8) or
                    (bytes[3].toLong() and 0xFF)
        return Base64.encodeToString(value.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun baseInfoJson(): JsonObject = buildJsonObject {
        put("channel_version", WEIXIN_PROTOCOL_VERSION)
        put("bot_agent", "LxChat/1.0.0")
    }

    companion object {
        const val WEIXIN_QR_BASE_URL = "https://ilinkai.weixin.qq.com/"
        const val WEIXIN_PROTOCOL_VERSION = "2.4.6"
        const val DEFAULT_BOT_TYPE = "3"
        const val WEIXIN_CDN_BASE_URL = "https://novac2c.cdn.weixin.qq.com/c2c"
        const val WEIXIN_CDN_HOST = "novac2c.cdn.weixin.qq.com"
        const val ILINK_APP_ID = "bot"
        /** (2 shl 16) or (4 shl 8) or 6 = 132102 */
        const val ILINK_CLIENT_VERSION = 132102
        const val DEFAULT_TIMEOUT_MS = 15_000L
        const val DEFAULT_LONG_POLL_TIMEOUT_MS = 35_000L
        const val DEFAULT_IMAGE_MAX_BYTES = 10L * 1024 * 1024  // 10 MB

        // 媒体发送常量（对齐 weixin_client.py）。
        const val WEIXIN_MEDIA_TYPE_IMAGE = 1
        const val WEIXIN_MEDIA_TYPE_FILE = 3
        const val WEIXIN_MSG_ITEM_IMAGE = 2
        const val WEIXIN_MSG_ITEM_FILE = 4

        private val OCTET_STREAM_MEDIA = "application/octet-stream".toMediaType()

        val LOGIN_STATUSES: Set<String> = setOf(
            "wait", "scaned", "confirmed", "expired",
            "scaned_but_redirect", "need_verifycode", "verify_code_blocked", "binded_redirect",
        )

        private val HEX_32_REGEX = Regex("^[0-9a-fA-F]{32}$")
        private val BASE64_REGEX = Regex("^[A-Za-z0-9+/]+={0,2}$")
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val SECURE_RANDOM = SecureRandom()

        // ── 静态工具函数（对齐 mjs 顶层导出） ─────────────────────────────

        /**
         * 提取消息文本，支持多种消息类型：
         * - type=1: text_item (文字)
         * - type=3: voice_item (语音转文字)
         * - type=4: video_item (视频 → [视频消息] 占位)
         * - type=5: location_item (位置 → [位置: 地址])
         * - type=6: link_item (链接 → [链接: 标题])
         * - type=7: card_item (名片 → [名片: 姓名])
         * - type=8: emoji_item (表情 → [表情] 占位)
         *
         * 之前只处理文字和语音转文字，视频/位置/链接/名片/表情被静默丢弃，
         * AI 完全不知道用户发了什么。现在用占位文本告知 AI 消息类型，
         * 让 AI 能做出合理回复（如"我收到了你的视频，但暂时无法查看"）。
         */
        fun extractWeixinText(message: JsonObject): String? {
            val itemList = message["item_list"].arr() ?: return null
            val parts = mutableListOf<String>()
            for (item in itemList) {
                val o = item.obj() ?: continue
                val type = o["type"].int()
                if (type == 1) {
                    val text = o["text_item"].obj()?.get("text").str()?.trim()
                    if (!text.isNullOrEmpty()) parts.add(text)
                }
                if (type == 3) {
                    val text = o["voice_item"].obj()?.get("text").str()?.trim()
                    if (!text.isNullOrEmpty()) parts.add(text)
                }
                // 视频消息：告知 AI 用户发了视频
                if (type == 4) {
                    val video = o["video_item"].obj()
                    if (video != null) parts.add("[视频消息]")
                }
                // 位置消息：提取地址信息
                if (type == 5) {
                    val loc = o["location_item"].obj()
                    if (loc != null) {
                        val label = loc["label"].str() ?: loc["address"].str() ?: loc["name"].str()
                        val lat = loc["latitude"].str() ?: loc["lat"].str()
                        val lng = loc["longitude"].str() ?: loc["lng"].str()
                        if (!label.isNullOrEmpty()) parts.add("[位置: $label]")
                        else if (!lat.isNullOrEmpty() && !lng.isNullOrEmpty()) parts.add("[位置: $lat,$lng]")
                        else parts.add("[位置消息]")
                    }
                }
                // 链接消息：提取标题
                if (type == 6) {
                    val link = o["link_item"].obj()
                    if (link != null) {
                        val title = link["title"].str() ?: link["desc"].str()
                        val url = link["url"].str() ?: link["link"].str()
                        if (!title.isNullOrEmpty()) parts.add("[链接: $title]")
                        else if (!url.isNullOrEmpty()) parts.add("[链接: $url]")
                        else parts.add("[链接消息]")
                    }
                }
                // 名片消息：提取姓名
                if (type == 7) {
                    val card = o["card_item"].obj()
                    if (card != null) {
                        val name = card["nickname"].str() ?: card["name"].str() ?: card["title"].str()
                        if (!name.isNullOrEmpty()) parts.add("[名片: $name]")
                        else parts.add("[名片消息]")
                    }
                }
                // 表情消息：占位
                if (type == 8) {
                    val emoji = o["emoji_item"].obj()
                    if (emoji != null) parts.add("[表情]")
                }
            }
            return if (parts.isEmpty()) null else parts.joinToString("\n")
        }

        /** 获取消息 ID（优先 message_id，fallback client_id）。 */
        fun weixinMessageId(message: JsonObject): String? {
            // dsh-im uses String(message.message_id) which converts any type; we must handle
            // both string and numeric message_id values.
            val messageIdEl = message["message_id"]
            if (messageIdEl != null) {
                val asStr = (messageIdEl as? JsonPrimitive)?.contentOrNull
                if (asStr != null && asStr.isNotEmpty()) return asStr
            }
            return message["client_id"].str()?.trim()?.takeIf { it.isNotEmpty() }
        }

        /** 分割长文本（最多 maxChars 字符一段，优先在换行处断开）。 */
        fun splitWeixinText(text: String, maxChars: Int = 4_000): List<String> {
            if (text.length <= maxChars) return listOf(text)
            val chunks = ArrayList<String>()
            var remaining = text
            while (remaining.length > maxChars) {
                var splitAt = remaining.lastIndexOf('\n', maxChars)
                if (splitAt < maxChars * 6 / 10) splitAt = maxChars
                chunks.add(remaining.substring(0, splitAt))
                remaining = remaining.substring(splitAt).trimStart('\n')
            }
            if (remaining.isNotEmpty()) chunks.add(remaining)
            return chunks
        }

        /** 解析图片 AES 密钥（16 字节）。 */
        fun parseWeixinImageAesKey(imageItem: JsonObject): ByteArray {
            val directHex = imageItem["aeskey"].str()?.trim()?.takeIf { it.isNotEmpty() }
            if (directHex != null) {
                if (!HEX_32_REGEX.matches(directHex)) {
                    throw WeixinApiError("invalid-image-key", "微信图片的加密密钥无效。")
                }
                return directHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            }
            val media = imageItem["media"].obj()
            val encoded = media?.get("aes_key").str()?.let { strictBase64(it) }
            if (encoded != null) {
                if (encoded.size == 16) return encoded
                if (encoded.size == 32) {
                    val text = encoded.toString(Charsets.US_ASCII)
                    if (HEX_32_REGEX.matches(text)) {
                        return text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    }
                }
            }
            throw WeixinApiError("invalid-image-key", "微信图片的加密密钥无效。")
        }

        /** AES-128-ECB 解密图片。 */
        fun decryptWeixinImage(ciphertext: ByteArray, key: ByteArray): ByteArray {
            if (key.size != 16 || ciphertext.isEmpty() || ciphertext.size % 16 != 0) {
                throw WeixinApiError("invalid-image-ciphertext", "微信图片的加密数据无效。")
            }
            return try {
                val cipher = Cipher.getInstance("AES/ECB/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
                cipher.doFinal(ciphertext)
            } catch (e: Exception) {
                throw WeixinApiError("image-decryption-failed", "微信图片解密失败。", e)
            }
        }

        /** 构造图片下载 URL。 */
        fun weixinImageDownloadUrl(media: JsonObject): String {
            val query = media["encrypt_query_param"].str()?.trim()?.takeIf { it.isNotEmpty() }
            if (query != null) {
                return "$WEIXIN_CDN_BASE_URL/download?encrypted_query_param=${URLEncoder.encode(query, "UTF-8")}"
            }
            val fullUrl = media["full_url"].str()?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw WeixinApiError("missing-image-url", "微信图片没有可用的下载地址。")
            val url = try { URI(fullUrl) } catch (e: Exception) {
                throw WeixinApiError("invalid-image-url", "微信图片的下载地址无效。", e)
            }
            val scheme = url.scheme?.lowercase()
            val host = url.host?.lowercase()
            if (scheme != "https" || host != WEIXIN_CDN_HOST ||
                (url.port != -1 && url.port != 443) ||
                !(url.path ?: "").startsWith("/c2c/")) {
                throw WeixinApiError("untrusted-image-url", "微信图片的下载地址不受信任。")
            }
            return fullUrl
        }

        /** 规范化 iLink API base URL（强制 https + weixin host + 末尾 /）。 */
        fun normalizeWeixinApiBaseUrl(value: String): String {
            val url = try { URI(value) } catch (e: Exception) {
                throw WeixinApiError("invalid-base-url", "微信服务返回了无效的连接地址。", e)
            }
            val scheme = url.scheme?.lowercase()
            val host = url.host?.lowercase()
            if (scheme != "https" || host == null || !isWeixinHost(host) ||
                (url.port != -1 && url.port != 443)) {
                throw WeixinApiError("untrusted-base-url", "微信服务返回了不受信任的连接地址。")
            }
            var path = url.path ?: "/"
            if (!path.endsWith('/')) path += '/'
            return "https://$host$path"
        }

        /** 规范化二维码 URL（强制 https + weixin host）。 */
        fun normalizeWeixinQrUrl(value: String): String {
            val text = value.trim().takeIf { it.isNotEmpty() }
                ?: throw WeixinApiError("invalid-qr", "微信服务没有返回扫码地址。")
            val url = try { URI(text) } catch (e: Exception) {
                throw WeixinApiError("invalid-qr", "微信服务返回了无效的扫码地址。", e)
            }
            val scheme = url.scheme?.lowercase()
            val host = url.host?.lowercase()
            if (scheme != "https" || host == null || !isWeixinHost(host)) {
                throw WeixinApiError("untrusted-qr", "微信服务返回了不受信任的扫码地址。")
            }
            return text
        }

        internal fun isWeixinHost(hostname: String): Boolean {
            val h = hostname.lowercase().trimEnd('.')
            return h == "weixin.qq.com" || h.endsWith(".weixin.qq.com")
        }

        private fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")

        private fun strictBase64(value: String): ByteArray? {
            val text = value.trim().takeIf { it.isNotEmpty() } ?: return null
            if (text.length % 4 != 0 || !BASE64_REGEX.matches(text)) return null
            return try { Base64.decode(text, Base64.DEFAULT) } catch (_: Exception) { null }
        }

        /** 图片缩略图元信息（加密后的字节 + 原始尺寸/MD5）。 */
        private class ThumbInfo(
            val encrypted: ByteArray,
            val aesKeyHex: String,
            val rawMd5: String,
            val rawSize: Int,
        )

        private fun md5Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("MD5").digest(bytes)
            return digest.joinToString("") { (it.toInt() and 0xFF).toHexByte() }
        }

        private fun Int.toHexByte(): String {
            val u = this / 16
            val l = this % 16
            return "" + "0123456789abcdef"[u] + "0123456789abcdef"[l]
        }

        private fun ByteArray.toHexString(): String =
            joinToString("") { b -> (b.toInt() and 0xFF).toHexByte() }
    }
}