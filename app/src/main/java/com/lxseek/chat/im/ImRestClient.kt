package com.lxseek.chat.im

import com.lxseek.chat.api.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Shared JSON instance for all IM channel code — every API client previously built its own
 * `Json { ignoreUnknownKeys = true }` (46 copies across the codebase). Thread-safe & immutable.
 */
val ImJson: Json = Json { ignoreUnknownKeys = true }

/** Unified REST exception for IM platform API clients ([ImRestClient]). */
class ImApiException(
    message: String,
    /** HTTP status code when the request reached the server, else null. */
    val httpCode: Int? = null,
    /** Platform-level error code (KOOK `code`, WeChat `errcode`, ...), else null. */
    val apiCode: Int? = null,
) : Exception(message)

/** Token shape shared by most platforms: non-blank after trimming. */
fun isValidImToken(value: String): Boolean = value.trim().isNotBlank()

/** Base-URL shape shared by self-hostable platforms (Mattermost/Misskey): http(s)://... */
fun isValidHttpBaseUrl(value: String): Boolean =
    value.trim().let { it.startsWith("http://") || it.startsWith("https://") }

/**
 * Minimal JSON-over-REST plumbing shared by the IM platform API clients. Each platform keeps
 * its own endpoint declarations; this base supplies the GET/POST + IO-dispatcher + error
 * mapping boilerplate that was previously copy-pasted per platform.
 *
 * [pathPrefix] is prepended to every [path] (e.g. `"api/v4"` for Mattermost). Response bodies
 * are parsed with [ImJson]; a non-JSON body yields an empty object instead of throwing.
 *
 * Error mapping is per-platform via [onError]: return an [ImApiException] to fail, or `null`
 * to accept the response (business-level codes like KOOK's `{code,data,message}` envelope
 * are checked there; HTTP-level failures are checked before it runs).
 */
open class ImRestClient(
    baseUrl: String,
    private val authHeaders: Map<String, String> = emptyMap(),
    private val pathPrefix: String = "",
    private val onError: (body: String, op: String, httpCode: Int) -> ImApiException? =
        { _, op, httpCode -> ImApiException("$op 失败 (HTTP $httpCode)") },
) {
    /** Trimmed base URL; protected so subclasses (e.g. WeixinMp token flow) can build raw URLs. */
    protected val base: String = baseUrl.trim().trimEnd('/')

    protected suspend fun get(path: String): JsonObject = withContext(Dispatchers.IO) {
        val op = "GET $path"
        val response = HttpClient.getTextResponse(url(path), authHeaders)
        handle(response.body, op, response.code, response.isSuccessful)
    }

    protected suspend fun post(path: String, payload: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val op = "POST $path"
        val response = HttpClient.postTextResponse(url(path), payload.toString(), authHeaders)
        handle(response.body, op, response.code, response.isSuccessful)
    }

    private fun url(path: String): String =
        if (pathPrefix.isEmpty()) "$base/$path" else "$base/$pathPrefix/$path"

    private fun handle(body: String, op: String, httpCode: Int, isSuccessful: Boolean): JsonObject {
        if (!isSuccessful) throw onError(body, op, httpCode)
            ?: ImApiException("$op 失败 (HTTP $httpCode)", httpCode)
        return runCatching { ImJson.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: JsonObject(emptyMap())
    }
}
