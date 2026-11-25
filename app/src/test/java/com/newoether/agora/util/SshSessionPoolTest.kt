package com.newoether.agora.util

import com.newoether.agora.data.ShellDeviceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SshSessionPoolTest {

    @Test
    fun idleLease_reusesLiveSessionAndEvictsDeadOrExpired() {
        data class Handle(var live: Boolean = true, var closed: Boolean = false)
        var now = 0L
        var created = 0
        val closed = mutableListOf<Handle>()
        val pool = IdleLeasePool<String, Handle>(
            idleTimeoutMs = 90_000L,
            nowMs = { now },
            isLive = { it.live && !it.closed },
            close = { handle ->
                handle.closed = true
                handle.live = false
                closed += handle
            },
        )
        fun create(): Handle {
            created += 1
            return Handle()
        }

        data class Expect(val name: String, val created: Int, val idle: Int, val closed: Int)
        fun expect(state: Expect) {
            assertEquals(state.name, state.created, created)
            assertEquals(state.name, state.idle, pool.idleCount())
            assertEquals(state.name, state.closed, closed.size)
        }

        val first = pool.acquire("a") { create() }
        expect(Expect("first acquire creates", created = 1, idle = 0, closed = 0))

        pool.release("a", first)
        expect(Expect("release parks idle", created = 1, idle = 1, closed = 0))

        assertSame(first, pool.acquire("a") { create() })
        expect(Expect("second acquire reuses", created = 1, idle = 0, closed = 0))

        pool.release("a", first)
        first.live = false
        val replacement = pool.acquire("a") { create() }
        expect(Expect("dead idle is closed then created", created = 2, idle = 0, closed = 1))

        pool.release("a", replacement, discard = true)
        expect(Expect("discard closes and leaves no idle", created = 2, idle = 0, closed = 2))

        val third = pool.acquire("a") { create() }
        pool.release("a", third)
        now = 90_000L
        pool.evictExpired()
        expect(Expect("expired idle is closed", created = 3, idle = 0, closed = 3))

        val parkedA = pool.acquire("a") { create() }
        pool.release("a", parkedA)
        val parkedB = pool.acquire("b") { create() }
        pool.release("b", parkedB)
        expect(Expect("other key does not steal", created = 5, idle = 2, closed = 3))

        val extraA = create()
        pool.release("a", extraA)
        assertTrue(parkedA.closed)
        expect(Expect("second idle replaces the older", created = 6, idle = 2, closed = 4))
    }

    @Test
    fun sessionKey_changesWhenHostAuthOrShellChanges() {
        val base = ShellDeviceConfig(
            id = "dev-1",
            name = "box",
            sshHost = "10.0.0.8",
            sshPort = 22,
            sshUser = "root",
            sshPassword = "secret",
            sshHostKey = "pin",
            sshShell = "auto",
        )
        data class Case(
            val name: String,
            val device: ShellDeviceConfig,
            val same: Boolean,
        )
        val baseline = SshSessionKey.of(base)
        listOf(
            Case("identical", base.copy(name = "other label"), true),
            Case("host", base.copy(sshHost = "10.0.0.9"), false),
            Case("port", base.copy(sshPort = 2222), false),
            Case("user", base.copy(sshUser = "admin"), false),
            Case("password", base.copy(sshPassword = "other"), false),
            Case("host key", base.copy(sshHostKey = "other-pin"), false),
            Case("shell", base.copy(sshShell = "posix"), false),
            Case(
                "private key is a different auth",
                base.copy(sshPrivateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nA\n-----END OPENSSH PRIVATE KEY-----"),
                false,
            ),
        ).forEach { case ->
            val key = SshSessionKey.of(case.device)
            if (case.same) {
                assertEquals(case.name, baseline, key)
            } else {
                assertNotEquals(case.name, baseline, key)
            }
        }
        assertEquals(
            SshAuth(password = "secret").fingerprint(),
            SshAuth(password = "secret").fingerprint(),
        )
        assertNotEquals(
            SshAuth(password = "secret").fingerprint(),
            SshAuth(password = "other").fingerprint(),
        )
    }
}
