package com.listentogether.music.api

import com.listentogether.core.common.AppError
import com.listentogether.core.common.AppResult
import com.listentogether.core.model.Track

/**
 * Provider-neutral catalogue contract. Implementations may talk to YouTube Music or any other
 * source; nothing outside `:music:*` may depend on implementation types (ADR-009).
 */
interface MusicCatalog {
    /**
     * Full-text search over the provider catalogue. [limit] is a hint; implementations may
     * return fewer results. Networks failures and provider failures are mapped to [AppError].
     */
    suspend fun search(query: String, limit: Int = DEFAULT_SEARCH_LIMIT): AppResult<List<Track>>

    /** Fetch fresh metadata for a known track, e.g. after a cache miss. */
    suspend fun track(providerTrackId: String): AppResult<Track>

    companion object {
        const val DEFAULT_SEARCH_LIMIT = 20
        const val MAX_SEARCH_LIMIT = 50
    }
}

/**
 * Resolves catalogue metadata into a playable, transient stream. URLs are never persisted and
 * never sent over the synchronization protocol (INV-6).
 */
interface TrackResolver {
    suspend fun resolve(track: Track): AppResult<ResolvedMedia>

    /**
     * Force a fresh resolution for [track], bypassing any cached URL. Playback calls this when a
     * stream expires; the selected track identity never changes.
     */
    suspend fun refresh(track: Track): AppResult<ResolvedMedia>

    /** Drop any cached resolution for the provider track id. */
    fun invalidate(providerTrackId: String)
}

data class ResolvedMedia(
    val url: String,
    val mimeType: String,
    val expiresAtEpochMs: Long? = null,
    val headers: Map<String, String> = emptyMap(),
    val bitrate: Int? = null,
) {
    fun isExpired(nowEpochMs: Long, safetyMarginMs: Long = 30_000): Boolean {
        val expiry = expiresAtEpochMs ?: return false
        return nowEpochMs >= expiry - safetyMarginMs
    }
}

/**
 * Provider failures, kept separate from wire errors so that a provider swap cannot change the
 * synchronization protocol. [toAppError] is the only supported crossing point.
 */
sealed class ProviderError(
    val code: String,
    message: String,
    val retryable: Boolean,
) {
    open val userMessage: String = message

    data object Unavailable : ProviderError("PROVIDER_UNAVAILABLE", "Track is not available", retryable = false)
    data object Network : ProviderError("PROVIDER_NETWORK", "Music service is unreachable", retryable = true)
    data object RateLimited : ProviderError("PROVIDER_RATE_LIMITED", "Music service is busy", retryable = true)
    data object ExtractionFailed : ProviderError("PROVIDER_EXTRACTION_FAILED", "Could not resolve the audio stream", retryable = true)
    data object UnsupportedFormat : ProviderError("PROVIDER_UNSUPPORTED_FORMAT", "Unsupported audio format", retryable = false)
    data class InvalidResponse(val detail: String) : ProviderError(
        "PROVIDER_INVALID_RESPONSE",
        detail.ifBlank { "Music service returned an invalid response" },
        retryable = true,
    )

    fun toAppError(): AppError = AppError(code = code, message = userMessage, retryable = retryable)

    companion object {
        /** Map an arbitrary implementation failure to the closest public provider error. */
        fun fromThrowable(error: Throwable): ProviderError = when (error) {
            is java.io.IOException -> Network
            is kotlinx.coroutines.CancellationException -> throw error
            else -> InvalidResponse(error.message ?: error::class.simpleName.orEmpty())
        }
    }
}

fun ProviderError.asFailure(): AppResult.Failure = AppResult.Failure(toAppError())
