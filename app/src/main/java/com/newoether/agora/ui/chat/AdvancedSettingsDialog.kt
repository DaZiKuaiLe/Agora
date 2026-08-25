package com.newoether.agora.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.ModelGenerationSettings
import com.newoether.agora.model.ContextBudget
import com.newoether.agora.ui.common.ThinkingControlPanel
import java.util.Locale

@Composable
fun AdvancedSettingsDialog(
    current: ModelGenerationSettings,
    providerName: String?,
    onSave: (ModelGenerationSettings) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var contextLimit by remember { mutableStateOf(current.contextLimit) }
    var compactThreshold by remember { mutableStateOf(current.compactThreshold) }
    var temperature by remember { mutableStateOf(current.temperature) }
    var maxTokens by remember { mutableStateOf(current.maxTokens) }
    var topP by remember { mutableStateOf(current.topP) }
    var frequencyPenalty by remember { mutableStateOf(current.frequencyPenalty) }
    var presencePenalty by remember { mutableStateOf(current.presencePenalty) }
    var thinkingEnabled by remember { mutableStateOf(current.thinkingEnabled) }
    var thinkingLevel by remember { mutableStateOf(current.thinkingLevel) }
    var thinkingBudgetEnabled by remember { mutableStateOf(current.thinkingBudgetEnabled) }
    var thinkingBudgetTokens by remember { mutableStateOf(current.thinkingBudgetTokens) }

    fun currentSettings() = current.copy(
        contextLimit = contextLimit,
        compactThreshold = compactThreshold,
        temperature = temperature,
        maxTokens = maxTokens,
        topP = topP,
        frequencyPenalty = frequencyPenalty,
        presencePenalty = presencePenalty,
        thinkingEnabled = thinkingEnabled,
        thinkingLevel = thinkingLevel,
        thinkingBudgetEnabled = thinkingBudgetEnabled,
        thinkingBudgetTokens = thinkingBudgetTokens
    )

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.advanced_generation_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                val fmt2: (Float) -> String = { v -> String.format(Locale.US, "%.2f", v) }

                // Context limit (real model ceiling, user-supplied).
                AdvancedParamRow(
                    label = stringResource(R.string.context_limit),
                    value = contextLimit,
                    presets = ContextBudget.PRESETS,
                    format = ContextBudget::compactLabel,
                    onChange = { contextLimit = it },
                    onReset = { contextLimit = null },
                    defaultValue = ContextBudget.DEFAULT_CONTEXT_LIMIT
                )
                // Compaction threshold (absolute token count).
                AdvancedParamRow(
                    label = stringResource(R.string.compact_threshold),
                    value = compactThreshold,
                    presets = ContextBudget.PRESETS,
                    format = ContextBudget::compactLabel,
                    onChange = { compactThreshold = it },
                    onReset = { compactThreshold = null },
                    defaultValue = ContextBudget.DEFAULT_COMPACT_THRESHOLD
                )
                // Temperature
                AdvancedParamRow(
                    label = stringResource(R.string.gen_temperature),
                    value = temperature,
                    valueRange = 0f..2f,
                    format = fmt2,
                    onChange = { temperature = it },
                    onReset = { temperature = null }
                )
                // Max Tokens
                val maxTokensPresets = intArrayOf(256, 512, 1024, 2048, 4096, 8192, 16384, 32768)
                AdvancedParamRow(
                    label = stringResource(R.string.gen_max_tokens),
                    value = maxTokens,
                    presets = maxTokensPresets,
                    format = { it.toString() },
                    onChange = { maxTokens = it },
                    onReset = { maxTokens = null }
                )
                // Top P
                AdvancedParamRow(
                    label = stringResource(R.string.gen_top_p),
                    value = topP,
                    valueRange = 0f..1f,
                    format = fmt2,
                    onChange = { topP = it },
                    onReset = { topP = null }
                )
                // Frequency Penalty
                AdvancedParamRow(
                    label = stringResource(R.string.gen_frequency_penalty),
                    value = frequencyPenalty,
                    valueRange = -2f..2f,
                    format = fmt2,
                    onChange = { frequencyPenalty = it },
                    onReset = { frequencyPenalty = null }
                )
                // Presence Penalty
                AdvancedParamRow(
                    label = stringResource(R.string.gen_presence_penalty),
                    value = presencePenalty,
                    valueRange = -2f..2f,
                    format = fmt2,
                    onChange = { presencePenalty = it },
                    onReset = { presencePenalty = null }
                )
                // Thinking depth (provider-specific tiers).
                Spacer(modifier = Modifier.height(12.dp))
                ThinkingControlPanel(
                    enabled = thinkingEnabled,
                    level = thinkingLevel,
                    budgetEnabled = thinkingBudgetEnabled,
                    budgetTokens = thinkingBudgetTokens,
                    onEnabledChange = { thinkingEnabled = it },
                    onLevelChange = { thinkingLevel = it },
                    onBudgetEnabledChange = { thinkingBudgetEnabled = it },
                    onBudgetTokensChange = { thinkingBudgetTokens = it },
                    providerName = providerName,
                    showHeader = true,
                    animateSections = false
                )
            }
        },
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = {
                    onReset()
                    contextLimit = null; compactThreshold = null
                    temperature = null; maxTokens = null; topP = null
                    frequencyPenalty = null; presencePenalty = null
                    thinkingEnabled = true; thinkingLevel = ""
                    thinkingBudgetEnabled = false; thinkingBudgetTokens = 4096
                }) { Text(stringResource(R.string.gen_reset)) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.provider_cancel))
                    }
                    TextButton(onClick = { onSave(currentSettings()) }) {
                        Text(stringResource(R.string.provider_save))
                    }
                }
            }
        },
        dismissButton = null
    )
}

/**
 * Parameter row for the Advanced dialog. Always shows value.
 * When value is null, shows "not specified" and the slider sits at mid-range.
 * Reset clears the local override (doesn't close dialog).
 */
@Composable
private fun AdvancedParamRow(
    label: String,
    value: Float?,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
    onReset: () -> Unit
) {
    val sliderPos = value ?: (valueRange.start + valueRange.endInclusive) / 2f
    val isOverride = value != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            if (isOverride) {
                Text(
                    text = format(value),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = stringResource(R.string.gen_reset),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.clickable(onClick = onReset)
                )
            } else {
                Text(
                    text = stringResource(R.string.gen_not_specified),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
        Slider(
            value = sliderPos,
            onValueChange = onChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun AdvancedParamRow(
    label: String,
    value: Int?,
    presets: IntArray,
    format: (Int) -> String,
    onChange: (Int) -> Unit,
    onReset: () -> Unit,
    defaultValue: Int? = null,
) {
    fun toIndex(v: Int) = presets.indices.minByOrNull { kotlin.math.abs(presets[it] - v) } ?: 3
    val effective = value ?: defaultValue
    val index = if (effective != null) toIndex(effective) else 3.coerceIn(0, presets.lastIndex)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            if (value != null) {
                Text(
                    text = format(value),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = stringResource(R.string.gen_reset),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.clickable(onClick = onReset)
                )
            } else if (effective != null) {
                Text(
                    text = format(effective),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Normal
                )
            } else {
                Text(
                    text = stringResource(R.string.gen_not_specified),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
        Slider(
            value = index.toFloat(),
            onValueChange = { onChange(presets[it.toInt().coerceIn(0, presets.lastIndex)]) },
            valueRange = 0f..(presets.size - 1).toFloat(),
            steps = presets.size - 2,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
