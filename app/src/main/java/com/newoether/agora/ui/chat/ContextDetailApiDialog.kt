package com.newoether.agora.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.ApiResponseArchive
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun showsApiResponse(message: ChatMessage): Boolean =
    message.id.startsWith(Constants.TOOL_MSG_PREFIX) ||
        (
            message.participant == Participant.MODEL &&
                !message.id.startsWith(Constants.RESULT_MSG_PREFIX)
            )

@Composable
internal fun ContextDetailApiHeaderAction(
    messageId: String?,
    tokenLabel: String,
    tint: Color,
) {
    if (messageId != null) {
        var open by remember(messageId) { mutableStateOf(false) }
        Text(
            stringResource(R.string.context_detail_api),
            color = tint,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .padding(end = 8.dp)
                .clickable { open = true },
        )
        if (open) {
            ContextDetailApiDialog(messageId = messageId, onDismiss = { open = false })
        }
    }
    Text(
        tokenLabel,
        style = MaterialTheme.typography.labelSmall,
        color = tint.copy(alpha = 0.85f),
        modifier = Modifier.padding(end = 6.dp),
    )
}

@Composable
private fun ContextDetailApiDialog(
    messageId: String,
    onDismiss: () -> Unit,
) {
    var loaded by remember(messageId) { mutableStateOf(false) }
    var body by remember(messageId) { mutableStateOf<String?>(null) }
    LaunchedEffect(messageId) {
        body = withContext(Dispatchers.IO) { ApiResponseArchive.read(messageId) }
        loaded = true
    }
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = {
            Text(stringResource(R.string.context_detail_api), fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                val shown = when {
                    !loaded -> stringResource(R.string.context_detail_api_loading)
                    body == null -> stringResource(R.string.context_detail_api_missing)
                    else -> body.orEmpty()
                }
                SelectionContainer {
                    Text(
                        shown,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    body?.let { clipboard.setText(AnnotatedString(it)) }
                    onDismiss()
                },
                enabled = body != null,
            ) {
                Text(stringResource(R.string.copy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
