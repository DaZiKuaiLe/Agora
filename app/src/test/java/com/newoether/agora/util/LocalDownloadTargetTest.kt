package com.newoether.agora.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalDownloadTargetTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun blankTargetUsesDefaultDir() {
        val defaultDir = tmp.newFolder("downloads")
        val resolved = LocalDownloadTarget.resolve("", defaultDir, context = null).getOrThrow()
        assertEquals(defaultDir.canonicalFile, resolved)
    }

    @Test
    fun nestedDefaultDirIsAllowedWithoutPermission() {
        val defaultDir = tmp.newFolder("downloads")
        val nested = File(defaultDir, "sub").apply { mkdirs() }
        val resolved = LocalDownloadTarget.resolve(nested.path, defaultDir, context = null).getOrThrow()
        assertEquals(nested.canonicalFile, resolved)
    }

    @Test
    fun outsideDefaultDirWithoutContextIsRejected() {
        val defaultDir = tmp.newFolder("downloads")
        val other = tmp.newFolder("other")
        val result = LocalDownloadTarget.resolve(other.path, defaultDir, context = null)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("all-files"))
    }

    @Test
    fun lockoutPathsAreRejected() {
        assertTrue(LocalDownloadTarget.isLockout(File("/data/user/0/com.newoether.agora/files")))
        assertTrue(LocalDownloadTarget.isLockout(File("/storage/emulated/0/Android/data/com.other/files")))
        assertTrue(LocalDownloadTarget.isLockout(File("/storage/emulated/0/Android/obb/com.other")))
    }
}
