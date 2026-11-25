package com.newoether.agora.util

import com.jcraft.jsch.JSch
import org.junit.Assert.assertTrue
import org.junit.Test

class SshJschConfigTest {
    @Test
    fun eddsaSignaturesUseBouncyCastleAndInit() {
        SshJschConfig.apply()
        val algorithms = listOf("ssh-ed25519", "ssh-ed448")
        for (algorithm in algorithms) {
            assertTrue(algorithm, SshJschConfig.eddsaSignatureUsable(algorithm))
        }
    }

    @Test
    fun opensshEd25519IdentityLoads() {
        SshJschConfig.apply()
        val jsch = JSch()
        jsch.addIdentity(
            "agora-test",
            OPENSSH_ED25519_PRIVATE.trimIndent().toByteArray(Charsets.UTF_8),
            null,
            null,
        )
        assertTrue(jsch.identityNames.isNotEmpty())
    }
}

// Disposable unencrypted OpenSSH Ed25519 key used only to prove JSch can load the format.
private const val OPENSSH_ED25519_PRIVATE = """
-----BEGIN OPENSSH PRIVATE KEY-----
b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
QyNTUxOQAAACCFOdo9RBIbJlFWTpLDuBCPXO0QMk0euByfBMefDbCWigAAAJBL5IqIS+SK
iAAAAAtzc2gtZWQyNTUxOQAAACCFOdo9RBIbJlFWTpLDuBCPXO0QMk0euByfBMefDbCWig
AAAECotLXxlgrI86El5sL6QmPRLzotC4wgUYj1I0E3Y9uoUIU52j1EEhsmUVZOksO4EI9c
7RAyTR64HJ8Ex58NsJaKAAAACmFnb3JhLXRlc3QBAgM=
-----END OPENSSH PRIVATE KEY-----
"""
