package com.listentogether.core.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-backed AES-GCM encryption for capability tokens. The only thing persisted in
 * DataStore is the encoded ciphertext.
 */
interface SecretVault {
    fun encrypt(plaintext: String): String
    fun decrypt(ciphertext: String): String
}

class KeystoreSecretVault(
    private val keyAlias: String = "listen_together_token_key",
) : SecretVault {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun key(): SecretKey {
        val existing = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + encrypted.size)
        iv.copyInto(combined)
        encrypted.copyInto(combined, iv.size)
        return Base64.getEncoder().encodeToString(combined)
    }

    override fun decrypt(ciphertext: String): String {
        val bytes = Base64.getDecoder().decode(ciphertext)
        require(bytes.size > IV_LENGTH) { "Invalid ciphertext" }
        val iv = bytes.copyOfRange(0, IV_LENGTH)
        val payload = bytes.copyOfRange(IV_LENGTH, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(payload), Charsets.UTF_8)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
    }
}

/** For tests and previews only; never used in a release graph. */
class InMemorySecretVault : SecretVault {
    private val values = mutableMapOf<String, String>()
    private var next = 0

    override fun encrypt(plaintext: String): String {
        val key = "cipher-${++next}"
        values[key] = plaintext
        return key
    }

    override fun decrypt(ciphertext: String): String =
        values[ciphertext] ?: error("Unknown ciphertext")
}
