package com.newoether.agora.tool

import android.os.Process as OsProcess
import java.io.File

/**
 * Kills a local [Process] and the descendants of `sh -c`, which [Process.destroy]
 * leaves behind.
 */
internal object UnixProcessTree {
    const val SIGTERM = 15
    const val SIGKILL = 9

    fun parseStatusPpid(status: String): Int? {
        val line = status.lineSequence().firstOrNull { it.startsWith("PPid:") } ?: return null
        return line.substringAfter(':').trim().toIntOrNull()?.takeIf { it >= 0 }
    }

    fun descendantPids(rootPid: Int, parentByPid: Map<Int, Int>): Set<Int> {
        if (rootPid <= 0) return emptySet()
        val children = parentByPid.entries
            .groupBy({ it.value }, { it.key })
        val out = linkedSetOf<Int>()
        val stack = ArrayDeque<Int>()
        stack.add(rootPid)
        while (stack.isNotEmpty()) {
            val parent = stack.removeFirst()
            for (child in children[parent].orEmpty()) {
                if (child != rootPid && out.add(child)) stack.add(child)
            }
        }
        return out
    }

    fun kill(process: Process) {
        val pid = pidOf(process)
        if (pid != null) {
            var tree = descendantPids(pid, readParentByPid()) + pid
            signal(tree, SIGTERM)
            waitUntilDead(process, ProcessTools.STOP_GRACE_MS)
            if (process.isAlive) {
                tree = descendantPids(pid, readParentByPid()) + pid
                signal(tree, SIGKILL)
            }
        }
        if (process.isAlive) {
            process.destroy()
            runCatching { process.destroyForcibly() }
            waitUntilDead(process, ProcessTools.STOP_FORCE_MS)
        }
    }

    internal fun pidOf(process: Process): Int? {
        var cls: Class<*>? = process.javaClass
        while (cls != null) {
            val type = cls
            for (name in listOf("pid", "id")) {
                val value = runCatching {
                    type.getDeclaredField(name).apply { isAccessible = true }.get(process)
                }.getOrNull()
                when (value) {
                    is Int -> if (value > 0) return value
                    is Long -> if (value > 0L) return value.toInt()
                }
            }
            val methodPid = runCatching {
                type.getMethod("pid").invoke(process)
            }.getOrNull()
            when (methodPid) {
                is Int -> if (methodPid > 0) return methodPid
                is Long -> if (methodPid > 0L) return methodPid.toInt()
            }
            cls = type.superclass
        }
        return null
    }

    private fun readParentByPid(): Map<Int, Int> {
        val proc = File("/proc")
        val dirs = proc.listFiles() ?: return emptyMap()
        val out = HashMap<Int, Int>()
        for (dir in dirs) {
            val pid = dir.name.toIntOrNull() ?: continue
            val status = runCatching { File(dir, "status").readText() }.getOrNull() ?: continue
            val ppid = parseStatusPpid(status) ?: continue
            out[pid] = ppid
        }
        return out
    }

    private fun signal(pids: Set<Int>, signal: Int) {
        for (pid in pids) {
            if (pid > 0) runCatching { OsProcess.sendSignal(pid, signal) }
        }
    }

    private fun waitUntilDead(process: Process, budgetMs: Int) {
        val deadline = System.currentTimeMillis() + budgetMs.toLong()
        while (process.isAlive && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }
}
