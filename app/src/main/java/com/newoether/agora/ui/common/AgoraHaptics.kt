package com.newoether.agora.ui.common

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import com.newoether.agora.service.AppForegroundTracker

@Stable
interface AgoraHaptics {
    fun selection()
    fun toggle(isOn: Boolean)
    fun longPress()
    fun confirm()
    fun reject()
    fun interrupt()
    fun destructiveConfirmed()
    fun startAnsweringTexture()
    fun stopAnsweringTexture()
}

object NoOpAgoraHaptics : AgoraHaptics {
    override fun selection() = Unit
    override fun toggle(isOn: Boolean) = Unit
    override fun longPress() = Unit
    override fun confirm() = Unit
    override fun reject() = Unit
    override fun interrupt() = Unit
    override fun destructiveConfirmed() = Unit
    override fun startAnsweringTexture() = Unit
    override fun stopAnsweringTexture() = Unit
}

val LocalAgoraHaptics = compositionLocalOf<AgoraHaptics> { NoOpAgoraHaptics }

@Composable
fun rememberAgoraHaptics(enabled: Boolean): AgoraHaptics {
    val view = LocalView.current
    val enabledState = rememberUpdatedState(enabled)
    val haptics = remember(view) {
        PlatformAgoraHaptics(view) { enabledState.value }
    }
    DisposableEffect(haptics) {
        val listener: (Boolean) -> Unit = { inForeground ->
            if (!inForeground) haptics.stopAnsweringTexture()
        }
        AppForegroundTracker.addListener(listener)
        onDispose {
            AppForegroundTracker.removeListener(listener)
            haptics.stopAnsweringTexture()
        }
    }
    return haptics
}

private class PlatformAgoraHaptics(
    private val view: View,
    private val enabled: () -> Boolean
) : AgoraHaptics {
    override fun selection() =
        performDiscrete(selectionFeedbackForSdk(Build.VERSION.SDK_INT))

    override fun toggle(isOn: Boolean) =
        performDiscrete(toggleFeedbackForSdk(Build.VERSION.SDK_INT, isOn))

    override fun longPress() = performDiscrete(HapticFeedbackConstants.LONG_PRESS)

    override fun confirm() =
        performDiscrete(confirmFeedbackForSdk(Build.VERSION.SDK_INT))

    override fun reject() =
        performDiscrete(rejectFeedbackForSdk(Build.VERSION.SDK_INT))

    override fun interrupt() {
        performTerminalFeedback()
    }

    override fun destructiveConfirmed() {
        performTerminalFeedback()
    }

    private fun performTerminalFeedback() {
        performDiscrete(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    // Answering texture (continuous vibration during streaming output) is disabled by request.
    override fun startAnsweringTexture() = Unit
    override fun stopAnsweringTexture() = Unit

    private fun performDiscrete(type: Int) {
        if (!isAllowed()) return
        view.performHapticFeedback(type)
    }

    private fun isAllowed(): Boolean = enabled() && AppForegroundTracker.isInForeground
}

internal fun selectionFeedbackForSdk(sdkInt: Int): Int =
    if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        HapticFeedbackConstants.SEGMENT_TICK
    } else {
        HapticFeedbackConstants.CLOCK_TICK
    }

internal fun toggleFeedbackForSdk(sdkInt: Int, isOn: Boolean): Int =
    if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        if (isOn) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
    } else {
        HapticFeedbackConstants.CLOCK_TICK
    }

internal fun confirmFeedbackForSdk(sdkInt: Int): Int =
    if (sdkInt >= Build.VERSION_CODES.R) {
        HapticFeedbackConstants.CONFIRM
    } else {
        HapticFeedbackConstants.VIRTUAL_KEY
    }

internal fun rejectFeedbackForSdk(sdkInt: Int): Int =
    if (sdkInt >= Build.VERSION_CODES.R) {
        HapticFeedbackConstants.REJECT
    } else {
        HapticFeedbackConstants.LONG_PRESS
    }
