package com.newoether.agora.tool

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LocalFileAccessTest {

    @Test
    fun writeReadReplaceAndMetadataCap() = runTest {
        val dir = Files.createTempDirectory("agora-file-ops").toFile()
        val access = LocalFileAccess(LocalFileLocator(null))
        val path = dir.resolve("sample.txt").absolutePath

        val written = access.write(path, "one\ntwo\nthree\n")
        assertNull(written.error)
        assertTrue(written.bytesWritten > 0)

        val unique = access.replace(path, "two", "TWO", replaceAll = false)
        assertNull(unique.error)
        assertEquals(1, unique.occurrences)

        val ambiguous = access.replace(path, "e", "E", replaceAll = false)
        assertNotNull(ambiguous.error)
        assertTrue(ambiguous.lineNumbers.isNotEmpty())

        val body = access.read(path, 2, 2)
        assertEquals("TWO", body.content)
        assertEquals(false, body.metadataOnly)

        val large = dir.resolve("large.txt").absolutePath
        val lines = (1..FileTextOps.DEFAULT_MAX_READ_LINES + 20).joinToString("\n") { "line-$it" }
        access.write(large, lines)
        val capped = access.read(large, null, null)
        assertEquals(true, capped.metadataOnly)
        assertTrue((capped.lineCount ?: 0) > FileTextOps.DEFAULT_MAX_READ_LINES)
        assertTrue(capped.content?.contains("start_line") == true)

        val bulky = dir.resolve("bulky.txt").absolutePath
        access.write(bulky, "x".repeat(FileTextOps.DEFAULT_MAX_READ_BYTES + 50))
        val bySize = access.read(bulky, null, null)
        assertEquals(true, bySize.metadataOnly)
        assertEquals(null, bySize.lineCount)
        assertTrue(bySize.content?.contains("${FileTextOps.DEFAULT_MAX_READ_BYTES + 50} bytes") == true)

        val crlfPath = dir.resolve("crlf.js").absolutePath
        access.write(crlfPath, "lineA\r\nlineB\r\nlineC\r\n")
        val crlf = access.replace(crlfPath, "lineB\nlineC", "lineB\nlineC2", replaceAll = false)
        assertNull(crlf.error)
        assertEquals(1, crlf.occurrences)
        val crlfBody = java.io.File(crlfPath).readText()
        assertEquals("lineA\r\nlineB\r\nlineC2\r\n", crlfBody)
    }

    @Test
    fun grep_searchesASingleFilePath() = runTest {
        val dir = Files.createTempDirectory("agora-grep-file").toFile()
        val file = dir.resolve("scene-split.js")
        file.writeText("const x = 1\nffmpeg('-q:v', '2')\n")
        val access = LocalFileAccess(LocalFileLocator(null))

        val inFile = access.grep("-q:v", file.absolutePath, "", noIgnore = false, extraIgnore = emptyList(), offset = 0)
        assertEquals(null, inFile.error)
        assertEquals(1, inFile.matches.size)
        assertEquals(2, inFile.matches.single().line)
        assertEquals(FileSearchPolicy.ENGINE_JAVA, inFile.engine)
        assertEquals(false, inFile.incomplete)

        val inDir = access.grep("-q:v", dir.absolutePath, "", noIgnore = false, extraIgnore = emptyList(), offset = 0)
        assertEquals(1, inDir.matches.size)
        assertEquals(file.absolutePath, inDir.matches.single().path)
        assertEquals(0, inDir.remaining)
        assertEquals(false, inDir.truncated)
    }

    @Test
    fun list_isOneLevelWithDirsFirst() = runTest {
        val root = Files.createTempDirectory("agora-list-one").toFile()
        val nested = root.resolve("src").apply { mkdirs() }
        nested.resolve("deep.kt").writeText("package deep\n")
        root.resolve("readme.md").writeText("hi\n")
        root.resolve("node_modules").mkdirs()
        val access = LocalFileAccess(LocalFileLocator(null))

        val listed = access.list(root.absolutePath, offset = 0, dirsOnly = false)
        assertEquals(null, listed.error)
        assertEquals(3, listed.entries.size)
        assertEquals(
            listOf(FileSearchPolicy.TYPE_DIR, FileSearchPolicy.TYPE_DIR, FileSearchPolicy.TYPE_FILE),
            listed.entries.map { it.type },
        )
        assertTrue(listed.entries.any { it.path.endsWith("node_modules") && it.type == FileSearchPolicy.TYPE_DIR })
        assertTrue(listed.entries.any { it.path.endsWith("readme.md") && it.size != null && it.mtimeMs != null })
        assertFalse(listed.entries.any { it.path.replace('\\', '/').contains("/src/deep.kt") })

        val dirsOnly = access.list(root.absolutePath, offset = 0, dirsOnly = true)
        assertTrue(dirsOnly.entries.all { it.type == FileSearchPolicy.TYPE_DIR })
        assertEquals(2, dirsOnly.entries.size)
    }

    @Test
    fun globAndGrep_ignoreNoiseAndHonorExtraIgnore() = runTest {
        val root = Files.createTempDirectory("agora-search-policy").toFile()
        val src = root.resolve("src").apply { mkdirs() }
        src.resolve("hit.kt").writeText("needle in source\n")
        val vendor = root.resolve("node_modules").apply { mkdirs() }
        vendor.resolve("skip.js").writeText("needle in vendor\n")
        val cache = root.resolve("Cache").apply { mkdirs() }
        cache.resolve("blob.bin").writeText("needle in cache\n")
        val extra = root.resolve("vendor-lib").apply { mkdirs() }
        extra.resolve("lib.kt").writeText("needle in extra\n")
        val access = LocalFileAccess(LocalFileLocator(null))

        val grepped = access.grep("needle", root.absolutePath, "", noIgnore = false, extraIgnore = emptyList(), offset = 0)
        assertEquals("default skips vendor and cache", 2, grepped.matches.size)
        assertTrue(grepped.matches.any { it.path == src.resolve("hit.kt").absolutePath })
        assertTrue(grepped.matches.any { it.path == extra.resolve("lib.kt").absolutePath })
        assertTrue(grepped.ignoredDirs.any { it.equals("node_modules", ignoreCase = true) })
        assertTrue(grepped.ignoredDirs.any { it.equals("Cache", ignoreCase = true) })

        val withVendor = access.grep("needle", root.absolutePath, "", noIgnore = true, extraIgnore = emptyList(), offset = 0)
        assertEquals("no_ignore sees vendor", 4, withVendor.matches.size)

        val extraIgnored = access.grep(
            "needle",
            root.absolutePath,
            "",
            noIgnore = false,
            extraIgnore = listOf("vendor-lib"),
            offset = 0,
        )
        assertEquals(1, extraIgnored.matches.size)
        assertEquals(src.resolve("hit.kt").absolutePath, extraIgnored.matches.single().path)

        val globbed = access.glob("*.kt", root.absolutePath, noIgnore = false, extraIgnore = emptyList(), offset = 0)
        assertEquals(2, globbed.paths.size)
        assertTrue(globbed.paths.any { it.endsWith("hit.kt") })
        assertTrue(globbed.paths.any { it.endsWith("lib.kt") })
        val globExtra = access.glob("*.kt", root.absolutePath, noIgnore = false, extraIgnore = listOf("src", "vendor-lib"), offset = 0)
        assertTrue(globExtra.paths.isEmpty())
    }

    @Test
    fun grep_skipsOversizeAndPagesMatches() = runTest {
        val root = Files.createTempDirectory("agora-grep-page").toFile()
        val access = LocalFileAccess(LocalFileLocator(null))
        val bulky = root.resolve("huge.txt")
        bulky.writeText("needle\n" + "x".repeat(FileTextOps.MAX_GREP_FILE_BYTES.toInt() + 10))
        val skipped = access.grep("needle", bulky.absolutePath, "", noIgnore = false, extraIgnore = emptyList(), offset = 0)
        assertEquals(0, skipped.matches.size)
        assertEquals(true, skipped.skippedOversize >= 1)

        val many = root.resolve("many.txt")
        many.writeText((1..(FileTextOps.MAX_GREP_MATCHES + 7)).joinToString("\n") { "token-$it" })
        val firstPage = access.grep("token-", many.absolutePath, "", noIgnore = false, extraIgnore = emptyList(), offset = 0)
        assertEquals(FileTextOps.MAX_GREP_MATCHES, firstPage.matches.size)
        assertEquals(7, firstPage.remaining)
        assertEquals(FileSearchPolicy.REASON_PAGE, firstPage.truncatedReason)
        assertEquals(false, firstPage.remainingIsLowerBound)
        assertEquals(false, firstPage.incomplete)
        val second = access.grep("token-", many.absolutePath, "", noIgnore = false, extraIgnore = emptyList(), offset = FileTextOps.MAX_GREP_MATCHES)
        assertEquals(7, second.matches.size)
        assertEquals(0, second.remaining)
    }

    @Test
    fun grep_timeBudgetOnWalkMarksIncomplete() = runTest {
        val dir = Files.createTempDirectory("agora-grep-time").toFile()
        dir.resolve("a.txt").writeText("needle\n")
        dir.resolve("b.txt").writeText("needle\n")
        val access = LocalFileAccess(LocalFileLocator(null), grepTimeBudgetMs = 0L)
        val timed = access.grep("needle", dir.absolutePath, "", noIgnore = false, extraIgnore = emptyList(), offset = 0)
        assertEquals(FileSearchPolicy.REASON_TIME, timed.truncatedReason)
        assertEquals(true, timed.truncated)
        assertEquals(true, timed.incomplete)
        assertEquals(FileSearchPolicy.PHASE_WALK, timed.phase)
        assertEquals(0, timed.scannedFiles)
        assertEquals(FileSearchPolicy.INCOMPLETE_NOTE, timed.note)
        assertEquals(true, timed.remainingIsLowerBound)
    }
}
