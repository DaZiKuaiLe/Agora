package com.newoether.agora.viewmodel

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.OutputStream

/** Full-optimize diagnostic trail. The chat overflow menu copies it to a user-chosen location. */
internal object OptimizeDiagnosticLog {
    const val FILE_NAME = "compact-optimize.log"

    fun file(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, FILE_NAME)
    }

    fun writeTo(source: File, output: OutputStream): Boolean {
        if (!source.isFile || source.length() == 0L) return false
        source.inputStream().use { input -> input.copyTo(output) }
        output.flush()
        return true
    }

    fun hasExportableContent(context: Context): Boolean {
        val optimize = file(context)
        val pages = ConversationLoadLog.file(context)
        return (optimize.isFile && optimize.length() > 0L) ||
            (pages.isFile && pages.length() > 0L)
    }

    fun copyTo(context: Context, destination: Uri): Boolean {
        if (!hasExportableContent(context)) return false
        val output = context.contentResolver.openOutputStream(destination) ?: return false
        return output.use { stream ->
            val optimize = writeTo(file(context), stream)
            val pages = ConversationLoadLog.copyInto(context, stream)
            optimize || pages
        }
    }
}
