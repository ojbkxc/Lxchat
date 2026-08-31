package com.lxseek.chat.im.weixin

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

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

/**
 * 微信 iLink 媒体发送器：AES-128-ECB 加密 → getuploadurl → CDN 上传 → sendmessage。
 * 独立成文件以控制 [WeixinIlinkApi] 源码体积（verifyKotlinFileSize 1499 行上限）。
 * 需要访问 [WeixinIlinkApi] 的 `internal` 成员：requestJson / baseInfoJson / client / urlEncode。
 */
class WeixinMediaSender(private val api: WeixinIlinkApi) {

    /** 发送媒体消息（图片/文件）。返回响应里下发的新 `context_token`（无则 null）。失败抛 [WeixinApiError]。 */
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
        val thumbBytes = spec.thumbBytes
        // stdlib 无 ByteArray?.isNullOrEmpty() 扩展，显式判空以触发智能转换。
        if (spec.kind == WeixinMediaKind.IMAGE && thumbBytes != null && thumbBytes.isNotEmpty()) {
            val tKey = ByteArray(16).also { SECURE_RANDOM.nextBytes(it) }
            val tEncrypted = encryptWeixinMedia(thumbBytes, tKey)
            thumb = ThumbInfo(
                encrypted = tEncrypted,
                aesKeyHex = tKey.toHexString(),
                rawMd5 = md5Hex(thumbBytes),
                rawSize = thumbBytes.size,
            )
        }
        // var 字段不做智能转换，取一次非空快照供后续 JSON 构造使用。
        val thumbInfo = thumb

        // 3) getuploadurl 获取上传凭证。
        val uploadBody = buildJsonObject {
            put("filekey", filekey)
            put("media_type", mediaType)
            put("to_user_id", recipient)
            put("rawsize", rawBytes.size)
            put("rawfilemd5", rawMd5)
            put("filesize", encrypted.size)
            put("aeskey", aesKeyHex)
            if (thumbInfo != null) {
                put("thumb_rawsize", thumbInfo.rawSize)
                put("thumb_rawfilemd5", thumbInfo.rawMd5)
                put("thumb_filesize", thumbInfo.encrypted.size)
                put("no_need_thumb", false)
            } else {
                put("no_need_thumb", true)
            }
            put("base_info", api.baseInfoJson())
        }.toString()
        val uploadResp = api.requestJson(
            method = "POST",
            baseUrl = baseUrl,
            endpoint = "ilink/bot/getuploadurl",
            body = uploadBody,
            token = token,
            timeoutMs = WeixinIlinkApi.DEFAULT_TIMEOUT_MS,
            authenticated = true,
        )
        val uploadParam = uploadResp["upload_param"].strSafe()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw WeixinApiError("upload-rejected", "微信服务没有返回媒体上传凭证。")
        val aesKeyB64 = Base64.encodeToString(aesKeyHex.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        // 4) 上传密文到 CDN，得到 media 对象。
        val media = uploadCdn(uploadParam, filekey, encrypted, aesKeyB64)

        // 5) 构造 item_list 并 sendmessage。
        // 缩略图二次上传是挂起操作，需在 buildJsonObject 的同步 lambda 之外先完成。
        val thumbUploadParam = uploadResp["thumb_upload_param"].strSafe()
        val thumbMedia: JsonObject? =
            if (spec.kind == WeixinMediaKind.IMAGE && thumbInfo != null && !thumbUploadParam.isNullOrEmpty()) {
                val thumbAesKeyB64 = Base64.encodeToString(
                    thumbInfo.aesKeyHex.toByteArray(Charsets.UTF_8), Base64.NO_WRAP,
                )
                uploadCdn(thumbUploadParam, "${filekey}_thumb", thumbInfo.encrypted, thumbAesKeyB64)
            } else {
                null
            }
        val itemList = ArrayList<JsonElement>(1).apply {
            if (spec.kind == WeixinMediaKind.IMAGE) {
                add(buildJsonObject {
                    put("type", WEIXIN_MSG_ITEM_IMAGE)
                    putJsonObject("image_item") {
                        put("media", media)
                        put("mid_size", encrypted.size)
                        thumbMedia?.let { tm ->
                            put("thumb_media", tm)
                            thumbInfo?.let { t -> put("thumb_size", t.encrypted.size) }
                        }
                    }
                })
            } else {
                add(buildJsonObject {
                    put("type", WEIXIN_MSG_ITEM_FILE)
                    putJsonObject("file_item") {
                        put("media", media)
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
            put("base_info", api.baseInfoJson())
        }.toString()
        val sendResp = api.requestJson(
            method = "POST",
            baseUrl = baseUrl,
            endpoint = "ilink/bot/sendmessage",
            body = msgBody,
            token = token,
            timeoutMs = WeixinIlinkApi.DEFAULT_TIMEOUT_MS,
            authenticated = true,
        )
        val ret = sendResp["ret"].longSafe()
        val errcode = sendResp["errcode"].longSafe()
        val ok = (ret == null || ret == 0L) && (errcode == null || errcode == 0L)
        if (!ok) {
            throw WeixinApiError("send-rejected", "微信服务拒绝了媒体消息（ret=$ret errcode=$errcode）。")
        }
        extractResponseContextToken(sendResp)
    }

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
        val url = "${WeixinIlinkApi.WEIXIN_CDN_BASE_URL}/upload" +
            "?encrypted_query_param=${WeixinIlinkApi.urlEncode(param)}&filekey=${WeixinIlinkApi.urlEncode(key)}"
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull()
        if (host != WeixinIlinkApi.WEIXIN_CDN_HOST) {
            throw WeixinApiError("untrusted-upload-url", "微信媒体上传地址不受信任。")
        }
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .post(encrypted.toRequestBody(OCTET_STREAM_MEDIA))
                .build()
            try {
                val response = api.client.newCall(request).execute()
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
    private fun extractResponseContextToken(response: JsonObject): String? {
        response["context_token"].strSafe()?.let { return it }
        for (key in listOf("msg", "message", "data", "result")) {
            response[key].objSafe()?.get("context_token")?.strSafe()?.let { return it }
        }
        return null
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

    private companion object {
        // 媒体发送常量（对齐 weixin_client.py）。
        const val WEIXIN_MEDIA_TYPE_IMAGE = 1
        const val WEIXIN_MEDIA_TYPE_FILE = 3
        const val WEIXIN_MSG_ITEM_IMAGE = 2
        const val WEIXIN_MSG_ITEM_FILE = 4
        const val WEIXIN_CDN_BASE_URL = "https://novac2c.cdn.weixin.qq.com/c2c"
        const val WEIXIN_CDN_HOST = "novac2c.cdn.weixin.qq.com"

        val OCTET_STREAM_MEDIA = "application/octet-stream".toMediaType()
        val SECURE_RANDOM = SecureRandom()
    }
}

private fun kotlinx.serialization.json.JsonElement?.strSafe(): String? =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

private fun kotlinx.serialization.json.JsonElement?.longSafe(): Long? =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull

private fun kotlinx.serialization.json.JsonElement?.objSafe(): JsonObject? = this as? JsonObject