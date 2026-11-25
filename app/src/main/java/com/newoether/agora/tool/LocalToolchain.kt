package com.newoether.agora.tool

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import java.io.File

/**
 * Maps friendly CLI names (e.g. `node`, `ffmpeg`, `rg`) to binaries packaged under
 * [android.content.pm.ApplicationInfo.nativeLibraryDir] as `lib*.so`.
 *
 * Android 10+ cannot exec from app-writable storage, so tools must live in the extracted
 * native lib dir. AGP requires the `lib` prefix + `.so` suffix; ProcessBuilder still execs
 * them as ordinary ELF binaries when named that way.
 *
 * BusyBox is packaged as `libbusybox.so`. Multicall needs argv0 basename `busybox`, but Magisk
 * builds also `realpath(argv0)`. A bare `exec -a busybox` therefore fails with
 * `Unable to get realpath of busybox`. We keep a filesDir symlink named `busybox` → the real
 * binary and `exec -a` that symlink path (symlink is only argv0 / realpath bait; the kernel
 * still loads the executable from nativeLibraryDir).
 */
internal object LocalToolchain {
    /** Internal map key for the argv0 symlink path; omitted from [availableNames]. */
    internal const val BUSYBOX_ARGV0_KEY = "__busybox_argv0"

    /** Logical command name → packaged file name under nativeLibraryDir (direct executables). */
    private val DIRECT = mapOf(
        "node" to "libnode_cli.so",
        "ffmpeg" to "libffmpeg.so",
        "ffprobe" to "libffprobe.so",
        "rg" to "librg.so",
        "busybox" to "libbusybox.so",
    )

    /**
     * BusyBox applets rewritten to `busybox <applet> …`. Keep this list to tools that are often
     * weak/missing on Android toybox and that we explicitly want the model to prefer.
     * Do not include `grep` (prefer `rg`) or core shell builtins like `ls`/`cp`.
     */
    internal val BUSYBOX_APPLETS = setOf(
        "tar",
        "awk",
        "sed",
        "find",
        "xargs",
        "diff",
        "xxd",
        "hexdump",
        "wc",
        "cut",
        "sort",
        "uniq",
        "gzip",
        "gunzip",
    )

    fun resolve(context: Context): Map<String, String> {
        val libDir = context.applicationInfo.nativeLibraryDir ?: return emptyMap()
        val out = linkedMapOf<String, String>()
        for ((name, fileName) in DIRECT) {
            val f = File(libDir, fileName)
            if (f.isFile && f.canRead()) {
                out[name] = f.absolutePath
            }
        }
        val busyboxPath = out["busybox"]
        if (busyboxPath != null) {
            ensureBusyboxArgv0Link(context, busyboxPath)?.let { argv0 ->
                out[BUSYBOX_ARGV0_KEY] = argv0
            }
            for (applet in BUSYBOX_APPLETS) {
                out.putIfAbsent(applet, busyboxPath)
            }
        }
        return out
    }

    /**
     * Symlink `$filesDir/agora-cli/busybox` → [binaryPath] for Magisk BusyBox realpath(argv0).
     * Returns the symlink absolute path, or null if linking fails.
     */
    internal fun ensureBusyboxArgv0Link(context: Context, binaryPath: String): String? {
        return try {
            val target = File(binaryPath).canonicalFile
            if (!target.isFile) return null
            val dir = File(context.applicationContext.filesDir, "agora-cli")
            if (!dir.exists() && !dir.mkdirs()) return null
            val link = File(dir, "busybox")
            val targetPath = target.absolutePath
            if (link.exists()) {
                val current = try {
                    Os.readlink(link.absolutePath)
                } catch (_: ErrnoException) {
                    null
                }
                if (current != null && File(current).canonicalFile == target) {
                    return link.absolutePath
                }
                if (!link.delete()) return null
            }
            Os.symlink(targetPath, link.absolutePath)
            link.absolutePath
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Rewrite a shell command so packaged tools are invoked by absolute path.
     *
     * Only rewrites a tool name when it appears in command position (start of the string, or
     * after `;` `|` `&` `&&` `||` / newline)—not when it is a bare argument like `echo node`.
     *
     * BusyBox / applets become a nested `/system/bin/sh -c 'exec -a <argv0-link> …'` invocation
     * (see [busyboxInvocation]). Requires [BUSYBOX_ARGV0_KEY] in [tools] from [resolve].
     */
    fun rewriteCommand(command: String, tools: Map<String, String>): String {
        if (tools.isEmpty() || command.isBlank()) return command
        val rewriteNames = tools.keys.filter { it != BUSYBOX_ARGV0_KEY }
            .sortedByDescending { it.length }
            .map { Regex.escape(it) }
        if (rewriteNames.isEmpty()) return command
        val nameAlt = rewriteNames.joinToString("|")
        val busyboxArgv0 = tools[BUSYBOX_ARGV0_KEY]
        // Longer separators first so `&&` / `||` are not partially consumed as `&` / `|`.
        val re = Regex("""(?:^|(?<=&&)|(?<=\|\|)|(?<=[;&|\n]))\s*($nameAlt)(?=\s|$)""")
        return re.replace(command) { m ->
            val full = m.value
            val name = m.groupValues[1]
            val path = tools[name] ?: return@replace full
            val leadingWs = full.removeSuffix(name)
            val invocation = when {
                busyboxArgv0 != null && name in BUSYBOX_APPLETS ->
                    busyboxInvocation(path, name, busyboxArgv0)
                busyboxArgv0 != null && name == "busybox" ->
                    busyboxInvocation(path, applet = null, argv0Path = busyboxArgv0)
                name in BUSYBOX_APPLETS -> "'$path' $name"
                else -> "'$path'"
            }
            "$leadingWs$invocation"
        }
    }

    /**
     * Nested `/system/bin/sh` so `exec -a` only replaces that child, not the outer `sh -c`
     * running the full tool command (otherwise `busybox … && other` would never reach `other`).
     *
     * `$0` = real ELF path (nativeLibraryDir), `$1` = argv0 symlink path, then applet / user args.
     */
    internal fun busyboxInvocation(binaryPath: String, applet: String?, argv0Path: String): String {
        val prefix =
            "/system/bin/sh -c 'argv0=\$1; shift; exec -a \"\$argv0\" \"\$0\" \"\$@\"' " +
                "'$binaryPath' '$argv0Path'"
        return if (applet.isNullOrEmpty()) prefix else "$prefix $applet"
    }

    fun availableNames(context: Context): List<String> =
        resolve(context).keys.filter { it != BUSYBOX_ARGV0_KEY }
}
