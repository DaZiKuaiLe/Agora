package com.newoether.agora.ui.chat

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.api.util.assembleText
import com.newoether.agora.api.util.isContextProjectionMessageId
import com.newoether.agora.data.freezeChapterCountFromWire
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.model.isContextCompact
import com.newoether.agora.model.isProcessCompletionNotice
import com.newoether.agora.util.Constants
import java.util.Locale

@Composable
internal fun ContextDetailTurnCheckbox(
    included: Boolean,
    onAccent: Color,
    accent: Color,
    onIncludedChange: (Boolean) -> Unit,
) {
    val sendTurnLabel = stringResource(R.string.context_detail_send_turn)
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Checkbox(
            checked = included,
            onCheckedChange = onIncludedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = onAccent,
                uncheckedColor = onAccent.copy(alpha = 0.75f),
                checkmarkColor = accent,
            ),
            modifier = Modifier
                .padding(end = 10.dp)
                .requiredSize(14.dp)
                .semantics { contentDescription = sendTurnLabel },
        )
    }
}

/** Wire-facing markdown/plain body shown in the panel and written into export JSON. */
internal fun contextDetailExportBody(message: ChatMessage): String {
    if (message.isProcessCompletionNotice()) return "—"
    return buildString {
        append(message.assembleText())
        val tool = message.toolCall
        if (tool != null) {
            if (isNotEmpty()) append("\n\n")
            append("tool: ").append(tool.toolName)
            if (tool.toolCallId != null) append(" (").append(tool.toolCallId).append(")")
            append("\nargs: ").append(tool.arguments)
            if (tool.result.isNotBlank()) {
                append("\nresult: ").append(tool.result)
            }
        }
        if (message.images.isNotEmpty()) {
            if (isNotEmpty()) append("\n\n")
            append("images[").append(message.images.size).append("]:\n")
            message.images.forEachIndexed { index, path ->
                append("  [").append(index).append("] ").append(path).append('\n')
            }
        }
    }.ifBlank { "—" }
}

/** Freeze-chapter total on compact projection rows; 0 when the 驻留 is a single unfrozen chapter. */
internal fun contextDetailFreezeChapterCount(messages: List<ChatMessage>): Int =
    messages
        .filter { isContextProjectionMessageId(it.id) || it.isContextCompact() }
        .maxOfOrNull { freezeChapterCountFromWire(contextDetailExportBody(it)) }
        ?: 0

internal fun contextDetailExportRole(message: ChatMessage): String = when {
    isContextProjectionMessageId(message.id) ||
        message.isContextCompact() -> "compact"
    message.id.startsWith(Constants.TOOL_MSG_PREFIX) -> "tool"
    message.id.startsWith(Constants.RESULT_MSG_PREFIX) -> "tool_result"
    message.participant == Participant.USER -> "user"
    message.participant == Participant.MODEL -> "assistant"
    else -> message.participant.name.lowercase(Locale.US)
}
