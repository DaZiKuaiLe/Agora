package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.common.OpenAiServiceTierControlPanel
import com.newoether.agora.ui.common.openAiServiceTierShortLabel
import com.newoether.agora.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsGenerationPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val openAiServiceTierEnabled by
        viewModel.settings.openAiServiceTierEnabled.collectAsState()
    val openAiServiceTier by viewModel.settings.openAiServiceTier.collectAsState()
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()

    CollapsingSettingsScaffold(
        title = stringResource(R.string.generation_title),
        onBack = onBack,
        floatingActionButton = { if (showDocFab) DocumentationFab("generation.md") }
    ) {
            SettingsGroupColumn {
                // ── Default OpenAI service tier ──
                SettingsGroup(
                    title = stringResource(R.string.default_service_tier),
                    items = listOf(
                        {
                            SettingsItem(
                                headlineContent = {
                                    Text(stringResource(R.string.openai_service_tier_title))
                                },
                                supportingContent = {
                                    Text(
                                        openAiServiceTierShortLabel(
                                            openAiServiceTierEnabled,
                                            openAiServiceTier,
                                        )
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.Speed,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = openAiServiceTierEnabled,
                                        onCheckedChange =
                                            viewModel.settings::setOpenAiServiceTierEnabled,
                                    )
                                },
                                modifier = Modifier.clickable {
                                    viewModel.settings.setOpenAiServiceTierEnabled(
                                        !openAiServiceTierEnabled,
                                    )
                                },
                            )
                        },
                        {
                            OpenAiServiceTierControlPanel(
                                enabled = openAiServiceTierEnabled,
                                tier = openAiServiceTier,
                                onEnabledChange =
                                    viewModel.settings::setOpenAiServiceTierEnabled,
                                onTierChange = viewModel.settings::setOpenAiServiceTier,
                                modifier = Modifier.padding(
                                    horizontal = 16.dp,
                                    vertical = 16.dp,
                                ),
                                showHeader = false,
                            )
                        },
                    ),
                )
            }

            if (showDocFab) { Spacer(modifier = Modifier.height(80.dp)) }
    }
}
