package com.newoether.agora.util

import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

enum class SshExecShell {
    POSIX,
    CMD,
    POWERSHELL,
}

data class ResolvedSshShell(
    val kind: SshExecShell,
    /** When the login shell is cmd, wrap the payload in PowerShell so PS syntax works. */
    val wrapCmdInPowershell: Boolean,
)

/**
 * Detects and wraps SSH exec-channel commands. OpenSSH on Windows defaults to cmd.exe;
 * POSIX quoting and UTF-8 decoding then break. Preference `auto` probes once per host.
 *
 * When preference is `auto` and the login shell is cmd, [wrap] runs the payload through
 * PowerShell EncodedCommand — so [commandSyntaxFor] reports `powershell`, not `cmd`.
 */
object SshRemoteShell {
    private val detectedByHost = ConcurrentHashMap<String, SshExecShell>()
    private val gbk: Charset = Charset.forName("GBK")

    fun hostKey(user: String, host: String, port: Int): String = "$user@$host:$port"

    fun parseKind(raw: String): SshExecShell? = when (raw.trim().lowercase()) {
        "posix", "bash", "sh", "zsh" -> SshExecShell.POSIX
        "cmd" -> SshExecShell.CMD
        "powershell", "pwsh" -> SshExecShell.POWERSHELL
        else -> null
    }

    fun storedKind(shell: SshExecShell): String = when (shell) {
        SshExecShell.POSIX -> "posix"
        SshExecShell.CMD -> "cmd"
        SshExecShell.POWERSHELL -> "powershell"
    }

    /**
     * Fields exposed to the model via list_remote_shells (and similar).
     *
     * @param shellPreference user setting: auto|posix|cmd|powershell
     * @param shell effective/detected login shell, or unknown when auto has not probed yet
     * @param commandSyntax syntax the model should use for execute_remote_command
     * @param osHint windows|unix|unknown derived from [commandSyntax]/[shell]
     */
    data class ShellFingerprint(
        val shellPreference: String,
        val shell: String,
        val commandSyntax: String,
        val osHint: String,
    )

    fun fingerprint(preference: String, detected: String): ShellFingerprint {
        val pref = preference.trim().lowercase().ifBlank { "auto" }.let { raw ->
            when (raw) {
                "auto", "posix", "cmd", "powershell" -> raw
                "bash", "sh", "zsh" -> "posix"
                "pwsh" -> "powershell"
                else -> "auto"
            }
        }
        if (pref != "auto") {
            return ShellFingerprint(
                shellPreference = pref,
                shell = pref,
                commandSyntax = pref,
                osHint = osHintFor(pref),
            )
        }
        val detectedKind = parseKind(detected)
        val shell = detectedKind?.let { storedKind(it) } ?: "unknown"
        // auto + cmd login → Agora wraps in PowerShell; model must write PS, not cmd.
        val commandSyntax = when (detectedKind) {
            SshExecShell.CMD -> "powershell"
            SshExecShell.POWERSHELL -> "powershell"
            SshExecShell.POSIX -> "posix"
            null -> "unknown"
        }
        return ShellFingerprint(
            shellPreference = "auto",
            shell = shell,
            commandSyntax = commandSyntax,
            osHint = osHintFor(commandSyntax).takeIf { it != "unknown" }
                ?: osHintFor(shell),
        )
    }

    private fun osHintFor(syntaxOrShell: String): String = when (syntaxOrShell) {
        "cmd", "powershell" -> "windows"
        "posix" -> "unix"
        else -> "unknown"
    }

    fun remember(hostKey: String, shell: SshExecShell) {
        detectedByHost[hostKey] = shell
    }

    fun cached(hostKey: String): SshExecShell? = detectedByHost[hostKey]

    fun resolve(
        preference: String,
        persistedDetected: String,
        hostKey: String,
    ): ResolvedSshShell? {
        val pref = preference.trim().lowercase().ifBlank { "auto" }
        if (pref != "auto") {
            val kind = parseKind(pref) ?: return null
            return ResolvedSshShell(kind, wrapCmdInPowershell = false)
        }
        val kind = parseKind(persistedDetected) ?: cached(hostKey) ?: return null
        return ResolvedSshShell(kind, wrapCmdInPowershell = kind == SshExecShell.CMD)
    }

    suspend fun probe(runRaw: suspend (String) -> String): SshExecShell {
        val comspec = runRaw("echo %COMSPEC%")
        if (comspec.contains("cmd.exe", ignoreCase = true)) return SshExecShell.CMD
        val shell = runRaw("echo \$SHELL")
        if (shell.trim().startsWith("/")) return SshExecShell.POSIX
        val ps = runRaw("echo \$PSVersionTable")
        if (ps.contains("PSVersion", ignoreCase = true) ||
            ps.contains("Desktop", ignoreCase = true) ||
            ps.contains("Core", ignoreCase = true)
        ) {
            return SshExecShell.POWERSHELL
        }
        val ver = runRaw("ver")
        if (ver.contains("Windows", ignoreCase = true)) return SshExecShell.CMD
        return SshExecShell.POSIX
    }

