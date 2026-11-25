package com.newoether.agora.ui.chat.message

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.tool.ProcessTools
import kotlinx.coroutines.delay

internal data class ProcessWaitSpec(
    val waitMs: Int,
    val startedAtMs: Long,
)

internal data class ProcessLiveWait(
    val waitMs: Int,
    val startedAtMs: Long?,
)

internal object ProcessWaitPresentation {
    /**
     * Spinner for a start/wait tool call that has not returned yet. Process liveness after
     * that return is owned by [AsyncCommandRegistry], not this card.
     */
    fun processStillRunning(segment: MessageSegment): Boolean {
        if (segment.type != "tool" || !isStartOrWaitTool(segment.toolName.orEmpty())) return false
        return ToolPresentationResolver.resolve(segment).isActive
    }

    fun processStillRunning(segments: List<MessageSegment>): Boolean =
        segments.any(::processStillRunning)

    fun showsSpinner(segment: MessageSegment): Boolean = processStillRunning(segment)

    fun showsSpinner(segments: List<MessageSegment>): Boolean = processStillRunning(segments)

    /**
     * Remaining model wait. Only while this start/wait call has not returned, and only when
     * wait_ms is longer than one second. A later wait tool replaces an earlier one.
     */
    fun liveWait(segment: MessageSegment): ProcessLiveWait? {
        if (!isStartOrWaitTool(segment.toolName.orEmpty())) return null
        if (!ToolPresentationResolver.resolve(segment).isActive) return null
        val waitMs = parseWaitMs(segment.toolArgs) ?: return null
        if (waitMs <= ProcessTools.COUNTDOWN_MIN_WAIT_MS) return null
        return ProcessLiveWait(waitMs, segment.toolStartedAtMs)
    }

    fun liveWait(segments: List<MessageSegment>): ProcessLiveWait? =
        segments.asReversed().firstNotNullOfOrNull(::liveWait)

    fun waitSpec(segment: MessageSegment, fallbackStartedAtMs: Long? = null): ProcessWaitSpec? {
        val live = liveWait(segment) ?: return null
        val startedAtMs = live.startedAtMs ?: fallbackStartedAtMs ?: return null
        return ProcessWaitSpec(live.waitMs, startedAtMs)
    }

    fun waitSpec(segments: List<MessageSegment>, fallbackStartedAtMs: Long? = null): ProcessWaitSpec? {
        val live = liveWait(segments) ?: return null
        val startedAtMs = live.startedAtMs ?: fallbackStartedAtMs ?: return null
        return ProcessWaitSpec(live.waitMs, startedAtMs)
    }

    fun countdownWidthAllowanceDp(segments: List<MessageSegment>): Int =
        if (liveWait(segments) != null) 40 else 0

    internal fun parseWaitMs(arguments: String?): Int? {
        if (arguments.isNullOrBlank()) return null
        return WAIT_MS_FIELD.find(arguments)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun isStartOrWaitTool(name: String): Boolean =
        name in ProcessTools.START_NAMES ||
            name in ProcessTools.LEGACY_START_NAMES ||
            name == ProcessTools.WAIT ||
            name in ProcessTools.LEGACY_WAIT_NAMES

    private val WAIT_MS_FIELD = Regex("\"wait_ms\"\\s*:\\s*\"?(\\d+)\"?")
}

@Composable
internal fun ProcessWaitCountdownLabel(
    segments: List<MessageSegment>,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
) {
    val live = ProcessWaitPresentation.liveWait(segments)
    val waitIdentity = segments.joinToString { "${it.toolCallId}:${it.toolName}" }
    val startedAtMs = remember(live?.waitMs, live?.startedAtMs, waitIdentity) {
        live?.startedAtMs ?: if (live != null) System.currentTimeMillis() else null
    }
    val spec = if (live != null && startedAtMs != null) {
        ProcessWaitSpec(live.waitMs, startedAtMs)
    } else {
        null
    }
    val label = rememberProcessWaitCountdown(spec)
    if (label != null) {
        Text(
            text = label,
            modifier = modifier,
            style = style,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            maxLines = 1,
        )
    }
}

@Composable
internal fun rememberProcessWaitCountdown(spec: ProcessWaitSpec?): String? {
    if (spec == null) return null
    var nowMs by remember(spec) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(spec) {
        while (true) {
            nowMs = System.currentTimeMillis()
            val remaining = ProcessTools.remainingCountdownSeconds(
                spec.waitMs,
                spec.startedAtMs,
                nowMs,
            )
            if (remaining == null) break
            delay(1_000L)
        }
    }
    val remaining = ProcessTools.remainingCountdownSeconds(spec.waitMs, spec.startedAtMs, nowMs)
        ?: return null
    return ProcessTools.formatCountdown(remaining)
}
