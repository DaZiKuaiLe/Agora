package com.newoether.agora.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.isResponsesApiEnabledForProvider
import com.newoether.agora.model.OpenAiServiceTiers
import com.newoether.agora.ui.common.AgoraHaptics
import com.newoether.agora.viewmodel.ChatViewModel

internal data class OpenAiConversationServiceTierState(
    val available: Boolean,
    val enabled: Boolean,
    val tier: String,
)

internal fun resolveOpenAiConversationServiceTier(
    globalEnabled: Boolean,
    globalTier: String,
    providerName: String,
    builtInOpenAiResponsesEnabled: Boolean,
    customProviders: List<CustomProviderConfig>,
): OpenAiConversationServiceTierState = OpenAiConversationServiceTierState(
    available = isResponsesApiEnabledForProvider(
        providerName = providerName,
        builtInOpenAiEnabled = builtInOpenAiResponsesEnabled,
        customProviders = customProviders,
    ),
    enabled = globalEnabled,
    tier = OpenAiServiceTiers.normalize(globalTier),
)

@Composable
internal fun openAiConversationServiceTierState(
    viewModel: ChatViewModel,
    providerName: String,
    builtInOpenAiResponsesEnabled: Boolean,
    customProviders: List<CustomProviderConfig>,
): OpenAiConversationServiceTierState {
    val globalEnabled by viewModel.settings.openAiServiceTierEnabled.collectAsState()
    val globalTier by viewModel.settings.openAiServiceTier.collectAsState()
    return resolveOpenAiConversationServiceTier(
        globalEnabled = globalEnabled,
        globalTier = globalTier,
        providerName = providerName,
        builtInOpenAiResponsesEnabled = builtInOpenAiResponsesEnabled,
        customProviders = customProviders,
    )
}

internal fun updateOpenAiConversationServiceTierEnabled(
    viewModel: ChatViewModel,
    haptics: AgoraHaptics,
    enabled: Boolean,
) {
    haptics.toggle(enabled)
    viewModel.settings.setOpenAiServiceTierEnabled(enabled)
}

internal fun updateOpenAiConversationServiceTier(
    viewModel: ChatViewModel,
    haptics: AgoraHaptics,
    tier: String,
) {
    haptics.selection()
    viewModel.settings.setOpenAiServiceTier(tier)
}
