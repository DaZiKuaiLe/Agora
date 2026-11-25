package com.newoether.agora.ui.chat

import com.newoether.agora.ui.chat.bottombar.CHAT_DROPDOWN_MENU_SHAPE

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.modelDisplayName
import com.newoether.agora.ui.components.clearFocusOnTap

/** Manual incremental archive compact: model only (prompt and B come from built-in / settings). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatManualCompactDialog(
    initialModel: String,
    enabledModels: Set<String>,
    modelAliases: Map<String, String>,
    customProviders: List<CustomProviderConfig>,
    isCompacting: Boolean,
    onCompact: (model: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var model by remember(initialModel) { mutableStateOf(initialModel) }
    var modelMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val busy = submitting || isCompacting
    val unavailableModelError = stringResource(R.string.context_compact_select_available_model)

    LaunchedEffect(isCompacting) {
        if (isCompacting) onDismiss()
    }

    AlertDialog(
        modifier = Modifier.clearFocusOnTap(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                stringResource(R.string.context_compact_manual),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CompactModelPicker(
                    model = model,
                    modelMenu = modelMenu,
                    enabledModels = enabledModels,
                    modelAliases = modelAliases,
                    customProviders = customProviders,
                    busy = busy,
                    onModelMenu = { modelMenu = it },
                    onModel = { model = it; error = null },
                )
                Text(
                    stringResource(R.string.context_compact_batch_size_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !busy,
            ) {
                Text(stringResource(R.string.provider_cancel))
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    if (submitting || isCompacting) return@TextButton
                    when {
                        model !in enabledModels -> error = unavailableModelError
                        else -> {
                            error = null
                            submitting = true
                            onCompact(model)
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.context_compact))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatFullContextOptimizeDialog(
    initialModel: String,
    enabledModels: Set<String>,
    modelAliases: Map<String, String>,
    customProviders: List<CustomProviderConfig>,
    isCompacting: Boolean,
    onOptimize: (model: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var model by remember(initialModel) { mutableStateOf(initialModel) }
    var modelMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val busy = submitting || isCompacting
    val unavailableModelError = stringResource(R.string.context_compact_select_available_model)

    LaunchedEffect(isCompacting) {
        if (isCompacting) onDismiss()
    }

    AlertDialog(
        modifier = Modifier.clearFocusOnTap(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                stringResource(R.string.context_full_optimize_title),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.context_full_optimize_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CompactModelPicker(
                    model = model,
                    modelMenu = modelMenu,
                    enabledModels = enabledModels,
                    modelAliases = modelAliases,
                    customProviders = customProviders,
                    busy = busy,
                    onModelMenu = { modelMenu = it },
                    onModel = { model = it; error = null },
                )
                error?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !busy,
            ) {
                Text(stringResource(R.string.provider_cancel))
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    if (submitting || isCompacting) return@TextButton
                    when {
                        model !in enabledModels -> error = unavailableModelError
                        else -> {
                            error = null
                            submitting = true
                            onOptimize(model)
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.context_full_optimize))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactModelPicker(
    model: String,
    modelMenu: Boolean,
    enabledModels: Set<String>,
    modelAliases: Map<String, String>,
    customProviders: List<CustomProviderConfig>,
    busy: Boolean,
    onModelMenu: (Boolean) -> Unit,
    onModel: (String) -> Unit,
) {
    ExposedDropdownMenuBox(
        expanded = modelMenu,
        onExpandedChange = { if (!busy) onModelMenu(it) },
    ) {
        OutlinedTextField(
            value = modelDisplayName(model, modelAliases, customProviders),
            onValueChange = {},
            readOnly = true,
            enabled = !busy,
            singleLine = true,
            label = { Text(stringResource(R.string.context_compact_model)) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenu)
            },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(
                    type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                    enabled = !busy,
                ),
        )
        ExposedDropdownMenu(
            expanded = modelMenu,
            onDismissRequest = { onModelMenu(false) },
            shape = CHAT_DROPDOWN_MENU_SHAPE,
        ) {
            enabledModels.sorted().forEach { candidate ->
                DropdownMenuItem(
                    text = {
                        Text(modelDisplayName(candidate, modelAliases, customProviders))
                    },
                    onClick = {
                        onModel(candidate)
                        onModelMenu(false)
                    },
                )
            }
        }
    }
}
