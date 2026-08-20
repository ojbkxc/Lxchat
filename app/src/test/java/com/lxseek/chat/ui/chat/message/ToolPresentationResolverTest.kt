package com.lxseek.chat.ui.chat.message

import com.lxseek.chat.model.MessageSegment
import com.lxseek.chat.model.ToolExecutionStates
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPresentationResolverTest {
    @Test
    fun shellCommandSummary_isAlwaysSingleLineAndBounded() {
        val command = "  echo first\r\n   &&   echo second  "

        assertEquals(
            "echo first && echo second",
            singleLineShellCommand(command),
        )
        assertEquals(null, singleLineShellCommand(" \n\t "))
        assertEquals("12345", singleLineShellCommand("123456789", maxCharacters = 5))
    }

    @Test
    fun unfinishedArgumentsExposeProgressiveSubjectsAcrossToolKinds() {
        val cases = listOf(
            Triple("execute_shell_command", """{"command":"cp /tmp/sour""", "cp /tmp/sour"),
            Triple("read_memory_file", """{"name":"project-no""", "project-no"),
            Triple("web_search", """{"query":"compose stream""", "compose stream"),
            Triple("web_fetch", """{"url":"https://exam""", "https://exam"),
            Triple("search_conversations", """{"query":"old deci""", "old deci"),
            Triple("read_conversation", """{"conversation_id":"conv-12""", "conv-12"),
            Triple("get_shell_job", """{"job_id":"job-45""", "job-45"),
            Triple("file_write", """{"path":"/tmp/progr""", "/tmp/progr"),
            Triple("view_image", """{"path":"/tmp/previ""", "/tmp/previ"),
            Triple("file_glob", """{"pattern":"**/*.k""", "**/*.k"),
            Triple("generate_image", """{"prompt":"blue mount""", "blue mount"),
            Triple("create_task", """{"name":"nightly ba""", "nightly ba"),
            Triple("delete_task", """{"id_or_name":"nightly ba""", "nightly ba"),
        )

        cases.forEach { (toolName, partialArguments, expectedSubject) ->
            val presentation = ToolPresentationResolver.resolve(
                MessageSegment(
                    type = "tool",
                    toolName = toolName,
                    toolArgs = partialArguments,
                    toolState = ToolExecutionStates.CALLING,
                ),
            )

            assertEquals(toolName, expectedSubject, presentation.subject)
            assertEquals(ToolPresentationState.CALLING, presentation.state)
        }
    }

    @Test
    fun unfinishedCommandDecodesEscapesAndKeepsGrowing() {
        val first = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolArgs = """{"command":"printf \"hel""",
                toolState = ToolExecutionStates.CALLING,
            ),
        )
        val second = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolArgs = """{"command":"printf \"hello\"""",
                toolState = ToolExecutionStates.CALLING,
            ),
        )

        assertEquals("printf \"hel", first.subject)
        assertEquals("printf \"hello\"", second.subject)
    }

    @Test
    fun missingArgumentsDoNotInventAToolNameAsSubject() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolArgs = """{"command":""",
                toolState = ToolExecutionStates.CALLING,
            ),
        )

        assertEquals(null, presentation.subject)
    }

    @Test
    fun emptyGlobJsonIsZeroFilesNotOneLine() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "file_glob",
                toolArgs = """{"pattern":"*.kt"}""",
                toolResult = """{"type":"file_glob","files":[]}""",
                toolState = ToolExecutionStates.SUCCEEDED,
            ),
        )

        assertEquals(0, presentation.count)
        assertEquals(ToolPresentationState.EMPTY, presentation.state)
    }

    @Test
    fun emptyGrepJsonIsZeroMatchesNotOneLine() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "file_grep",
                toolArgs = """{"pattern":"missing"}""",
                toolResult = """{"type":"file_grep","matches":[]}""",
            ),
        )

        assertEquals(0, presentation.count)
        assertEquals(ToolPresentationState.EMPTY, presentation.state)
    }

    @Test
    fun nullResultAndLiveOutputAreRunning() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolResult = null,
                toolState = ToolExecutionStates.RUNNING,
                toolProgress = "line one\n",
                toolTarget = "tinybox",
            ),
        )

        assertEquals(ToolPresentationState.RUNNING, presentation.state)
        assertEquals("line one\n", presentation.liveOutput)
        assertEquals("tinybox", presentation.device)
    }

    @Test
    fun backgroundJobRemainsActiveAfterToolCallReturns() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolResult = """{"background":true,"job_id":"abc","state":"running"}""",
            ),
        )

        assertEquals(ToolPresentationState.BACKGROUND_RUNNING, presentation.state)
        assertEquals("abc", presentation.jobId)
        assertTrue(presentation.isActive)
    }

    @Test
    fun structuredErrorUsesServerMessage() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolResult = """{"error":"error","message":"Cannot connect to Conch: refused"}""",
            ),
        )

        assertEquals(ToolPresentationState.FAILED, presentation.state)
        assertEquals("Cannot connect to Conch: refused", presentation.errorMessage)
    }

    @Test
    fun providerNoResultsCodeIsAnEmptySuccess() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "web_search",
                toolArgs = """{"query":"nothing"}""",
                toolResult = """{"type":"web_search","query":"nothing","error":"no_results"}""",
                toolState = ToolExecutionStates.FAILED,
            ),
        )

        assertEquals(ToolPresentationState.EMPTY, presentation.state)
        assertEquals(null, presentation.errorMessage)
    }

    @Test
    fun structuredErrorWithoutMessageStillFailsWithSpecificCode() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "web_search",
                toolResult = """{"type":"web_search","error":"no_api_key"}""",
            ),
        )

        assertEquals(ToolPresentationState.FAILED, presentation.state)
        assertEquals("no api key", presentation.errorMessage)
    }

    @Test
    fun shellOutputLengthExcludesJsonEnvelope() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolResult = """{"type":"execute_shell_command","exit_code":0,"output":"abc"}""",
            ),
        )

        assertEquals(3, presentation.outputLength)
    }

    @Test
    fun successfulShellWithoutOutputIsStillSucceeded() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolResult = """{"type":"execute_shell_command","exit_code":0,"output":""}""",
            ),
        )

        assertEquals(ToolPresentationState.COMPLETED, presentation.state)
        assertEquals(0, presentation.exitCode)
    }

    @Test
    fun nonZeroShellExitIsFailed() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolResult = """{"type":"execute_shell_command","exit_code":127,"output":"not found"}""",
            ),
        )

        assertEquals(ToolPresentationState.FAILED, presentation.state)
        assertEquals(127, presentation.exitCode)
    }

    @Test
    fun nonZeroShellExitOverridesStaleSucceededWireState() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolState = ToolExecutionStates.SUCCEEDED,
                toolResult = """{"type":"execute_shell_command","exit_code":2,"output":"bad arguments"}""",
            ),
        )

        assertEquals(ToolPresentationState.FAILED, presentation.state)
        assertEquals(2, presentation.exitCode)
    }

    @Test
    fun completedShellResultUsesAuthoritativeServerAndOutput() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolArgs = """{"server":"requested"}""",
                toolResult = """
                    {"type":"execute_shell_command","server":"actual","exit_code":0,"output":"done"}
                """.trimIndent(),
                toolTarget = "resolved",
            ),
        )

        assertEquals("actual", presentation.device)
        assertEquals("done", shellOutputText(presentation))
    }

    @Test
    fun durableForegroundShellUnwrapsTerminalExitAndOutput() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolArgs = """{"command":"printf done"}""",
                toolResult = """
                    {
                      "type":"execute_shell_command",
                      "server":"conch",
                      "job_id":"job-1",
                      "result":{"state":"succeeded","exit_code":0,"output":"done"}
                    }
                """.trimIndent(),
            ),
        )

        assertEquals(ToolPresentationState.COMPLETED, presentation.state)
        assertEquals(ShellPresentationStatus.Exit(0), shellPresentationStatus(presentation))
        assertEquals(0, presentation.exitCode)
        assertEquals("done", shellOutputText(presentation))
        assertEquals("conch", presentation.device)
        assertEquals("job-1", presentation.jobId)
    }

    @Test
    fun shellPresentationHasOnlyExecutingOrExitStates() {
        val running = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolState = ToolExecutionStates.RUNNING,
                toolProgress = "partial",
            ),
        )
        val terminalWithoutCode = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolResult = """{"type":"execute_shell_command","output":"done"}""",
            ),
        )

        assertEquals(ShellPresentationStatus.Executing, shellPresentationStatus(running))
        assertEquals(
            ShellPresentationStatus.Exit(code = null),
            shellPresentationStatus(terminalWithoutCode),
        )
        assertEquals("done", shellOutputText(terminalWithoutCode))
    }

    @Test
    fun shellOutputFallsBackToSeparateStdoutAndStderr() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolResult = """
                    {"type":"execute_shell_command","exit_code":2,"stdout":"out","stderr":"err"}
                """.trimIndent(),
            ),
        )

        assertEquals("out\nerr", shellOutputText(presentation))
        assertEquals(ShellPresentationStatus.Exit(2), shellPresentationStatus(presentation))
    }

    @Test
    fun legacyConnectingProgressIsNotRenderedAsCommandOutput() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "execute_shell_command",
                toolState = ToolExecutionStates.RUNNING,
                toolProgress = "Connecting to tinybox",
                toolTarget = "tinybox",
            ),
        )

        assertEquals(null, shellOutputText(presentation))
    }

    @Test
    fun mcpTitleUsesResolvedNameAndLegacyFallbackNeverLeaksRoutingParts() {
        assertEquals(
            "Read Image",
            mcpToolDisplayName(
                publicName = "mcp_server123_read_image_a1b2c3",
                resolvedName = "read_image",
            ),
        )
        assertEquals(
            "Read Image",
            mcpToolDisplayName(
                publicName = "mcp_server123_read_image_a1b2c3",
                resolvedName = null,
            ),
        )
        assertNull(
            mcpToolDisplayName(
                publicName = "mcp_server123_read_image",
                resolvedName = null,
            ),
        )
    }

    @Test
    fun mcpStructuredResultIsParsedIndependentlyFromProtocolText() {
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = "mcp_server123_inspect_a1b2c3",
                toolResult = """Human summary

                    {"value":7}
                """.trimIndent(),
                toolResultText = "Human summary",
                toolStructuredResult = """{"value":7}""",
                toolTarget = "Filesystem",
                toolState = ToolExecutionStates.SUCCEEDED,
            ),
        )

        assertEquals(ToolKind.MCP, presentation.kind)
        assertEquals(
            7,
            ((presentation.result as JsonObject)["value"] as JsonPrimitive).int,
        )
        assertEquals("Human summary", presentation.rawTextResult)
        assertEquals("""{"value":7}""", presentation.rawStructuredResult)
        assertEquals("Filesystem", presentation.device)
        assertEquals(ToolPresentationState.COMPLETED, presentation.state)
    }
}
