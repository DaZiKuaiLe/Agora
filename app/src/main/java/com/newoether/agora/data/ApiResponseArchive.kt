package com.newoether.agora.data

import java.io.File

/**
 * One raw provider response per message, outside the message table.
 * Conversation open and context detail never read this directory.
 */
internal object ApiResponseArchive {
    private var root: File? = null

    fun install(filesDir: File) {
        root = File(filesDir, "api-responses")
    }

    fun write(messageId: String, body: String) {
        if (body.isEmpty()) return
        val file = file(messageId) ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(body)
        }
    }

    fun read(messageId: String): String? {
        val file = file(messageId) ?: return null
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull()
    }

    fun delete(messageIds: Collection<String>) {
        messageIds.forEach { id -> runCatching { file(id)?.delete() } }
    }

    private fun file(messageId: String): File? {
        val dir = root ?: return null
        val name = safeName(messageId) ?: return null
        return File(dir, name)
    }

    private fun safeName(messageId: String): String? {
        if (messageId.isEmpty()) return null
        val safe = buildString(messageId.length) {
            for (ch in messageId) {
                append(if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '_')
            }
        }
        if (safe.isEmpty() || safe == "." || safe == "..") return null
        return safe
    }
}
