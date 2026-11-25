package com.newoether.agora.util

import java.io.File

/**
 * SSH user authentication for one device. Password and private key are exclusive:
 * a non-blank private key always wins, and the password is ignored.
 */
data class SshAuth(
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
) {
    val usesPublicKey: Boolean get() = privateKey.isNotBlank()

    fun preferredAuthentications(): String =
        if (usesPublicKey) "publickey" else "password"

    companion object {
        const val MAX_PRIVATE_KEY_BYTES = 64 * 1024
        const val IMPORT_TOO_LARGE = "too_large"
        const val IMPORT_UNRECOGNIZED = "unrecognized"

        fun exclusive(
            usePublicKey: Boolean,
            password: String = "",
            privateKey: String = "",
            passphrase: String = "",
        ): SshAuth = if (usePublicKey) {
            SshAuth(privateKey = privateKey, passphrase = passphrase)
        } else {
            SshAuth(password = password)
        }
    }
}

fun looksLikeSshPrivateKey(text: String): Boolean {
    val body = text.trimStart()
    if (body.startsWith("PuTTY-User-Key-File-")) return true
    return PEM_PRIVATE_KEY.containsMatchIn(body)
}

fun parseImportedSshPrivateKey(bytes: ByteArray): Result<String> {
    if (bytes.size > SshAuth.MAX_PRIVATE_KEY_BYTES) {
        return Result.failure(IllegalArgumentException(SshAuth.IMPORT_TOO_LARGE))
    }
    val text = bytes.toString(Charsets.UTF_8)
    if (!looksLikeSshPrivateKey(text)) {
        return Result.failure(IllegalArgumentException(SshAuth.IMPORT_UNRECOGNIZED))
    }
    return Result.success(text)
}

fun readSshPrivateKeyFile(file: File): Result<String> {
    if (!file.isFile) return Result.failure(IllegalArgumentException("read"))
    if (file.length() > SshAuth.MAX_PRIVATE_KEY_BYTES) {
        return Result.failure(IllegalArgumentException(SshAuth.IMPORT_TOO_LARGE))
    }
    val bytes = runCatching { file.readBytes() }.getOrElse {
        return Result.failure(IllegalArgumentException("read"))
    }
    return parseImportedSshPrivateKey(bytes)
}

private val PEM_PRIVATE_KEY = Regex("-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----")
