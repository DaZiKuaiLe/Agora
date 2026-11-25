package com.newoether.agora.viewmodel

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.ApiResponseArchive
import com.newoether.agora.data.local.CompactMessageGraphRow
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/** Executes one idle-only durable message-branch or Compact-boundary deletion. */
internal class ConversationBranchMutationService(
    private val scope: CoroutineScope,
    private val conversations: ConversationRepository,
    private val executionCoordinator: ConversationExecutionCoordinator,
    private val isConversationOpen: (String) -> Boolean,
    private val projectGraph: (List<ChatMessage>, Map<String?, String>) -> Unit,
    private val onMutationStart: suspend (scrollToTarget: Boolean) -> Long?,
    private val onMutationSettling: (Long?, String?) -> Unit,
    private val onMutationFailed: (Long?) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun delete(
        conversationId: String,
        messageId: String,
        state: ConversationGenerationState,
        snapshot: List<ChatMessage>,
    ): Int {
        if (state.generating.value) return 0
        if (snapshot.none { it.id == messageId }) return 0
        val compactOnly = messageId.startsWith(Constants.COMPACT_MSG_PREFIX)
        val previewIds = if (compactOnly) {
            setOf(messageId)
        } else {
            structuralDescendantIds(snapshot, messageId)
        }

        scope.launch(ioDispatcher) {
            val switchingRequestId = onMutationStart(!compactOnly)
            var committed = false
            try {
                state.queueMutationMutex.withLock {
                    // Recheck after the overlay fade and under the same mutex that accepts Send.
                    if (state.generating.value) return@withLock
                    executionCoordinator.withConversationLock(conversationId) lock@ {
                        if (conversations.getLiveRun(conversationId) != null) return@lock
                        if (compactOnly) {
                            check(conversations.removeContextCompact(messageId))
                            val remainingGraph =
                                conversations.getCompactMessageGraph(conversationId)
                            val remainingChatMessages = projectRemainingMessages(
                                graph = remainingGraph,
                                deletedIds = setOf(messageId),
                            )
                            val selections = conversations.restoreBranchSelections(conversationId)
                            if (isConversationOpen(conversationId)) {
                                projectGraph(remainingChatMessages, selections)
                            }
                            committed = true
                            onMutationSettling(switchingRequestId, null)
                            return@lock
                        }

                        val runs = conversations.getRunsForConversationSnapshot(conversationId)
                        val graph = conversations.getCompactMessageGraph(conversationId)
                        val identityMessages = graph.map { it.toStubMessageEntity() }
                        val previousSelected =
                            conversations.restoreBranchSelections(conversationId)
                        val previousRunSelections =
                            conversations.restoreRunBranchSelections(conversationId)
                        val plan = BranchDeletionPlanner.plan(
                            rootMessageId = messageId,
                            messages = identityMessages,
                            runs = runs,
                            messageSelections = previousSelected,
                            runSelections = previousRunSelections,
                        )
                        val staleRefs = conversations.getMessageAttachmentReferencesByIds(
                            plan.deletedMessageIds.toList(),
                        )
                        check(
                            conversations.deleteMessageSubtree(
                                conversationId = conversationId,
                                rootMessageId = messageId,
                                staleMessageIds = plan.deletedMessageIds.toList(),
                                rootRunIdsToDelete = plan.rootRunIdsToDelete.toList(),
                                messageSelections = plan.messageSelections,
                                runSelections = plan.runSelections,
                            )
                        ) { "Message $messageId disappeared during delete" }

                        // Files are external to Room, so remove them only after graph commit.
                        conversations.deleteMessageAttachmentFiles(staleRefs)
                        ApiResponseArchive.delete(plan.deletedMessageIds)
                        val remainingChatMessages = projectRemainingMessages(
                            graph = graph,
                            deletedIds = plan.deletedMessageIds,
                        )
                        val remainingPath = ConversationUiState.resolvePath(
                            allMessages = remainingChatMessages,
                            streamingMsg = null,
                            selectedChildren = plan.messageSelections,
                        )
                        val targetAfterDelete = deleteSettlementTargetMessageId(
                            messagesBeforeDelete = snapshot,
                            deletedRootMessageId = messageId,
                            remainingPath = remainingPath,
                        )
                        if (isConversationOpen(conversationId)) {
                            projectGraph(remainingChatMessages, plan.messageSelections)
                        }
                        committed = true
                        onMutationSettling(switchingRequestId, targetAfterDelete)
                    }
                }
            } catch (error: Exception) {
                DebugLog.e("AgoraVM", "Failed to delete message branch $messageId", error)
            } finally {
                if (!committed) onMutationFailed(switchingRequestId)
            }
        }

        return previewIds.size
    }

    private fun projectRemainingMessages(
        graph: List<CompactMessageGraphRow>,
        deletedIds: Set<String>,
    ): List<ChatMessage> =
        graph.filter { it.id !in deletedIds }.map { it.toPathChatMessage() }

    private fun structuralDescendantIds(
        messages: List<ChatMessage>,
        rootMessageId: String,
    ): Set<String> {
        val childrenByParent = messages.groupBy { it.parentId }
        val descendants = linkedSetOf(rootMessageId)
        val pending = ArrayDeque<String>().apply { add(rootMessageId) }
        while (pending.isNotEmpty()) {
            for (child in childrenByParent[pending.removeFirst()].orEmpty()) {
                if (descendants.add(child.id)) pending.add(child.id)
            }
        }
        return descendants
    }
}
