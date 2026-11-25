package com.newoether.agora.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.DialogProperties
import com.newoether.agora.R
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.replaceCustomProviderIdsForDisplay
import com.newoether.agora.ui.common.AgoraHaptics
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.viewmodel.DigestIntegrityAlert
import com.newoether.agora.viewmodel.DigestIntegrityKind
import com.newoether.agora.viewmodel.startFullContextOptimize

@Stable
internal class ChatAppDialogState internal constructor(
    private val manualCompactVisibleState: MutableState<Boolean>,
) {
    var renameConversationId by mutableStateOf<String?>(null)
        private set
    var renameInitialName by mutableStateOf("")
        private set
    var deleteConversationId by mutableStateOf<String?>(null)
        private set
    var promptVisible by mutableStateOf(false)
        private set
    var advancedVisible by mutableStateOf(false)
        private set
    var contextDetailVisible by mutableStateOf(false)
        private set
    var fullOptimizeVisible by mutableStateOf(false)
        private set
    val manualCompactVisible: Boolean
        get() = manualCompactVisibleState.value

    fun requestRename(conversationId: String, initialName: String) {
        renameConversationId = conversationId
        renameInitialName = initialName
    }

    fun dismissRename() {
        renameConversationId = null
    }

    fun requestDelete(conversationId: String) {
        deleteConversationId = conversationId
    }

    fun dismissDelete() {
        deleteConversationId = null
    }

    fun showPrompt() {
        promptVisible = true
    }

    fun dismissPrompt() {
        promptVisible = false
    }

    fun showAdvanced() {
        advancedVisible = true
    }

    fun dismissAdvanced() {
        advancedVisible = false
    }

    fun showManualCompact() {
        manualCompactVisibleState.value = true
    }

    fun dismissManualCompact() {
        manualCompactVisibleState.value = false
    }

    fun showFullOptimize() {
        fullOptimizeVisible = true
    }

    fun dismissFullOptimize() {
        fullOptimizeVisible = false
    }

    fun showContextDetail() {
        contextDetailVisible = true
    }

    fun dismissContextDetail() {
        contextDetailVisible = false
    }
}

@Composable
internal fun rememberChatAppDialogState(
    manualCompactVisibleState: MutableState<Boolean>,
): ChatAppDialogState = remember(manualCompactVisibleState) {
    ChatAppDialogState(manualCompactVisibleState)
}

@Composable
internal fun ChatAppDialogHost(
    state: ChatAppDialogState,
    viewModel: ChatViewModel,
    haptics: AgoraHaptics,
    compactModel: String?,
    selectedModel: String,
    enabledModels: Set<String>,
    modelAliases: Map<String, String>,
    customProviders: List<CustomProviderConfig>,
    isCompacting: Boolean,
    contextTokenBudget: Int,
    conversationId: String?,
    toolConfigurationKey: Any,
) {
    state.renameConversationId?.let { id ->
        ChatRenameDialog(
            initialName = state.renameInitialName,
            initialDisplayName = replaceCustomProviderIdsForDisplay(
                state.renameInitialName,
                customProviders,
            ),
            onSave = { newName ->
                viewModel.renameConversation(id, newName)
                state.dismissRename()
            },
            onDismiss = state::dismissRename,
        )
    }

    state.deleteConversationId?.let { id ->
        ChatDeleteConfirmDialog(
            onConfirm = {
                haptics.destructiveConfirmed()
                viewModel.deleteConversation(id)
                state.dismissDelete()
            },
            onDismiss = state::dismissDelete,
        )
    }

    if (state.promptVisible) {
        ChatSystemPromptDialog(viewModel = viewModel, onDismiss = state::dismissPrompt)
    }

    if (state.advancedVisible) {
        ChatAdvancedSettingsDialog(viewModel = viewModel, onDismiss = state::dismissAdvanced)
    }

    if (state.manualCompactVisible) {
        ChatManualCompactDialog(
            initialModel = compactModel ?: selectedModel,
            enabledModels = enabledModels,
            modelAliases = modelAliases,
            customProviders = customProviders,
            isCompacting = isCompacting,
            onCompact = { model ->
                state.dismissManualCompact()
                viewModel.startContextCompactManual(model, false)
            },
            onDismiss = state::dismissManualCompact,
        )
    }

    if (state.fullOptimizeVisible) {
        ChatFullContextOptimizeDialog(
            initialModel = compactModel ?: selectedModel,
            enabledModels = enabledModels,
            modelAliases = modelAliases,
            customProviders = customProviders,
            isCompacting = isCompacting,
            onOptimize = { model ->
                state.dismissFullOptimize()
                viewModel.startFullContextOptimize(model)
            },
            onDismiss = state::dismissFullOptimize,
        )
    }

    if (state.contextDetailVisible) {
        ContextDetailPanel(
            viewModel = viewModel,
            conversationId = conversationId,
            selectedModelId = selectedModel,
            tokenBudget = contextTokenBudget,
            toolConfigurationKey = toolConfigurationKey,
            onDismiss = state::dismissContextDetail,
        )
    }

    val pendingAutomaticCompact by viewModel.pendingAutomaticCompactPrompt.collectAsState()
    if (pendingAutomaticCompact) {
        ChatAutomaticCompactConfirmDialog(
            onConfirm = viewModel::confirmAutomaticCompactSend,
            onSkip = viewModel::skipAutomaticCompactSend,
        )
    }

    val pendingDigestUpdate by viewModel.pendingDigestUpdatePrompt.collectAsState()
    if (pendingDigestUpdate) {
        ChatDigestUpdateConfirmDialog(
            onConfirm = viewModel::confirmDigestUpdateSend,
            onAbort = viewModel::abortDigestUpdateSend,
        )
    }

    val pendingCompactFormat by viewModel.pendingCompactFormatPrompt.collectAsState()
    if (pendingCompactFormat) {
        ChatCompactFormatConfirmDialog(
            onConfirm = viewModel::confirmCompactFormatSend,
            onAbort = viewModel::abortCompactFormatSend,
        )
    }

    val digestIntegrityAlert by viewModel.digestIntegrityAlert.collectAsState()
    digestIntegrityAlert?.let { alert ->
        ChatDigestIntegrityAlertDialog(
            alert = alert,
            onConfirm = viewModel::dismissDigestIntegrityAlert,
        )
    }

    val compactTurnTooLarge by viewModel.compactTurnTooLargeAlert.collectAsState()
    if (compactTurnTooLarge) {
        ChatCompactTurnTooLargeDialog(
            onConfirm = viewModel::dismissCompactTurnTooLargeAlert,
        )
    }
}

