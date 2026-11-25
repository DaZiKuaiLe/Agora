package com.newoether.agora.tool

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenFileTest {

    @Test
    fun classify_table() {
        data class Case(val name: String, val raw: String, val ok: Boolean, val http: Boolean? = null)
        val cases = listOf(
            Case("blank rejected", "  ", ok = false),
            Case("https accepted", "https://example.com/a.pdf", ok = true, http = true),
            Case("http accepted", "http://192.168.1.8:8080/app.apk", ok = true, http = true),
            Case("local path", "/sdcard/Download/a.pdf", ok = true, http = false),
            Case("windows path is local not url", """C:\apps\a.apk""", ok = true, http = false),
            Case("javascript rejected", "javascript:alert(1)", ok = false),
            Case("file scheme rejected", "file:///sdcard/a.pdf", ok = false),
            Case("content scheme rejected", "content://media/external/1", ok = false),
            Case("ftp rejected", "ftp://example.com/a.pdf", ok = false),
            Case("mailto rejected", "mailto:user@example.com", ok = false),
            Case("https without host rejected", "https://", ok = false),
        )
        cases.forEach { case ->
            val result = OpenFile.classify(case.raw)
            assertEquals(case.name, case.ok, result.isSuccess)
            if (case.ok && case.http != null) {
                assertEquals(case.name, case.http, result.getOrNull() is OpenFile.Target.Http)
            }
        }
    }

    @Test
    fun mimeType_apkAndCommonTypes() {
        data class Case(val name: String, val path: String, val mime: String)
        val cases = listOf(
            Case("apk", "setup.apk", OpenFile.APK_MIME),
            Case("pdf", "/tmp/doc.PDF", "application/pdf"),
            Case("png", "a.png", "image/png"),
            Case("unknown", "a.nope", "application/octet-stream"),
            Case("no ext", "README", "application/octet-stream"),
        )
        cases.forEach { case ->
            assertEquals(case.name, case.mime, OpenFile.mimeType(case.path))
        }
        assertTrue(OpenFile.isApk("/sdcard/app.APK"))
    }

    @Test
    fun httpMime_usesUrlPathSuffix() {
        data class Case(val name: String, val url: String, val mime: String)
        listOf(
            Case("pdf", "https://this-domain-does-not-exist-xyz-12345.com/a.pdf", "application/pdf"),
            Case("pdf with query", "https://example.com/doc.PDF?dl=1", "application/pdf"),
            Case("apk", "http://192.168.1.8:8080/app.apk", OpenFile.APK_MIME),
            Case("no suffix", "https://www.baidu.com", "application/octet-stream"),
            Case("trailing slash", "https://example.com/", "application/octet-stream"),
        ).forEach { case ->
            assertEquals(case.name, case.mime, OpenFile.httpMime(case.url))
        }
    }

    @Test
    fun localRejection_distinguishesDirectoryFromMissing() {
        val root = File.createTempFile("openfile", ".dir").apply {
            delete()
            mkdirs()
        }
        try {
            val dir = File(root, "Download").apply { mkdirs() }
            val missing = File(root, "no-such.txt")
            data class Case(val name: String, val file: File, val requested: String, val needle: String)
            listOf(
                Case("directory", dir, dir.path, "Not a file:"),
                Case("missing", missing, missing.path, "File not found:"),
                Case(
                    "windows remote still hints download",
                    missing,
                    """C:\Users\a.txt""",
                    "download_file",
                ),
            ).forEach { case ->
                val message = OpenFile.localRejection(case.file, case.requested)
                assertTrue("${case.name}: $message", message.contains(case.needle))
                assertTrue("${case.name}: $message", message.contains(case.file.path))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun launch_validatesPathBeforeRequiringContext() {
        data class Case(val name: String, val raw: String, val needle: String)
        listOf(
            Case("blank", "  ", "path is required"),
            Case("javascript", "javascript:alert(1)", "http/https"),
            Case("valid https needs context", "https://example.com/a.pdf", "app context"),
            Case("valid local needs context", "/sdcard/Download/a.pdf", "app context"),
        ).forEach { case ->
            val message = OpenFile.launch(null, case.raw).exceptionOrNull()?.message.orEmpty()
            assertTrue("${case.name}: $message", message.contains(case.needle))
        }
    }
}
