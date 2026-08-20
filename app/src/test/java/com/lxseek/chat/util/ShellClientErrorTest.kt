package com.lxseek.chat.util

import org.junit.Assert.assertTrue
import org.junit.Test

class ShellClientErrorTest {
    @Test
    fun connectionRefusedIsNotReportedAsEncryptionFailure() {
        val message = describeConchConnectionFailure(
            "http://oneplus:14216",
            java.net.ConnectException("Connection refused"),
        )

        assertTrue(message.contains("Cannot connect to Conch"))
        assertTrue(message.contains("Connection refused"))
        assertTrue(!message.contains("encryption", ignoreCase = true))
    }

    @Test
    fun unknownHostNamesTheResolutionFailure() {
        val message = describeConchConnectionFailure(
            "http://missing:14216",
            java.net.UnknownHostException("missing"),
        )

        assertTrue(message.contains("Cannot resolve Conch host"))
        assertTrue(message.contains("missing"))
    }

    @Test
    fun jobTimeoutNamesTheOperationInsteadOfEncryption() {
        val message = describeConchRequestFailure(
            "http://oneplus:14216",
            "/jobs/get request",
            java.net.SocketTimeoutException("timeout"),
        )

        assertTrue(message.contains("/jobs/get request timed out"))
        assertTrue(!message.contains("encryption", ignoreCase = true))
    }
}
