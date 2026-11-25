package com.newoether.agora.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.DrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.ui.common.AgoraHaptics
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.openWithMotionPolicy
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.viewmodel.OptimizeDiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ChatAppTopBar(
    isNewChatMode: Boolean,
    currentConversationId: String?,
    totalTokens: Int,
    messages: State<List<ChatMessage>>,
    listState: LazyListState,
    conversationInteraction: ConversationInteractionProjection,
    conversationSearchActive: Boolean,
    conversationSearchQuery: String,
    conversationSearchMatchIndex: Int,
    conversationSearchMatchCount: Int,
    isLoading: Boolean,
    shareSelectionActive: Boolean,
    onNavigateBack: (() -> Unit)?,
    drawerEnabled: Boolean,
    drawerState: DrawerState,
    motionPolicy: AgoraMotionPolicy,
    focusManager: FocusManager,
    scope: CoroutineScope,
    haptics: AgoraHaptics,
    viewModel: ChatViewModel,
    dialogState: ChatAppDialogState,
    onForkConversation: () -> Unit,
    inputFocusRequester: FocusRequester,
    onBeforeNewChat: () -> Unit,
) {
    val context = LocalContext.current
    val exportOptimizeLog = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { OptimizeDiagnosticLog.copyTo(context, uri) }.getOrDefault(false)
            }
            viewModel.emitSnackbar(
                context.getString(
                    if (saved) {
                        R.string.export_optimize_log_saved
                    } else {
                        R.string.export_optimize_log_failed
                    },
                ),
            )
        }
    }
    ChatTopBar(
        isNewChatMode = isNewChatMode,
        totalTokens = totalTokens,
        messages = messages,
        listState = listState,
        searchActive = conversationSearchActive,
        searchQuery = conversationSearchQuery,
        searchMatchIndex = conversationSearchMatchIndex,
        searchMatchCount = conversationSearchMatchCount,
        jumpIdActive = conversationInteraction.jumpIdActive,
        jumpIdQuery = conversationInteraction.jumpIdQuery,
        conversationActionsEnabled =
            !isNewChatMode && currentConversationId != null && !isLoading &&
                !shareSelectionActive,
        onNavigateBack = onNavigateBack,
        onOpenDrawer = {
            if (drawerEnabled) {
                focusManager.clearFocus()
                scope.launch { drawerState.openWithMotionPolicy(motionPolicy) }
            }
        },
        onSearchQueryChange = conversationInteraction::updateSearchQuery,
        onSearchPrevious = {
            if (conversationInteraction.previousSearchMatch()) {
                haptics.selection()
            }
        },
        onSearchNext = {
            if (conversationInteraction.nextSearchMatch()) {
                haptics.selection()
            }
        },
        onSearchDismiss = {
            conversationInteraction.dismissSearch()
            focusManager.clearFocus()
        },
        onSearchClick = conversationInteraction::activateSearch,
        onJumpIdQueryChange = conversationInteraction::updateJumpIdQuery,
        onJumpIdDismiss = {
            conversationInteraction.dismissJumpId()
            focusManager.clearFocus()
        },
        onJumpIdClick = conversationInteraction::activateJumpId,
        onJumpIdConfirm = {
            val messageId = conversationInteraction.jumpIdQuery
            if (resolveJumpTurnIndex(messageId, messages.value) < 0) {
                viewModel.emitSnackbar(
                    context.getString(R.string.conversation_jump_not_found),
                )
                return@ChatTopBar
            }
            conversationInteraction.requestJumpToMessage(messageId)
            conversationInteraction.dismissJumpId()
            focusManager.clearFocus()
        },
        onSystemPromptClick = dialogState::showPrompt,
        onForkConversation = onForkConversation,
        onShareConversation = {
            conversationInteraction.dismissSearch()
            conversationInteraction.dismissJumpId()
            focusManager.clearFocus()
            conversationInteraction.activateShareSelection()
        },
        onViewContextClick = dialogState::showContextDetail,
        onExportOptimizeLog = {
            if (!OptimizeDiagnosticLog.hasExportableContent(context)) {
                viewModel.emitSnackbar(context.getString(R.string.export_optimize_log_empty))
            } else {
                exportOptimizeLog.launch(OptimizeDiagnosticLog.FILE_NAME)
            }
        },
        onNewChat = {
            if (!isNewChatMode) {
                onBeforeNewChat()
                viewModel.createNewChat()
                inputFocusRequester.requestFocus()
            }
        },
    )
}
