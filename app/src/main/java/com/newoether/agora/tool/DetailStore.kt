package com.newoether.agora.tool

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * App-private archive for tool payloads that have been replaced in provider context by a
 * short detail_ref stub. Full text remains retrievable via [DetailArchive.QUERY_TOOL].
 */
class DetailStore(
    private val directory: File,
) {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, "detail-store"),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        directory.mkdirs()
    }

    @Serializable
    data class Record(
        val detailId: String,
        val toolName: String,
        val summary: String,
        val payload: String,
        val createdAt: Long = System.currentTimeMillis(),
    )

    fun put(toolName: String, summary: String, payload: String, preferredId: String? = null): Record {
        val id = sanitizeId(preferredId) ?: "d_${UUID.randomUUID()}"
        val existing = get(id)
        if (existing != null && existing.payload == payload) return existing
        val record = Record(
            detailId = id,
            toolName = toolName,
            summary = summary,
            payload = payload,
        )
        val destination = fileFor(id)
        val temporary = File(directory, ".${destination.name}.tmp")
        try {
            temporary.writeText(json.encodeToString(record))
            if (!temporary.renameTo(destination)) {
                destination.writeText(json.encodeToString(record))
                temporary.delete()
            }
        } catch (error: Exception) {
            temporary.delete()
            throw IOException("Failed to write detail $id", error)
        }
        return record
    }

    fun get(detailId: String): Record? {
        val id = sanitizeId(detailId) ?: return null
        val file = fileFor(id)
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString<Record>(file.readText())
        }.getOrNull()
    }

    /** Idempotent delete of a detail record. Returns true if a file was removed. */
    fun delete(detailId: String): Boolean {
        val id = sanitizeId(detailId) ?: return false
        val file = fileFor(id)
        return file.isFile && file.delete()
    }

    private fun fileFor(id: String): File = File(directory, "$id.json")

    companion object {
        fun sanitizeId(raw: String?): String? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val safe = trimmed.map { ch ->
                if (ch.isLetterOrDigit() || ch == '_' || ch == '-') ch else '_'
            }.joinToString("").trim('_')
            return safe.take(120).ifBlank { null }
        }
    }
}
