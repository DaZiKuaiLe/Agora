package com.newoether.agora.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalToolchainTest {

    private val busyboxBin = "/data/app/lib/arm64/libbusybox.so"
    private val busyboxArgv0 = "/data/user/0/com.newoether.agora/files/agora-cli/busybox"

    private val tools = mapOf(
        "node" to "/data/app/lib/arm64/libnode_cli.so",
        "ffmpeg" to "/data/app/lib/arm64/libffmpeg.so",
        "ffprobe" to "/data/app/lib/arm64/libffprobe.so",
        "rg" to "/data/app/lib/arm64/librg.so",
        "busybox" to busyboxBin,
        LocalToolchain.BUSYBOX_ARGV0_KEY to busyboxArgv0,
        "tar" to busyboxBin,
        "awk" to busyboxBin,
        "find" to busyboxBin,
    )

    @Test
    fun rewrite_replacesCommandPositionOnly() {
        assertEquals(
            "'/data/app/lib/arm64/libnode_cli.so' -e '1+1'",
            LocalToolchain.rewriteCommand("node -e '1+1'", tools),
        )
        assertEquals(
            "echo node && '/data/app/lib/arm64/libnode_cli.so' -v",
            LocalToolchain.rewriteCommand("echo node && node -v", tools),
        )
        assertEquals(
            "echo node",
            LocalToolchain.rewriteCommand("echo node", tools),
        )
    }

    @Test
    fun rewrite_ffmpegAndFfprobe() {
        assertEquals(
            "'/data/app/lib/arm64/libffmpeg.so' -i in.mp4 out.mp4",
            LocalToolchain.rewriteCommand("ffmpeg -i in.mp4 out.mp4", tools),
        )
        assertEquals(
            "'/data/app/lib/arm64/libffprobe.so' -v quiet -show_format clip.mkv",
            LocalToolchain.rewriteCommand("ffprobe -v quiet -show_format clip.mkv", tools),
        )
        assertEquals(
            "'/data/app/lib/arm64/libffmpeg.so' -i a.mp4 - | '/data/app/lib/arm64/libffprobe.so' -",
            LocalToolchain.rewriteCommand("ffmpeg -i a.mp4 - | ffprobe -", tools),
        )
    }

    @Test
    fun rewrite_rg() {
        assertEquals(
            "'/data/app/lib/arm64/librg.so' -n TODO src",
            LocalToolchain.rewriteCommand("rg -n TODO src", tools),
        )
    }

    @Test
    fun rewrite_busyboxUsesArgv0SymlinkViaNestedSh() {
        val wrap = LocalToolchain.busyboxInvocation(busyboxBin, applet = null, busyboxArgv0)
        val wrapTar = LocalToolchain.busyboxInvocation(busyboxBin, "tar", busyboxArgv0)
        val wrapAwk = LocalToolchain.busyboxInvocation(busyboxBin, "awk", busyboxArgv0)
        val wrapFind = LocalToolchain.busyboxInvocation(busyboxBin, "find", busyboxArgv0)

        assertEquals(
            "/system/bin/sh -c 'argv0=\$1; shift; exec -a \"\$argv0\" \"\$0\" \"\$@\"' " +
                "'$busyboxBin' '$busyboxArgv0'",
            wrap,
        )
        assertFalse(wrap.contains("exec -a busybox "))
        assertEquals(
            "$wrapTar -tzf archive.tgz",
            LocalToolchain.rewriteCommand("tar -tzf archive.tgz", tools),
        )
        assertEquals(
            "$wrapAwk -F, '{print NF}'",
            LocalToolchain.rewriteCommand("awk -F, '{print NF}'", tools),
        )
        assertEquals(
            "echo tar | $wrapFind . -name '*.kt'",
            LocalToolchain.rewriteCommand("echo tar | find . -name '*.kt'", tools),
        )
        assertEquals(
            "$wrap --help",
            LocalToolchain.rewriteCommand("busybox --help", tools),
        )
        assertEquals(
            "$wrap echo hi && true",
            LocalToolchain.rewriteCommand("busybox echo hi && true", tools),
        )
    }

    @Test
    fun rewrite_busyboxWithoutArgv0LinkFallsBackToBarePath() {
        val noLink = tools - LocalToolchain.BUSYBOX_ARGV0_KEY
        assertEquals(
            "'$busyboxBin' --help",
            LocalToolchain.rewriteCommand("busybox --help", noLink),
        )
        assertEquals(
            "'$busyboxBin' tar -tzf a.tgz",
            LocalToolchain.rewriteCommand("tar -tzf a.tgz", noLink),
        )
    }

    @Test
    fun rewrite_handlesSeparatorsAndWhitespace() {
        assertEquals(
            "; '/data/app/lib/arm64/libnode_cli.so' -v",
            LocalToolchain.rewriteCommand("; node -v", tools),
        )
        assertEquals(
            "| '/data/app/lib/arm64/libnode_cli.so'",
            LocalToolchain.rewriteCommand("| node", tools),
        )
        assertEquals(
            "foo || '/data/app/lib/arm64/libnode_cli.so' -p process.version",
            LocalToolchain.rewriteCommand("foo || node -p process.version", tools),
        )
        assertEquals(
            "  '/data/app/lib/arm64/libnode_cli.so'\n'/data/app/lib/arm64/libnode_cli.so' -v",
            LocalToolchain.rewriteCommand("  node\nnode -v", tools),
        )
    }

    @Test
    fun rewrite_noToolsOrBlankLeavesInput() {
        assertEquals("node -v", LocalToolchain.rewriteCommand("node -v", emptyMap()))
        assertEquals("", LocalToolchain.rewriteCommand("", tools))
        assertEquals("   ", LocalToolchain.rewriteCommand("   ", tools))
    }
}
