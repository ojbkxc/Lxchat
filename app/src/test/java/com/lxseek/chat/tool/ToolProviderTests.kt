package com.lxseek.chat.tool

import com.lxseek.chat.viewmodel.GenerationContext
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class WebSearchToolProviderTest {
    private val provider = WebSearchToolProvider()
    private val enabledCtx = GenerationContext(webSearchEnabled = true)
    private val disabledCtx = GenerationContext(webSearchEnabled = false)

    @Test
    fun definitions_whenEnabled_returnsTwoTools() {
        val defs = provider.definitions(enabledCtx)
        assertEquals(2, defs.size)
        assertEquals("web_search", defs[0].function.name)
        assertEquals("web_fetch", defs[1].function.name)
    }

    @Test
    fun definitions_whenDisabled_returnsEmpty() {
        assertTrue(provider.definitions(disabledCtx).isEmpty())
    }

    @Test
    fun handles_returnsTrueForWebTools() {
        assertTrue(provider.handles("web_search"))
        assertTrue(provider.handles("web_fetch"))
        assertFalse(provider.handles("unknown"))
    }
}

class RagToolProviderTest {
    private val provider = RagToolProvider(mockk(relaxed = true))
    private val enabledCtx = GenerationContext(accessPastConversations = true)
    private val disabledCtx = GenerationContext(accessPastConversations = false)

    @Test
    fun definitions_whenEnabled_returnsThreeTools() {
        val defs = provider.definitions(enabledCtx)
        assertEquals(3, defs.size)
        assertEquals("search_conversations", defs[0].function.name)
        assertEquals("list_conversations", defs[1].function.name)
        assertEquals("read_conversation", defs[2].function.name)
    }

    @Test
    fun definitions_whenDisabled_returnsEmpty() {
        assertTrue(provider.definitions(disabledCtx).isEmpty())
    }

    @Test
    fun handles_returnsTrueForConversationTools() {
        // RagToolProvider now owns execution of the conversation tools.
        assertTrue(provider.handles("search_conversations"))
        assertTrue(provider.handles("list_conversations"))
        assertTrue(provider.handles("read_conversation"))
        assertFalse(provider.handles("web_search"))
    }
}

class ShellToolProviderTest {
    private val provider = ShellToolProvider()
    private val emptyCtx = GenerationContext(shellEnabled = false)
    private val disabledCtx = GenerationContext(shellEnabled = true, shellDevices = emptyList())

    @Test
    fun definitions_whenDisabled_returnsEmpty() {
        assertTrue(provider.definitions(emptyCtx).isEmpty())
        assertTrue(provider.definitions(disabledCtx).isEmpty())
    }

    @Test
    fun definitions_whenSingleDevice_shellAndFileTools() {
        val ctx = GenerationContext(
            shellEnabled = true,
            shellDevices = listOf(
                com.lxseek.chat.data.ShellDeviceConfig(name = "server1", serverUrl = "http://localhost")
            )
        )
        val defs = provider.definitions(ctx)
        assertEquals(12, defs.size)
        val names = defs.map { it.function.name }.toSet()
        assertEquals(
            setOf(
                "list_shells",
                "execute_shell_command",
                "list_shell_jobs",
                "get_shell_job",
                "wait_for_job",
                "stop_shell_job",
                "file_read",
                "file_write",
                "file_edit",
                "file_glob",
                "file_grep",
                "view_image",
            ),
            names,
        )
        val command = defs.single { it.function.name == "execute_shell_command" }
        assertTrue(command.function.parameters.properties.containsKey("background"))
        val getJob = defs.single { it.function.name == "get_shell_job" }
        assertEquals(listOf("job_id"), getJob.function.parameters.required)
    }

    @Test
    fun definitions_whenMultiDevice_commandRequiresServer() {
        val ctx = GenerationContext(
            shellEnabled = true,
            shellDevices = listOf(
                com.lxseek.chat.data.ShellDeviceConfig(name = "s1", serverUrl = "http://a"),
                com.lxseek.chat.data.ShellDeviceConfig(name = "s2", serverUrl = "http://b")
            )
        )
        val defs = provider.definitions(ctx)
        val cmdTool = defs.find { it.function.name == "execute_shell_command" }
        assertNotNull(cmdTool)
        assertEquals(
            listOf("command", "server", "timeout_ms"),
            cmdTool!!.function.parameters.required,
        )
    }

    @Test
    fun definitions_singleDevice_commandRequiresTimeout() {
        val ctx = GenerationContext(
            shellEnabled = true,
            shellDevices = listOf(
                com.lxseek.chat.data.ShellDeviceConfig(name = "s1", serverUrl = "http://a"),
            ),
        )
        val defs = provider.definitions(ctx)
        val cmdTool = defs.single { it.function.name == "execute_shell_command" }
        assertEquals(listOf("command", "timeout_ms"), cmdTool.function.parameters.required)
        assertTrue(
            cmdTool.function.parameters.properties.getValue("timeout_ms").description
                .contains("never killed or restarted")
        )
        val waitTool = defs.single { it.function.name == "wait_for_job" }
        assertTrue(waitTool.function.parameters.required.contains("timeout_ms"))
        assertTrue(waitTool.function.parameters.required.contains("job_id"))
        assertTrue(
            waitTool.function.parameters.properties.getValue("timeout_ms").description
                .contains("295000ms")
        )
        assertEquals(295_000, ShellToolProvider.maxWaitMs(ctx))
    }

    @Test
    fun executeShellCommand_missingTimeout_returnsError() = kotlinx.coroutines.test.runTest {
        val ctx = GenerationContext(
            shellEnabled = true,
            shellDevices = listOf(
                com.lxseek.chat.data.ShellDeviceConfig(name = "s1", serverUrl = "http://a"),
            ),
        )
        val result = provider.execute(
            "execute_shell_command", """{"command":"echo hi","server":"s1"}""", ctx,
        )
        assertTrue(result.contains("timeout_ms is required"))
    }

    @Test
    fun handles_returnsTrueForShellAndFileTools() {
        assertTrue(provider.handles("list_shells"))
        assertTrue(provider.handles("execute_shell_command"))
        assertTrue(provider.handles("list_shell_jobs"))
        assertTrue(provider.handles("get_shell_job"))
        assertTrue(provider.handles("wait_for_job"))
        assertTrue(provider.handles("stop_shell_job"))
        assertTrue(provider.handles("file_read"))
        assertTrue(provider.handles("file_write"))
        assertTrue(provider.handles("file_edit"))
        assertTrue(provider.handles("file_glob"))
        assertTrue(provider.handles("file_grep"))
        assertTrue(provider.handles("view_image"))
        assertFalse(provider.handles("unknown"))
    }
}
