package com.listentogether.backend.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class Secrets(private val random: SecureRandom = SecureRandom()) {
    fun randomToken(bytes: Int = 32): String {
        val data = ByteArray(bytes)
        random.nextBytes(data)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    }

    fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest)
    }

    fun secureEquals(left: String, right: String): Boolean {
        return MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))
    }
}

data class TokenBinding(
    val roomId: String,
    val participantId: String,
    val role: com.listentogether.core.model.Role,
)
