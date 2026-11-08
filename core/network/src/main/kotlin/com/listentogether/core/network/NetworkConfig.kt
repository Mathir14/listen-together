package com.listentogether.core.network

data class NetworkConfig(
    val baseUrl: String,
) {
    val normalizedBaseUrl: String get() = baseUrl.trimEnd('/')
    val webSocketUrl: String
        get() {
            val scheme = when {
                normalizedBaseUrl.startsWith("https://") -> "wss://"
                normalizedBaseUrl.startsWith("http://") -> "ws://"
                else -> error("Backend URL must be http(s)://")
            }
            val rest = normalizedBaseUrl.substringAfter("://")
            return "$scheme$rest/ws"
        }

    companion object {
        const val PRODUCTION_BASE_URL = "https://listen-together-backend-2o5j.onrender.com"
        const val LOCAL_DEV_BASE_URL = "http://10.0.2.2:8080"
        const val DEFAULT_BASE_URL = PRODUCTION_BASE_URL
    }
}
