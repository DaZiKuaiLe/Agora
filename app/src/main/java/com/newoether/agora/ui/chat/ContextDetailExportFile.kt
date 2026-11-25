package com.newoether.agora.ui.chat

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.newoether.agora.viewmodel.ConversationContextDetail
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun contextDetailExportFileName(conversationId: String?): String {
    val idPart = conversationId
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.replace(Regex("""[\\/:*?"<>|\s]+"""), "_")
        ?: "new"
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    return "agora_context_${idPart}_$stamp.json"
}

/** Writes context detail JSON into public Downloads. Returns the file name on success. */
internal fun exportContextDetailToDownloads(
    context: Context,
    detail: ConversationContextDetail,
    conversationId: String?,
): Result<String> = runCatching {
    val fileName = contextDetailExportFileName(conversationId)
    val bytes = buildContextDetailExportJson(detail).toByteArray(Charsets.UTF_8)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create download entry")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("Unable to open output stream")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    } else {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists() && !dir.mkdirs()) {
            error("Downloads directory unavailable")
        }
        File(dir, fileName).writeBytes(bytes)
    }
    fileName
}
