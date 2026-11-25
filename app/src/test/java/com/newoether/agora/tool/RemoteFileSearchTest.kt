package com.newoether.agora.tool

import com.newoether.agora.util.SshExecShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteFileSearchTest {

    @Test
    fun quotingAndParse_table() {
        data class Case(val name: String, val run: () -> Unit)
        val cases = listOf(
            Case("posix quote escapes single quotes") {
                assertEquals("'it'\\''s'", RemoteFileSearch.posixQuote("it's"))
            },
            Case("powershell quote doubles single quotes") {
                assertEquals("'it''s'", RemoteFileSearch.powershellQuote("it's"))
            },
            Case("grep include strips directories") {
                assertEquals("*.kt", RemoteFileSearch.grepIncludeGlob("**/*.kt"))
                assertEquals(null, RemoteFileSearch.grepIncludeGlob("*"))
            },
            Case("posix grep command quotes pattern") {
                val cmd = RemoteFileSearch.posixGrepCommand("a b", "/tmp", "*.kt", emptyList(), noIgnore = true)
                assertTrue(cmd.contains("grep -nIH"))
                assertTrue(cmd.contains("-name '*.kt'"))
                assertTrue(cmd.contains("'a b'"))
                assertTrue(cmd.contains("-size +${FileTextOps.MAX_GREP_FILE_BYTES}c"))
                assertTrue(cmd.contains("AGORA_META"))
                assertFalse(cmd.contains("--exclude-dir"))
            },
            Case("posix grep prunes noise dirs with find") {
                val cmd = RemoteFileSearch.posixGrepCommand("x", "/proj", "", emptyList(), noIgnore = false)
                assertTrue(cmd.contains("-iname 'node_modules'"))
                assertTrue(cmd.contains("! -path '/proj'"))
                assertTrue(cmd.contains("-exec grep -nIH"))
            },
            Case("posix find prunes noise but not the start path") {
                val cmd = RemoteFileSearch.posixFindCommand("/proj/logs", emptyList(), noIgnore = false)
                assertTrue(cmd.contains("-iname 'node_modules'"))
                assertTrue(cmd.contains("-iname 'cache'"))
                assertTrue(cmd.contains("-prune"))
                assertTrue(cmd.contains("! -path '/proj/logs'"))
            },
            Case("parse posix grep skips drive colon") {
                val match = RemoteFileSearch.parseColonGrepLine("C:/src/a.kt:12:hello")
                assertEquals("C:/src/a.kt", match?.path)
                assertEquals(12, match?.line)
                assertEquals("hello", match?.text)
            },
            Case("parse posix grep unix path") {
                val match = RemoteFileSearch.parseColonGrepLine("/root/a.kt:3:text")
                assertEquals("/root/a.kt", match?.path)
                assertEquals(3, match?.line)
            },
            Case("parse tab grep") {
                val match = RemoteFileSearch.parseTabGrep("C:\\a.kt\t4\thit").single()
                assertEquals("C:\\a.kt", match.path)
                assertEquals(4, match.line)
                assertEquals("hit", match.text)
            },
            Case("powershell grep searches file contents not path strings") {
                val cmd = RemoteFileSearch.powershellGrepCommand(
                    "scanPlayGroups",
                    """C:\apps\dy-monitor""",
                    glob = "",
                    extraIgnore = emptyList(),
                    noIgnore = true,
                )
                assertTrue(cmd.contains("Select-String -LiteralPath"))
                assertTrue(cmd.contains("-Encoding UTF8"))
                assertTrue(cmd.contains("[char]9"))
                assertFalse(cmd.contains("|Select-String -Pattern"))
                assertFalse(cmd.contains("| Select-String -Pattern"))
            },
            Case("powershell grep parse accepts tab and colon records") {
                val tab = RemoteFileSearch.parsePowerShellGrep("C:\\a.js\t133\tscanPlayGroups").matches.single()
                assertEquals("""C:\a.js""", tab.path)
                assertEquals(133, tab.line)
                val colon = RemoteFileSearch.parsePowerShellGrep(
                    """C:\apps\dy-monitor\api-server.js:133:function scanPlayGroups() {""",
                ).matches.single()
                assertEquals("""C:\apps\dy-monitor\api-server.js""", colon.path)
                assertEquals(133, colon.line)
                assertTrue(colon.text.contains("scanPlayGroups"))
            },
            Case("powershell grep counts skipped oversize from meta") {
                val parsed = RemoteFileSearch.parsePowerShellGrep(
                    "AGORA_META\tskipped_oversize=2\nC:\\a.js\t1\thit",
                )
                assertEquals(2, parsed.skippedOversize)
                assertEquals(1, parsed.matches.size)
            },
            Case("missing path is No such file not engine unavailable") {
                assertEquals(
                    "No such file",
                    RemoteFileSearch.grepFailure(null, "No such file"),
                )
                assertEquals(
                    "No such file",
                    RemoteFileSearch.globSearchFailure(null, "exit 2"),
                )
                val unavailable = RemoteFileSearch.grepFailure(null, "command not found")
                assertTrue(unavailable.contains("Select-String"))
            },
            Case("powershell skip is relative to search root") {
                val cmd = RemoteFileSearch.powershellGrepCommand(
                    "START",
                    """C:\apps\dy-monitor\logs\record.log""",
                    glob = "",
                    extraIgnore = emptyList(),
                    noIgnore = false,
                )
                assertTrue(cmd.contains("\$rootFull="))
                assertTrue(cmd.contains("Substring"))
                assertTrue(cmd.contains("skipped_oversize"))
            },
            Case("powershell exit 1 is not a successful scan") {
                assertTrue(RemoteFileSearch.searchSucceeded(0, posixNoMatchOk = false))
                assertFalse(RemoteFileSearch.searchSucceeded(1, posixNoMatchOk = false))
                assertTrue(RemoteFileSearch.searchSucceeded(1, posixNoMatchOk = true))
                assertFalse(RemoteFileSearch.searchSucceeded(2, posixNoMatchOk = true))
            },
            Case("failure message names both engines") {
                val msg = RemoteFileSearch.engineFailure("not found", "command not found")
                assertTrue(msg.contains("grep"))
                assertTrue(msg.contains("Select-String"))
                assertTrue(msg.contains("not found"))
            },
            Case("windows exec path is native not sftp") {
                assertEquals(
                    """C:\apps\dy-monitor""",
                    RemoteFileSearch.execPath("/C:/apps/dy-monitor", SshExecShell.POWERSHELL),
                )
                assertEquals(
                    "/root",
                    RemoteFileSearch.execPath("/root", SshExecShell.POSIX),
                )
            },
            Case("posix find is not used on Windows shells") {
                assertTrue(RemoteFileSearch.preferPosixSearch(SshExecShell.POSIX))
                assertFalse(RemoteFileSearch.preferPosixSearch(SshExecShell.CMD))
                assertFalse(RemoteFileSearch.preferPosixSearch(SshExecShell.POWERSHELL))
            },
            Case("powershell glob uses native path and name filter") {
                val cmd = RemoteFileSearch.powershellGlobCommand(
                    """C:\apps\dy-monitor""",
                    extraIgnore = emptyList(),
                    noIgnore = true,
                    nameFilter = "*.js",
                )
                assertTrue(cmd.contains("""'C:\apps\dy-monitor'"""))
                assertFalse(cmd.contains("/C:"))
                assertTrue(cmd.contains("-Filter '*.js'"))
                assertTrue(cmd.contains("Test-Path"))
            },
            Case("glob name filter strips **") {
                assertEquals("*.js", RemoteFileSearch.globNameFilter("*.js"))
                assertEquals("*.js", RemoteFileSearch.globNameFilter("**/*.js"))
                assertEquals(null, RemoteFileSearch.globNameFilter("*"))
            },
            Case("windows find.exe output is not gnu find") {
                assertTrue(
                    RemoteFileSearch.looksLikeWindowsFindExe("", "FIND: Parameter format not correct"),
                )
                assertFalse(RemoteFileSearch.looksLikeWindowsFindExe("/root/a.js\n", ""))
            },
            Case("windows-only glob failure does not claim find ran") {
                val msg = RemoteFileSearch.globFailure(null, "exit 2")
                assertTrue(msg.contains("Get-ChildItem"))
                assertFalse(msg.contains("find and PowerShell"))
            },
        )
        cases.forEach { case ->
            try {
                case.run()
            } catch (error: AssertionError) {
                throw AssertionError(case.name, error)
            }
        }
    }
}
