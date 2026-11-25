package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsMemoryPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val unknownError = stringResource(R.string.unknown_error)
    var activeMemoryContent by remember { mutableStateOf("") }
    var memoryLoaded by remember { mutableStateOf(false) }
    var memoryOperationInFlight by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    var editorContent by remember { mutableStateOf("") }

    fun reportMemoryFailure(action: String, error: Throwable) {
        DebugLog.e("SettingsMemory", action, error)
        viewModel.emitSnackbar(error.localizedMessage ?: unknownError)
    }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { viewModel.memoryManager.getActiveMemory() }
        }
        loaded.onSuccess { activeMemoryContent = it }
            .onFailure { error -> reportMemoryFailure("Unable to load memories", error) }
        memoryLoaded = true
    }
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()

    CollapsingSettingsScaffold(
        title = stringResource(R.string.memory_title),
        onBack = onBack,
        floatingActionButton = { if (showDocFab) DocumentationFab("memory.md") }
    ) {
        SettingsGroupColumn {
            SettingsGroup(
                title = stringResource(R.string.memory_active_title),
                items = listOf(
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.memory_active_context)) },
                            supportingContent = {
                                if (memoryLoaded) {
                                    Text(
                                        if (activeMemoryContent.isBlank()) {
                                            stringResource(R.string.memory_active_empty)
                                        } else {
                                            activeMemoryContent.take(100) +
                                                if (activeMemoryContent.length > 100) "..." else ""
                                        }
                                    )
                                } else {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            },
                            leadingContent = {
                                Icon(Icons.Default.Memory, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier.clickable(
                                enabled = memoryLoaded && !memoryOperationInFlight,
                            ) {
                                editorContent = activeMemoryContent
                                showEditor = true
                            }
                        )
                    }
                )
            )
        }
        if (showDocFab) { Spacer(modifier = Modifier.height(80.dp)) }
    }

    if (showEditor) {
        var editContent by remember { mutableStateOf(editorContent) }
        AlertDialog(
            modifier = Modifier.clearFocusOnTap(),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showEditor = false },
            title = {
                Text(stringResource(R.string.memory_edit_active), fontWeight = FontWeight.Bold)
            },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.memory_active_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editContent,
                        onValueChange = { editContent = it },
                        label = { Text(stringResource(R.string.memory_content_hint)) },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp, max = 400.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!memoryOperationInFlight) {
                            val contentSnapshot = editContent
                            memoryOperationInFlight = true
                            scope.launch {
                                val saved = withContext(Dispatchers.IO) {
                                    runCatching {
                                        viewModel.memoryManager.updateActiveMemory(contentSnapshot)
                                        viewModel.memoryManager.getActiveMemory()
                                    }
                                }
                                saved.onSuccess { active ->
                                    activeMemoryContent = active
                                    showEditor = false
                                }.onFailure { error ->
                                    reportMemoryFailure("Unable to save memory", error)
                                }
                                memoryOperationInFlight = false
                            }
                        }
                    },
                    enabled = !memoryOperationInFlight,
                ) { Text(stringResource(R.string.provider_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showEditor = false }) {
                    Text(stringResource(R.string.provider_cancel))
                }
            }
        )
    }
}
