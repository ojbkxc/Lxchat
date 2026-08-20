package com.lxseek.chat.viewmodel

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionDiagnosticsTest {
    @Test
    fun blankSshHostFailsBeforeCreatingSession() = runTest {
        var created = false
        val verifier = SshHostKeyVerifier(
            createSession = { _, _, _, _ ->
                created = true
                error("unexpected")
            },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = verifier.verify(" ", 22, "user", "password")

        assertEquals("Host is empty", result.exceptionOrNull()?.message)
        assertFalse(created)
    }

    @Test
    fun sshProbeUsesRootDefaultReturnsFingerprintAndAlwaysCloses() = runTest {
        val calls = mutableListOf<String>()
        val verifier = SshHostKeyVerifier(
            createSession = { host, port, user, password ->
                calls += "$host:$port:$user:$password"
                HostKeyCaptureSession(
                    probe = { calls += "probe" },
                    capturedHostKey = { "key" },
                    close = { calls += "close" },
                )
            },
            fingerprint = { "fingerprint:$it" },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = verifier.verify("host", 2222, "", "secret")

        assertEquals("key" to "fingerprint:key", result.getOrNull())
        assertEquals(listOf("host:2222:root:secret", "probe", "close"), calls)
    }

    @Test
    fun sshAuthenticationFailureStillAcceptsCapturedKey() = runTest {
        var closed = false
        val verifier = SshHostKeyVerifier(
            createSession = { _, _, _, _ ->
                HostKeyCaptureSession(
                    probe = { throw IllegalStateException("auth") },
                    capturedHostKey = { "key" },
                    close = { closed = true },
                )
            },
            fingerprint = { "fingerprint" },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = verifier.verify("host", 22, "user", "bad")

        assertTrue(result.isSuccess)
        assertTrue(closed)
    }

    @Test
    fun missingSshKeyFailsAfterClosingSession() = runTest {
        var closed = false
        val verifier = SshHostKeyVerifier(
            createSession = { _, _, _, _ ->
                HostKeyCaptureSession(
                    probe = {},
                    capturedHostKey = { null },
                    close = { closed = true },
                )
            },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = verifier.verify("host", 22, "user", "password")

        assertEquals(
            "Could not reach host or no host key presented",
            result.exceptionOrNull()?.message,
        )
        assertTrue(closed)
    }

    @Test
    fun embeddingProbeUsesExplicitConnectionAndReportsDimension() = runTest {
        val calls = mutableListOf<List<String>>()
        val tester = RemoteEmbeddingConnectionTester(
            resolveApiKey = { error("explicit key must win") },
            resolveBaseUrl = { error("explicit URL must win") },
            computeEmbedding = { text, key, model, url ->
                calls += listOf(text, key, model, url)
                floatArrayOf(1f, 2f, 3f)
            },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = tester.test("model", "https://base", "key")

        assertEquals("OK (dim=3)", result)
        assertEquals(
            listOf(listOf("test connection", "key", "model", "https://base")),
            calls,
        )
    }

    @Test
    fun embeddingProbeUsesFallbacksAndPreservesFailureMessages() = runTest {
        var result: FloatArray? = null
        var failure: Exception? = null
        val calls = mutableListOf<Pair<String, String>>()
        val tester = RemoteEmbeddingConnectionTester(
            resolveApiKey = { "fallback-key" },
            resolveBaseUrl = { "fallback-url" },
            computeEmbedding = { _, key, _, url ->
                calls += key to url
                failure?.let { throw it }
                result
            },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        assertEquals(
            "Request failed. Check API key, URL, and model name.",
            tester.test("model", "", ""),
        )
        failure = IllegalStateException("network")
        assertEquals("network", tester.test("model", "", ""))
        failure = Exception()
        assertEquals("Error", tester.test("model", "", ""))
        assertEquals(
            listOf(
                "fallback-key" to "fallback-url",
                "fallback-key" to "fallback-url",
                "fallback-key" to "fallback-url",
            ),
            calls,
        )
    }
}
