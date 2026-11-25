package com.newoether.agora.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FileSearchIgnoreTest {

    @Test
    fun shouldIgnoreDir_prunesNoiseUnlessNoIgnore() {
        data class Case(val name: String, val dir: String, val noIgnore: Boolean, val ignore: Boolean)
        val cases = listOf(
            Case("node_modules", "node_modules", false, true),
            Case("git", ".git", false, true),
            Case("case insensitive", "Node_Modules", false, true),
            Case("source stays", "src", false, false),
            Case("dot github stays", ".github", false, false),
            Case("nvm", ".nvm", false, true),
            Case("cache", "Cache", false, true),
            Case("gpu cache", "GPUCache", false, true),
            Case("code cache", "Code Cache", false, true),
            Case("site-packages", "site-packages", false, true),
            Case("no_ignore disables", "node_modules", true, false),
            Case("dot and dotdot never", ".", false, false),
            Case("extra ignore", "vendor-lib", false, false),
        )
        cases.forEach { case ->
            assertEquals(
                case.name,
                case.ignore,
                FileSearchIgnore.shouldIgnoreDir(case.dir, case.noIgnore),
            )
        }
        assertEquals(
            true,
            FileSearchIgnore.shouldIgnoreDir("vendor-lib", false, listOf("vendor-lib")),
        )
    }

    @Test
    fun pathIgnoredUnderRoot_explicitRootIsNotSkipped() {
        data class Case(
            val name: String,
            val root: String,
            val full: String,
            val ignored: Boolean,
        )
        val cases = listOf(
            Case(
                "logs under repo is ignored",
                "C:/apps/dy-monitor",
                "C:/apps/dy-monitor/logs/record.log",
                true,
            ),
            Case(
                "explicit logs dir is searchable",
                "C:/apps/dy-monitor/logs",
                "C:/apps/dy-monitor/logs/record.log",
                false,
            ),
            Case(
                "explicit log file is searchable",
                "C:/apps/dy-monitor/logs/record.log",
                "C:/apps/dy-monitor/logs/record.log",
                false,
            ),
            Case(
                "windows slashes",
                """C:\apps\dy-monitor\logs""",
                """C:\apps\dy-monitor\logs\record.log""",
                false,
            ),
            Case(
                "src is not noise",
                "C:/apps/dy-monitor",
                "C:/apps/dy-monitor/src/api-server.js",
                false,
            ),
        )
        cases.forEach { case ->
            assertEquals(
                case.name,
                case.ignored,
                FileSearchIgnore.pathIgnoredUnderRoot(case.root, case.full, noIgnore = false),
            )
        }
        assertEquals(
            false,
            FileSearchIgnore.pathIgnoredUnderRoot(
                "C:/apps/dy-monitor",
                "C:/apps/dy-monitor/logs/record.log",
                noIgnore = true,
            ),
        )
    }
}
