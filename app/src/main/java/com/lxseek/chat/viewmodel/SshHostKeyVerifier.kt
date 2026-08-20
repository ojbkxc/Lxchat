package com.lxseek.chat.viewmodel

import com.lxseek.chat.util.SshClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class HostKeyCaptureSession(
    val probe: suspend () -> Unit,
    val capturedHostKey: () -> String?,
    val close: () -> Unit,
)

/**
 * Connects in capture mode and returns the server host key with its SHA-256 fingerprint for user
 * review and pinning. The key exchange precedes authentication, so a bad password may still yield
 * a valid key. This verifier owns no saved device state.
 */
internal class SshHostKeyVerifier(
    private val createSession: (
        host: String,
        port: Int,
        user: String,
        password: String,
    ) -> HostKeyCaptureSession = ::createHostKeyCaptureSession,
    private val fingerprint: (String) -> String = SshClient::fingerprintSha256,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun verify(
        host: String,
        port: Int,
        user: String,
        password: String,
    ): Result<Pair<String, String>> = withContext(ioDispatcher) {
        if (host.isBlank()) return@withContext Result.failure(Exception("Host is empty"))
        val session = createSession(host, port, user.ifBlank { "root" }, password)
        try {
            try {
                session.probe()
            } catch (_: Exception) {
                // Authentication may fail after the handshake; the presented host key is still valid.
            }
        } finally {
            session.close()
        }
        val key = session.capturedHostKey()
        if (key.isNullOrBlank()) {
            Result.failure(Exception("Could not reach host or no host key presented"))
        } else {
            Result.success(key to fingerprint(key))
        }
    }
}

private fun createHostKeyCaptureSession(
    host: String,
    port: Int,
    user: String,
    password: String,
): HostKeyCaptureSession {
    val client = SshClient(
        host = host,
        port = port,
        user = user,
        password = password,
        pinnedHostKey = "",
        allowUnknownHostKey = true,
    )
    return HostKeyCaptureSession(
        probe = { client.executeCommand("true") },
        capturedHostKey = { client.capturedHostKey },
        close = client::close,
    )
}
