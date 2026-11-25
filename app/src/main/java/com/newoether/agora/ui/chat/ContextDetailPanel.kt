package com.newoether.agora.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.newoether.agora.R
import com.newoether.agora.data.TimeBandPolicy
import com.newoether.agora.data.freezeChapterCountFromWire
import com.newoether.agora.api.util.CONTEXT_DIGEST_TYPE
import com.newoether.agora.api.util.ContextDigestPayload
import com.newoether.agora.api.util.ContextTokenEstimator
import com.newoether.agora.api.util.SUMMARY_BATCHES_TYPE
import com.newoether.agora.api.util.SummaryBatchesPayload
import com.newoether.agora.api.util.assembleText
import com.newoether.agora.api.util.compressionRatio
import com.newoether.agora.api.util.estimateIntegratedTurnTokens
import com.newoether.agora.api.util.formatContextDigestForReading
import com.newoether.agora.api.util.formatSummaryBatchesForReading
import com.newoether.agora.api.util.formatTurnIntegrationForReading
import com.newoether.agora.api.util.parseTurnIntegrations
import com.newoether.agora.api.util.copySuppressedDialogueTurnIds
import com.newoether.agora.api.util.isContextProjectionMessageId
import com.newoether.agora.api.util.mutedWireMessageIds
import com.newoether.agora.api.util.omitSuppressedDialogueTurns
import com.newoether.agora.api.util.setDialogueTurnSuppressed
import com.newoether.agora.api.util.showsWireSendCheckbox
import com.newoether.agora.api.util.uniqueBatchNotes
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ContextBudget
import com.newoether.agora.model.Participant
import com.newoether.agora.model.isContextCompact
import com.newoether.agora.model.isProcessCompletionNotice
import com.newoether.agora.tool.CompactTurnIndexer
import com.newoether.agora.ui.components.DialogWindowEdgeToEdge
import com.newoether.agora.ui.components.FadeVerticalLazyListScrollbar
import com.newoether.agora.util.Constants
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.viewmodel.ContextDetailBodyTooLargeException
import com.newoether.agora.viewmodel.ConversationContextDetail
import com.newoether.agora.viewmodel.cacheCorrectedByContextDetail
import com.newoether.agora.viewmodel.persistSendTokenCache
import com.newoether.agora.viewmodel.sendTokenConfigKey
import com.newoether.agora.viewmodel.sendTokenMismatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Full-screen panel showing the exact provider-bound messages that would be sent on the next
 * generation (system prompt + prepared history). Excludes the composer draft.
 */
