package com.newoether.agora.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.tool.AsyncCommandRegistry
import com.newoether.agora.tool.BackgroundProcessView
import com.newoether.agora.tool.ProcessTools
import com.newoether.agora.tool.formatAsyncRunning
import com.newoether.agora.tool.formatExecResult
import com.newoether.agora.ui.chat.message.SegmentDetailSheet
import com.newoether.agora.ui.chat.message.rememberChatMarkdownAssets
import com.newoether.agora.ui.chat.message.singleLineShellCommand
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import com.newoether.agora.ui.theme.ChatType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
internal fun BackgroundProcessHud(modifier: Modifier = Modifier) {
    val processes by AsyncCommandRegistry.background.collectAsState()
    if (processes.isEmpty()) return
    var menuOpen by remember { mutableStateOf(false) }
    var detailProcessId by remember { mutableStateOf<String?>(null) }
    val detailView = processes.firstOrNull { item -> item.id == detailProcessId }
        ?: detailProcessId?.let { id -> AsyncCommandRegistry.get(id) }?.let { run ->
            BackgroundProcessView(run.id.encode(), run.command, run.server)
        }
    val hudLabel = stringResource(
        if (processes.size == 1) R.string.background_process else R.string.background_processes,
    )
    Box(modifier = modifier) {
        IconButton(
            onClick = { menuOpen = true },
            modifier = Modifier
                .size(40.dp)
                .semantics { contentDescription = hudLabel },
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            modifier = Modifier
                .widthIn(min = 200.dp, max = 280.dp)
                .heightIn(max = 240.dp),
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            processes.forEachIndexed { index, process ->
                if (index > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                    )
                }
                BackgroundProcessCard(
                    process = process,
                    onClick = {
                        menuOpen = false
                        detailProcessId = process.id
                    },
                )
            }
        }
    }
    detailView?.let { process ->
        val message = remember(process.id, process.command, process.server) {
            backgroundProcessDetailMessage(process)
        }
        val markdownAssets = rememberChatMarkdownAssets(MaterialTheme.colorScheme.onSurface)
        SegmentDetailSheet(
            message = message,
            selectedSegmentIndex = 0,
            selectedSegmentIndices = listOf(0),
            isStreaming = false,
            markdownRenderContext = markdownAssets.thoughtRenderContext,
            onMediaClick = { _, _ -> },
            titleOverride = stringResource(R.string.background_process),
            onDismiss = { detailProcessId = null },
        )
    }
}

@Composable
private fun BackgroundProcessCard(
    process: BackgroundProcessView,
    onClick: () -> Unit,
) {
    val subtitle = singleLineShellCommand(process.command).orEmpty()
    val stopLabel = stringResource(R.string.tool_stop_process)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.background_process),
                style = ChatType.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = ChatType.metaNormal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(
            onClick = { AsyncCommandRegistry.stopByUser(process.id) },
            modifier = Modifier
                .size(36.dp)
                .semantics { contentDescription = stopLabel },
        ) {
            Icon(
                imageVector = Icons.Default.Stop,
                contentDescription = stopLabel,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

internal fun backgroundProcessDetailMessage(process: BackgroundProcessView): ChatMessage {
    val run = AsyncCommandRegistry.get(process.id)
    val snapshot = run?.snapshot()
    val toolName = if (process.id.startsWith("ssh:")) {
        ProcessTools.START_REMOTE
    } else {
        ProcessTools.START_LOCAL
    }
    val result = if (snapshot == null || snapshot.running) {
        formatAsyncRunning(
            type = toolName,
            command = process.command,
            server = process.server,
            id = process.id,
            stdout = snapshot?.stdout.orEmpty(),
            stderr = snapshot?.stderr.orEmpty(),
        )
    } else {
        formatExecResult(
            type = toolName,
            command = process.command,
            server = process.server,
            exitCode = snapshot.exitCode ?: -1,
            stdout = snapshot.stdout,
            stderr = snapshot.stderr,
            okExit = null,
            detailStore = null,
            processId = process.id,
            running = false,
        )
    }
    return ChatMessage(
        text = "",
        participant = Participant.MODEL,
        status = MessageStatus.SUCCESS,
        segments = listOf(
            MessageSegment(
                type = "tool",
                toolName = toolName,
                toolArgs = buildJsonObject { put("command", process.command) }.toString(),
                toolResult = result,
                toolState = ToolExecutionStates.SUCCEEDED,
                toolTarget = process.server,
            ),
        ),
    )
}
