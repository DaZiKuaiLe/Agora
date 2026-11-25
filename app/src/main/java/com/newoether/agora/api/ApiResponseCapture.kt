package com.newoether.agora.api

/** Raw response text for one provider attempt. Cleared when the next attempt opens. */
class ApiResponseCapture {
    private val body = StringBuilder()

    fun clear() {
        body.setLength(0)
    }

    fun appendLine(line: String) {
        if (body.isNotEmpty()) body.append('\n')
        body.append(line)
    }

    fun appendRaw(text: String) {
        if (text.isEmpty()) return
        if (body.isNotEmpty() && !body.endsWith('\n')) body.append('\n')
        body.append(text)
    }

    fun text(): String = body.toString()
}
