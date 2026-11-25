package com.newoether.agora.util

import com.newoether.agora.data.ShellDeviceConfig
import java.security.MessageDigest

/**
 * One idle SSH session per device identity. Callers borrow for a single tool, then
 * [release] instead of disconnecting. A live idle session is reused; a dead or
 * expired one is closed and the next borrow opens a new handshake.
 *
 * [SshClient] is not thread-safe: a given instance is never lent to two callers
 * at once. A start_remote_process session stays off this pool so its exec
 * channel cannot race a later SFTP or exec on the same JSch session.
 */
class SshSessionPool(
    idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val leases = IdleLeasePool<SshSessionKey, SshClient>(
        idleTimeoutMs = idleTimeoutMs,
        nowMs = nowMs,
        isLive = SshClient::isConnected,
        close = SshClient::close,
    )

    fun acquire(
        device: ShellDeviceConfig,
        persistDetectedShell: ((deviceId: String, detected: String) -> Unit)?,
    ): SshClient = leases.acquire(SshSessionKey.of(device)) {
        device.createSshClient(persistDetectedShell)
    }

    fun release(device: ShellDeviceConfig, client: SshClient, discard: Boolean = false) {
        leases.release(SshSessionKey.of(device), client, discard)
    }

    suspend fun <R> use(
        device: ShellDeviceConfig,
        persistDetectedShell: ((deviceId: String, detected: String) -> Unit)?,
        block: suspend (SshClient) -> R,
    ): R {
        val client = acquire(device, persistDetectedShell)
        try {
            return block(client)
        } finally {
            release(device, client, discard = !client.isConnected())
        }
    }

    fun evictExpired() {
        leases.evictExpired()
    }

    internal fun idleCount(): Int = leases.idleCount()

    internal fun clear() {
        leases.clear()
    }

    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MS = 90_000L
        const val SWEEP_INTERVAL_MS = 30_000L
        val shared = SshSessionPool()
    }
}

internal data class SshSessionKey(
    val deviceId: String,
    val host: String,
    val port: Int,
    val user: String,
    val authFingerprint: String,
    val pinnedHostKey: String,
    val shell: String,
) {
    companion object {
        fun of(device: ShellDeviceConfig): SshSessionKey = SshSessionKey(
            deviceId = device.id,
            host = device.sshHost,
            port = device.sshPort,
            user = device.sshUser,
            authFingerprint = device.sshAuth().fingerprint(),
            pinnedHostKey = device.sshHostKey,
            shell = device.sshShell,
        )
    }
}

internal fun ShellDeviceConfig.createSshClient(
    persistDetectedShell: ((deviceId: String, detected: String) -> Unit)?,
): SshClient = SshClient(
    host = sshHost,
    port = sshPort,
    user = sshUser,
    auth = sshAuth(),
    pinnedHostKey = sshHostKey,
    allowUnknownHostKey = sshHostKey.isBlank(),
    shellPreference = sshShell,
    persistedDetectedShell = sshDetectedShell,
    onShellDetected = { detected -> persistDetectedShell?.invoke(id, detected) },
)

internal fun SshAuth.fingerprint(): String {
    val material = if (usesPublicKey) "k\n$privateKey\n$passphrase" else "p\n$password"
    val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray())
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}

/**
 * At most one idle value per key. Acquire takes it; release puts a live value
 * back (replacing an older idle). Expired or dead values are closed.
 */
internal class IdleLeasePool<K, T>(
    private val idleTimeoutMs: Long,
    private val nowMs: () -> Long,
    private val isLive: (T) -> Boolean,
    private val close: (T) -> Unit,
) {
    private val lock = Any()
    private val idle = LinkedHashMap<K, Idle<T>>()

    private data class Idle<T>(val value: T, val sinceMs: Long)

    fun acquire(key: K, create: () -> T): T {
        val recycled = synchronized(lock) {
            evictExpiredLocked()
            val existing = idle.remove(key) ?: return@synchronized null
            if (isLive(existing.value)) existing.value else {
                close(existing.value)
                null
            }
        }
        return recycled ?: create()
    }

    fun release(key: K, value: T, discard: Boolean = false) {
        if (discard || !isLive(value)) {
            close(value)
            return
        }
        synchronized(lock) {
            evictExpiredLocked()
            idle.remove(key)?.let { close(it.value) }
            idle[key] = Idle(value, nowMs())
        }
    }

    fun evictExpired() {
        synchronized(lock) { evictExpiredLocked() }
    }

    fun idleCount(): Int = synchronized(lock) { idle.size }

    fun clear() {
        synchronized(lock) {
            idle.values.forEach { close(it.value) }
            idle.clear()
        }
    }

    private fun evictExpiredLocked() {
        val now = nowMs()
        val iterator = idle.entries.iterator()
        while (iterator.hasNext()) {
            val held = iterator.next().value
            if (!isLive(held.value) || now - held.sinceMs >= idleTimeoutMs) {
                close(held.value)
                iterator.remove()
            }
        }
    }
}
