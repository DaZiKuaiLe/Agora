package com.newoether.agora.tool

import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.ui.chat.message.ToolPresentationResolver
import com.newoether.agora.ui.chat.message.ToolPresentationState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class BackgroundProcessCompletionTest {
    @After
    fun tearDown() {
        AsyncCommandRegistry.resetForTests()
    }

    @Test
    fun shouldNotifyBackgroundExit_table() {
        data class Case(
            val name: String,
            val finished: Boolean,
            val waiterCount: Int,
            val eligible: Boolean,
            val notified: Boolean,
            val expect: Boolean,
        )
        listOf(
            Case("background exit", true, 0, true, false, true),
            Case("still waiting", true, 1, true, false, false),
            Case("not eligible", true, 0, false, false, false),
            Case("already notified", true, 0, true, true, false),
            Case("still running", false, 0, true, false, false),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expect,
                shouldNotifyBackgroundExit(
                    case.finished,
                    case.waiterCount,
                    case.eligible,
                    case.notified,
                ),
            )
        }
    }

    @Test
    fun liveSnapshotIncludesWaitingAndBackground() {
        val waiting = FakeRun(AsyncCommandId.local("a"), "waited")
        val background = FakeRun(AsyncCommandId.local("b"), "bg")
        val dead = FakeRun(AsyncCommandId.local("c"), "done", finished = true)
        val runs = mapOf(
            waiting.id.encode() to waiting,
            background.id.encode() to background,
            dead.id.encode() to dead,
        )
        val waiters = mapOf(waiting.id.encode() to AtomicInteger(1))
        val live = liveProcessSnapshot(runs, waiters)
        assertEquals(listOf(waiting.id.encode(), background.id.encode()), live.map { it.id })
        assertEquals(true, live.single { it.id == waiting.id.encode() }.waiting)
        assertEquals(false, live.single { it.id == background.id.encode() }.waiting)
        assertEquals(listOf(background.id.encode()), backgroundSnapshot(runs, waiters).map { it.id })
    }

    @Test
    fun waitReturnThenExitNotifiesOnce() {
        val run = FakeRun(AsyncCommandId.local("live"), "ffmpeg")
        val exits = mutableListOf<String>()
        AsyncCommandRegistry.setExitListener { event -> exits += event.processId }
        AsyncCommandRegistry.put(run, waiting = true, conversationId = "conv-1")
        AsyncCommandRegistry.endWait(run.id.encode())
        assertEquals(listOf(run.id.encode()), AsyncCommandRegistry.background.value.map { it.id })
        run.finished = true
        run.fireExit()
        run.fireExit()
        assertEquals(listOf(run.id.encode()), exits)
        assertTrue(AsyncCommandRegistry.background.value.none { it.id == run.id.encode() })
    }

    @Test
    fun userStopKillsWithoutNotifyingTheModel() {
        val run = FakeRun(AsyncCommandId.local("bg"), "ffmpeg")
        val events = mutableListOf<BackgroundProcessExit>()
        AsyncCommandRegistry.setExitListener { event -> events += event }
        AsyncCommandRegistry.put(run, waiting = true, conversationId = "conv-1")
        AsyncCommandRegistry.endWait(run.id.encode())
        AsyncCommandRegistry.stopByUser(run.id.encode())
        assertTrue(run.isFinished())
        assertEquals(1, events.size)
        assertFalse(events.single().notifyModel)
        assertEquals(run.id.encode(), events.single().processId)
        run.fireExit()
        assertEquals(1, events.size)
    }

    @Test
    fun exitDuringWaitDoesNotNotify() {
        val run = FakeRun(AsyncCommandId.local("fg"), "sleep")
        val exits = mutableListOf<String>()
        AsyncCommandRegistry.setExitListener { event -> exits += event.processId }
        AsyncCommandRegistry.put(run, waiting = true, conversationId = "conv-1")
        run.finished = true
        run.fireExit()
        AsyncCommandRegistry.endWait(run.id.encode())
        assertTrue(exits.isEmpty())
    }

    @Test
    fun overlayRewritesOpenStartAndWaitCardsOnly() {
        val processId = "local:aa"
        val running = """{"running":true,"process_id":"$processId"}"""
        val done = """{"running":false,"process_id":"$processId","exit_code":0,"output":"ok"}"""
        ProcessCardOverlay.put(processId, ProcessCardSettlement(done, ToolExecutionStates.SUCCEEDED))
        data class Case(
            val name: String,
            val tool: String,
            val result: String,
            val args: String,
            val expectPatched: Boolean,
        )
        listOf(
            Case("start running", ProcessTools.START_LOCAL, running, "{}", true),
            Case("wait running", ProcessTools.WAIT, running, """{"process_id":"$processId"}""", true),
            Case("get running", ProcessTools.GET, running, """{"process_id":"$processId"}""", true),
            Case("already done", ProcessTools.START_LOCAL, done, "{}", false),
            Case("other process", ProcessTools.WAIT, """{"running":true,"process_id":"local:bb"}""", "{}", false),
        ).forEach { case ->
            val segment = MessageSegment(
                type = "tool",
                toolName = case.tool,
                toolArgs = case.args,
                toolResult = case.result,
                toolState = ToolExecutionStates.BACKGROUND_RUNNING.takeIf { case.expectPatched },
            )
            val applied = ProcessCardOverlay.apply(segment)
            if (case.expectPatched) {
                assertEquals(case.name, done, applied.toolResult)
                assertEquals(case.name, ToolExecutionStates.SUCCEEDED, applied.toolState)
            } else {
                assertEquals(case.name, case.result, applied.toolResult)
            }
        }
    }

    @Test
    fun overlayStopsBackgroundSpinnerOnVisibleCard() {
        val processId = "local:aa"
        val running = """{"running":true,"process_id":"$processId","command":"ffmpeg"}"""
        val done = """{"running":false,"process_id":"$processId","exit_code":0,"output":"ok"}"""
        ProcessCardOverlay.put(processId, ProcessCardSettlement(done, ToolExecutionStates.SUCCEEDED))
        val presentation = ToolPresentationResolver.resolve(
            MessageSegment(
                type = "tool",
                toolName = ProcessTools.START_LOCAL,
                toolResult = running,
                toolState = ToolExecutionStates.BACKGROUND_RUNNING,
            ),
        )
        assertEquals(ToolPresentationState.COMPLETED, presentation.state)
        assertFalse(presentation.isActive)
        assertEquals(0, presentation.exitCode)
    }

    @Test
    fun completedMessageStartsWithSystemNotice() {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val now = java.time.ZonedDateTime.of(2026, 10, 3, 3, 20, 0, 0, zone).toInstant().toEpochMilli()
        data class Case(
            val name: String,
            val server: String,
            val exitCode: Int,
            val stdout: String,
            val stderr: String,
            val error: String?,
            val serverLabel: String,
            val ok: Boolean,
        )
        listOf(
            Case("local success", "Local", 0, "转码完成", "", null, "local", true),
            Case("remote failure", "build-box", 1, "", "boom", "killed", "build-box", false),
            Case("sandbox label", "Local Sandbox", 0, "", "", null, "local", true),
        ).forEach { case ->
            val text = formatBackgroundProcessCompletedMessage(
                processId = "local:aa",
                command = "ffmpeg -i in.mp4 out.mp4",
                server = case.server,
                exitCode = case.exitCode,
                stdout = case.stdout,
                stderr = case.stderr,
                error = case.error,
                nowMs = now,
                zone = zone,
            )
            assertTrue(case.name, text.startsWith("【系统提示】\n"))
            assertTrue(case.name, "time: 2026-10-03 03:20:00 Asia/Shanghai (UTC+08:00)" in text)
            assertTrue(case.name, "process: 后台进程已结束" in text)
            assertTrue(case.name, "process_id: local:aa" in text)
            assertTrue(case.name, "server: ${case.serverLabel}" in text)
            assertTrue(case.name, "exit_code: ${case.exitCode}" in text)
            assertTrue(case.name, "ok: ${case.ok}" in text)
            if (case.stdout.isEmpty()) assertFalse(case.name, "\noutput:\n" in text)
            else assertTrue(case.name, "\noutput:\n${case.stdout}" in text)
            if (case.stderr.isEmpty()) assertFalse(case.name, "\nstderr:\n" in text)
            else assertTrue(case.name, "\nstderr:\n${case.stderr}" in text)
            if (case.error == null) assertFalse(case.name, "\nerror:" in text)
            else assertTrue(case.name, "error: ${case.error}" in text)
            assertFalse(case.name, "[Background process completed]" in text)
        }
    }

    private class FakeRun(
        override val id: AsyncCommandId,
        override val command: String,
        override val server: String = "Local",
        var finished: Boolean = false,
    ) : AsyncCommandRun {
        private val exitListeners = mutableListOf<() -> Unit>()

        override fun isFinished(): Boolean = finished
        override fun snapshot() = AsyncCommandSnapshot(
            running = !finished,
            exitCode = if (finished) 0 else null,
            stdout = "ok",
            stderr = "",
        )
        override fun stop() {
            finished = true
        }
        override fun release() {}
        override fun observeExit(onExit: () -> Unit) {
            exitListeners += onExit
        }
        fun fireExit() {
            exitListeners.forEach { listener -> listener() }
        }
    }
}
