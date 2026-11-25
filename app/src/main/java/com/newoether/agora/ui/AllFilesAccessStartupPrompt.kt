package com.newoether.agora.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.newoether.agora.R
import com.newoether.agora.sandbox.SandboxSharedStorageAccess

/**
 * Prompts for all-files / legacy storage access when entering the main UI if not already granted.
 * Dismissing only hides it for this process; the next cold start will ask again until granted.
 */
@Composable
fun AllFilesAccessStartupPrompt() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(SandboxSharedStorageAccess.isGranted(context)) }
    var dismissedForSession by rememberSaveable { mutableStateOf(false) }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        granted = SandboxSharedStorageAccess.isGranted(context)
    }
    val legacyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        granted = SandboxSharedStorageAccess.isGranted(context)
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = SandboxSharedStorageAccess.isGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (granted || dismissedForSession) return
    AllFilesAccessPromptDialog(
        reason = null,
        onDismiss = { dismissedForSession = true },
        onLaunchGrant = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                SandboxSharedStorageAccess.settingsIntent(context)
                    ?.let(settingsLauncher::launch)
                    ?: run { dismissedForSession = true }
            } else {
                legacyLauncher.launch(SandboxSharedStorageAccess.legacyPermissions)
            }
        },
    )
}

/**
 * Tool-driven all-files prompt. [onSettingsAttemptFinished] is invoked after the system
 * Settings / legacy permission UI returns so the awaiting tool call can resolve.
 */
@Composable
fun AllFilesAccessToolRequestDialog(
    reason: String?,
    onDismiss: () -> Unit,
    onSettingsAttemptFinished: () -> Unit,
) {
    val context = LocalContext.current
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        onSettingsAttemptFinished()
    }
    val legacyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        onSettingsAttemptFinished()
    }
    AllFilesAccessPromptDialog(
        reason = reason,
        onDismiss = onDismiss,
        onLaunchGrant = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent = SandboxSharedStorageAccess.settingsIntent(context)
                if (intent != null) settingsLauncher.launch(intent) else onDismiss()
            } else {
                legacyLauncher.launch(SandboxSharedStorageAccess.legacyPermissions)
            }
        },
    )
}

@Composable
private fun AllFilesAccessPromptDialog(
    reason: String?,
    onDismiss: () -> Unit,
    onLaunchGrant: () -> Unit,
) {
    val body = buildString {
        append(stringResource(R.string.shell_all_files_startup_desc))
        if (!reason.isNullOrBlank()) {
            append("\n\n")
            append(reason)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shell_all_files_startup_title), fontWeight = FontWeight.Bold) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onLaunchGrant) {
                Text(stringResource(R.string.shell_all_files_grant))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.shell_all_files_startup_later))
            }
        },
    )
}
