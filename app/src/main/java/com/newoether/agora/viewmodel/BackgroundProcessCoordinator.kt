package com.newoether.agora.viewmodel

import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.tool.AsyncCommandRegistry
import com.newoether.agora.tool.BackgroundProcessExit
import com.newoether.agora.tool.ProcessCardOverlay
import com.newoether.agora.tool.ProcessCardSettlement
import com.newoether.agora.tool.ProcessTools
import com.newoether.agora.tool.formatBackgroundProcessCompletedMessage
import com.newoether.agora.tool.formatExecResult
import com.newoether.agora.tool.processCardStateForExit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal object ProcessCompletionSendPort {
    @Volatile
    var send: (suspend (conversationId: String, modelId: String, text: String) -> Unit)? = null
}

internal class BackgroundProcessCoordinator(
    private val conversations: ConversationRepository,
    private val generationRegistry: ConversationStateRegistry,
    private val scope: CoroutineScope,
) {
    fun start() {
        AsyncCommandRegistry.setExitListener { event ->
            scope.launch { settle(event) }
        }
    }

    internal suspend fun settle(event: BackgroundProcessExit) {
        val exitCode = event.snapshot.exitCode ?: -1
        val result = formatExecResult(
            type = if (event.processId.startsWith("ssh:")) {
                ProcessTools.START_REMOTE
            } else {
                ProcessTools.START_LOCAL
            },
            command = event.command,
            server = event.server,
            exitCode = exitCode,
            stdout = event.snapshot.stdout,
            stderr = event.snapshot.stderr,
            okExit = null,
            detailStore = null,
            processId = event.processId,
            running = false,
        )
        ProcessCardOverlay.put(
            event.processId,
            ProcessCardSettlement(result, processCardStateForExit(exitCode)),
        )
        conversations.patchVisibleProcessCards(event.conversationId, event.processId)
        generationRegistry.get(event.conversationId)?.patchStreamingProcessCards()
        if (!event.notifyModel) return
        val conversation = conversations.getConversation(event.conversationId) ?: return
        val modelId = conversation.modelId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val text = formatBackgroundProcessCompletedMessage(
            processId = event.processId,
            command = event.command,
            server = event.server,
            exitCode = exitCode,
            stdout = event.snapshot.stdout,
            stderr = event.snapshot.stderr,
            error = event.snapshot.error,
        )
        val send = ProcessCompletionSendPort.send
        if (send != null) {
            send(event.conversationId, modelId, text)
        }
    }
}

internal fun ConversationGenerationState.patchStreamingProcessCards() {
    val current = streamingMessage.value ?: return
    val segments = current.segments ?: return
    val next = segments.map(ProcessCardOverlay::apply)
    if (next == segments) return
    streamUpdate(captureUiToken(), current.copy(segments = next))
}
