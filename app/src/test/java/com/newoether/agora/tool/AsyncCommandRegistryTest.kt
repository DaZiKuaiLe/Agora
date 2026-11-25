package com.newoether.agora.tool

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AsyncCommandRegistryTest {
    @After
    fun tearDown() {
        AsyncCommandRegistry.resetForTests()
    }

    @Test
    fun backgroundListHidesWaitersAndFinishedRuns() {
        data class Case(
            val name: String,
            val finished: Boolean,
            val waiterCount: Int,
            val expectedIds: List<String>,
        )
        val live = FakeRun(AsyncCommandId.local("a"), "ffmpeg", finished = false)
        val dead = FakeRun(AsyncCommandId.local("b"), "sleep 1", finished = true)
        val cases = listOf(
            Case("waiting start is not background", false, 1, emptyList()),
            Case("live with no waiter is background", false, 0, listOf(live.id.encode())),
            Case("finished is never background", true, 0, emptyList()),
        )
        cases.forEach { case ->
            val run = if (case.finished) dead else live
            val runs = mapOf(run.id.encode() to run)
            val waiters = if (case.waiterCount > 0) {
                mapOf(run.id.encode() to AtomicInteger(case.waiterCount))
            } else {
                emptyMap()
            }
            assertEquals(
                case.name,
                case.expectedIds,
                backgroundSnapshot(runs, waiters).map { item -> item.id },
            )
        }
    }

    @Test
    fun returnedWaitPublishesLiveProcessAsBackground() {
        val run = FakeRun(AsyncCommandId.local("live"), "ffmpeg")
        AsyncCommandRegistry.put(run, waiting = true)
        assertTrue(AsyncCommandRegistry.background.value.none { item -> item.id == run.id.encode() })
        AsyncCommandRegistry.endWait(run.id.encode())
        assertEquals(
            listOf(run.id.encode()),
            AsyncCommandRegistry.background.value.map { item -> item.id },
        )
        run.finished = true
        run.fireExit()
        assertTrue(AsyncCommandRegistry.background.value.none { item -> item.id == run.id.encode() })
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
            stdout = "",
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
