package com.newoether.agora.viewmodel

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.tool.ToolExecutionEvent
import com.newoether.agora.tool.ToolProvider
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationToolExecutorTest {
    @Test
    fun `completed result retains the authorized batch and call identities`() = runTest {
        val provider = FakeToolProvider()
        val executor = GenerationToolExecutor.forTest(listOf(provider))
        val events = mutableListOf<ToolExecutionEvent>()

        val executed = executor.execute(
            call = AuthorizedToolCall(
                batchIdentity = BATCH_IDENTITY,
                callId = "call-1",
                name = "known_tool",
                arguments = "{}",
                context = GenerationContext(),
                authorizedToolNames = setOf("known_tool"),
            ),
            onEvent = events::add,
        )

        assertEquals(BATCH_IDENTITY, executed.batchIdentity)
        assertEquals("call-1", executed.callId)
        assertEquals("done", executed.result.text)
        assertEquals(1, provider.executionCount)
        assertTrue(events.single() is ToolExecutionEvent.Completed)
    }

    @Test
    fun `incomplete arguments fail before provider execution and retain identity`() = runTest {
        val provider = FakeToolProvider()
        val executor = GenerationToolExecutor.forTest(listOf(provider))

        val executed = executor.execute(
            call = AuthorizedToolCall(
                batchIdentity = BATCH_IDENTITY,
                callId = "call-invalid",
                name = "known_tool",
                arguments = "{",
                context = GenerationContext(),
                authorizedToolNames = setOf("known_tool"),
            ),
            onEvent = {},
        )

        assertEquals(BATCH_IDENTITY, executed.batchIdentity)
        assertEquals("call-invalid", executed.callId)
        assertTrue(executed.result.isError)
        assertTrue(executed.result.text.contains("complete JSON object"))
        assertEquals(0, provider.executionCount)
    }

    @Test
    fun `call omitted from the frozen definition set never reaches a matching provider`() = runTest {
        val provider = FakeToolProvider()
        val executor = GenerationToolExecutor.forTest(listOf(provider))

        val executed = executor.execute(
            call = AuthorizedToolCall(
                batchIdentity = BATCH_IDENTITY,
                callId = "call-disabled",
                name = "known_tool",
                arguments = "{}",
                context = GenerationContext(),
                authorizedToolNames = emptySet(),
            ),
            onEvent = {},
        )

        assertTrue(executed.result.isError)
        assertTrue(executed.result.text.contains("not authorized"))
        assertEquals(0, provider.executionCount)
    }

    @Test
    fun unauthorizedToolErrorNamesAskModeOnlyWhenActive() = runTest {
        val provider = FakeToolProvider()
        val executor = GenerationToolExecutor.forTest(listOf(provider))
        data class Case(val name: String, val askMode: Boolean, val contains: String, val absent: String)
        listOf(
            Case("off", askMode = false, contains = "not authorized", absent = "Ask mode"),
            Case("on", askMode = true, contains = "Ask mode", absent = "not authorized"),
        ).forEach { case ->
            val executed = executor.execute(
                call = AuthorizedToolCall(
                    batchIdentity = BATCH_IDENTITY,
                    callId = "call-${case.name}",
                    name = "known_tool",
                    arguments = "{}",
                    context = GenerationContext(askMode = case.askMode),
                    authorizedToolNames = emptySet(),
                ),
                onEvent = {},
            )
            assertTrue(case.name, executed.result.isError)
            assertTrue(case.name, case.contains in executed.result.text)
            assertTrue(case.name, case.absent !in executed.result.text)
            if (case.askMode) assertTrue(case.name, "Do not retry" in executed.result.text)
        }
        assertEquals(0, provider.executionCount)
    }

    @Test
    fun `tool timeout is a recoverable identified result`() = runTest {
        val provider = object : ToolProvider {
            override fun definitions(ctx: GenerationContext): List<ToolDefinition> = emptyList()

            override suspend fun execute(
                name: String,
                arguments: String,
                ctx: GenerationContext,
            ): String = awaitCancellation()

            override fun handles(name: String): Boolean = true
        }
        val executor = GenerationToolExecutor.forTest(listOf(provider))

        val executed = executor.execute(
            call = AuthorizedToolCall(
                batchIdentity = BATCH_IDENTITY,
                callId = "call-timeout",
                name = "blocking_tool",
                arguments = "{}",
                context = GenerationContext(toolTimeoutMs = 25L),
                authorizedToolNames = setOf("blocking_tool"),
            ),
            onEvent = {},
        )

        assertEquals(BATCH_IDENTITY, executed.batchIdentity)
        assertEquals("call-timeout", executed.callId)
        assertTrue(executed.result.isError)
        assertTrue(executed.result.text.contains("timed out after 25ms"))
    }

    @Test
    fun askModeOmitsWriteToolsButKeepsCommands() {
        fun def(name: String) = ToolDefinition(
            function = ToolFunction(
                name = name,
                description = name,
                parameters = ToolParameters(properties = emptyMap()),
            ),
        )
        val provider = object : ToolProvider {
            override fun definitions(ctx: GenerationContext): List<ToolDefinition> = listOf(
                def("read_local_file"),
                def("write_local_file"),
                def("execute_local_command"),
                def("mcp_fs_write_aaaaaa"),
            )
            override suspend fun execute(name: String, arguments: String, ctx: GenerationContext) = ""
            override fun handles(name: String) = true
        }
        val executor = GenerationToolExecutor.forTest(listOf(provider))
        val names = executor.definitions(GenerationContext(askMode = true)).map { it.function.name }
        assertEquals(listOf("read_local_file", "execute_local_command"), names)
        assertEquals(
            4,
            executor.definitions(GenerationContext(askMode = false)).size,
        )
    }

    private class FakeToolProvider : ToolProvider {
        var executionCount = 0

        override fun definitions(ctx: GenerationContext): List<ToolDefinition> = emptyList()

        override suspend fun execute(
            name: String,
            arguments: String,
            ctx: GenerationContext,
        ): String {
            executionCount++
            return "done"
        }

        override fun handles(name: String): Boolean = name == "known_tool"
    }

    private companion object {
        val BATCH_IDENTITY = RunEffectIdentity(
            conversationId = "conversation",
            ownerToken = 1L,
            runId = "run",
            pass = 2,
            effectId = "tool-batch",
        )
    }
}
