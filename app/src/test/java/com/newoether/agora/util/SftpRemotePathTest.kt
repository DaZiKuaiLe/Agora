package com.newoether.agora.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SftpRemotePathTest {
    @Test
    fun windowsBackslashDrivePathBecomesPosix() {
        assertEquals(
            "/C:/Users/Xie/Desktop/a.jpg",
            SftpRemotePath.normalize("""C:\Users\Xie\Desktop\a.jpg"""),
        )
    }

    @Test
    fun windowsForwardSlashDrivePathGetsLeadingSlash() {
        assertEquals(
            "/C:/Users/Xie/Desktop/a.jpg",
            SftpRemotePath.normalize("C:/Users/Xie/Desktop/a.jpg"),
        )
    }

    @Test
    fun alreadyPosixWindowsPathIsUnchanged() {
        assertEquals(
            "/C:/Users/Xie/Desktop/a.jpg",
            SftpRemotePath.normalize("/C:/Users/Xie/Desktop/a.jpg"),
        )
    }

    @Test
    fun relativePathStaysRelative() {
        assertEquals("a.jpg", SftpRemotePath.normalize("a.jpg"))
        assertEquals("uploads/a.jpg", SftpRemotePath.normalize("""uploads\a.jpg"""))
    }

    @Test
    fun linuxAbsolutePathIsUnchanged() {
        assertEquals("/data/uploads/a.jpg", SftpRemotePath.normalize("/data/uploads/a.jpg"))
    }

    @Test
    fun fileNameUsesLastPosixSegment() {
        assertEquals("a.jpg", SftpRemotePath.fileName("""C:\Users\Xie\Desktop\a.jpg"""))
        assertEquals("a.jpg", SftpRemotePath.fileName("/C:/Users/Xie/Desktop/a.jpg"))
    }

    @Test
    fun parentStopsAtDriveRoot() {
        assertEquals("/C:/Users/Xie/Desktop", SftpRemotePath.parent("""C:\Users\Xie\Desktop\a.jpg"""))
        assertTrue(SftpRemotePath.isDriveRoot("/C:"))
        assertFalse(SftpRemotePath.isDriveRoot("/C:/Users"))
        assertEquals("", SftpRemotePath.parent("/C:"))
    }

    @Test
    fun sizeMismatchReportsFailure() {
        assertNull(SftpRemotePath.sizeMismatch(10, 10, "/C:/a.jpg"))
        val error = SftpRemotePath.sizeMismatch(10, 0, "/C:/a.jpg")
        requireNotNull(error)
        assertTrue(error.contains("failed"))
        assertTrue(error.contains("remote=0"))
        assertTrue(error.contains("local=10"))
    }
}
