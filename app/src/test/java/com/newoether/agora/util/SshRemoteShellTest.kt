package com.newoether.agora.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshRemoteShellTest {
    @Test
    fun fingerprint_autoCmdReportsPowershellSyntax() {
        val fp = SshRemoteShell.fingerprint("auto", "cmd")
        assertEquals("auto", fp.shellPreference)
        assertEquals("cmd", fp.shell)
        assertEquals("powershell", fp.commandSyntax)
        assertEquals("windows", fp.osHint)
    }

    @Test
    fun fingerprint_explicitCmdKeepsCmdSyntax() {
        val fp = SshRemoteShell.fingerprint("cmd", "powershell")
        assertEquals("cmd", fp.shellPreference)
        assertEquals("cmd", fp.shell)
        assertEquals("cmd", fp.commandSyntax)
        assertEquals("windows", fp.osHint)
    }

    @Test
    fun fingerprint_autoUnknownUntilProbed() {
        val fp = SshRemoteShell.fingerprint("auto", "")
        assertEquals("unknown", fp.shell)
        assertEquals("unknown", fp.commandSyntax)
        assertEquals("unknown", fp.osHint)
    }

    @Test
    fun fingerprint_posix() {
        val fp = SshRemoteShell.fingerprint("auto", "posix")
        assertEquals("posix", fp.shell)
        assertEquals("posix", fp.commandSyntax)
        assertEquals("unix", fp.osHint)
    }

    @Test
    fun autoPrefersPersistedDetectionAndWrapsCmd() {
        val resolved = SshRemoteShell.resolve("auto", "cmd", "user@host:22")
        requireNotNull(resolved)
        assertEquals(SshExecShell.CMD, resolved.kind)
        assertTrue(resolved.wrapCmdInPowershell)
    }

    @Test
    fun explicitCmdDoesNotWrap() {
        val resolved = SshRemoteShell.resolve("cmd", "cmd", "user@host:22")
        requireNotNull(resolved)
        assertEquals(SshExecShell.CMD, resolved.kind)
        assertFalse(resolved.wrapCmdInPowershell)
    }

    @Test
    fun wrapCmdUsesEncodedPowershell() {
        val wrapped = SshRemoteShell.wrap(
            "Get-ChildItem",
            """C:\Users\Xie""",
            ResolvedSshShell(SshExecShell.CMD, wrapCmdInPowershell = true),
        )
        assertTrue(wrapped.startsWith("powershell -NoProfile -NonInteractive -EncodedCommand "))
        val b64 = wrapped.removePrefix("powershell -NoProfile -NonInteractive -EncodedCommand ").trim()
        val script = String(java.util.Base64.getDecoder().decode(b64), Charsets.UTF_16LE)
        assertTrue(script.contains("\$ProgressPreference='SilentlyContinue'"))
        assertTrue(script.contains("Get-ChildItem"))
    }

    @Test
    fun nativePowershellInjectsProgressPreference() {
        val wrapped = SshRemoteShell.wrap(
            "Write-Output hi",
            "",
            ResolvedSshShell(SshExecShell.POWERSHELL, wrapCmdInPowershell = false),
        )
        assertTrue(wrapped.startsWith("\$ProgressPreference='SilentlyContinue'; "))
        assertTrue(wrapped.contains("Write-Output hi"))
    }

    @Test
    fun stripTrailingCliXmlRemovesPollution() {
        val polluted = "hello\r\nworld\r\n#< CLIXML\r\n<Objs Version=\"1.1.0.1\" xmlns=\"http://schemas.microsoft.com/powershell/2004/04\"><S>正在准备首次使用模块</S></Objs>\r\n"
        assertEquals("hello\r\nworld", SshRemoteShell.stripTrailingCliXml(polluted))
        assertEquals("clean", SshRemoteShell.stripTrailingCliXml("clean"))
    }

    @Test
    fun posixWrapUsesBashCd() {
        val wrapped = SshRemoteShell.wrap(
            "ls",
            "/tmp/work",
            ResolvedSshShell(SshExecShell.POSIX, wrapCmdInPowershell = false),
        )
        assertEquals("cd '/tmp/work' && ls", wrapped)
    }

    @Test
    fun nativeShellPathConvertsSftpDriveForm() {
        assertEquals(
            """C:\Users\Xie""",
            SshRemoteShell.toNativeShellPath("/C:/Users/Xie", SshExecShell.CMD),
        )
        assertEquals(
            """C:\apps\dy-monitor""",
            SshRemoteShell.toNativeShellPath("/C:/apps/dy-monitor", SshExecShell.POWERSHELL),
        )
        assertEquals(
            "/C:/Users/Xie",
            SshRemoteShell.toNativeShellPath("""C:\Users\Xie""", SshExecShell.POSIX),
        )
    }

    @Test
    fun decodeFallsBackToGbkWhenUtf8IsMojibake() {
        val gbkBytes = "中文".toByteArray(charset("GBK"))
        val decoded = SshRemoteShell.decodeOutput(gbkBytes, SshExecShell.CMD)
        assertEquals("中文", decoded)
    }

    @Test
    fun encodeBase64NoWrapMatchesRfc4648() {
        assertEquals("TWFu", SshRemoteShell.encodeBase64NoWrap("Man".toByteArray()))
        assertEquals("TWE=", SshRemoteShell.encodeBase64NoWrap("Ma".toByteArray()))
        assertEquals("TQ==", SshRemoteShell.encodeBase64NoWrap("M".toByteArray()))
    }

    @Test
    fun probeDetectsCmdFromComspec() = kotlinx.coroutines.test.runTest {
        val shell = SshRemoteShell.probe { cmd ->
            if (cmd.contains("%COMSPEC%")) "C:\\Windows\\system32\\cmd.exe" else ""
        }
        assertEquals(SshExecShell.CMD, shell)
    }

    @Test
    fun probeDetectsPosixFromShell() = kotlinx.coroutines.test.runTest {
        val shell = SshRemoteShell.probe { cmd ->
            when {
                cmd.contains("%COMSPEC%") -> "%COMSPEC%"
                cmd.contains("SHELL") -> "/bin/bash"
                else -> ""
            }
        }
        assertEquals(SshExecShell.POSIX, shell)
    }
}
