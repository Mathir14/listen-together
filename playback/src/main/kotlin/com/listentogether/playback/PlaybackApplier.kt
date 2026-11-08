package com.listentogether.playback

import com.listentogether.core.common.AppError
import com.listentogether.core.common.Clock
import com.listentogether.core.common.logging.Logger
import com.listentogether.core.common.logging.NoopLogger
import com.listentogether.core.model.PlaybackSnapshot
import com.listentogether.core.model.Track
import com.listentogether.music.api.ProviderError
import com.listentogether.music.api.ResolvedMedia
import com.listentogether.music.api.TrackResolver
import com.listentogether.sync.Origin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

data class AppliedPlayback(
    val playback: PlaybackSnapshot? = null,
    val trackId: String? = null,
    val isLoading: Boolean = false,
    val failure: AppError? = null,
)

/**
 * Applies authoritative (or optimistic) snapshots to the player. Resolution is guarded by a
 * generation counter so a slow resolver for an old track can never overwrite a newer selection.
 */
class PlaybackApplier(
    private val resolver: TrackResolver,
    private val engine: PlayerEngine,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val serverNowMs: () -> Long,
    private val retryDelayMs: Long = 250,
    private val logger: Logger = NoopLogger,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(AppliedPlayback())
    val state: StateFlow<AppliedPlayback> = _state.asStateFlow()

    private val _seekEvents = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    val seekEvents: SharedFlow<Long> = _seekEvents.asSharedFlow()

    private var generation = 0L
    private val recoveredKeys = mutableSetOf<String>()

    suspend fun applyPlayback(playback: PlaybackSnapshot?, origin: Origin) {
        var pending: PendingResolution? = null
        mutex.withLock {
            val snapshot = playback
            val track = snapshot?.track
            if (snapshot == null || track == null) {
                generation++
                engine.clear()
                _state.value = AppliedPlayback()
                return
            }
            val previous = _state.value.playback
            val trackChanged = previous?.track?.providerTrackId != track.providerTrackId
            _state.value = _state.value.copy(playback = snapshot, failure = null)

            if (trackChanged) {
                generation++
                val expectedGeneration = generation
                engine.clear()
                _state.value = _state.value.copy(isLoading = true, trackId = track.providerTrackId, failure = null)
                pending = PendingResolution(expectedGeneration, snapshot, track)
            } else {
                if (snapshot.isPlaying != engine.state.value.isPlaying) {
                    if (snapshot.isPlaying) engine.play() else engine.pause()
                }
                val now = serverNowMs()
                val projected = snapshot.projectedPositionMs(now)
                val divergence = abs(engine.positionMs() - projected)
                val recentlyMutated = abs(now - snapshot.updatedAt) <= MUTATION_WINDOW_MS
                val shouldSeek = when (origin) {
                    Origin.LOCAL_USER -> divergence > LOCAL_SEEK_THRESHOLD_MS
                    Origin.REMOTE, Origin.SYSTEM -> recentlyMutated && divergence > REMOTE_SEEK_THRESHOLD_MS
                }
                if (shouldSeek) {
                    engine.seekTo(projected)
                    _seekEvents.tryEmit(clock.monotonicMs())
                }
            }
        }

        val resolution = pending ?: return
        val media = resolveWithRetry(resolution.track)
        mutex.withLock {
            if (resolution.generation != generation) return
            if (media == null) {
                fail(ProviderError.ExtractionFailed.toAppError())
                return
            }
            // A newer snapshot for the same track may have arrived while resolving. Position and
            // play state come from the latest snapshot so that seek updates are not lost.
            val latest = _state.value.playback
                ?.takeIf { it.track?.providerTrackId == resolution.track.providerTrackId }
                ?: resolution.snapshot
            val projected = latest.projectedPositionMs(serverNowMs())
            engine.prepare(resolution.track, media, projected, latest.isPlaying)
            _state.value = _state.value.copy(isLoading = false, trackId = resolution.track.providerTrackId, failure = null)
            _seekEvents.tryEmit(clock.monotonicMs())
        }
    }

    private data class PendingResolution(
        val generation: Long,
        val snapshot: PlaybackSnapshot,
        val track: Track,
    )

    suspend fun applyQueue(@Suppress("UNUSED_PARAMETER") queue: com.listentogether.core.model.QueueState?) = Unit

    suspend fun purge() {
        mutex.withLock {
            generation++
            engine.clear()
            _state.value = AppliedPlayback()
            recoveredKeys.clear()
        }
    }

    /**
     * Called once per item after an ExoPlayer error. Refreshes the stream without changing track
     * identity; returns false when the item has already been retried or cannot be resolved.
     */
    suspend fun recoverFromFailure(): Boolean {
        mutex.withLock {
            val playback = _state.value.playback ?: return false
            val track = playback.track ?: return false
            val key = "${playback.sessionId}:${playback.currentItemId ?: track.providerTrackId}"
            if (!recoveredKeys.add(key)) return false
            val media = resolveWithRetry(track) ?: run {
                fail(ProviderError.ExtractionFailed.toAppError())
                return false
            }
            val projected = playback.projectedPositionMs(serverNowMs())
            generation++
            engine.prepare(track, media, projected, playback.isPlaying)
            _state.value = _state.value.copy(isLoading = false, trackId = track.providerTrackId, failure = null)
            _seekEvents.tryEmit(clock.monotonicMs())
            return true
        }
    }

    private suspend fun resolveWithRetry(track: Track): ResolvedMedia? {
        val first = resolver.resolve(track)
        val candidate = first.getOrNull()
        if (candidate != null && !candidate.isExpired(clock.nowMs())) return candidate
        resolver.invalidate(track.providerTrackId)
        if (candidate != null) logger.event("playback.stream.expired", mapOf("track_id" to track.providerTrackId))
        delay(retryDelayMs)
        val refreshed = resolver.refresh(track).getOrNull()
        if (refreshed == null) {
            logger.event("playback.resolve.failed", mapOf("track_id" to track.providerTrackId))
        }
        return refreshed?.takeIf { !it.isExpired(clock.nowMs()) }
    }

    private fun fail(error: AppError) {
        _state.value = _state.value.copy(isLoading = false, trackId = null, failure = error)
    }

    companion object {
        private const val LOCAL_SEEK_THRESHOLD_MS = 300L
        private const val REMOTE_SEEK_THRESHOLD_MS = 300L
        private const val MUTATION_WINDOW_MS = 1_500L
    }
}
