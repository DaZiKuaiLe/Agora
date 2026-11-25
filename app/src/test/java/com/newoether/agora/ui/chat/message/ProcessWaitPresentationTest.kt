package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.ToolExecutionStates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcessWaitPresentationTest {

    @Test
    fun processSpinnerAndCountdown_followProcessLivenessVersusModelWait() {
        data class Case(
            val name: String,
            val toolName: String,
            val state: String,
            val args: String,
            val startedAt: Long?,
            val spinner: Boolean,
            val countdown: Boolean,
        )
        val cases = listOf(
            Case(
                "start wait shows remaining time",
                "start_local_process",
                ToolExecutionStates.RUNNING,
                """{"command":"ffmpeg","wait_ms":5000}""",
                1_000L,
                true,
                true,
            ),
            Case(
                "start wait of one second hides countdown",
                "start_local_process",
                ToolExecutionStates.RUNNING,
                """{"command":"ffmpeg","wait_ms":1000}""",
                1_000L,
                true,
                false,
            ),
            Case(
                "start wait under one second hides countdown",
                "start_local_process",
                ToolExecutionStates.RUNNING,
                """{"command":"ffmpeg","wait_ms":500}""",
                1_000L,
                true,
                false,
            ),
            Case(
                "default zero wait returned is not a live wait card",
                "start_remote_process",
                ToolExecutionStates.BACKGROUND_RUNNING,
                """{"command":"ffmpeg"}""",
                1_000L,
                false,
                false,
            ),
            Case(
                "start wait returned while process lives is not a live wait card",
                "start_local_process",
                ToolExecutionStates.BACKGROUND_RUNNING,
                """{"command":"ffmpeg","wait_ms":5000}""",
                1_000L,
                false,
                false,
            ),
            Case(
                "wait tool blocking shows new countdown",
                "wait_process",
                ToolExecutionStates.RUNNING,
                """{"process_id":"local:aa","wait_ms":8000}""",
                1_000L,
                true,
                true,
            ),
            Case(
                "wait tool returned running is not a live wait card",
                "wait_process",
                ToolExecutionStates.BACKGROUND_RUNNING,
                """{"process_id":"local:aa","wait_ms":8000}""",
                1_000L,
                false,
                false,
            ),
            Case(
                "start waiting without timestamp still counts down",
                "start_remote_process",
                ToolExecutionStates.RUNNING,
                """{"command":"ffmpeg","wait_ms":"5000"}""",
                null,
                true,
                true,
            ),
            Case(
                "get process does not own wait chrome",
                "get_process",
                ToolExecutionStates.RUNNING,
                """{"process_id":"local:aa"}""",
                1_000L,
                false,
                false,
            ),
            Case(
                "stop process does not own wait chrome",
                "stop_process",
                ToolExecutionStates.RUNNING,
                """{"process_id":"local:aa"}""",
                1_000L,
                false,
                false,
            ),
            Case(
                "calling start with wait still counts down",
                "start_remote_process",
                ToolExecutionStates.CALLING,
                """{"command":"ffmpeg","wait_ms":5000}""",
                1_000L,
                true,
                true,
            ),
            Case(
                "exited start hides spinner",
                "start_remote_process",
                ToolExecutionStates.SUCCEEDED,
                """{"command":"ffmpeg","wait_ms":5000}""",
                1_000L,
                false,
                false,
            ),
        )
        cases.forEach { case ->
            val segment = MessageSegment(
                type = "tool",
                toolName = case.toolName,
                toolArgs = case.args,
                toolState = case.state,
                toolStartedAtMs = case.startedAt,
                toolResult = when (case.state) {
                    ToolExecutionStates.BACKGROUND_RUNNING ->
                        """{"running":true,"process_id":"local:aa"}"""
                    ToolExecutionStates.SUCCEEDED ->
                        """{"running":false,"exit_code":0,"process_id":"local:aa"}"""
                    else -> null
                },
            )
            assertEquals(case.name, case.spinner, ProcessWaitPresentation.processStillRunning(segment))
            val spec = ProcessWaitPresentation.waitSpec(segment, fallbackStartedAtMs = 1_000L)
            if (case.countdown) {
                assertEquals(case.name, true, (spec?.waitMs ?: 0) > 1_000)
            } else {
                assertNull(case.name, spec)
            }
        }
    }

    @Test
    fun laterWaitToolReplacesEarlierCountdown() {
        val start = MessageSegment(
            type = "tool",
            toolName = "start_remote_process",
            toolArgs = """{"command":"ffmpeg","wait_ms":30000}""",
            toolState = ToolExecutionStates.BACKGROUND_RUNNING,
            toolStartedAtMs = 1_000L,
            toolResult = """{"running":true,"process_id":"ssh:aa"}""",
        )
        val wait = MessageSegment(
            type = "tool",
            toolName = "wait_process",
            toolArgs = """{"process_id":"ssh:aa","wait_ms":8000}""",
            toolState = ToolExecutionStates.RUNNING,
            toolStartedAtMs = 10_000L,
        )
        val spec = ProcessWaitPresentation.waitSpec(listOf(start, wait), fallbackStartedAtMs = 1L)
        assertEquals(8_000, spec?.waitMs)
        assertEquals(10_000L, spec?.startedAtMs)
        assertEquals(true, ProcessWaitPresentation.processStillRunning(listOf(start, wait)))
    }

    @Test
    fun conversationHeaderIconIgnoresBackgroundProcess() {
        data class Case(
            val name: String,
            val conversationLoading: Boolean,
            val processRunning: Boolean,
            val expected: CompactSegmentIcon,
        )
        val cases = listOf(
            Case("conversation loading wins", true, true, CompactSegmentIcon.LOADING),
            Case("process loading after turn ends", false, true, CompactSegmentIcon.PROCESS),
            Case("idle tools", false, false, CompactSegmentIcon.TOOL),
        )
        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                compactSegmentHeaderIcon(
                    conversationLoading = case.conversationLoading,
                    processRunning = case.processRunning,
                    isThinking = false,
                    hasThought = false,
                    toolCount = 1,
                    isTranscribingOrImage = false,
                ),
            )
        }
    }
}
