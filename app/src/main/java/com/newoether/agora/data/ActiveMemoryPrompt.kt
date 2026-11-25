package com.newoether.agora.data

internal const val ACTIVE_MEMORY_CONTEXT_OPEN = "<active_memory_context>"
internal const val ACTIVE_MEMORY_CONTEXT_CLOSE = "</active_memory_context>"

private val activeMemoryBlock =
    Regex("""$ACTIVE_MEMORY_CONTEXT_OPEN[\s\S]*?$ACTIVE_MEMORY_CONTEXT_CLOSE""")

internal fun wrapActiveMemoryContext(body: String): String =
    "$ACTIVE_MEMORY_CONTEXT_OPEN\n${body.trim()}\n$ACTIVE_MEMORY_CONTEXT_CLOSE"

/**
 * Compile-time injection: non-blank active memory always appears in the system prompt inside
 * [ACTIVE_MEMORY_CONTEXT_OPEN] markers. Custom prompts without `{active_memory}` get the block
 * appended. Blank memory removes the block so empty tags are not sent.
 */
internal fun applyActiveMemoryToSystemPrompt(
    compiledSystemPrompt: String?,
    activeMemory: String,
): String? {
    val text = compiledSystemPrompt.orEmpty()
    val body = activeMemory.trim()
    if (body.isEmpty()) {
        return activeMemoryBlock.replace(text, "").trim().ifBlank { null }
    }
    val wrapped = wrapActiveMemoryContext(body)
    val withBlock = if (activeMemoryBlock.containsMatchIn(text)) {
        activeMemoryBlock.replace(text, wrapped)
    } else if (text.isBlank()) {
        wrapped
    } else {
        text.trimEnd() + "\n\n" + wrapped
    }
    return withBlock.trim().ifBlank { null }
}
