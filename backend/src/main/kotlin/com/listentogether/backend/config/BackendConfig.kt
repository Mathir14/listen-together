package com.listentogether.backend.config

data class BackendConfig(
    val port: Int = env("PORT", "8080").toInt(),
    val host: String = env("HOST", "0.0.0.0"),
    val gracePeriodMs: Long = env("RECONNECT_GRACE_MS", "60000").toLong(),
    val queueCap: Int = env("QUEUE_CAP", "200").toInt(),
    val chatCap: Int = env("CHAT_CAP", "500").toInt(),
    val maxFrameBytes: Int = env("MAX_FRAME_BYTES", "65536").toInt(),
    val maxChatLength: Int = env("MAX_CHAT_LENGTH", "2000").toInt(),
    val handshakeTimeoutMs: Long = env("HANDSHAKE_TIMEOUT_MS", "10000").toLong(),
    val heartbeatStaleMs: Long = env("HEARTBEAT_STALE_MS", "40000").toLong(),
    val maxCommandsPerSecond: Int = env("MAX_COMMANDS_PER_SECOND", "50").toInt(),
    val maxChatPerMinute: Int = env("MAX_CHAT_PER_MINUTE", "30").toInt(),
    val turnUrls: List<String> = env("TURN_URLS", "").split(',').map { it.trim() }.filter { it.isNotEmpty() },
    val turnSecret: String = env("TURN_SECRET", ""),
    val turnTtlSeconds: Long = env("TURN_TTL_SECONDS", "600").toLong(),
    val stunUrls: List<String> = env("STUN_URLS", "stun:stun.l.google.com:19302").split(',')
        .map { it.trim() }.filter { it.isNotEmpty() },
    val deploymentEnvironment: String = env("APP_ENV", "development"),
) {
    val turnEnabled: Boolean get() = turnUrls.isNotEmpty() && turnSecret.isNotBlank()

    companion object {
        private fun env(key: String, default: String): String =
            System.getenv(key)?.takeIf { it.isNotBlank() } ?: default
    }
}
