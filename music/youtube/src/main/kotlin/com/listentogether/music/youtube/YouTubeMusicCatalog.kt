package com.listentogether.music.youtube

import com.listentogether.core.common.AppResult
import com.listentogether.music.api.MusicCatalog
import com.listentogether.music.api.ProviderError
import com.listentogether.music.api.asFailure
import com.listentogether.core.model.Track
import kotlinx.coroutines.delay

/**
 * YouTube Music catalogue backed by the InnerTube web interface. Search is retried once on a
 * retryable failure; malformed responses are rejected rather than surfaced as partial results.
 */
class YouTubeMusicCatalog(
    private val client: InnerTubeClient = InnerTubeClient(),
    private val retryDelayMs: Long = 250,
) : MusicCatalog {
    override suspend fun search(query: String, limit: Int): AppResult<List<Track>> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return AppResult.Success(emptyList())
        val capped = limit.coerceIn(1, MusicCatalog.MAX_SEARCH_LIMIT)
        return attempt {
            InnerTubeSearchParser.parse(client.search(normalized), capped)
        }
    }

    override suspend fun track(providerTrackId: String): AppResult<Track> = attempt {
        InnerTubeSearchParser.videoDetails(client.player(providerTrackId))
            ?: throw IllegalStateException("No video details for $providerTrackId")
    }

    private suspend fun <T> attempt(block: suspend () -> T): AppResult<T> {
        var last: ProviderError? = null
        repeat(2) { attempt ->
            try {
                return AppResult.Success(block())
            } catch (error: Throwable) {
                val mapped = ProviderErrors.map(error)
                last = mapped
                if (!mapped.retryable || attempt == 1) return mapped.asFailure()
                delay(retryDelayMs)
            }
        }
        return (last ?: ProviderError.InvalidResponse("unknown failure")).asFailure()
    }
}
