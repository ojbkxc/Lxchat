package com.lxseek.chat.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun describeConchConnectionFailure(serverUrl: String, error: Exception): String =
    describeConchRequestFailure(serverUrl, "public-key request", error)

internal fun describeConchRequestFailure(
    serverUrl: String,
    operation: String,
    error: Exception,
): String =
    when (error) {
        is java.net.UnknownHostException ->
            "Cannot resolve Conch host for $serverUrl: ${error.message ?: "unknown host"}"
        is java.net.ConnectException ->
            "Cannot connect to Conch at $serverUrl: ${error.message ?: "connection refused"}"
        is java.net.SocketTimeoutException ->
            "Conch $operation timed out at $serverUrl"
        is javax.net.ssl.SSLException ->
            "TLS connection to Conch at $serverUrl failed: ${error.message ?: "SSL error"}"
        else ->
            "Conch $operation to $serverUrl failed: ${error.message ?: error.javaClass.simpleName}"
    }

class ShellClient(
    private val serverUrl: String,
    private val apiKey: String,
    cachedPublicKey: String = ""
) {
    private var serverPublicKey: java.security.PublicKey? = null
    private var currentAesKey: ByteArray? = null
    private var currentKeyPair: java.security.KeyPair? = null
    var lastError: String? = null
        private set

    init {
        if (cachedPublicKey.isNotBlank()) {
            try {
                serverPublicKey = ShellCrypto.decodePublicKey(cachedPublicKey)
            } catch (_: Exception) {
                // Will fetch fresh
            }
        }
    }

    suspend fun fetchPublicKey(): Boolean {
        if (serverPublicKey != null) return true
        if (apiKey.isBlank()) {
            lastError = "Conch authentication is disabled locally; no public-key exchange is needed"
            return false
        }
        var rawResponse: String? = null
        return try {
            val response = com.lxseek.chat.api.HttpClient.getTextResponse(
                "$serverUrl/public-key",
                emptyMap()
            )
            rawResponse = response.body
            if (!response.isSuccessful) {
                val detail = response.body.take(240).ifBlank { "empty response" }
                lastError = "Conch at $serverUrl returned HTTP ${response.code}: $detail"
                DebugLog.e("ShellClient", lastError!!)
                return false
            }
            val json = Json.parseToJsonElement(rawResponse).jsonObject
            val pubKeyStr = json["public_key"]?.jsonPrimitive?.content
            val nonce = json["nonce"]?.jsonPrimitive?.content
            val sig = json["signature"]?.jsonPrimitive?.content
            if (pubKeyStr == null || nonce == null || sig == null) {
                lastError = "Invalid Conch public-key response from $serverUrl: missing public_key, nonce, or signature"
                DebugLog.e("ShellClient", "$lastError: $rawResponse")
                return false
            }
            if (!verifyPublicKeySignature(pubKeyStr, nonce, sig)) {
                lastError = "Conch authentication failed at $serverUrl: the public-key signature does not match the configured API key"
                DebugLog.e("ShellClient", lastError!!)
                return false
            }
            serverPublicKey = ShellCrypto.decodePublicKey(pubKeyStr)
            lastError = null
            true
        } catch (e: Exception) {
            lastError = describeConchConnectionFailure(serverUrl, e)
            DebugLog.w("ShellClient", lastError!!)
            false
        }
    }

    private fun verifyPublicKeySignature(pubKey: String, nonce: String, sig: String): Boolean {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(apiKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val message = "$nonce|$pubKey"
        val expected = mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return java.security.MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            sig.toByteArray(Charsets.UTF_8)
        )
    }

    fun getServerPublicKeyBase64(): String? {
        return serverPublicKey?.let { ShellCrypto.encodePublicKey(it) }
    }

    data class PreparedRequest(
        val body: String,
        val headers: Map<String, String>,
        val isEncrypted: Boolean,
        val serverUrl: String
    )

    fun prepareRequest(
        command: String,
        timeoutMs: Int,
        workdir: String
    ): PreparedRequest {
        val jsonBody = buildJsonBody(command, timeoutMs, workdir)

        if (apiKey.isBlank()) {
            return PreparedRequest(jsonBody, mapOf("Content-Type" to "application/json"), false, serverUrl)
        }

        val pubKey = serverPublicKey
            ?: throw IllegalStateException("Server public key not available. Call fetchPublicKey() first.")

        // Generate ephemeral key pair and derive AES key
        val ephemeralKP = ShellCrypto.generateEphemeralKeyPair()
        val aesKey = ShellCrypto.deriveAesKey(ephemeralKP.private, pubKey)
        currentAesKey = aesKey
        currentKeyPair = ephemeralKP

        // Encrypt body
        val encryptedBody = ShellCrypto.encrypt(aesKey, jsonBody.toByteArray(Charsets.UTF_8))
        val bodyBytes = encryptedBody.toByteArray(Charsets.UTF_8)
        val bodySha256 = ShellCrypto.sha256Hex(bodyBytes)
        val timestamp = System.currentTimeMillis() / 1000
        val nonce = ShellCrypto.generateNonce()
        val clientPubKey = ShellCrypto.encodePublicKey(ephemeralKP.public)
        val signature = ShellCrypto.sign(apiKey, timestamp, "POST", "/execute", bodySha256, nonce, clientPubKey)

        val headers = mapOf(
            "Content-Type" to "application/octet-stream",
            "X-Timestamp" to timestamp.toString(),
            "X-Signature" to signature,
            "X-Nonce" to nonce,
            "X-Encryption" to "v1",
            "X-Client-Public-Key" to clientPubKey
        )

        return PreparedRequest(encryptedBody, headers, true, serverUrl)
    }

    fun decryptSseData(encryptedData: String): String {
        val key = currentAesKey ?: throw IllegalStateException("No session key")
        return String(ShellCrypto.decrypt(key, encryptedData), Charsets.UTF_8)
    }

    fun getSessionKey(): ByteArray? = currentAesKey

    private fun buildJsonBody(command: String, timeoutMs: Int, workdir: String): String {
        return buildJsonObject {
            put("command", command)
            put("timeout_ms", timeoutMs)
            if (workdir.isNotBlank()) {
                put("workdir", workdir)
            }
        }.toString()
    }

    // --- File API ---

    data class FileReadResult(
        val content: String,
        val lines: Int,
        val totalLines: Int,
        val error: String? = null
    )

    data class FileImageResult(
        val data: String,
        val mimeType: String,
        val size: Long,
        val error: String? = null,
    )

    data class GrepMatch(
        val path: String,
        val line: Int,
        val content: String
    )

    private suspend fun encryptedPost(path: String, payload: String): String {
        if (apiKey.isBlank()) {
            val response = try {
                com.lxseek.chat.api.HttpClient.postTextResponse(
                    "$serverUrl$path",
                    payload,
                    mapOf("Content-Type" to "application/json"),
                )
            } catch (e: Exception) {
                throw IllegalStateException(
                    describeConchRequestFailure(serverUrl, "$path request", e),
                    e,
                )
            }
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "Conch at $serverUrl returned HTTP ${response.code}: " +
                        response.body.take(240).ifBlank { "empty response" },
                )
            }
            return response.body
        }
        // Lazily establish the encrypted session. The file endpoints need the server
        // public key just like /execute does, but (unlike executeCommand) nothing
        // pre-fetches it for the file tools — so fetch it here on first use.
        if (serverPublicKey == null && !fetchPublicKey()) {
            throw IllegalStateException(lastError ?: "Failed to fetch server public key")
        }
        val pubKey = serverPublicKey
            ?: throw IllegalStateException("Server public key not available. Call fetchPublicKey() first.")

        val ephemeralKP = ShellCrypto.generateEphemeralKeyPair()
        val aesKey = ShellCrypto.deriveAesKey(ephemeralKP.private, pubKey)
        val encryptedBody = ShellCrypto.encrypt(aesKey, payload.toByteArray(Charsets.UTF_8))
        val bodyBytes = encryptedBody.toByteArray(Charsets.UTF_8)
        val bodySha256 = ShellCrypto.sha256Hex(bodyBytes)
        val timestamp = System.currentTimeMillis() / 1000
        val nonce = ShellCrypto.generateNonce()
        val clientPubKey = ShellCrypto.encodePublicKey(ephemeralKP.public)
        val signature = ShellCrypto.sign(apiKey, timestamp, "POST", path, bodySha256, nonce, clientPubKey)

        val headers = mapOf(
            "Content-Type" to "application/octet-stream",
            "X-Timestamp" to timestamp.toString(),
            "X-Signature" to signature,
            "X-Nonce" to nonce,
            "X-Encryption" to "v1",
            "X-Client-Public-Key" to clientPubKey
        )

        val response = try {
            com.lxseek.chat.api.HttpClient.postTextResponse(
                "$serverUrl$path", encryptedBody, headers
            )
        } catch (e: Exception) {
            throw IllegalStateException(
                describeConchRequestFailure(serverUrl, "$path request", e),
                e,
            )
        }
        if (!response.isSuccessful) {
            throw IllegalStateException(
                "Conch at $serverUrl returned HTTP ${response.code}: " +
                    response.body.take(240).ifBlank { "empty response" },
            )
        }

        val plaintext = try {
            ShellCrypto.decrypt(aesKey, response.body)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Conch response decryption failed at $serverUrl: ${e.message}",
                e,
            )
        }
        return String(plaintext, Charsets.UTF_8)
    }

    private suspend fun filePost(path: String, payload: String): String =
        encryptedPost(path, payload)

    suspend fun fileRead(path: String, offset: Long = 0, limit: Long = 0): FileReadResult {
        val limitVal = if (limit > 0) limit else 1048576
        val payload = buildJsonBodyFileMixed(mapOf(
            "path" to path,
            "offset" to offset,
            "limit" to limitVal
        ))
        val jsonStr = filePost("/file/read", payload)
        val json = Json.parseToJsonElement(jsonStr).jsonObject
        val error = json["error"]?.jsonPrimitive?.content
        if (error != null) return FileReadResult("", 0, 0, error = error)
        return FileReadResult(
            content = json["content"]?.jsonPrimitive?.content ?: "",
            lines = json["lines"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            totalLines = json["totalLines"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        )
    }

    suspend fun fileImage(path: String): FileImageResult {
        val payload = buildJsonBodyFile(mapOf("path" to path))
        val jsonStr = filePost("/file/image", payload)
        val json = Json.parseToJsonElement(jsonStr).jsonObject
        val error = json["error"]?.jsonPrimitive?.content
        if (error != null) {
            return FileImageResult("", "", 0L, error = error)
        }
        return FileImageResult(
            data = json["data"]?.jsonPrimitive?.content.orEmpty(),
            mimeType = json["mimeType"]?.jsonPrimitive?.content.orEmpty(),
            size = json["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
        )
    }

    suspend fun fileWrite(path: String, content: String): String? {
        val payload = buildJsonBodyFile(mapOf(
            "path" to path,
            "content" to content
        ))
        val jsonStr = filePost("/file/write", payload)
        val json = Json.parseToJsonElement(jsonStr).jsonObject
        return json["error"]?.jsonPrimitive?.content
    }

    suspend fun fileGlob(pattern: String, basePath: String = "", depth: Int? = null): Result<List<String>> {
        val params = mutableMapOf<String, Any>("pattern" to pattern)
        if (basePath.isNotBlank()) params["path"] = basePath
        if (depth != null) params["depth"] = depth
        val payload = buildJsonBodyFileMixed(params)
        val jsonStr = filePost("/file/glob", payload)
        val json = Json.parseToJsonElement(jsonStr).jsonObject
        val error = json["error"]?.jsonPrimitive?.content
        if (error != null) return Result.failure(Exception(error))
        val files = json["files"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        return Result.success(files)
    }

    suspend fun fileGrep(pattern: String, basePath: String = "", fileGlob: String = ""): Result<List<GrepMatch>> {
        val params = mutableMapOf("pattern" to pattern)
        if (basePath.isNotBlank()) params["path"] = basePath
        if (fileGlob.isNotBlank()) params["glob"] = fileGlob
        val payload = buildJsonBodyFile(params)
        val jsonStr = filePost("/file/grep", payload)
        val json = Json.parseToJsonElement(jsonStr).jsonObject
        val error = json["error"]?.jsonPrimitive?.content
        if (error != null) return Result.failure(Exception(error))
        val matches = json["matches"]?.jsonArray?.map {
            val obj = it.jsonObject
            GrepMatch(
                path = obj["path"]?.jsonPrimitive?.content ?: "",
                line = obj["line"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                content = obj["content"]?.jsonPrimitive?.content ?: ""
            )
        } ?: emptyList()
        return Result.success(matches)
    }

    private fun buildJsonBodyFileMixed(params: Map<String, Any>): String {
        return buildJsonObject {
            for ((key, value) in params) {
                when (value) {
                    is Long -> put(key, value)
                    is Int -> put(key, value)
                    else -> put(key, value.toString())
                }
            }
        }.toString()
    }

    private fun buildJsonBodyFile(params: Map<String, String>): String {
        return buildJsonObject {
            for ((key, value) in params) {
                put(key, value)
            }
        }.toString()
    }

    suspend fun startJob(
        command: String,
        timeoutMs: Int,
        workdir: String,
    ): String = encryptedPost(
        "/jobs/start",
        buildJsonBody(command, timeoutMs, workdir),
    )

    suspend fun listJobs(): String =
        encryptedPost("/jobs/list", "{}")

    suspend fun getJob(jobId: String): String =
        encryptedPost("/jobs/get", buildJsonObject { put("job_id", jobId) }.toString())

    suspend fun stopJob(jobId: String): String =
        encryptedPost("/jobs/stop", buildJsonObject { put("job_id", jobId) }.toString())

}