    fun wrap(command: String, workdir: String, resolved: ResolvedSshShell): String {
        val nativeDir = toNativeShellPath(workdir, resolved.kind)
        return when {
            resolved.kind == SshExecShell.POSIX -> posixCommand(command, nativeDir)
            resolved.wrapCmdInPowershell || resolved.kind == SshExecShell.POWERSHELL ->
                powershellPayload(command, nativeDir, encoded = resolved.wrapCmdInPowershell)
            else -> cmdCommand(command, nativeDir)
        }
    }

    fun decodeOutput(bytes: ByteArray, kind: SshExecShell): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        if (kind == SshExecShell.POSIX) return utf8
        val replacement = utf8.count { it == '\uFFFD' }
        val text = if (replacement == 0) {
            utf8
        } else {
            val gbkText = String(bytes, gbk)
            if (gbkText.count { it == '\uFFFD' } < replacement) gbkText else utf8
        }
        // PowerShell progress/module-init records often leak as a trailing "#< CLIXML ..." block
        // (cmd-wrapped EncodedCommand and native powershell shells both hit this path).
        return stripTrailingCliXml(text)
    }

    /** Drop PowerShell CLIXML progress dumps that pollute remote stdout/stderr. */
    internal fun stripTrailingCliXml(text: String): String {
        val marker = Regex("""#<\s*CLIXML\b""", RegexOption.IGNORE_CASE).find(text) ?: return text
        return text.substring(0, marker.range.first).trimEnd()
    }

    fun toNativeShellPath(path: String, kind: SshExecShell): String {
        if (path.isBlank()) return path
        val sftp = SftpRemotePath.normalize(path)
        if (kind == SshExecShell.POSIX) return sftp
        val drive = Regex("^/([A-Za-z]):(/.*)?$").matchEntire(sftp)
        if (drive != null) {
            val rest = drive.groupValues[2].replace('/', '\\')
            return "${drive.groupValues[1].uppercase()}:$rest"
        }
        return sftp.replace('/', '\\')
    }

    private fun posixCommand(command: String, workdir: String): String =
        if (workdir.isBlank()) command else "cd ${escapeBash(workdir)} && $command"

    private fun cmdCommand(command: String, workdir: String): String {
        val prefix = "chcp 65001 >nul"
        val body = if (workdir.isBlank()) command else "cd /d ${quoteCmd(workdir)} & $command"
        return "$prefix & $body"
    }

    private fun powershellPayload(command: String, workdir: String, encoded: Boolean): String {
        val script = buildString {
            // Suppress Write-Progress / first-use module banners that otherwise serialize as CLIXML.
            append("\$ProgressPreference='SilentlyContinue'; ")
            append("[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding \$false; ")
            append("\$OutputEncoding = [Console]::OutputEncoding; ")
            if (workdir.isNotBlank()) {
                append("Set-Location -LiteralPath ${quotePowerShell(workdir)}; ")
            }
            append(command)
        }
        return if (encoded) {
            val b64 = encodeBase64NoWrap(script.toByteArray(Charsets.UTF_16LE))
            "powershell -NoProfile -NonInteractive -EncodedCommand $b64"
        } else {
            script
        }
    }

    private fun escapeBash(s: String): String = "'${s.replace("'", "'\\''")}'"

    private fun quoteCmd(path: String): String = "\"${path.replace("\"", "\"\"")}\""

    private fun quotePowerShell(path: String): String = "'${path.replace("'", "''")}'"

    internal fun encodeBase64NoWrap(bytes: ByteArray): String {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = ((bytes[i].toInt() and 0xff) shl 16) or
                ((bytes[i + 1].toInt() and 0xff) shl 8) or
                (bytes[i + 2].toInt() and 0xff)
            out.append(table[(n shr 18) and 63])
            out.append(table[(n shr 12) and 63])
            out.append(table[(n shr 6) and 63])
            out.append(table[n and 63])
            i += 3
        }
        val rem = bytes.size - i
        if (rem == 1) {
            val n = (bytes[i].toInt() and 0xff) shl 16
            out.append(table[(n shr 18) and 63])
            out.append(table[(n shr 12) and 63])
            out.append("==")
        } else if (rem == 2) {
            val n = ((bytes[i].toInt() and 0xff) shl 16) or ((bytes[i + 1].toInt() and 0xff) shl 8)
            out.append(table[(n shr 18) and 63])
            out.append(table[(n shr 12) and 63])
            out.append(table[(n shr 6) and 63])
            out.append('=')
        }
        return out.toString()
    }
}
