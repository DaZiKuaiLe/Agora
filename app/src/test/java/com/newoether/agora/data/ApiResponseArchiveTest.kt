package com.newoether.agora.data

import com.newoether.agora.api.ApiResponseCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class ApiResponseArchiveTest {
    @Test
    fun storesOneBodyPerMessage() {
        val root = Files.createTempDirectory("api-response").toFile()
        ApiResponseArchive.install(root)
        val cases = listOf(
            "missing" to null,
            "msg-1" to "data: a",
            "tool_abc" to "data: {\"a\":1}\n\ndata: [DONE]",
        )
        cases.forEach { (id, body) ->
            if (body == null) {
                assertNull(ApiResponseArchive.read(id))
            } else {
                ApiResponseArchive.write(id, body)
                assertEquals(body, ApiResponseArchive.read(id))
            }
        }
        ApiResponseArchive.write("msg-1", "data: b")
        assertEquals("data: b", ApiResponseArchive.read("msg-1"))
        ApiResponseArchive.write("msg-1", "")
        assertEquals("data: b", ApiResponseArchive.read("msg-1"))
        ApiResponseArchive.delete(listOf("msg-1", "tool_abc"))
        assertNull(ApiResponseArchive.read("msg-1"))
        assertNull(ApiResponseArchive.read("tool_abc"))
        ApiResponseArchive.write("../escape", "no")
        assertEquals("no", ApiResponseArchive.read("../escape"))
        assertEquals(true, root.listFiles().orEmpty().all { it.parentFile == root })
    }

    @Test
    fun keepsRawResponseLines() {
        val cases = listOf(
            emptyList<String>() to "",
            listOf("data: {}") to "data: {}",
            listOf("data: {", "", "data: [DONE]") to "data: {\n\ndata: [DONE]",
        )
        cases.forEach { (lines, expected) ->
            val capture = ApiResponseCapture()
            lines.forEach(capture::appendLine)
            assertEquals(expected, capture.text())
        }
        val capture = ApiResponseCapture()
        capture.appendLine("old")
        capture.clear()
        capture.appendRaw("{\"error\":true}")
        assertEquals("{\"error\":true}", capture.text())
    }
}
