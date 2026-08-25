package com.newoether.agora.model

object ThinkingLevels {
    const val DefaultEffort = "medium"
    const val DefaultBudgetTokens = 4096

    val effortValues = listOf("minimal", "low", "medium", "high", "xhigh", "max")
    val budgetPresets = listOf(1024, 2048, 4096, 8192, 16384, 32768)

    /** The provider's supported effort tiers, in display order. Stored values are a subset of
     *  the abstract [effortValues], chosen so provider mapping functions round-trip cleanly. */
    fun effortsForProvider(providerName: String?): List<String> = when (providerName?.lowercase()) {
        "deepseek" -> listOf("low", "high", "max")
        "openai", "open router" -> listOf("minimal", "low", "medium", "high", "xhigh")
        "google", "gemini" -> listOf("minimal", "low", "medium", "high")
        else -> effortValues
    }

    fun effortRangeForProvider(providerName: String?): IntRange =
        0..effortsForProvider(providerName).lastIndex

    fun effortForIndex(index: Int): String = effortValues.getOrElse(index) { "medium" }

    fun effortForIndexInProvider(providerName: String?, index: Int): String =
        effortsForProvider(providerName).getOrElse(index) { DefaultEffort }

    fun indexForEffort(effort: String): Int =
        effortValues.indexOf(normalizeEffort(effort)).coerceAtLeast(0)

    fun indexForEffortInProvider(providerName: String?, effort: String): Int =
        effortsForProvider(providerName).indexOf(normalizeEffort(effort)).coerceAtLeast(0)

    /** The default effort for a provider when the user has not chosen one. */
    fun defaultEffortForProvider(providerName: String?): String = when (providerName?.lowercase()) {
        "deepseek" -> "high"
        else -> "medium"
    }

    fun normalize(raw: String?): String = normalizeEffortValue(raw)

    fun normalizeEffortValue(raw: String?): String {
        val value = raw?.trim()?.lowercase().orEmpty()
        return when {
            value.isBlank() -> DefaultEffort
            // Legacy compat: old budget mode now migrates into independent budget fields.
            value == "auto" || value == "budget:dynamic" || value.startsWith("budget:") -> DefaultEffort
            else -> normalizeEffort(value)
        }
    }

    fun legacyBudgetTokens(raw: String?): Int? {
        val value = raw?.trim()?.lowercase().orEmpty()
        if (!value.startsWith("budget:")) return null
        return value.substringAfter("budget:").toIntOrNull()?.takeIf { it > 0 }
    }

    fun openAiEffort(effort: String): String = when (normalizeEffort(effort)) {
        "none", "minimal", "low", "medium", "high", "xhigh" -> normalizeEffort(effort)
        "max" -> "xhigh"
        else -> "medium"
    }

    fun openRouterEffort(effort: String): String = when (normalizeEffort(effort)) {
        "none", "minimal", "low", "medium", "high", "xhigh" -> normalizeEffort(effort)
        "max" -> "xhigh"
        else -> "medium"
    }

    fun anthropicEffort(effort: String): String = when (normalizeEffort(effort)) {
        "none", "minimal" -> "low"
        "low", "medium", "high", "xhigh", "max" -> normalizeEffort(effort)
        else -> "medium"
    }

    fun geminiLevel(effort: String): String = when (normalizeEffort(effort)) {
        "none", "minimal" -> "minimal"
        "low" -> "low"
        "medium" -> "medium"
        "high", "xhigh", "max" -> "high"
        else -> "medium"
    }

    /** DeepSeek V4 accepts only low/high/max (medium/xhigh collapse to high, per its docs). */
    fun deepseekEffort(effort: String): String = when (normalizeEffort(effort)) {
        "none", "minimal", "low" -> "low"
        "medium", "high", "xhigh" -> "high"
        "max" -> "max"
        else -> "high"
    }

    private fun normalizeEffort(raw: String): String = when (raw.trim().lowercase()) {
        "none" -> "none"
        "minimal", "min" -> "minimal"
        "low", "quick", "fast" -> "low"
        "medium", "balanced", "balance" -> "medium"
        "high", "deep" -> "high"
        "xhigh", "x-high", "extra-high", "extreme" -> "xhigh"
        "max", "maximum" -> "max"
        else -> "medium"
    }
}
