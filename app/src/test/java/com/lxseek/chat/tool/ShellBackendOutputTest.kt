package com.lxseek.chat.tool

import com.lxseek.chat.sandbox.SandboxManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ShellBackendOutputTest {
    @Test
    fun sandboxResultKeepsOutputAndExitCodeWithoutSuccessState() = runTest {
        val manager = mockk<SandboxManager>()
        coEvery { manager.isAvailable() } returns true
        coEvery { manager.executeCommand("command", "/work", 5_000) } returns
            SandboxManager.SandboxResult(
                stdout = "stdout",
                stderr = "stderr",
                exitCode = 7,
            )

        val raw = SandboxBackend(manager).executeCommand("command", "/work", 5_000)
        val result = Json.parseToJsonElement(raw).jsonObject

        assertEquals("7", (result["exit_code"] as JsonPrimitive).content)
        assertEquals("stdout\nstderr", (result["output"] as JsonPrimitive).content)
        assertFalse(result.containsKey("success"))
    }
}
