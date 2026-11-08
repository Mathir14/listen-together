package com.listentogether.playback

import com.listentogether.audio.AudioArbiter
import com.listentogether.core.common.Clock
import com.listentogether.core.common.logging.Logger
import com.listentogether.core.common.logging.NoopLogger
import com.listentogether.core.model.PlaybackSnapshot
import com.listentogether.core.model.QueueState
import com.listentogether.music.api.TrackResolver
import com.listentogether.sync.LocalPlaybackObservation
import com.listentogether.sync.LocalPlaybackProbe
import com.listentogether.sync.Origin
import com.listentogether.sync.RemoteStateSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-scoped playback runtime: applies authoritative snapshots, exposes a local playback
 * observation to `:sync`, and owns drift correction. It never emits protocol commands.
 */
class RoomPlayer(
    private val applier: PlaybackApplier,
    private val engine: PlayerEngine,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val serverNowMs: () -> Long,
    private val audioArbiter: AudioArbiter? = null,
    private val driftCorrector: DriftCorrector = DriftCorrector(clock),
    private val logger: Logger = NoopLogger,
) : RemoteStateSink, LocalPlaybackProbe, AutoCloseable {

    private val _observation = MutableStateFlow(LocalPlaybackObservation.Idle)
    override val observation: StateFlow<LocalPlaybackObservation> = _observation.asStateFlow()

    private val mutex = Mutex()
    private val jobs = mutableListOf<Job>()
    private var preparedTrackId: String? = null
    private var recovering = false

    fun start() {
        if (jobs.isNotEmpty()) return
        audioArbiter?.setVolumeSink(engine::setVolume)
        jobs += scope.launch {
            engine.state.collect { state ->
                updateObservation()
                if (state.isReady && preparedTrackId != state.trackId) {
                    preparedTrackId = state.trackId
                    driftCorrector.noteTrackReady(clock.monotonicMs())
                }
                if (state.hasFailed && !recovering) {
                    recovering = true
                    scope.launch {
                        val recovered = applier.recoverFromFailure()
                        if (!recovered) logger.event("playback.recovery.failed", emptyMap())
                        recovering = false
                        updateObservation()
                    }
                }
            }
        }
        jobs += scope.launch {
            applier.seekEvents.collect { at -> driftCorrector.noteRemoteSeek(at) }
        }
        jobs += scope.launch {
            while (isActive) {
                delay(OBSERVATION_INTERVAL_MS)
                updateObservation()
            }
        }
        jobs += scope.launch {
            while (isActive) {
                delay(DRIFT_INTERVAL_MS)
                driftStep()
            }
        }
    }

    override suspend fun applyPlayback(playback: PlaybackSnapshot?, serverNowMs: Long, origin: Origin) {
        mutex.withLock {
            audioArbiter?.setMusicActive(playback?.track != null)
            if (playback != null && origin == Origin.REMOTE) {
                // A track change is a fresh horizon for drift; the next READY opens the gate.
                if (applier.state.value.playback?.track?.providerTrackId != playback.track?.providerTrackId) {
                    preparedTrackId = null
                    driftCorrector.noteTrackChanged(clock.monotonicMs())
                }
            }
            applier.applyPlayback(playback, origin)
            updateObservation()
        }
    }

    override suspend fun applyQueue(queue: QueueState?) {
        applier.applyQueue(queue)
    }

    override suspend fun purge() {
        mutex.withLock {
            applier.purge()
            audioArbiter?.setMusicActive(false)
            driftCorrector.reset()
            preparedTrackId = null
            _observation.value = LocalPlaybackObservation.Idle
        }
    }

    fun currentPlayback(): PlaybackSnapshot? = applier.state.value.playback

    private fun driftStep() {
        val playback = applier.state.value.playback ?: return
        val playerState = engine.state.value
        if (!playback.isPlaying || !playerState.isPlaying) {
            if (playerState.playbackRate != 1f) engine.setRate(1f)
            return
        }
        val projected = playback.projectedPositionMs(serverNowMs())
        val action = driftCorrector.evaluate(
            localPositionMs = engine.positionMs(),
            authoritativePositionMs = projected,
            isPlaying = playerState.isPlaying && playback.isPlaying,
            isReady = playerState.isReady,
            isBuffering = playerState.isBuffering,
        ) ?: return
        when (action) {
            is DriftAction.RateNudge -> engine.setRate(action.rate)
            is DriftAction.HardSeek -> {
                engine.setRate(1f)
                engine.seekTo(action.positionMs)
                logger.event("playback.drift.seek", mapOf("position_ms" to action.positionMs))
            }
            DriftAction.RestoreRate -> engine.setRate(1f)
        }
    }

    private fun updateObservation() {
        val applied = applier.state.value
        val playerState = engine.state.value
        val failureDetail = playerState.failureDetail ?: applied.failure?.message
        _observation.value = LocalPlaybackObservation(
            trackId = playerState.trackId ?: applied.trackId,
            itemId = applied.playback?.currentItemId,
            positionMs = engine.positionMs(),
            durationMs = playerState.durationMs,
            isPlaying = playerState.isPlaying,
            isReady = playerState.isReady,
            hasEnded = playerState.hasEnded,
            hasFailed = playerState.hasFailed || applied.failure != null,
            failureDetail = failureDetail,
        )
    }

    override fun close() {
        jobs.forEach(Job::cancel)
        jobs.clear()
        audioArbiter?.setMusicActive(false)
        engine.release()
    }

    companion object {
        private const val OBSERVATION_INTERVAL_MS = 250L
        private const val DRIFT_INTERVAL_MS = 1_000L
    }
}

/** MediaSession factory kept in `:playback` so `:service` never touches player construction. */
object RoomMediaSession {
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun create(
        context: android.content.Context,
        player: androidx.media3.common.Player,
        sessionActivity: android.app.PendingIntent? = null,
        callback: androidx.media3.session.MediaSession.Callback? = null,
        customLayout: List<androidx.media3.session.CommandButton> = emptyList(),
    ): androidx.media3.session.MediaSession {
        val builder = androidx.media3.session.MediaSession.Builder(context, player)
        sessionActivity?.let(builder::setSessionActivity)
        callback?.let(builder::setCallback)
        if (customLayout.isNotEmpty()) builder.setCustomLayout(customLayout)
        return builder.build()
    }
}

fun createRoomPlayer(
    resolver: TrackResolver,
    engine: PlayerEngine,
    clock: Clock,
    scope: CoroutineScope,
    serverNowMs: () -> Long,
    audioArbiter: AudioArbiter? = null,
): RoomPlayer {
    val applier = PlaybackApplier(resolver, engine, clock, scope, serverNowMs)
    return RoomPlayer(applier, engine, clock, scope, serverNowMs, audioArbiter).also { it.start() }
}
