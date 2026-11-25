package com.newoether.agora.tool

import com.newoether.agora.util.FileSearchIgnore
import com.newoether.agora.util.SshExecShell
import com.newoether.agora.util.SshRemoteShell

/**
 * Builds and parses remote glob/grep commands. Quoting is the caller's injection fence:
 * pattern, path, and glob never go unescaped into the shell.
 */
internal object RemoteFileSearch {
    fun posixQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    fun powershellQuote(value: String): String = "'${value.replace("'", "''")}'"

    /** SFTP `/C:/apps` is not a PowerShell path; exec must use the native form. */
    fun execPath(basePath: String, kind: SshExecShell): String =
        SshRemoteShell.toNativeShellPath(basePath.ifBlank { "." }, kind)

    /** GNU grep uses 1 for "no matches"; PowerShell 1 is often a failed script. */
    fun searchSucceeded(exitCode: Int, posixNoMatchOk: Boolean): Boolean =
        exitCode == 0 || (posixNoMatchOk && exitCode == 1)

    /** Windows OpenSSH `find` is find.exe (string search), not GNU find. */
    fun preferPosixSearch(kind: SshExecShell): Boolean = kind == SshExecShell.POSIX

    fun looksLikeWindowsFindExe(stdout: String, stderr: String): Boolean {
        val blob = "$stdout\n$stderr"
        if (blob.contains("FIND: Parameter format not correct", ignoreCase = true)) return true
        if (blob.contains("FIND: 参数格式不正确")) return true
        val lines = stdout.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }.take(8).toList()
        return lines.isNotEmpty() && lines.all { it.startsWith("----------") || it.startsWith("FIND:") }
    }

    fun globNameFilter(pattern: String): String? =
        grepIncludeGlob(FileSearchPolicy.globSearchPattern(pattern))

    fun posixFindCommand(
        basePath: String,
        extraIgnore: List<String>,
        noIgnore: Boolean,
    ): String {
        val base = posixQuote(basePath.ifBlank { "." })
        if (noIgnore) {
            return "find $base \\( -type f -o -type d \\) -print"
        }
        val names = FileSearchIgnore.mergedNames(extraIgnore)
        val prune = names.joinToString(" -o ") { "-iname ${posixQuote(it)}" }
        return "find $base \\( \\( ! -path $base \\) \\( $prune \\) \\) -prune -o \\( -type f -o -type d \\) -print"
    }

    fun posixGrepCommand(
        pattern: String,
        basePath: String,
        glob: String,
        extraIgnore: List<String>,
        noIgnore: Boolean,
    ): String {
        val base = posixQuote(basePath.ifBlank { "." })
        val pat = posixQuote(pattern)
        val maxBytes = FileTextOps.MAX_GREP_FILE_BYTES
        val include = grepIncludeGlob(glob)
        val namePred = if (include == null) "" else " -name ${posixQuote(include)}"
        val prunePred = if (noIgnore) {
            ""
        } else {
            val prune = FileSearchIgnore.mergedNames(extraIgnore)
                .joinToString(" -o ") { "-iname ${posixQuote(it)}" }
            "\\( \\( ! -path $base \\) \\( $prune \\) \\) -prune -o "
        }
        return buildString {
            append("if [ ! -e $base ]; then printf '%s\\n' 'No such file' >&2; exit 2; fi; ")
            append("skipped=\$(find $base $prunePred -type f -size +${maxBytes}c$namePred -print | wc -l); ")
            append("skipped=\$(echo \$skipped); ")
            append("printf 'AGORA_META\\tskipped_oversize=%s\\n' \"\$skipped\"; ")
            append("find $base $prunePred -type f ! -size +${maxBytes}c$namePred -exec grep -nIH -- $pat {} +")
        }
    }

    fun powershellGlobCommand(
        basePath: String,
        extraIgnore: List<String>,
        noIgnore: Boolean,
        nameFilter: String? = null,
    ): String {
        val root = powershellQuote(basePath.ifBlank { "." })
        val skip = powershellSkipArray(extraIgnore, noIgnore)
        val filter = nameFilter?.let { " -Filter ${powershellQuote(it)}" }.orEmpty()
        return buildString {
            append(powershellMissingPathGuard(root))
            append("\$ErrorActionPreference='SilentlyContinue';")
            append("\$rootFull=(Get-Item -LiteralPath $root).FullName.TrimEnd('\\','/');")
            append("Get-ChildItem -LiteralPath $root -Force -Recurse$filter |")
            append("Where-Object { $skip } |")
            append("Select-Object -First ${FileSearchIgnore.WALK_FILE_CAP + 1} -ExpandProperty FullName")
        }
    }

    fun powershellGrepCommand(
        pattern: String,
        basePath: String,
        glob: String,
        extraIgnore: List<String>,
        noIgnore: Boolean,
    ): String {
        val root = powershellQuote(basePath.ifBlank { "." })
        val pat = powershellQuote(pattern)
        val skip = powershellSkipArray(extraIgnore, noIgnore)
        val include = grepIncludeGlob(glob)
        val nameFilter = if (include == null) {
            ""
        } else {
            "| Where-Object { \$_.Name -like ${powershellQuote(include)} }"
        }
        return buildString {
            append(powershellMissingPathGuard(root))
            append("\$ErrorActionPreference='SilentlyContinue';")
            append("\$rootFull=(Get-Item -LiteralPath $root).FullName.TrimEnd('\\','/');")
            append("\$skipped=0;\$files=@();")
            append("Get-ChildItem -LiteralPath $root -Force -Recurse -File $nameFilter |")
            append("Where-Object { $skip } |")
            append("ForEach-Object { if (\$_.Length -gt ${FileTextOps.MAX_GREP_FILE_BYTES}) { \$skipped++ } ")
            append("elseif (\$files.Count -lt ${FileTextOps.MAX_GREP_FILES}) { \$files += \$_.FullName } };")
            append("Write-Output ('AGORA_META' + [char]9 + 'skipped_oversize=' + \$skipped);")
            append("if (\$files.Count -lt 1) { exit 0 };")
            append("\$ErrorActionPreference='Stop';")
            append("Select-String -LiteralPath \$files -Pattern $pat -Encoding UTF8 |")
            append("ForEach-Object { \$_.Path + [char]9 + \$_.LineNumber + [char]9 + \$_.Line };")
            append("exit 0")
        }
    }

    fun parsePosixFind(stdout: String): List<String> =
        stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && it != "." && it != ".." }.toList()

    fun parsePosixGrep(stdout: String): List<FileGrepMatch> =
        stdout.lineSequence().mapNotNull { parseColonGrepLine(it) }.toList()

    fun parseTabGrep(stdout: String): List<FileGrepMatch> =
        stdout.lineSequence().mapNotNull { parseTabGrepLine(it) }.toList()

    /** Tab records from our script, or Select-String's default `path:line:text`. */
    fun parsePowerShellGrep(stdout: String): PowerShellGrepStdout {
        var skippedOversize = 0
        val matches = stdout.lineSequence().mapNotNull { raw ->
            val line = raw.trimEnd('\r')
            if (line.startsWith("AGORA_META")) {
                skippedOversize = Regex("skipped_oversize=(\\d+)").find(line)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                null
            } else {
                parseTabGrepLine(line) ?: parseColonGrepLine(line)
            }
        }.toList()
        return PowerShellGrepStdout(matches, skippedOversize)
    }

    fun parseTabGrepLine(line: String): FileGrepMatch? {
        val parts = line.trimEnd('\r').split('\t', limit = 3)
        if (parts.size < 3) return null
        val n = parts[1].toIntOrNull() ?: return null
        return FileGrepMatch(parts[0], n, parts[2].take(FileTextOps.GREP_LINE_CHARS))
    }

    fun parseColonGrepLine(line: String): FileGrepMatch? {
        val trimmed = line.trimEnd('\r')
        if (trimmed.isBlank()) return null
        val first = indexOfPathLineSplit(trimmed) ?: return null
        val second = trimmed.indexOf(':', startIndex = first + 1)
        if (second <= first) return null
        val n = trimmed.substring(first + 1, second).toIntOrNull() ?: return null
        return FileGrepMatch(
            path = trimmed.substring(0, first),
            line = n,
            text = trimmed.substring(second + 1).take(FileTextOps.GREP_LINE_CHARS),
        )
    }

    /** Skip a Windows drive colon so `C:/a:12:text` still parses. */
    private fun indexOfPathLineSplit(line: String): Int? {
        val start = if (line.length >= 3 && line[1] == ':' && line[0].isLetter()) 2 else 0
        val idx = line.indexOf(':', startIndex = start)
        return if (idx < 0) null else idx
    }

    fun grepIncludeGlob(glob: String): String? {
        val raw = glob.trim()
        if (raw.isEmpty() || raw == "*" || raw == "**" || raw == "**/*") return null
        val slash = raw.lastIndexOf('/')
        return if (slash >= 0) raw.substring(slash + 1).ifBlank { null } else raw
    }

    private fun powershellMissingPathGuard(rootQuoted: String): String =
        "if (-not (Test-Path -LiteralPath $rootQuoted)) { [Console]::Error.WriteLine('No such file'); exit 2 };"

    private fun powershellSkipArray(extraIgnore: List<String>, noIgnore: Boolean): String {
        if (noIgnore) return "\$true"
        val names = FileSearchIgnore.mergedNames(extraIgnore)
            .joinToString(",") { powershellQuote(it.lowercase()) }
        return "\$n=@($names); \$full=\$_.FullName; " +
            "\$rel=if (\$full.Length -le \$rootFull.Length) {''} else {\$full.Substring(\$rootFull.Length).TrimStart([char]'\\',[char]'/')}; " +
            "-not (\$rel.Split([IO.Path]::DirectorySeparatorChar,'/') | " +
            "Where-Object { \$_ -and \$n -contains \$_.ToLower() })"
    }

    fun isMissingPath(error: String?): Boolean {
        val text = error?.trim() ?: return false
        if (text.equals("exit 2", ignoreCase = true)) return true
        return text.contains("No such file", ignoreCase = true)
    }

    fun missingPathMessage(): String = "No such file"

    fun grepFailure(grepErr: String?, powershellErr: String?): String =
        if (isMissingPath(grepErr) || isMissingPath(powershellErr)) {
            missingPathMessage()
        } else {
            engineFailure(grepErr, powershellErr)
        }

    fun globSearchFailure(findErr: String?, powershellErr: String?): String =
        if (isMissingPath(findErr) || isMissingPath(powershellErr)) {
            missingPathMessage()
        } else {
            globFailure(findErr, powershellErr)
        }

    fun engineFailure(grepErr: String?, powershellErr: String?): String = buildString {
        append("Remote content search is unavailable on this host. ")
        if (grepErr != null) {
            append("GNU grep -rnI/--include and PowerShell Select-String both failed. ")
        } else {
            append("PowerShell Select-String failed. ")
        }
        grepErr?.takeIf { it.isNotBlank() }?.let { append("grep: $it ") }
        powershellErr?.takeIf { it.isNotBlank() }?.let { append("powershell: $it") }
    }.trim()

    fun globFailure(findErr: String?, powershellErr: String?): String = buildString {
        append("Remote file-name search is unavailable on this host. ")
        if (findErr != null) {
            append("find and PowerShell Get-ChildItem both failed. ")
        } else {
            append("PowerShell Get-ChildItem failed. ")
        }
        findErr?.takeIf { it.isNotBlank() }?.let { append("find: $it ") }
        powershellErr?.takeIf { it.isNotBlank() }?.let { append("powershell: $it") }
    }.trim()
}

internal data class PowerShellGrepStdout(
    val matches: List<FileGrepMatch>,
    val skippedOversize: Int = 0,
)
