package com.listentogether.testing

import com.listentogether.core.common.AppError
import com.listentogether.core.common.AppResult
import com.listentogether.core.common.Clock
import com.listentogether.core.model.Track
import com.listentogether.music.api.MusicCatalog
import com.listentogether.music.api.ResolvedMedia
import com.listentogether.music.api.TrackResolver
import kotlinx.coroutines.delay

/**
 * Fixed catalogue with configurable delay/failure, following the [MusicCatalog] seam from
 * architecture §17. Search matches title/artist case-insensitively.
 */
class FakeMusicCatalog(
    initialTracks: List<Track> = emptyList(),
) : MusicCatalog {
    val tracks: MutableList<Track> = initialTracks.toMutableList()
    val searchQueries = mutableListOf<String>()
    val trackRequests = mutableListOf<String>()

    var searchDelayMs: Long = 0
    var trackDelayMs: Long = 0
    var searchFailure: AppError? = null
    var trackFailure: AppError? = null

    override suspend fun search(query: String, limit: Int): AppResult<List<Track>> {
        searchQueries += query
        if (searchDelayMs > 0) delay(searchDelayMs)
        searchFailure?.let { return AppResult.Failure(it) }
        val matches = tracks.filter {
            it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true)
        }
        return AppResult.Success(matches.take(limit.coerceAtMost(MusicCatalog.MAX_SEARCH_LIMIT)))
    }

    override suspend fun track(providerTrackId: String): AppResult<Track> {
        trackRequests += providerTrackId
        if (trackDelayMs > 0) delay(trackDelayMs)
        trackFailure?.let { return AppResult.Failure(it) }
        val track = tracks.firstOrNull { it.providerTrackId == providerTrackId }
        return if (track != null) {
            AppResult.Success(track)
        } else {
            AppResult.Failure(AppError("PROVIDER_UNAVAILABLE", "Track is not available"))
        }
    }
}

/**
 * Resolver fake with an in-memory URL cache, per-track expiry and one-shot failures. Every
 * [refresh] mints a new URL so tests can prove stream renewal never changes track identity.
 */
class FakeTrackResolver(
    private val clock: Clock,
    initialMedia: Map<String, ResolvedMedia> = emptyMap(),
) : TrackResolver {
    enum class Kind { RESOLVE, REFRESH }

    data class Call(val kind: Kind, val providerTrackId: String)

    val calls = mutableListOf<Call>()
    val invalidated = mutableListOf<String>()
    private val cache = initialMedia.toMutableMap()
    private var generation = 0

    var resolveDelayMs: Long = 0
    var refreshDelayMs: Long = 0
    var failureFor: (Track) -> AppError? = { null }
    var expiryFor: (Track) -> Long? = { Long.MAX_VALUE }

    override suspend fun resolve(track: Track): AppResult<ResolvedMedia> {
        calls += Call(Kind.RESOLVE, track.providerTrackId)
        if (resolveDelayMs > 0) delay(resolveDelayMs)
        failureFor(track)?.let { return AppResult.Failure(it) }
        return AppResult.Success(cache.getOrPut(track.providerTrackId) { freshMedia(track) })
    }

    override suspend fun refresh(track: Track): AppResult<ResolvedMedia> {
        calls += Call(Kind.REFRESH, track.providerTrackId)
        if (refreshDelayMs > 0) delay(refreshDelayMs)
        failureFor(track)?.let { return AppResult.Failure(it) }
        return AppResult.Success(freshMedia(track).also { cache[track.providerTrackId] = it })
    }

    override fun invalidate(providerTrackId: String) {
        invalidated += providerTrackId
        cache.remove(providerTrackId)
    }

    fun cachedMedia(providerTrackId: String): ResolvedMedia? = cache[providerTrackId]

    fun putMedia(providerTrackId: String, media: ResolvedMedia) {
        cache[providerTrackId] = media
    }

    private fun freshMedia(track: Track): ResolvedMedia {
        generation++
        return ResolvedMedia(
            url = "https://fake.stream/${track.providerTrackId}/$generation",
            mimeType = "audio/mp4",
            expiresAtEpochMs = expiryFor(track),
        )
    }
}

fun fakeTrack(
    id: String,
    title: String = "Track $id",
    artist: String = "Artist",
    durationMs: Long = 180_000,
): Track = Track(
    providerTrackId = id,
    title = title,
    artist = artist,
    durationMs = durationMs,
)
