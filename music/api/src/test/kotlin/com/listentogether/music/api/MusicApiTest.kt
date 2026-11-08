package com.listentogether.music.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MusicApiTest {
    @Test
    fun `resolved media expiry honours safety margin`() {
        val media = ResolvedMedia(url = "https://stream", mimeType = "audio/webm", expiresAtEpochMs = 100_000)
        assertFalse(media.isExpired(nowEpochMs = 60_000))
        assertTrue(media.isExpired(nowEpochMs = 70_000))
        assertTrue(media.isExpired(nowEpochMs = 120_000))
    }

    @Test
    fun `media without expiry never expires`() {
        assertFalse(ResolvedMedia(url = "https://stream", mimeType = "audio/mp4").isExpired(Long.MAX_VALUE))
    }

    @Test
    fun `provider errors map to stable app codes`() {
        val cases = listOf(
            ProviderError.Unavailable,
            ProviderError.Network,
            ProviderError.RateLimited,
            ProviderError.ExtractionFailed,
            ProviderError.UnsupportedFormat,
            ProviderError.InvalidResponse("bad body"),
        )
        cases.forEach { error ->
            val appError = error.toAppError()
            assertEquals(error.code, appError.code)
            assertEquals(error.retryable, appError.retryable)
            assertTrue(appError.message.isNotBlank())
        }
    }

    @Test
    fun `io failures become retryable network errors`() {
        val mapped = ProviderError.fromThrowable(java.io.IOException("offline"))
        assertEquals(ProviderError.Network, mapped)
        assertTrue(mapped.retryable)
    }
}