private val ContextDetailUserLight = Color(0xFFA5D6A7)
private val ContextDetailUserOnLight = Color(0xFF1B5E20)
private val ContextDetailUserDark = Color(0xFF2E7D32)
private val ContextDetailUserOnDark = Color(0xFFE8F5E9)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContextDetailPanel(
    viewModel: ChatViewModel,
    conversationId: String?,
    selectedModelId: String,
    tokenBudget: Int,
    toolConfigurationKey: Any,
    onDismiss: () -> Unit,
) {
    val sendTokenCache by viewModel.sendTokenCache.collectAsState()
    val retainTurns by viewModel.settings.contextCompactRetainCount.collectAsState()
    val configKey = remember(selectedModelId, tokenBudget, retainTurns, toolConfigurationKey) {
        sendTokenConfigKey(selectedModelId, tokenBudget, retainTurns, toolConfigurationKey)
    }
    var loading by remember { mutableStateOf(true) }
    var detail by remember { mutableStateOf<ConversationContextDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(conversationId, selectedModelId, tokenBudget, retainTurns, configKey) {
        loading = true
        error = null
        runCatching {
            val computed = viewModel.previewConversationContextDetail(
                conversationId = conversationId,
                selectedModelId = selectedModelId,
                tokenBudget = tokenBudget,
            )
            if (conversationId != null) {
                viewModel.persistSendTokenCache(
                    conversationId,
                    cacheCorrectedByContextDetail(
                        previous = viewModel.sendTokenCache.value,
                        sentTokens = computed.sentTokenCount,
                        prefixTokens = computed.unbatchedPrefixTokens,
                        fixedCost = computed.fixedTokenCost,
                        ledgerCursor = computed.ledgerCursor,
                        tokenBudget = tokenBudget,
                        retainTurns = retainTurns,
                        configKey = configKey,
                    ),
                )
            }
            computed
        }.onSuccess { computed ->
            detail = computed
            loading = false
        }.onFailure { failure ->
            error = if (failure is ContextDetailBodyTooLargeException) {
                context.getString(R.string.context_detail_too_large)
            } else {
                context.getString(
                    R.string.context_detail_error,
                    failure.message ?: failure::class.java.simpleName,
                )
            }
            loading = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        DialogWindowEdgeToEdge()
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    stringResource(R.string.context_detail_title),
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    stringResource(R.string.context_detail_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.cancel),
                                )
                            }
                        },
                        actions = {
                            val current = detail
                            if (current != null && !loading) {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                exportContextDetailToDownloads(
                                                    context = context,
                                                    detail = current,
                                                    conversationId = conversationId,
                                                )
                                            }
                                            result.fold(
                                                onSuccess = { fileName ->
                                                    snackbar.showSnackbar(
                                                        context.getString(
                                                            R.string.context_detail_exported,
                                                            fileName,
                                                        ),
                                                    )
                                                },
                                                onFailure = { err ->
                                                    snackbar.showSnackbar(
                                                        context.getString(
                                                            R.string.context_detail_export_failed,
                                                            err.message
                                                                ?: err::class.java.simpleName,
                                                        ),
                                                    )
                                                },
                                            )
                                        }
                                    },
                                ) {
                                    Icon(
                                        Icons.Default.Download,
                                        contentDescription = stringResource(R.string.export_button),
                                    )
                                }
                            }
                        },
                    )
                },
                snackbarHost = { SnackbarHost(snackbar) },
            ) { padding ->
                when {
                    loading -> {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(padding),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    error != null -> {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                error.orEmpty(),
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = onDismiss) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    }
                    else -> {
                        val current = detail ?: return@Scaffold
                        ContextDetailBody(
                            detail = current,
                            persistedSendTokens = sendTokenCache
                                .takeIf { it.isSeated() }
                                ?.tokens,
                            contentPadding = padding,
                            onCopyMessageId = {
                                scope.launch {
                                    snackbar.showSnackbar(
                                        context.getString(R.string.context_detail_id_copied),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextDetailBody(
    detail: ConversationContextDetail,
    persistedSendTokens: Int?,
    contentPadding: PaddingValues,
    onCopyMessageId: (String) -> Unit = {},
) {
    val listState = rememberLazyListState()
    // Survives LazyColumn item recycling so collapsed sections stay collapsed.
    val expandedByKey = remember { mutableStateMapOf<String, Boolean>() }
    // Display-only: render summary_batches JSON as separator sections. Does not change wire text.
    var readingOptEnabled by remember { mutableStateOf(false) }
    fun isExpanded(key: String): Boolean = expandedByKey[key] ?: false
    fun toggleExpanded(key: String) {
        expandedByKey[key] = !isExpanded(key)
    }
    val turnNumberByMessageId = remember(detail.exportSourceMessages) {
        CompactTurnIndexer.indexDialogueTurns(detail.exportSourceMessages)
            .flatMapIndexed { index, turn ->
                turn.messages.map { message -> message.id to index + 1 }
            }
            .toMap()
    }
    var suppressedTurnIds by remember { mutableStateOf(copySuppressedDialogueTurnIds()) }
    val mutedIds = remember(detail.messages, suppressedTurnIds) {
        mutedWireMessageIds(detail.messages, suppressedTurnIds)
    }
    val sentTokenCount = remember(detail.messages, detail.fixedTokenCost, suppressedTurnIds) {
        val wire = omitSuppressedDialogueTurns(detail.messages, suppressedTurnIds)
        (ContextTokenEstimator.estimate(wire).toLong() + detail.fixedTokenCost.coerceAtLeast(0))
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "stats") {
                ContextDetailStats(detail, persistedSendTokens, sentTokenCount)
            }
            item(key = "system") {
                ContextDetailBlock(
                    roleLabel = stringResource(R.string.context_detail_role_system),
                    kindLabel = null,
                    idHint = null,
                    tokenCount = ContextTokenEstimator.estimateSystemPrompt(detail.systemPrompt),
                    body = detail.systemPrompt.ifBlank {
                        stringResource(R.string.context_detail_system_empty)
                    },
                    accent = MaterialTheme.colorScheme.tertiaryContainer,
                    onAccent = MaterialTheme.colorScheme.onTertiaryContainer,
                    expanded = isExpanded("system"),
                    onToggleExpanded = { toggleExpanded("system") },
                )
            }
            if (detail.messages.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.context_detail_empty_messages),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                itemsIndexed(
                    items = detail.messages,
                    key = { index, msg -> "${msg.id}#$index" },
                ) { index, message ->
                    val key = "${message.id}#$index"
                    ContextDetailMessageCard(
                        index = index,
                        message = message,
                        turnNumber = turnNumberByMessageId[message.id],
                        expanded = isExpanded(key),
                        onToggleExpanded = { toggleExpanded(key) },
                        readingOptEnabled = readingOptEnabled,
                        onReadingOptChange = { readingOptEnabled = it },
                        onCopyMessageId = onCopyMessageId,
                        sendIncluded = if (showsWireSendCheckbox(message)) {
                            message.id !in suppressedTurnIds
                        } else {
                            null
                        },
                        muted = message.id in mutedIds,
                        onSendIncludedChange = { checked ->
                            setDialogueTurnSuppressed(message.id, !checked)
                            suppressedTurnIds = copySuppressedDialogueTurnIds()
                        },
                    )
                }
            }
        }
        FadeVerticalLazyListScrollbar(state = listState)
    }
}

@Composable
private fun ContextDetailStats(
    detail: ConversationContextDetail,
    persistedSendTokens: Int?,
    sentTokenCount: Int,
) {
    val usage = detail.usage
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                RoundedCornerShape(12.dp),
            )
            .padding(12.dp),
    ) {
        Text(
            stringResource(
                R.string.context_detail_stats,
                detail.messages.size,
                detail.dialogueTurnCount,
                ContextBudget.groupedLabel(detail.originalTokenCount),
                ContextBudget.groupedLabel(sentTokenCount),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (persistedSendTokens != null &&
            sendTokenMismatch(persistedSendTokens, sentTokenCount)
        ) {
            Spacer(Modifier.height(4.dp))
            val delta = sentTokenCount - persistedSendTokens
            val deltaLabel = if (delta < 0) {
                "-${ContextBudget.groupedLabel(-delta)}"
            } else {
                ContextBudget.groupedLabel(delta)
            }
            Text(
                stringResource(
                    R.string.context_detail_send_ledger_mismatch,
                    ContextBudget.groupedLabel(persistedSendTokens),
                    ContextBudget.groupedLabel(sentTokenCount),
                    deltaLabel,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (usage.hasCompactBoundary) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.context_detail_has_compact),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        val freezeCount = contextDetailFreezeChapterCount(detail.messages)
        if (freezeCount >= 2) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.context_detail_freeze_chapters, freezeCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ContextDetailMessageCard(
    index: Int,
    message: ChatMessage,
    turnNumber: Int?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    readingOptEnabled: Boolean,
    onReadingOptChange: (Boolean) -> Unit,
    onCopyMessageId: (String) -> Unit = {},
    sendIncluded: Boolean? = null,
    muted: Boolean = false,
    onSendIncludedChange: (Boolean) -> Unit = {},
) {
    val (roleLabel, accent, onAccent) = contextDetailRoleStyle(message)
    val kind = contextDetailKindLabel(message)
    val wireBody = if (message.isProcessCompletionNotice()) {
        stringResource(R.string.background_process_completed)
    } else {
        contextDetailExportBody(message)
    }
    val isCompactProjection = isContextProjectionMessageId(message.id) ||
        message.isContextCompact()
    val readingBody = formatContextDigestForReading(wireBody)
        ?: formatSummaryBatchesForReading(wireBody)
        ?: formatTurnIntegrationForReading(wireBody)
    val body = if (isCompactProjection && readingOptEnabled) {
        readingBody ?: wireBody
    } else {
        wireBody
    }
    val isOrdinaryUser = message.participant == Participant.USER &&
        !message.id.startsWith(Constants.TOOL_MSG_PREFIX) &&
        !message.id.startsWith(Constants.RESULT_MSG_PREFIX) &&
        !isCompactProjection
    val title = if (isOrdinaryUser && turnNumber != null) {
        "#${index + 1} $roleLabel · ${stringResource(R.string.context_detail_turn_index, turnNumber)}"
    } else {
        "#${index + 1} $roleLabel"
    }
    ContextDetailBlock(
        roleLabel = title,
        kindLabel = kind,
        idHint = message.id,
        tokenCount = ContextTokenEstimator.estimateMessage(message),
        apiMessageId = message.id.takeIf { showsApiResponse(message) },
        copyableMessageId = message.id.takeIf { isOrdinaryUser },
        onCopyMessageId = onCopyMessageId,
        body = body,
        accent = accent,
        onAccent = onAccent,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
        showReadingOpt = isCompactProjection && readingBody != null,
        readingOptEnabled = readingOptEnabled,
        onReadingOptChange = onReadingOptChange,
        sendIncluded = sendIncluded,
        muted = muted,
        onSendIncludedChange = onSendIncludedChange,
    )
}

@Composable
private fun contextDetailRoleStyle(
    message: ChatMessage,
): Triple<String, Color, Color> {
    val isSummary = isContextProjectionMessageId(message.id) ||
        message.isContextCompact()
    return when {
        message.id.startsWith(TimeBandPolicy.TURN_INTEGRATION_ID_PREFIX) -> Triple(
            stringResource(R.string.context_detail_role_integrate),
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        isSummary -> Triple(
            stringResource(R.string.context_detail_role_compact),
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        message.id.startsWith(Constants.TOOL_MSG_PREFIX) ||
            message.id.startsWith(Constants.RESULT_MSG_PREFIX) -> {
            val isResult = message.id.startsWith(Constants.RESULT_MSG_PREFIX)
            Triple(
                stringResource(
                    if (isResult) R.string.context_detail_role_tool_result
                    else R.string.context_detail_role_tool,
                ),
                if (isResult) {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
                MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        message.participant == Participant.USER -> {
            val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
            Triple(
                stringResource(R.string.context_detail_role_user),
                if (dark) ContextDetailUserDark else ContextDetailUserLight,
                if (dark) ContextDetailUserOnDark else ContextDetailUserOnLight,
            )
        }
        message.participant == Participant.MODEL -> Triple(
            stringResource(R.string.context_detail_role_assistant),
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Triple(
            message.participant.name,
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun contextDetailKindLabel(message: ChatMessage): String? {
    val freezeCount = freezeChapterCountFromWire(message.assembleText())
    val freezeLabel = freezeCount.takeIf { it >= 2 }?.let {
        stringResource(R.string.context_detail_kind_freeze_chapters, it)
    }
    return when {
        message.id.startsWith("context_batch_") ->
            freezeLabel ?: stringResource(R.string.context_detail_kind_summary)
        message.id.startsWith("context_digest_") ->
            freezeLabel ?: stringResource(R.string.context_detail_kind_summary)
        message.id.startsWith(TimeBandPolicy.TURN_INTEGRATION_ID_PREFIX) ->
            stringResource(R.string.context_detail_kind_integrate)
        message.id.startsWith("context_summary_") ->
            freezeLabel ?: stringResource(R.string.context_detail_kind_summary)
        message.isContextCompact() ->
            freezeLabel ?: stringResource(R.string.context_detail_kind_compact_row)
        message.id.startsWith(Constants.TOOL_MSG_PREFIX) ->
            stringResource(R.string.context_detail_kind_tool_call)
        message.id.startsWith(Constants.RESULT_MSG_PREFIX) ->
            stringResource(R.string.context_detail_kind_tool_result)
        message.isProcessCompletionNotice() ->
            stringResource(R.string.context_detail_kind_process_notice)
        else -> null
    }
}

@Composable
private fun ContextDetailBlock(
    roleLabel: String,
    kindLabel: String?,
    idHint: String?,
    tokenCount: Int,
    apiMessageId: String? = null,
    copyableMessageId: String? = null,
    body: String,
    accent: Color,
    onAccent: Color,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    showReadingOpt: Boolean = false,
    readingOptEnabled: Boolean = false,
    onReadingOptChange: (Boolean) -> Unit = {},
    onCopyMessageId: ((String) -> Unit)? = null,
    sendIncluded: Boolean? = null,
    muted: Boolean = false,
    onSendIncludedChange: (Boolean) -> Unit = {},
) {
    val headerShape = if (expanded) {
        RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)
    } else {
        RoundedCornerShape(10.dp)
    }
    Column(modifier = Modifier.fillMaxWidth().alpha(if (muted) 0.45f else 1f)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(accent, headerShape)
                .clickable(onClick = onToggleExpanded)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (sendIncluded != null) {
                ContextDetailTurnCheckbox(sendIncluded, onAccent, accent, onSendIncludedChange)
            } else {
                Spacer(Modifier.width(24.dp))
            }
            Text(
                roleLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = onAccent,
            )
            Spacer(Modifier.weight(1f))
            if (kindLabel != null) {
                Text(
                    kindLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = onAccent.copy(alpha = 0.85f),
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
            ContextDetailApiHeaderAction(
                messageId = apiMessageId,
                tokenLabel = stringResource(
                    R.string.context_detail_item_tokens,
                    ContextBudget.groupedLabel(tokenCount),
                ),
                tint = onAccent,
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = onAccent,
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            Surface(
                shape = RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (showReadingOpt) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                stringResource(R.string.context_detail_reading_opt),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Switch(
                                checked = readingOptEnabled,
                                onCheckedChange = onReadingOptChange,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (copyableMessageId != null) {
                        val clipboard = LocalClipboardManager.current
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    clipboard.setText(AnnotatedString(copyableMessageId))
                                    onCopyMessageId?.invoke(copyableMessageId)
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = copyableMessageId,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.copy),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    } else if (idHint != null) {
                        Text(
                            idHint,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    Text(
                        body,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class ContextDetailExportItem(
    val role: String,
    val content: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val id: String? = null,
)

@Serializable
private data class SummaryBatchExportItem(
    val batchId: String,
    val summary: String,
    val origin: List<ContextDetailExportItem> = emptyList(),
)

@Serializable
private data class SummaryBatchesExportPayload(
    val type: String = SUMMARY_BATCHES_TYPE,
    val batches: List<SummaryBatchExportItem>,
)

private val contextDetailExportJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class TurnIntegrationExportItem(
    val turn_id: String,
    val integration: String,
    val original_tokens: Int,
    val integrated_tokens: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val compression_ratio: Double? = null,
    val origin: List<ContextDetailExportItem> = emptyList(),
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class TurnIntegrationsExportPayload(
    val type: String = TimeBandPolicy.TURN_INTEGRATIONS_TYPE,
    val turns: List<TurnIntegrationExportItem>,
)

@Serializable
private data class ContextDigestExportNote(
    val id: String,
    val summary: String = "",
    val origin: List<ContextDetailExportItem> = emptyList(),
)

@Serializable
private data class ContextDigestExportPayload(
    val type: String = CONTEXT_DIGEST_TYPE,
    val rev: Int,
    val digest: String,
    val notes: List<ContextDigestExportNote>,
)

/**
 * Export-only: attach original turns under compact rows. Working-model 驻留 is plain text;
 * old context_digest / summary_batches JSON is still unwrapped. Origin is rebuilt from
 * [sourceMessages], not from the wire.
 */
internal fun enrichCompactContentForExport(
    content: String,
    sourceMessages: List<ChatMessage>,
): String {
    val indexed = CompactTurnIndexer.indexDialogueTurns(sourceMessages)
    val dialogueTurns = indexed
    val originByBatchId = indexed
        .filter { it.batched }
        .groupBy { it.batchId!! }
        .mapValues { (_, turns) -> turns.flatMap { it.messages } }
    val originByTurnId = dialogueTurns.associate { turn ->
        turn.turnId to turn.messages
    }
    fun originItems(messages: List<ChatMessage>): List<ContextDetailExportItem> =
        messages.map { message ->
            val role = contextDetailExportRole(message)
            ContextDetailExportItem(
                role = role,
                content = contextDetailExportBody(message),
                id = message.id.takeIf { role == "user" },
            )
        }
    fun originItemsForBatch(batchId: String): List<ContextDetailExportItem> =
        originItems(originByBatchId[batchId].orEmpty())

    parseTurnIntegrations(content)?.let { payload ->
        val turns = payload.turns.map { item ->
            val originMessages = originByTurnId[item.turn_id].orEmpty()
            val originalTokens = ContextTokenEstimator.estimate(originMessages)
            val integratedTokens = estimateIntegratedTurnTokens(item.integration)
            TurnIntegrationExportItem(
                turn_id = item.turn_id,
                integration = item.integration,
                original_tokens = originalTokens,
                integrated_tokens = integratedTokens,
                compression_ratio = compressionRatio(originalTokens, integratedTokens),
                origin = originItems(originMessages),
            )
        }
        return contextDetailExportJson.encodeToString(
            TurnIntegrationsExportPayload(
                type = TimeBandPolicy.TURN_INTEGRATIONS_TYPE,
                turns = turns,
            ),
        )
    }

    val digest = runCatching {
        contextDetailExportJson.decodeFromString<ContextDigestPayload>(content.trim())
    }.getOrNull()
    if (digest != null && digest.type == CONTEXT_DIGEST_TYPE) {
        return contextDetailExportJson.encodeToString(
            ContextDigestExportPayload(
                type = CONTEXT_DIGEST_TYPE,
                rev = digest.rev,
                digest = digest.digest,
                notes = uniqueBatchNotes(indexed).map { note ->
                    ContextDigestExportNote(
                        id = note.id,
                        summary = note.summary,
                        origin = originItemsForBatch(note.id),
                    )
                },
            ),
        )
    }

    val payload = runCatching {
        contextDetailExportJson.decodeFromString<SummaryBatchesPayload>(content.trim())
    }.getOrNull()
    if (payload != null && payload.type == SUMMARY_BATCHES_TYPE) {
        val enriched = SummaryBatchesExportPayload(
            type = SUMMARY_BATCHES_TYPE,
            batches = payload.batches.map { batch ->
                SummaryBatchExportItem(
                    batchId = batch.batchId,
                    summary = batch.summary,
                    origin = originItemsForBatch(batch.batchId),
                )
            },
        )
        return contextDetailExportJson.encodeToString(enriched)
    }

    val digestRev = indexed.maxOfOrNull { it.digestRev } ?: 0
    return contextDetailExportJson.encodeToString(
        ContextDigestExportPayload(
            type = CONTEXT_DIGEST_TYPE,
            rev = digestRev,
            digest = content,
            notes = uniqueBatchNotes(indexed).map { note ->
                ContextDigestExportNote(
                    id = note.id,
                    summary = note.summary,
                    origin = originItemsForBatch(note.id),
                )
            },
        ),
    )
}

internal fun buildContextDetailExportJson(detail: ConversationContextDetail): String {
    val items = buildList {
        add(ContextDetailExportItem(role = "system", content = detail.systemPrompt))
        detail.messages.forEach { message ->
            val role = contextDetailExportRole(message)
            val raw = contextDetailExportBody(message)
            val content = if (role == "compact") {
                enrichCompactContentForExport(raw, detail.exportSourceMessages)
            } else {
                raw
            }
            add(
                ContextDetailExportItem(
                    role = role,
                    content = content,
                    id = message.id.takeIf { role == "user" },
                ),
            )
        }
    }
    return contextDetailExportJson.encodeToString(items)
}

