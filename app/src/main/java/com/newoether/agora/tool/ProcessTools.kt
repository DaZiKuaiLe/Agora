package com.newoether.agora.tool

import com.newoether.agora.util.Constants

/** Wire names and wait rules for start/get/wait/stop process tools. */
object ProcessTools {
    const val START_LOCAL = "start_local_process"
    const val START_REMOTE = "start_remote_process"
    const val GET = "get_process"
    const val LIST = "list_background_processes"
    const val WAIT = "wait_process"
    const val STOP = "stop_process"
    const val PROCESS_ID = "process_id"
    const val LONG_PROCESS_MS = 5_000
    const val COUNTDOWN_MIN_WAIT_MS = 1_000
    const val LONG_PROCESS_SECONDS = LONG_PROCESS_MS / 1_000
    const val STOP_GRACE_MS = 400
    const val STOP_FORCE_MS = 400
    const val STOP_SETTLE_MS = 500
    const val STILL_RUNNING = "still_running"

    val START_NAMES = setOf(START_LOCAL, START_REMOTE)
    val NAMES = START_NAMES + setOf(GET, LIST, WAIT, STOP)
    const val BACKGROUND_EXIT_NOTIFY =
        "Agora will send a new user message starting with 【系统提示】 when a background process exits"
    val LEGACY_START_NAMES = setOf(
        "execute_local_async_command",
        "execute_remote_async_command",
    )
    val LEGACY_WAIT_NAMES = setOf("wait_async_command", "wait_remote_job")
    val CARD_UPDATE_NAMES = START_NAMES + LEGACY_START_NAMES + setOf(
        GET,
        WAIT,
        "get_async_command",
        "wait_async_command",
        "wait_remote_job",
    )

    val POLICY =
        "execute_local_command / execute_remote_command are only for work expected to finish in " +
            "under $LONG_PROCESS_SECONDS seconds (git status, ls, echo, a short test, ffprobe). " +
            "If a command may take $LONG_PROCESS_SECONDS seconds or more, duration is unknown, " +
            "or you need to speak or start other work first, you must call " +
            "$START_LOCAL / $START_REMOTE immediately. Do not sleep or poll with execute_*. " +
            "wait_ms is optional and defaults to 0 (returns {running, $PROCESS_ID} at once). " +
            "Foreground: this start/wait call is still inside wait_ms; the tool result is the " +
            "outcome. Background: wait_ms ended and the process is still running; the tool card " +
            "ends, the same handle keeps running, and $LIST sees it. $BACKGROUND_EXIT_NOTIFY " +
            "with process_id and output — wait for that message instead of polling. Use $LIST " +
            "or $GET only if you need status before then; $WAIT pulls it foreground; $STOP " +
            "ends it (stop's own result is the outcome). Handles die with this app process or " +
            "SSH session. A timed-out sync command is killed; it is not promoted. ffmpeg encode, " +
            "long builds, installs, and downloads must use start_*_process."

    fun parseOptionalWaitMs(raw: String): Int? {
        if (raw.isBlank()) return 0
        return raw.toIntOrNull()?.coerceIn(0, Constants.TOOL_EXECUTION_TIMEOUT_MS.toInt())
    }

    fun parseRequiredWaitMs(raw: String): Int? {
        if (raw.isBlank()) return null
        return raw.toIntOrNull()?.coerceIn(0, Constants.TOOL_EXECUTION_TIMEOUT_MS.toInt())
    }

    fun remainingCountdownSeconds(waitMs: Int, startedAtMs: Long, nowMs: Long): Int? {
        if (waitMs < COUNTDOWN_MIN_WAIT_MS) return null
        val remainingMs = startedAtMs + waitMs.toLong() - nowMs
        if (remainingMs < 1_000L) return null
        return ((remainingMs + 999L) / 1_000L).toInt()
    }

    fun formatCountdown(seconds: Int): String =
        if (seconds < 60) seconds.toString()
        else "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

    data class StopWire(
        val running: Boolean,
        val stopped: Boolean,
        val ok: Boolean,
        val error: String? = null,
    )

    fun stopWire(stillRunning: Boolean): StopWire =
        if (stillRunning) {
            StopWire(running = true, stopped = false, ok = false, error = STILL_RUNNING)
        } else {
            StopWire(running = false, stopped = true, ok = true)
        }
}
