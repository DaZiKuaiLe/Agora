package com.newoether.agora.api.openai

import com.newoether.agora.api.OpenAiChatRequest
import com.newoether.agora.api.OpenAiThinking
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.model.ThinkingLevels
import com.newoether.agora.util.Constants

open class DeepSeekProvider : BaseOpenAiProvider() {
    override val name: String = Constants.PROVIDER_DEEPSEEK
    override val defaultBaseUrl: String = "https://api.deepseek.com"

    /** DeepSeek V4 (deepseek-v4-flash / deepseek-v4-pro / -vision-exp) exposes thinking as a
     *  per-request parameter rather than a separate model ID, and accepts reasoning_effort
     *  low/high/max (medium/xhigh collapse to high). Sending thinking:disabled truly turns the
     *  trace off, honoring thinkingEnabled instead of only hiding reasoning_content client-side.
     *
     *  Legacy IDs (deepseek-chat / deepseek-reasoner / r1) are left untouched: reasoner has
     *  always-on thinking and rejects reasoning_effort, so sending it would break those models. */
    override fun customizeRequest(request: OpenAiChatRequest, config: ProviderConfig): OpenAiChatRequest {
        val isV4Family = config.modelId.contains("v4", ignoreCase = true)
        if (!isV4Family) return request
        return if (config.thinkingEnabled) {
            request.copy(
                reasoningEffort = ThinkingLevels.deepseekEffort(config.thinkingLevel),
                thinking = OpenAiThinking(type = "enabled"),
            )
        } else {
            request.copy(
                reasoningEffort = null,
                thinking = OpenAiThinking(type = "disabled"),
            )
        }
    }
}
