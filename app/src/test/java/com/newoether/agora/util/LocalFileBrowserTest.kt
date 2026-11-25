package com.newoether.agora.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalFileBrowserTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun listsDirectoriesBeforeFilesAndSkipsHidden() {
        val root = tmp.newFolder("root")
        File(root, "b.txt").writeText("b")
        File(root, "a-dir").mkdir()
        File(root, ".secret").writeText("x")
        File(root, "Android").mkdir()
        File(root, "Android/data").mkdirs()

        val entries = LocalFileBrowser.listEntries(root).getOrThrow()
        assertEquals(listOf("a-dir", "Android", "b.txt"), entries.map { it.name })
        assertTrue(entries[0].isDirectory)
        assertTrue(entries[1].isDirectory)
        assertFalse(entries[2].isDirectory)
    }

    @Test
    fun listAndSearch_includeDotfilesWhenShowHidden() {
        val root = tmp.newFolder("hidden-root")
        File(root, "visible.txt").writeText("v")
        File(root, ".ssh").mkdir()
        File(root, ".ssh/id_ed25519").writeText("k")

        val hiddenOff = LocalFileBrowser.listEntries(root).getOrThrow().map { it.name }
        assertEquals(listOf("visible.txt"), hiddenOff)

        val hiddenOn = LocalFileBrowser.listEntries(root, showHidden = true).getOrThrow().map { it.name }
        assertEquals(listOf(".ssh", "visible.txt"), hiddenOn)

        assertTrue(LocalFileBrowser.searchUnder(root, "id_ed25519").getOrThrow().isEmpty())
        val found = LocalFileBrowser.searchUnder(root, "id_ed25519", showHidden = true).getOrThrow()
        assertEquals(1, found.size)
        assertEquals("id_ed25519", found[0].name)
    }

    @Test
    fun sortBySizeDescending() {
        val root = tmp.newFolder("sized")
        File(root, "small.txt").writeText("a")
        File(root, "large.txt").writeText("abcdefghij")
        File(root, "mid").mkdir()
        val entries = LocalFileBrowser.listEntries(root, sort = LocalFileSort.SIZE_DESC).getOrThrow()
        assertEquals(listOf("mid", "large.txt", "small.txt"), entries.map { it.name })
    }

    @Test
    fun searchUnderFindsNestedNames() {
        val root = tmp.newFolder("search")
        File(root, "keep/nested").mkdirs()
        File(root, "keep/nested/photo.jpg").writeText("x")
        File(root, "other.txt").writeText("y")
        val found = LocalFileBrowser.searchUnder(root, "photo").getOrThrow()
        assertEquals(1, found.size)
        assertEquals("photo.jpg", found[0].name)
        assertEquals(LocalFileMediaKind.IMAGE, found[0].mediaKind)
    }

    @Test
    fun mediaKindDetectsVideoExtension() {
        assertEquals(
            LocalFileMediaKind.VIDEO,
            LocalFileBrowser.mediaKindFor(File("/tmp/clip.mp4")),
        )
        assertEquals(
            LocalFileMediaKind.NONE,
            LocalFileBrowser.mediaKindFor(File("/tmp/notes.txt")),
        )
    }

    @Test
    fun lockoutSkipsAndroidDataChildren() {
        val root = tmp.newFolder("shared")
        val androidData = File(root, "Android/data/com.other").apply { mkdirs() }
        File(androidData, "secret.txt").writeText("nope")
        // Listing Android/data itself should fail lockout when that path is the directory.
        val listed = LocalFileBrowser.listEntries(File(root, "Android")).getOrThrow()
        assertTrue(listed.none { it.name == "data" })
    }

    @Test
    fun parentWithinRootStopsAtRoot() {
        val root = tmp.newFolder("root")
        val child = File(root, "sub/nested").apply { mkdirs() }
        val parent = LocalFileBrowser.parentWithinRoot(child, root)
        assertEquals(File(root, "sub").canonicalFile, parent!!.canonicalFile)
        assertNull(LocalFileBrowser.parentWithinRoot(root, root))
    }

    @Test
    fun pickerLockoutDetectsAndroidObb() {
        assertTrue(LocalFileBrowser.isPickerLockout(File("/storage/emulated/0/Android/obb/x")))
        assertFalse(LocalFileBrowser.isPickerLockout(File("/storage/emulated/0/Download")))
    }
}
