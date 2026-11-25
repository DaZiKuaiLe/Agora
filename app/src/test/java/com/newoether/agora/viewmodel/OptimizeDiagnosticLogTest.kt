package com.newoether.agora.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory

class OptimizeDiagnosticLogTest {
    @Test
    fun writeTo_copiesAnExistingLogAndSkipsAnEmptyOne() {
        data class Case(val name: String, val prepare: (File) -> Unit, val expectCopied: Boolean, val expectBytes: String)
        val cases = listOf(
            Case("missing", { }, false, ""),
            Case("empty", { file -> file.writeText("") }, false, ""),
            Case(
                "lines",
                { file -> file.writeText("compact.full scan.ready\n") },
                true,
                "compact.full scan.ready\n",
            ),
        )
        val dir = createTempDirectory("optimize-log").toFile()
        for (case in cases) {
            val source = File(dir, case.name + ".log")
            source.delete()
            case.prepare(source)
            val output = ByteArrayOutputStream()
            val copied = OptimizeDiagnosticLog.writeTo(source, output)
            assertEquals(case.name, case.expectCopied, copied)
            if (case.expectCopied) {
                assertEquals(case.name, case.expectBytes, output.toString(Charsets.UTF_8.name()))
            } else {
                assertTrue(case.name, output.size() == 0)
            }
        }
        assertFalse(File(dir, "absent.log").isFile)
    }
}
