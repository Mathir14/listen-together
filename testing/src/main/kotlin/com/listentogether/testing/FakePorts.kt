package com.listentogether.testing

import com.listentogether.audio.ExternalFocusChange
import com.listentogether.audio.FocusController
import com.listentogether.audio.RouteController
import com.listentogether.core.model.PlaybackSnapshot
import com.listentogether.core.model.QueueState
import com.listentogether.sync.LocalPlaybackObservation
import com.listentogether.sync.LocalPlaybackProbe
import com.listentogether.sync.Origin
import com.listentogether.sync.RemoteStateSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Records every authoritative application so tests can assert order and INV-4. */
class RecordingRemoteStateSink : RemoteStateSink {
    data class PlaybackApplication(
        val playback: PlaybackSnapshot?,
        val serverNowMs: Long,
        val origin: Origin,
    )

    val playbackApplications = mutableListOf<PlaybackApplication>()
    val queueApplications = mutableListOf<QueueState?>()
    var purgeCount: Int = 0
        private set

    val lastPlayback: PlaybackSnapshot? get() = playbackApplications.lastOrNull()?.playback
    val lastQueue: QueueState? get() = queueApplications.lastOrNull()

    override suspend fun applyPlayback(playback: PlaybackSnapshot?, serverNowMs: Long, origin: Origin) {
        playbackApplications += PlaybackApplication(playback, serverNowMs, origin)
    }

    override suspend fun applyQueue(queue: QueueState?) {
        queueApplications += queue
    }

    override suspend fun purge() {
        purgeCount++
    }

    fun lastOrigins(): List<Origin> = playbackApplications.map(PlaybackApplication::origin)
}

/** Mutable local observation seam consumed by `SessionEngine`. */
class MutableLocalPlaybackProbe(
    initial: LocalPlaybackObservation = LocalPlaybackObservation.Idle,
) : LocalPlaybackProbe {
    private val _observation = MutableStateFlow(initial)
    override val observation: StateFlow<LocalPlaybackObservation> = _observation.asStateFlow()

    fun publish(observation: LocalPlaybackObservation) {
        _observation.value = observation
    }
}

class RecordingFocusController(
    var grant: Boolean = true,
) : FocusController {
    var requestCount: Int = 0
        private set
    var abandonCount: Int = 0
        private set
    var hasFocus: Boolean = false
        private set

    override fun requestFocus(): Boolean {
        requestCount++
        hasFocus = grant
        return grant
    }

    override fun abandonFocus() {
        abandonCount++
        hasFocus = false
    }
}

class RecordingRouteController : RouteController {
    var communicationMode: Boolean = false
        private set
    var enterCount: Int = 0
        private set
    var exitCount: Int = 0
        private set

    override fun enterCommunicationMode() {
        enterCount++
        communicationMode = true
    }

    override fun exitCommunicationMode() {
        exitCount++
        communicationMode = false
    }
}

class RecordingVolumeSink {
    val volumes = mutableListOf<Float>()

    fun record(volume: Float) {
        volumes += volume
    }
}

/** Convenience for tests that drive focus loss without touching Android. */
fun focusLoss(recording: RecordingFocusController, change: ExternalFocusChange) {
    if (change == ExternalFocusChange.GAIN) recording.requestFocus() else recording.abandonFocus()
}