@Composable
private fun ChatAutomaticCompactConfirmDialog(
    onConfirm: () -> Unit,
    onSkip: () -> Unit,
) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onSkip,
        title = {
            Text(
                stringResource(R.string.context_compact_auto_prompt_title),
                fontWeight = FontWeight.Bold,
            )
        },
        text = { Text(stringResource(R.string.context_compact_auto_prompt_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.context_compact_auto_prompt_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip) {
                Text(stringResource(R.string.context_compact_auto_prompt_skip))
            }
        },
    )
}

@Composable
private fun ChatDigestUpdateConfirmDialog(
    onConfirm: () -> Unit,
    onAbort: () -> Unit,
) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onAbort,
        title = {
            Text(
                stringResource(R.string.context_digest_update_prompt_title),
                fontWeight = FontWeight.Bold,
            )
        },
        text = { Text(stringResource(R.string.context_digest_update_prompt_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.context_digest_update_prompt_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onAbort) {
                Text(stringResource(R.string.context_digest_update_prompt_abort))
            }
        },
    )
}

@Composable
private fun ChatCompactFormatConfirmDialog(
    onConfirm: () -> Unit,
    onAbort: () -> Unit,
) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onAbort,
        title = {
            Text(
                stringResource(R.string.context_compact_format_prompt_title),
                fontWeight = FontWeight.Bold,
            )
        },
        text = { Text(stringResource(R.string.context_compact_format_prompt_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.context_compact_format_prompt_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onAbort) {
                Text(stringResource(R.string.context_compact_format_prompt_abort))
            }
        },
    )
}

@Composable
private fun ChatCompactTurnTooLargeDialog(
    onConfirm: () -> Unit,
) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onConfirm,
        title = {
            Text(
                stringResource(R.string.context_compact_turn_too_large_title),
                fontWeight = FontWeight.Bold,
            )
        },
        text = { Text(stringResource(R.string.context_compact_turn_too_large_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.context_digest_integrity_confirm))
            }
        },
    )
}

@Composable
private fun ChatDigestIntegrityAlertDialog(
    alert: DigestIntegrityAlert,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = {
            Text(
                stringResource(R.string.context_digest_integrity_title),
                fontWeight = FontWeight.Bold,
            )
        },
        text = { Text(digestIntegrityMessage(alert)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.context_digest_integrity_confirm))
            }
        },
    )
}

@Composable
private fun digestIntegrityMessage(alert: DigestIntegrityAlert): String = buildString {
    append(stringResource(R.string.context_digest_integrity_conversation, alert.conversationId))
    for (issue in alert.issues) {
        append("\n\n")
        append(
            when (issue.kind) {
                DigestIntegrityKind.MISSING_CLOSED_SET_CITES ->
                    stringResource(
                        R.string.context_digest_integrity_missing_cites,
                        issue.items.joinToString("\n"),
                    )
                DigestIntegrityKind.ILLEGAL_CITES_STRIPPED ->
                    stringResource(
                        R.string.context_digest_integrity_illegal_cites,
                        issue.items.joinToString("\n"),
                    )
                DigestIntegrityKind.DIGEST_WRITE_FAILED ->
                    stringResource(
                        R.string.context_digest_integrity_write_failed,
                        issue.items.joinToString(", ").ifBlank { "unknown" },
                    )
                DigestIntegrityKind.DIGEST_MISSING_AFTER_COMPACT -> {
                    val batchCount = issue.items.getOrNull(1) ?: issue.items.firstOrNull().orEmpty()
                    stringResource(R.string.context_digest_integrity_missing_after, batchCount)
                }
            },
        )
    }
}
