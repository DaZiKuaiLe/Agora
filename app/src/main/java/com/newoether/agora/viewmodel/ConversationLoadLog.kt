package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.util.DebugLog
import java.io.File
import java.io.OutputStream

/** Page-load trail. Exported together with the optimize log from the chat overflow menu. */
internal object ConversationLoadLog {
    const val FILE_NAME = "transcript-page.log"
    private const val MAX_BYTES = 200_000L

    fun file(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, FILE_NAME)
    }

    fun note(context: Context, message: String) {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMb = runtime.maxMemory() / (1024 * 1024)
        val line = "transcript.page $message heap=${usedMb}M/${maxMb}M"
        runCatching { DebugLog.d("TranscriptPage", line) }
        runCatching { append(context, line) }
    }

    fun copyInto(context: Context, output: OutputStream): Boolean {
        val source = file(context)
        if (!source.isFile || source.length() == 0L) return false
        source.inputStream().use { input -> input.copyTo(output) }
        output.flush()
        return true
    }

    private fun append(context: Context, line: String) {
        val directory = runCatching { context.getExternalFilesDir(null) }.getOrNull()
            ?: runCatching { context.filesDir }.getOrNull()
            ?: return
        if (!directory.isDirectory && !directory.mkdirs()) return
        val file = File(directory, FILE_NAME)
        if (file.isFile && file.length() > MAX_BYTES) file.writeText("")
        file.appendText(line + "\n")
    }
}
