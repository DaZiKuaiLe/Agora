package com.newoether.agora.tool

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-scoped async command runs. If the app dies or SSH drops, the run is gone
 * unless the model used nohup itself. Not a Conch durable job.
 */
internal data class BackgroundProcessView(
    val id: String,
    val command: String,
    val server: String,
)

internal data class LiveProcessView(
    val id: String,
    val command: String,
    val server: String,
    val waiting: Boolean,
)

internal data class BackgroundProcessExit(
    val processId: String,
    val conversationId: String,
    val command: String,
    val server: String,
    val snapshot: AsyncCommandSnapshot,
    val notifyModel: Boolean = true,
)

internal fun interface BackgroundProcessExitListener {
    fun onBackgroundExit(event: BackgroundProcessExit)
}

internal object AsyncCommandRegistry {
    private val tracked = ConcurrentHashMap<String, TrackedRun>()
    private val waiters = ConcurrentHashMap<String, AtomicInteger>()
    private val _background = MutableStateFlow<List<BackgroundProcessView>>(emptyList())
    private val exitListener = AtomicReference<BackgroundProcessExitListener?>(null)

    val background: StateFlow<List<BackgroundProcessView>> = _background.asStateFlow()

    fun setExitListener(listener: BackgroundProcessExitListener?) {
        exitListener.set(listener)
    }

    fun put(
        run: AsyncCommandRun,
        waiting: Boolean = false,
        conversationId: String? = null,
    ) {
        val id = run.id.encode()
        tracked[id] = TrackedRun(run, conversationId?.trim()?.takeIf { it.isNotEmpty() })
        if (waiting) {
            waiters.getOrPut(id) { AtomicInteger(0) }.incrementAndGet()
        }
        run.observeExit { onProcessExit(id) }
        publish()
    }

    fun get(id: String): AsyncCommandRun? = tracked[id.trim()]?.run

    fun remove(id: String): AsyncCommandRun? {
        val key = id.trim()
        waiters.remove(key)
        val removed = tracked.remove(key)?.run
        publish()
        return removed
    }

    fun beginWait(id: String) {
        val key = id.trim()
        if (key.isEmpty()) return
        waiters.getOrPut(key) { AtomicInteger(0) }.incrementAndGet()
        tracked[key]?.backgroundEligible?.set(false)
        publish()
    }

    fun endWait(id: String) {
        val key = id.trim()
        val counter = waiters[key]
        if (counter != null && counter.decrementAndGet() <= 0) waiters.remove(key)
        val record = tracked[key]
        if (record != null && waiterCount(key) == 0 && !record.run.isFinished()) {
            record.backgroundEligible.set(true)
        }
        publish()
    }

    fun waiterCount(id: String): Int = waiters[id.trim()]?.get() ?: 0

    fun stopByUser(id: String) {
        val key = id.trim()
        val record = tracked[key] ?: return
        if (record.run.isFinished()) return
        record.userStopped.set(true)
        record.run.stop()
        if (record.run.isFinished()) onProcessExit(key)
    }

    fun liveSnapshot(): List<LiveProcessView> = liveProcessSnapshot(tracked.mapValues { it.value.run }, waiters)

    internal fun resetForTests() {
        tracked.values.forEach { record -> runCatching { record.run.release() } }
        tracked.clear()
        waiters.clear()
        _background.value = emptyList()
        exitListener.set(null)
        ProcessCardOverlay.resetForTests()
    }

    private fun onProcessExit(id: String) {
        publish()
        val record = tracked[id] ?: return
        if (!record.run.isFinished()) return
        val userStopped = record.userStopped.get()
        val notifyModel = shouldNotifyBackgroundExit(
            finished = true,
            waiterCount = waiterCount(id),
            backgroundEligible = record.backgroundEligible.get(),
            alreadyNotified = record.notified.get(),
        ) && !userStopped
        if (!notifyModel && !userStopped) return
        if (!record.notified.compareAndSet(false, true)) return
        val conversationId = record.conversationId ?: return
        exitListener.get()?.onBackgroundExit(
            BackgroundProcessExit(
                processId = id,
                conversationId = conversationId,
                command = record.run.command,
                server = record.run.server,
                snapshot = record.run.snapshot(),
                notifyModel = notifyModel,
            ),
        )
    }

    private fun publish() {
        _background.value = backgroundSnapshot(tracked.mapValues { it.value.run }, waiters)
    }
}

private class TrackedRun(
    val run: AsyncCommandRun,
    val conversationId: String?,
    val backgroundEligible: AtomicBoolean = AtomicBoolean(false),
    val notified: AtomicBoolean = AtomicBoolean(false),
    val userStopped: AtomicBoolean = AtomicBoolean(false),
)

internal fun backgroundSnapshot(
    runs: Map<String, AsyncCommandRun>,
    waiters: Map<String, AtomicInteger>,
): List<BackgroundProcessView> = liveProcessSnapshot(runs, waiters)
    .filterNot { item -> item.waiting }
    .map { item -> BackgroundProcessView(item.id, item.command, item.server) }

internal fun liveProcessSnapshot(
    runs: Map<String, AsyncCommandRun>,
    waiters: Map<String, AtomicInteger>,
): List<LiveProcessView> = runs.entries
    .mapNotNull { (id, run) ->
        if (run.isFinished()) return@mapNotNull null
        LiveProcessView(
            id = id,
            command = run.command,
            server = run.server,
            waiting = (waiters[id]?.get() ?: 0) > 0,
        )
    }
    .sortedBy { item -> item.id }

internal fun shouldNotifyBackgroundExit(
    finished: Boolean,
    waiterCount: Int,
    backgroundEligible: Boolean,
    alreadyNotified: Boolean,
): Boolean = finished && waiterCount <= 0 && backgroundEligible && !alreadyNotified

internal interface AsyncCommandRun {
    val id: AsyncCommandId
    val command: String
    val server: String
    fun isFinished(): Boolean
    fun snapshot(): AsyncCommandSnapshot
    fun stop()
    fun release()
    fun observeExit(onExit: () -> Unit)
}

internal data class AsyncCommandSnapshot(
    val running: Boolean,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val error: String? = null,
)
