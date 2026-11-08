package com.listentogether.sync

import com.listentogether.core.common.AppError
import com.listentogether.core.common.Ids
import com.listentogether.core.model.ChatMessage
import com.listentogether.core.model.ConnectionState
import com.listentogether.core.model.ParticipantView
import com.listentogether.core.model.PlaybackIssue
import com.listentogether.core.model.PlaybackIssueState
import com.listentogether.core.model.PlaybackSnapshot
import com.listentogether.core.model.QueueState
import com.listentogether.core.model.RepeatMode
import com.listentogether.core.model.Role
import com.listentogether.core.model.RoomView
import com.listentogether.core.model.SessionView
import com.listentogether.core.model.Track
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Typed transport seam consumed by `:sync`. The production implementation wraps the single
 * `:core:network` WebSocket; tests replace it with an in-memory bus (architecture §17).
 */
interface SessionTransport {
    val events: SharedFlow<SessionEvent>
    val connectionState: StateFlow<ConnectionState>

    fun connect(
        roomId: String,
        token: String,
        lastSessionId: String? = null,
        lastRevision: Long? = null,
    )

    /**
     * Encode and send one command. The returned id is the id carried on the wire so that
     * `COMMAND_REJECTED` can be correlated with the pending optimistic change.
     */
    fun send(command: SessionCommand, sessionId: String? = null, observedRevision: Long? = null): EmittedCommand

    fun disconnect()
    fun serverNowMs(): Long
}

data class SessionCommand(
    val id: String = Ids.newId(),
    val payload: CommandPayload,
)

sealed interface CommandPayload {
    data object Play : CommandPayload
    data object Pause : CommandPayload
    data class Seek(val positionMs: Long) : CommandPayload
    data class SelectTrack(val track: Track) : CommandPayload
    data object Next : CommandPayload
    data object Previous : CommandPayload
    data class TrackEnded(val itemId: String, val positionMs: Long) : CommandPayload
    data class AddToQueue(val track: Track, val index: Int? = null) : CommandPayload
    data class RemoveFromQueue(val itemId: String) : CommandPayload
    data class ReorderQueue(val itemIds: List<String>) : CommandPayload
    data object ClearQueue : CommandPayload
    data class ChangeRepeat(val mode: RepeatMode) : CommandPayload
    data class ChangeShuffle(val enabled: Boolean) : CommandPayload
    data class ReportPlaybackIssue(
        val trackId: String,
        val state: PlaybackIssueState,
        val detail: String? = null,
    ) : CommandPayload
    data class ChatSend(val clientMessageId: String, val text: String) : CommandPayload
    data object Leave : CommandPayload
}

sealed interface SessionEvent {
    data class Snapshot(
        val room: RoomView,
        val session: SessionView?,
        val playback: PlaybackSnapshot?,
        val queue: QueueState?,
        val chat: List<ChatMessage>,
        val role: Role,
        val serverTime: Long,
        val revision: Long,
    ) : SessionEvent

    data class RoomState(val room: RoomView) : SessionEvent
    data class SessionStarted(val session: SessionView, val playback: PlaybackSnapshot, val queue: QueueState) : SessionEvent
    data class SessionClosed(val sessionId: String, val reason: String) : SessionEvent
    data class Presence(val participants: List<ParticipantView>, val graceExpiresAt: Long? = null) : SessionEvent
    data class PlaybackChanged(val playback: PlaybackSnapshot) : SessionEvent
    data class QueueChanged(val queue: QueueState) : SessionEvent
    data class ChatReceived(val message: ChatMessage) : SessionEvent
    data class ChatAcknowledged(val clientMessageId: String, val chatSequence: Long) : SessionEvent
    data class PlaybackIssueReported(val issue: PlaybackIssue) : SessionEvent
    data class CommandRejected(val commandId: String, val error: AppError) : SessionEvent
    data class ServerError(val error: AppError) : SessionEvent
}
