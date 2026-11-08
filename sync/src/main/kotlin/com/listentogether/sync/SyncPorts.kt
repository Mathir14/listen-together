package com.listentogether.sync

import com.listentogether.core.common.AppResult
import com.listentogether.core.model.PlaybackIssueState
import com.listentogether.core.model.PlaybackSnapshot
import com.listentogether.core.model.QueueItem
import com.listentogether.core.model.QueueState
import com.listentogether.core.model.RepeatMode
import com.listentogether.core.model.Track

/**
 * User intents exposed to the UI. Implementations must emit at most one command per call and
 * only while a session is active. Chat is intentionally not here: it belongs to
 * `:communication`.
 */
interface SyncCommands {
    fun play()
    fun pause()
    fun seek(positionMs: Long)

    /** Select [track] as the current item, replacing the current slot in place. */
    fun selectTrack(track: Track)

    fun next()
    fun previous()

    fun addToQueue(track: Track, index: Int? = null)
    fun removeFromQueue(itemId: String)
    fun reorderQueue(itemIds: List<String>)
    fun clearQueue()
    fun setRepeat(mode: RepeatMode)
    fun setShuffle(enabled: Boolean)

    /** Report a local provider/player failure to the peer (informational only). */
    fun reportPlaybackIssue(trackId: String, state: PlaybackIssueState, detail: String? = null)

    /** Explicit leave: the server keeps membership and closes only if both leave. */
    fun leave()
}

/** Local playback observation consumed by the engine. */
data class LocalPlaybackObservation(
    val trackId: String? = null,
    val itemId: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isPlaying: Boolean = false,
    val isReady: Boolean = false,
    val hasEnded: Boolean = false,
    val hasFailed: Boolean = false,
    val failureDetail: String? = null,
) {
    companion object {
        val Idle = LocalPlaybackObservation()
    }
}

interface LocalPlaybackProbe {
    val observation: kotlinx.coroutines.flow.StateFlow<LocalPlaybackObservation>
}

/** Applies authoritative snapshots to the local player. Implemented by `:playback`. */
interface RemoteStateSink {
    /**
     * Apply playback. [origin] is REMOTE for server frames, LOCAL_OPTIMISTIC for the local user's
     * pending command and SYSTEM for recovery. Implementations must never emit commands for
     * any origin.
     */
    suspend fun applyPlayback(playback: PlaybackSnapshot?, serverNowMs: Long, origin: Origin)

    suspend fun applyQueue(queue: QueueState?)

    /** Stop audio and drop all track-related local state (session closed/left). */
    suspend fun purge()
}

/** Result of an emitted command, used to correlate `COMMAND_REJECTED` frames. */
data class EmittedCommand(
    val commandId: String,
    val accepted: Boolean,
) {
    fun asResult(): AppResult<Unit> =
        if (accepted) AppResult.Success(Unit)
        else AppResult.Failure(com.listentogether.core.common.AppError.network("Transport is not connected"))
}

data class PendingCommand(
    val commandId: String,
    val description: String,
)

/** Convenience helpers shared by playback and tests. */
object QueueProjection {
    fun withItems(queue: QueueState?, items: List<QueueItem>): QueueState =
        (queue ?: QueueState()).copy(items = items)

    fun remove(queue: QueueState?, itemId: String): QueueState? =
        queue?.copy(items = queue.items.filterNot { it.itemId == itemId })
}
