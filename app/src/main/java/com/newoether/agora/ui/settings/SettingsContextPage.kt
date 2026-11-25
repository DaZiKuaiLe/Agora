package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.modelAliasDisplayName
import com.newoether.agora.data.modelDisplayName
import com.newoether.agora.data.providerDisplayName
import com.newoether.agora.model.ContextBudget
import com.newoether.agora.model.ModelId
import com.newoether.agora.ui.common.PersistedSliderFeedbackGate
import com.newoether.agora.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContextPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val compact by viewModel.settings.contextCompactEnabled.collectAsState()
    val compactModel by viewModel.settings.contextCompactModel.collectAsState()
    val batchSize by viewModel.settings.contextCompactBatchSize.collectAsState()
    val freezeThreshold by viewModel.settings.contextCompactFreezeThreshold.collectAsState()
    val enabledModels by viewModel.settings.enabledModels.collectAsState()
    val aliases by viewModel.settings.modelAliases.collectAsState()
    val customProviders by viewModel.settings.customProviders.collectAsState()
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()
    var modelDialog by remember { mutableStateOf(false) }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.context_title),
        onBack = onBack,
        floatingActionButton = { if (showDocFab) DocumentationFab("context.md") }
    ) {
        SettingsGroupColumn {
            SettingsGroup(
                title = stringResource(R.string.context_compact),
                items = buildList {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.context_compact_model)) },
                            supportingContent = {
                                Text(
                                    compactModel?.let {
                                        modelDisplayName(it, aliases, customProviders)
                                    } ?: stringResource(R.string.title_gen_current_model)
                                )
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Default.Chat,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            modifier = Modifier.clickable { modelDialog = true },
                        )
                    }
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.context_compact_auto)) },
                            supportingContent = { Text(stringResource(R.string.context_compact_auto_desc)) },
                            leadingContent = {
                                Icon(
                                    Icons.Default.Compress,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = compact,
                                    onCheckedChange = viewModel.settings::setContextCompactEnabled,
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.settings.setContextCompactEnabled(!compact)
                            },
                        )
                    }
                    add {
                        val sliderGate = remember {
                            PersistedSliderFeedbackGate(
                                initialPersisted = batchSize,
                                toDisplay = { it.toFloat() },
                            )
                        }
                        LaunchedEffect(batchSize) { sliderGate.reconcile(batchSize) }
                        val draft = sliderGate.displayed
                        ContextSliderItem(
                            icon = Icons.Default.Memory,
                            label = stringResource(R.string.context_compact_batch_size),
                            description = stringResource(R.string.context_compact_batch_size_desc),
                            displayValue = draft.toInt().toString(),
                            sliderValue = draft,
                            valueRange = 2f..32f,
                            steps = 29,
                            onValueChange = sliderGate::updateFromGesture,
                            onValueChangeFinished = {
                                val committed = draft.toInt().coerceIn(2, 32)
                                if (committed != batchSize) {
                                    sliderGate.expectPersisted(committed, committed.toFloat())
                                    viewModel.settings.setContextCompactBatchSize(committed)
                                } else {
                                    sliderGate.settleWithoutWrite(batchSize, committed.toFloat())
                                }
                            },
                        )
                    }
                    add {
                        val presets = ContextBudget.PRESETS
                        fun presetIndex(tokens: Int) =
                            presets.indices.minBy { kotlin.math.abs(presets[it] - tokens) }
                        val sliderGate = remember {
                            PersistedSliderFeedbackGate(
                                initialPersisted = freezeThreshold,
                                toDisplay = { presetIndex(it).toFloat() },
                            )
                        }
                        LaunchedEffect(freezeThreshold) { sliderGate.reconcile(freezeThreshold) }
                        val draftIndex = sliderGate.displayed.toInt()
                            .coerceIn(0, presets.lastIndex)
                        ContextSliderItem(
                            icon = Icons.Default.AcUnit,
                            label = stringResource(R.string.freeze_threshold),
                            description = stringResource(R.string.freeze_threshold_desc),
                            displayValue = ContextBudget.groupedLabel(presets[draftIndex]),
                            sliderValue = draftIndex.toFloat(),
                            valueRange = 0f..(presets.lastIndex).toFloat(),
                            steps = (presets.size - 2).coerceAtLeast(0),
                            onValueChange = sliderGate::updateFromGesture,
                            onValueChangeFinished = {
                                val committed = presets[draftIndex]
                                if (committed != freezeThreshold) {
                                    sliderGate.expectPersisted(committed, draftIndex.toFloat())
                                    viewModel.settings.setContextCompactFreezeThreshold(committed)
                                } else {
                                    sliderGate.settleWithoutWrite(
                                        freezeThreshold,
                                        presetIndex(freezeThreshold).toFloat(),
                                    )
                                }
                            },
                        )
                    }
                },
            )
        }
        if (showDocFab) { Spacer(modifier = Modifier.height(80.dp)) }
    }

    if (modelDialog) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { modelDialog = false },
            title = {
                Text(
                    stringResource(R.string.context_compact_select_model),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    item {
                        CompactModelItem(
                            label = stringResource(R.string.title_gen_current_model),
                            provider = null,
                            selected = compactModel == null,
                            onClick = {
                                viewModel.settings.setContextCompactModel(null)
                                modelDialog = false
                            },
                        )
                    }
                    items(enabledModels.toList(), key = { it }) { model ->
                        val parsed = ModelId.parse(model)
                        CompactModelItem(
                            label = modelAliasDisplayName(
                                model,
                                aliases,
                                customProviders,
                            ),
                            provider = providerDisplayName(parsed.providerName, customProviders),
                            selected = compactModel == model,
                            onClick = {
                                viewModel.settings.setContextCompactModel(model)
                                modelDialog = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { modelDialog = false }) {
                    Text(stringResource(R.string.provider_cancel))
                }
            },
        )
    }
}

@Composable
private fun CompactModelItem(
    label: String,
    provider: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    SettingsItem(
        headlineContent = { Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
        supportingContent = provider?.let {
            {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        },
        leadingContent = { RadioButton(selected = selected, onClick = onClick) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun ContextSliderItem(
    icon: ImageVector,
    label: String,
    description: String,
    displayValue: String,
    sliderValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = displayValue,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Slider(
                    value = sliderValue,
                    onValueChange = onValueChange,
                    valueRange = valueRange,
                    steps = steps,
                    onValueChangeFinished = onValueChangeFinished,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        }
    }
}
