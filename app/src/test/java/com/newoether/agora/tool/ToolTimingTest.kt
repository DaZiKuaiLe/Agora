package com.newoether.agora.tool

import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.ToolExecutionStates
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolTimingTest {

    @Test
    fun format_and_lines_and_completedExec_followConnectThenCommand() {
        data class FormatCase(val name: String, val ms: Long, val text: String)
        listOf(
            FormatCase("sub-second", 320L, "320ms"),
            FormatCase("one decimal", 1_200L, "1.2s"),
            FormatCase("tens of seconds", 45_000L, "45s"),
            FormatCase("minutes padded", 65_000L, "1m 05s"),
        ).forEach { case ->
            assertEquals(case.name, case.text, ToolTiming.format(case.ms))
        }

        data class LineCase(
            val name: String,
            val toolName: String,
            val connectMs: Long?,
            val execMs: Long?,
            val state: String?,
            val kinds: List<ToolTiming.Kind>,
            val pending: List<Boolean>,
        )
        listOf(
            LineCase(
                "ssh connecting",
                "execute_remote_command",
                null,
                null,
                ToolExecutionStates.RUNNING,
                listOf(ToolTiming.Kind.CONNECT),
                listOf(true),
            ),
            LineCase(
                "ssh running command",
                "read_remote_file",
                320L,
                null,
                ToolExecutionStates.RUNNING,
                listOf(ToolTiming.Kind.CONNECT, ToolTiming.Kind.COMMAND),
                listOf(false, true),
            ),
            LineCase(
                "start still running hides command pending",
                "start_remote_process",
                210L,
                null,
                ToolExecutionStates.BACKGROUND_RUNNING,
                listOf(ToolTiming.Kind.CONNECT),
                listOf(false),
            ),
            LineCase(
                "ssh after command",
                "execute_remote_command",
                320L,
                1_200L,
                ToolExecutionStates.SUCCEEDED,
                listOf(ToolTiming.Kind.CONNECT, ToolTiming.Kind.COMMAND),
                listOf(false, false),
            ),
            LineCase(
                "local running",
                "execute_local_command",
                null,
                null,
                ToolExecutionStates.RUNNING,
                listOf(ToolTiming.Kind.DURATION),
                listOf(true),
            ),
            LineCase(
                "local tool",
                "execute_local_command",
                null,
                800L,
                ToolExecutionStates.SUCCEEDED,
                listOf(ToolTiming.Kind.DURATION),
                listOf(false),
            ),
            LineCase(
                "wait is not ssh connect",
                "wait_process",
                null,
                5_000L,
                ToolExecutionStates.SUCCEEDED,
                listOf(ToolTiming.Kind.DURATION),
                listOf(false),
            ),
        ).forEach { case ->
            val lines = ToolTiming.lines(
                MessageSegment(
                    type = "tool",
                    toolName = case.toolName,
                    toolState = case.state,
                    toolConnectMs = case.connectMs,
                    toolExecMs = case.execMs,
                ),
            )
            assertEquals(case.name, case.kinds, lines.map { it.kind })
            assertEquals(case.name, case.pending, lines.map { it.pending })
        }

        data class CompleteCase(
            val name: String,
            val toolName: String,
            val currentExecMs: Long?,
            val startedAtMs: Long?,
            val nowMs: Long,
            val state: String,
            val expected: Long?,
        )
        listOf(
            CompleteCase(
                "keep emitted exec",
                "execute_remote_command",
                400L,
                1_000L,
                9_000L,
                ToolExecutionStates.SUCCEEDED,
                400L,
            ),
            CompleteCase(
                "start still running hides exec",
                "start_remote_process",
                null,
                1_000L,
                9_000L,
                ToolExecutionStates.BACKGROUND_RUNNING,
                null,
            ),
            CompleteCase(
                "non-ssh fills from start",
                "grep_local",
                null,
                1_000L,
                1_800L,
                ToolExecutionStates.SUCCEEDED,
                800L,
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                ToolTiming.completedExecMs(
                    toolName = case.toolName,
                    currentExecMs = case.currentExecMs,
                    startedAtMs = case.startedAtMs,
                    nowMs = case.nowMs,
                    finalState = case.state,
                ),
            )
        }
        assertNull(
            ToolTiming.completedExecMs(
                toolName = "read_local_file",
                currentExecMs = null,
                startedAtMs = null,
                nowMs = 10L,
                finalState = ToolExecutionStates.SUCCEEDED,
            ),
        )

        data class AttachCase(
            val name: String,
            val toolName: String,
            val text: String,
            val connectMs: Long?,
            val commandMs: Long?,
            val connect: Long?,
            val command: Long?,
            val unchanged: Boolean = false,
        )
        listOf(
            AttachCase(
                "ssh both times",
                "execute_remote_command",
                """{"ok":true}""",
                320L,
                1_200L,
                320L,
                1_200L,
            ),
            AttachCase(
                "start still running omits command",
                "start_remote_process",
                """{"running":true}""",
                210L,
                null,
                210L,
                null,
            ),
            AttachCase(
                "local stays unchanged",
                "execute_local_command",
                """{"ok":true}""",
                null,
                800L,
                null,
                null,
                unchanged = true,
            ),
            AttachCase(
                "plain text stays unchanged",
                "download_file",
                "not-json",
                100L,
                200L,
                null,
                null,
                unchanged = true,
            ),
        ).forEach { case ->
            val attached = ToolTiming.attachToResult(case.text, case.toolName, case.connectMs, case.commandMs)
            if (case.unchanged) {
                assertEquals(case.name, case.text, attached)
                return@forEach
            }
            val fields = Json.parseToJsonElement(attached).jsonObject
            assertEquals(
                case.name,
                case.connect,
                fields[ToolTiming.CONNECT_MS_KEY]?.jsonPrimitive?.long,
            )
            assertEquals(
                case.name,
                case.command,
                fields[ToolTiming.COMMAND_MS_KEY]?.jsonPrimitive?.long,
            )
        }
    }
}
