package com.newoether.agora.viewmodel

enum class DigestIntegrityKind {
    MISSING_CLOSED_SET_CITES,
    ILLEGAL_CITES_STRIPPED,
    DIGEST_WRITE_FAILED,
    DIGEST_MISSING_AFTER_COMPACT,
}

data class DigestIntegrityIssue(
    val kind: DigestIntegrityKind,
    val items: List<String> = emptyList(),
)

data class DigestIntegrityAlert(
    val conversationId: String,
    val issues: List<DigestIntegrityIssue>,
)

internal data class DigestRefreshOutcome(
    val persisted: Boolean,
    val issues: List<DigestIntegrityIssue>,
)

internal fun mergeOptimizeResults(
    integrated: CompactResult,
    archived: CompactResult,
): CompactResult {
    if (archived is CompactResult.Failed) return archived
    val issues = (archived as? CompactResult.Created)?.integrityIssues.orEmpty()
    if (issues.isEmpty()) return integrated
    return when (integrated) {
        is CompactResult.Created -> integrated.copy(integrityIssues = issues)
        else -> CompactResult.Created(
            messageId = (archived as CompactResult.Created).messageId,
            integrityIssues = issues,
        )
    }
}

internal fun mergeDigestIntegrityIssues(issues: List<DigestIntegrityIssue>): List<DigestIntegrityIssue> {
    if (issues.isEmpty()) return emptyList()
    return DigestIntegrityKind.entries.mapNotNull { kind ->
        val matched = issues.filter { it.kind == kind }
        if (matched.isEmpty()) return@mapNotNull null
        DigestIntegrityIssue(kind, matched.flatMap { it.items }.distinct())
    }
}
