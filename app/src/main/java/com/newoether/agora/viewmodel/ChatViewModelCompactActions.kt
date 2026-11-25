package com.newoether.agora.viewmodel

import androidx.lifecycle.viewModelScope
import com.newoether.agora.R
import com.newoether.agora.data.BuiltInPrompts
import kotlinx.coroutines.launch

internal fun ChatViewModel.launchFullContextOptimize(model: String) {
    viewModelScope.launch {
        val result = try {
            generationController.optimizeFullContext(model)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (_: Exception) {
            CompactResult.Failed(CompactFailureReason.GENERIC)
        }
        when (result) {
            is CompactResult.Failed -> presentInteractiveCompactFailure(result)
            is CompactResult.Created ->
                if (result.integrityIssues.isEmpty()) {
                    emitSnackbar(appContext.getString(R.string.context_full_optimize_done))
                }
            CompactResult.NotNeeded ->
                emitSnackbar(appContext.getString(R.string.context_full_optimize_done))
            is CompactResult.Stopped -> Unit
        }
    }
}

fun ChatViewModel.startFullContextOptimize(model: String) = launchFullContextOptimize(model)

internal fun ChatViewModel.launchContextCompactManual(model: String, fullRecompact: Boolean) {
    viewModelScope.launch {
        val result = try {
            generationController.compactManual(
                CompactRequest(
                    model = model,
                    prompt = BuiltInPrompts.CONTEXT_COMPACT_SYSTEM,
                    retainLogicalMessages = settings.contextCompactBatchSize.value,
                    fullRecompact = fullRecompact,
                ),
            )
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (_: Exception) {
            CompactResult.Failed(CompactFailureReason.GENERIC)
        }
        emitCompactOutcomeSnackbar(result)
    }
}

internal fun ChatViewModel.launchContextRecompact(messageId: String) {
    val model = settings.contextCompactModel.value?.takeIf(String::isNotBlank)
        ?: currentActiveModel.value
    viewModelScope.launch {
        val result = try {
            generationController.compactManual(
                CompactRequest(
                    model = model,
                    prompt = BuiltInPrompts.CONTEXT_COMPACT_SYSTEM,
                    retainLogicalMessages = settings.contextCompactBatchSize.value,
                    replaceMessageId = messageId,
                    fullRecompact = true,
                ),
            )
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (_: Exception) {
            CompactResult.Failed(CompactFailureReason.GENERIC)
        }
        emitCompactOutcomeSnackbar(result)
    }
}

private fun ChatViewModel.emitCompactOutcomeSnackbar(result: CompactResult) {
    when (result) {
        is CompactResult.Failed -> presentInteractiveCompactFailure(result)
        is CompactResult.Created ->
            if (result.integrityIssues.isEmpty()) {
                emitSnackbar(appContext.getString(R.string.context_compact_done))
            }
        CompactResult.NotNeeded ->
            emitSnackbar(appContext.getString(R.string.context_compact_done))
        is CompactResult.Stopped -> Unit
    }
}

private fun ChatViewModel.presentInteractiveCompactFailure(failed: CompactResult.Failed) {
    if (failed.shouldPresentAsDialog()) {
        generationController.presentCompactTurnTooLarge()
    } else {
        emitSnackbar(compactFailureMessage(appContext, failed))
    }
}
