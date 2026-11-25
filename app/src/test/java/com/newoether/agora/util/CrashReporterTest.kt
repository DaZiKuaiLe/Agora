package com.newoether.agora.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class CrashReporterTest {
    @Test
    fun persistCrashReportReplacesPendingFile() {
        val dir = createTempDirectory("agora-crash").toFile()
        try {
            val first = """{"type":"java.lang.IllegalStateException"}"""
            val second = """{"type":"java.lang.OutOfMemoryError"}"""
            CrashReporter.persistCrashReport(dir, first)
            CrashReporter.persistCrashReport(dir, second)
            val pending = File(dir, "pending.json")
            assertTrue(pending.isFile && pending.length() > 0)
            assertEquals(second, pending.readText())
            val archived = File(dir, "archive").listFiles { file ->
                file.isFile && file.name.endsWith(".json")
            }.orEmpty().sortedBy { it.name }
            assertEquals(2, archived.size)
            assertEquals(setOf(first, second), archived.map { it.readText() }.toSet())
        } finally {
            dir.deleteRecursively()
        }
    }
}
