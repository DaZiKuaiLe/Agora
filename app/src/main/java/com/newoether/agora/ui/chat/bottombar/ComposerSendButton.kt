package com.newoether.agora.ui.chat.bottombar

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.chat.message.COMPOSER_ICON_CROSSFADE_DURATION_MS
import com.newoether.agora.viewmodel.SendAcceptance
import kotlinx.coroutines.launch

private enum class ComposerActionIcon {
    STOPPING,
    PENDING,
    STOP,
    SEND,
}

/**
 * The composer's send / stop / pending-send FAB. Owns the "wait for attachment
 * processing then auto-send" handshake. State changes are rendered atomically so
 * generation start/stop cannot compete with the message-list transition.
 */
@Composable
internal fun ComposerSendButton(
    textFieldState: TextFieldState,
    composer: ChatComposerState,
    isLoading: Boolean,
    isSwitching: Boolean,
    /** A Stop was pressed and the generation is still unwinding. The FAB goes gray + spinner:
     *  the send form returning is the contract that the next message launches immediately. */
    isStopping: Boolean = false,
    /** Cooperative stop was requested. Keep the Stop icon, grayed, and ignore taps until
     *  this turn actually ends. Distinct from [isStopping] (force-stop unwind). */
    isStopRequested: Boolean = false,
    isCompacting: Boolean = false,
    isModelValid: Boolean,
    onSendMessage: suspend (
        String,
        List<SelectedAttachment>,
        suspend () -> Unit,
    ) -> SendAcceptance?,
    onStopGeneration: () -> Unit,
    onCollapse: () -> Unit,
) {
    val haptics = LocalAgoraHaptics.current
    val submitScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    var isSubmitting by remember { mutableStateOf(false) }
    // Pending send: wait for processing to finish, then auto-send
    val anyProcessing = composer.processingStates.isNotEmpty()

    suspend fun submit(
        submittedText: String,
        submittedAttachments: List<SelectedAttachment>,
    ) {
        // Keep path-reference tokens in the stored user message so history can render capsules.
        // ProviderMessageProjector expands them when building the model/tool request.
        val originalText = submittedText
        val submittedAttachmentIds = submittedAttachments.map { it.localId }
        isSubmitting = true
        try {
            // Dismiss the IME immediately on the send tap so the gesture feels responsive;
            // the actual handoff may take time (attachment processing) before the send lands.
            keyboardController?.hide()
            onSendMessage(
                originalText,
                submittedAttachments,
            ) {
                // Confirm the durable handoff, not the initial tap. Attachment-backed sends can
                // spend noticeable time processing before this point.
                // haptics now unified in ChatApp via ChatViewModel.onSendAccepted
                if (composer.selectedAttachments.map { it.localId } == submittedAttachmentIds) {
                    composer.clearAttachments()
                }
                if (textFieldState.text.toString() == originalText) {
                    textFieldState.edit { replace(0, length, "") }
                }
                // End the gray busy state in the same successful handoff that clears the input.
                // The Controller publishes the bubble and scroll only after this callback returns.
                composer.pendingSend = false
                isSubmitting = false
                onCollapse()
            }
        } finally {
            isSubmitting = false
        }
    }

    LaunchedEffect(composer.pendingSend, anyProcessing) {
        if (composer.pendingSend && !anyProcessing) {
            val submittedText = textFieldState.text.toString()
            val submittedAttachments = composer.selectedAttachments.toList()
            submit(submittedText, submittedAttachments)
            composer.pendingSend = false
        }
    }
    val textIsEmpty = textFieldState.text.isBlank()
    val attachmentsIsEmpty = composer.selectedAttachments.isEmpty()
    val showingStop = isLoading && !isStopping && !isCompacting && textIsEmpty && attachmentsIsEmpty
    val stopRequestSpent = isStopRequested && showingStop

    val canSend = (textFieldState.text.isNotBlank() || composer.selectedAttachments.isNotEmpty()) &&
        isModelValid && !isSwitching && !isStopping && !isCompacting && !isSubmitting &&
        composer.selectedAttachments.none { it.localPath == null && (it.type == "image" || it.type == "file") }
    val isBusy = isStopping || isCompacting || isSubmitting || composer.pendingSend
    val isActionable = (isLoading || canSend) && !isSwitching && !isBusy && !stopRequestSpent
    val containerColor by animateColorAsState(
        targetValue = if (isActionable) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(durationMillis = 400),
        label = "fabContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isActionable) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 400),
        label = "fabContent",
    )
    FloatingActionButton(
        onClick = {
            if (isSwitching || isStopping || isCompacting || stopRequestSpent) return@FloatingActionButton
            if (showingStop) onStopGeneration()
            else if (composer.pendingSend) {
                haptics.selection()
                composer.pendingSend = false
            }
            else if (canSend) {
                if (anyProcessing) {
                    composer.pendingSend = true
                } else {
                    val submittedText = textFieldState.text.toString()
                    val submittedAttachments = composer.selectedAttachments.toList()
                    submitScope.launch {
                        submit(submittedText, submittedAttachments)
                    }
                }
            }
        },
        containerColor = containerColor,
        contentColor = contentColor,
        modifier = Modifier.size(46.dp),
        shape = CircleShape,
        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)
    ) {
        val fabIcon = when {
            isStopping || isSubmitting || isCompacting -> ComposerActionIcon.STOPPING
            composer.pendingSend -> ComposerActionIcon.PENDING
            showingStop -> ComposerActionIcon.STOP
            else -> ComposerActionIcon.SEND
        }
        Crossfade(
            targetState = fabIcon,
            animationSpec = tween(
                durationMillis = COMPOSER_ICON_CROSSFADE_DURATION_MS,
                easing = LinearEasing,
            ),
            label = "composerActionIcon",
        ) { icon ->
            when (icon) {
                ComposerActionIcon.STOPPING -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ComposerActionIcon.PENDING -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ComposerActionIcon.STOP -> Icon(
                    Icons.Default.Stop,
                    stringResource(
                        if (isStopRequested) R.string.stop_generation_requested
                        else R.string.stop_generation,
                    ),
                    modifier = Modifier.size(24.dp),
                )
                ComposerActionIcon.SEND -> Icon(
                    Icons.Default.ArrowUpward,
                    stringResource(R.string.action),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
