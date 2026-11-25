package com.newoether.agora.util

import com.newoether.agora.data.ShellDeviceConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshAuthTest {
    @Test
    fun preferredAuthentications_isExclusive() {
        data class Case(
            val name: String,
            val auth: SshAuth,
            val usesPublicKey: Boolean,
            val preferred: String,
        )
        val cases = listOf(
            Case("password only", SshAuth(password = "secret"), false, "password"),
            Case("empty password", SshAuth(), false, "password"),
            Case("private key", SshAuth(privateKey = OPENSSH_KEY), true, "publickey"),
            Case("key with passphrase", SshAuth(privateKey = OPENSSH_KEY, passphrase = "p"), true, "publickey"),
            Case(
                "key wins when both present",
                SshAuth(password = "secret", privateKey = OPENSSH_KEY),
                true,
                "publickey",
            ),
        )
        for (case in cases) {
            assertEquals(case.name, case.usesPublicKey, case.auth.usesPublicKey)
            assertEquals(case.name, case.preferred, case.auth.preferredAuthentications())
        }
        val exclusivePassword = SshAuth.exclusive(
            usePublicKey = false,
            password = "secret",
            privateKey = OPENSSH_KEY,
            passphrase = "p",
        )
        assertEquals("secret", exclusivePassword.password)
        assertEquals("", exclusivePassword.privateKey)
        assertEquals("", exclusivePassword.passphrase)
        assertEquals("password", exclusivePassword.preferredAuthentications())

        val exclusiveKey = SshAuth.exclusive(
            usePublicKey = true,
            password = "secret",
            privateKey = OPENSSH_KEY,
            passphrase = "p",
        )
        assertEquals("", exclusiveKey.password)
        assertEquals(OPENSSH_KEY, exclusiveKey.privateKey)
        assertEquals("p", exclusiveKey.passphrase)
        assertEquals("publickey", exclusiveKey.preferredAuthentications())
    }

    @Test
    fun deviceExclusiveSave_clearsTheInactiveCredential() {
        val passwordSaved = ShellDeviceConfig(name = "box").withExclusiveSshAuth(
            usePublicKey = false,
            password = "secret",
            privateKey = OPENSSH_KEY,
            passphrase = "p",
            keyLabel = "id_ed25519",
        )
        assertEquals("secret", passwordSaved.sshPassword)
        assertEquals("", passwordSaved.sshPrivateKey)
        assertEquals("", passwordSaved.sshKeyPassphrase)
        assertEquals("", passwordSaved.sshKeyLabel)
        assertFalse(passwordSaved.sshAuth().usesPublicKey)

        val keySaved = ShellDeviceConfig(name = "box", sshPassword = "secret").withExclusiveSshAuth(
            usePublicKey = true,
            password = "secret",
            privateKey = OPENSSH_KEY,
            passphrase = "p",
            keyLabel = "id_ed25519",
        )
        assertEquals("", keySaved.sshPassword)
        assertEquals(OPENSSH_KEY, keySaved.sshPrivateKey)
        assertEquals("p", keySaved.sshKeyPassphrase)
        assertEquals("id_ed25519", keySaved.sshKeyLabel)
        assertTrue(keySaved.sshAuth().usesPublicKey)
    }

    @Test
    fun parseImportedPrivateKey_acceptsKnownFormatsAndRejectsJunk() {
        data class Case(val name: String, val bytes: ByteArray, val success: Boolean)
        val cases = listOf(
            Case("openssh", OPENSSH_KEY.toByteArray(), true),
            Case("pem rsa", PEM_RSA_KEY.toByteArray(), true),
            Case("putty", "PuTTY-User-Key-File-3: ssh-ed25519\n".toByteArray(), true),
            Case("leading whitespace", "\n  $OPENSSH_KEY".toByteArray(), true),
            Case("empty", ByteArray(0), false),
            Case("public key", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI comment\n".toByteArray(), false),
            Case("too large", ByteArray(SshAuth.MAX_PRIVATE_KEY_BYTES + 1) { 'A'.code.toByte() }, false),
        )
        for (case in cases) {
            assertEquals(case.name, case.success, parseImportedSshPrivateKey(case.bytes).isSuccess)
        }
    }

    @Test
    fun readSshPrivateKeyFile_usesPathAndRejectsOversizedOrMissing() {
        val dir = java.nio.file.Files.createTempDirectory("ssh-key").toFile()
        try {
            val keyFile = File(dir, "id_ed25519")
            keyFile.writeText(OPENSSH_KEY)
            assertTrue(readSshPrivateKeyFile(keyFile).isSuccess)

            val missing = File(dir, "nope")
            assertEquals("read", readSshPrivateKeyFile(missing).exceptionOrNull()?.message)

            val huge = File(dir, "huge")
            huge.writeBytes(ByteArray(SshAuth.MAX_PRIVATE_KEY_BYTES + 1) { 'A'.code.toByte() })
            assertEquals(SshAuth.IMPORT_TOO_LARGE, readSshPrivateKeyFile(huge).exceptionOrNull()?.message)
        } finally {
            dir.deleteRecursively()
        }
    }
}

private const val OPENSSH_KEY = """
-----BEGIN OPENSSH PRIVATE KEY-----
b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
-----END OPENSSH PRIVATE KEY-----
"""

private const val PEM_RSA_KEY = """
-----BEGIN RSA PRIVATE KEY-----
MIIBOgIBAAJBAK8=
-----END RSA PRIVATE KEY-----
"""
