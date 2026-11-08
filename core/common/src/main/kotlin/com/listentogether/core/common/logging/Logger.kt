package com.listentogether.core.common.logging

/**
 * Minimal structured logging facade. Chat text, tokens, SDP and TTS content must never be
 * logged; [RedactingLogger] enforces that structurally at this boundary.
 */
interface Logger {
    fun event(name: String, fields: Map<String, Any?> = emptyMap())
    fun error(name: String, error: Throwable? = null, fields: Map<String, Any?> = emptyMap())
}

class RedactingLogger(
    private val sink: (String, Map<String, Any?>) -> Unit = { name, fields ->
        println(buildString {
            append(name)
            fields.forEach { (key, value) -> append(" $key=").append(value) }
        })
    },
    private val sensitiveKeys: Set<String> = DEFAULT_SENSITIVE_KEYS,
) : Logger {
    override fun event(name: String, fields: Map<String, Any?>) {
        sink(name, redact(fields))
    }

    override fun error(name: String, error: Throwable?, fields: Map<String, Any?>) {
        val safe = redact(fields) + mapOf(
            "error_type" to (error?.javaClass?.simpleName ?: "none"),
        )
        sink(name, safe)
    }

    fun redact(fields: Map<String, Any?>): Map<String, Any?> = fields.mapValues { (key, value) ->
        if (key.lowercase() in sensitiveKeys) REDACTED else value
    }

    companion object {
        const val REDACTED = "[redacted]"
        val DEFAULT_SENSITIVE_KEYS = setOf(
            "text", "content", "message", "chat", "token", "participant_token", "invite_secret",
            "secret", "sdp", "candidate", "tts", "utterance", "authorization", "password",
        )
    }
}

object NoopLogger : Logger {
    override fun event(name: String, fields: Map<String, Any?>) = Unit
    override fun error(name: String, error: Throwable?, fields: Map<String, Any?>) = Unit
}
