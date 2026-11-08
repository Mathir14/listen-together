package com.listentogether.backend.turn

import com.listentogether.backend.auth.Secrets
import com.listentogether.backend.auth.TokenBinding
import com.listentogether.backend.config.BackendConfig
import com.listentogether.core.common.Clock
import com.listentogether.core.model.TurnCredentials
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

class TurnService(
    private val config: BackendConfig,
    private val clock: Clock,
) {
    fun credentials(binding: TokenBinding): TurnCredentials? {
        if (!config.turnEnabled) return null
        val expiresAt = clock.nowMs() / 1000 + config.turnTtlSeconds
        val username = "$expiresAt:${binding.participantId}"
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(config.turnSecret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val credential = Base64.getEncoder().encodeToString(mac.doFinal(username.toByteArray(Charsets.UTF_8)))
        return TurnCredentials(
            username = username,
            credential = credential,
            ttlSeconds = config.turnTtlSeconds,
            urls = config.turnUrls,
        )
    }

    fun stunUrls(): List<String> = config.stunUrls
}
