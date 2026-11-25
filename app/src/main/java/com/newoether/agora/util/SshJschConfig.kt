package com.newoether.agora.util

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Signature

/**
 * Pin JSch EdDSA to BouncyCastle before the first session handshake.
 *
 * JSch is a multi-release JAR: when the runtime reports Java 15+, it uses
 * `Signature.getInstance("Ed25519")`. Android often reports a high spec
 * version while that JCE algorithm is missing or broken, so CheckSignatures
 * marks `ssh-ed25519` unavailable and publickey auth never sends a signed
 * request. The BC implementations talk to BouncyCastle's lightweight API
 * instead of JCE.
 */
internal object SshJschConfig {
    private val lock = Any()

    @Volatile
    private var applied = false

    fun apply() {
        if (applied) return
        synchronized(lock) {
            if (applied) return
            JSch.setConfig("ssh-ed25519", BC_SIGNATURE_ED25519)
            JSch.setConfig("ssh-ed448", BC_SIGNATURE_ED448)
            JSch.setConfig("keypairgen.eddsa", BC_KEYPAIR_EDDSA)
            JSch.setConfig("keypairgen_fromprivate.eddsa", BC_KEYPAIR_EDDSA)
            applied = true
        }
    }

    fun eddsaSignatureUsable(algorithm: String): Boolean = runCatching {
        apply()
        val impl = JSch.getConfig(algorithm)
        require(!impl.contains(".jce.")) { "JCE EdDSA is not reliable on Android" }
        val signature = Class.forName(impl)
            .getDeclaredConstructor()
            .newInstance() as Signature
        signature.init()
    }.isSuccess

    private const val BC_SIGNATURE_ED25519 = "com.jcraft.jsch.bc.SignatureEd25519"
    private const val BC_SIGNATURE_ED448 = "com.jcraft.jsch.bc.SignatureEd448"
    private const val BC_KEYPAIR_EDDSA = "com.jcraft.jsch.bc.KeyPairGenEdDSA"
}
