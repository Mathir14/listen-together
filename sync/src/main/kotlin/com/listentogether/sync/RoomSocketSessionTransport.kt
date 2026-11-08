package com.listentogether.sync

import com.listentogether.core.common.AppError
import com.listentogether.core.model.PlaybackIssueState
import com.listentogether.core.network.ProtocolChannel
import com.listentogether.protocol.ChatAckPayload
import com.listentogether.protocol.ChatMessagePayload
import com.listentogether.protocol.ChatSendPayload
import com.listentogether.protocol.CommandRejectedPayload
import com.listentogether.protocol.Envelope
import com.listentogether.protocol.ErrorPayload
import com.listentogether.protocol.FrameKind
import com.listentogether.protocol.LeavePayload
import com.listentogether.protocol.MessageType
import com.listentogether.protocol.PlaybackIssuePayload
import com.listentogether.protocol.PlaybackReportPayload
import com.listentogether.protocol.PlaybackStatePayload
import com.listentogether.protocol.PresencePayload
import com.listentogether.protocol.ProtocolJson
import com.listentogether.protocol.QueueAddPayload
import com.listentogether.protocol.QueueRemovePayload
import com.listentogether.protocol.QueueReorderPayload
import com.listentogether.protocol.QueueStatePayload
import com.listentogether.protocol.RepeatChangePayload
import com.listentogether.protocol.RoomStatePayload
import com.listentogether.protocol.SeekPayload
import com.listentogether.protocol.SessionClosedPayload
import com.listentogether.protocol.SessionStartedPayload
import com.listentogether.protocol.ShuffleChangePayload
import com.listentogether.protocol.SnapshotPayload
import com.listentogether.protocol.TrackEndedPayload
import com.listentogether.protocol.TrackSelectPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn
import kotlinx.serialization.json.JsonObject
import com.listentogether.protocol.decodePayload

/**
 * Adapts the single `:core:network` channel into typed [SessionEvent]s. Wire envelopes never
 * escape this class; every payload is decoded through the shared `:protocol` contract.
 */
class RoomSocketSessionTransport(
    private val channel: ProtocolChannel,
    scope: CoroutineScope,
) : SessionTransport {
    private val typedEvents = channel.incoming
        .mapNotNull(::toEvent)
        .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    override val events: SharedFlow<SessionEvent> = typedEvents
    override val connectionState: StateFlow<com.listentogether.core.model.ConnectionState> = channel.connectionState

    @Volatile private var connectedRoomId: String? = null

    override fun connect(roomId: String, token: String, lastSessionId: String?, lastRevision: Long?) {
        connectedRoomId = roomId
        channel.connect(roomId, token, lastSessionId, lastRevision)
    }

    override fun send(command: SessionCommand, sessionId: String?, observedRevision: Long?): EmittedCommand {
        val roomId = connectedRoomId ?: return EmittedCommand(command.id, accepted = false)
        val frame = buildFrame(command, roomId, sessionId, observedRevision)
        return EmittedCommand(command.id, channel.send(frame))
    }

    override fun disconnect() {
        channel.disconnect()
    }

    override fun serverNowMs(): Long = channel.serverNowMs()

    private fun buildFrame(
        command: SessionCommand,
        roomId: String,
        sessionId: String?,
        observedRevision: Long?,
    ): Envelope {
        val (type, payload) = encode(command.payload)
        return Envelope(
            kind = FrameKind.COMMAND,
            id = command.id,
            type = type,
            roomId = roomId,
            sessionId = sessionId,
            observedRevision = observedRevision,
            payload = payload,
        )
    }

    private fun encode(payload: CommandPayload): Pair<MessageType, JsonObject> = when (payload) {
        CommandPayload.Play -> MessageType.PLAY to JsonObject(emptyMap())
        CommandPayload.Pause -> MessageType.PAUSE to JsonObject(emptyMap())
        is CommandPayload.Seek -> MessageType.SEEK to ProtocolJson.encodePayload(SeekPayload(payload.positionMs))
        is CommandPayload.SelectTrack -> MessageType.TRACK_SELECT to ProtocolJson.encodePayload(TrackSelectPayload(payload.track))
        CommandPayload.Next -> MessageType.NEXT to JsonObject(emptyMap())
        CommandPayload.Previous -> MessageType.PREV to JsonObject(emptyMap())
        is CommandPayload.TrackEnded -> MessageType.TRACK_ENDED to
            ProtocolJson.encodePayload(TrackEndedPayload(payload.itemId, payload.positionMs))
        is CommandPayload.AddToQueue -> MessageType.QUEUE_ADD to
            ProtocolJson.encodePayload(QueueAddPayload(payload.track, payload.index))
        is CommandPayload.RemoveFromQueue -> MessageType.QUEUE_REMOVE to
            ProtocolJson.encodePayload(QueueRemovePayload(payload.itemId))
        is CommandPayload.ReorderQueue -> MessageType.QUEUE_REORDER to
            ProtocolJson.encodePayload(QueueReorderPayload(payload.itemIds))
        CommandPayload.ClearQueue -> MessageType.QUEUE_CLEAR to JsonObject(emptyMap())
        is CommandPayload.ChangeRepeat -> MessageType.REPEAT_CHANGE to
            ProtocolJson.encodePayload(RepeatChangePayload(payload.mode))
        is CommandPayload.ChangeShuffle -> MessageType.SHUFFLE_CHANGE to
            ProtocolJson.encodePayload(ShuffleChangePayload(payload.enabled))
        is CommandPayload.ReportPlaybackIssue -> MessageType.PLAYBACK_REPORT to
            ProtocolJson.encodePayload(PlaybackReportPayload(payload.trackId, payload.state, payload.detail))
        is CommandPayload.ChatSend -> MessageType.CHAT_SEND to
            ProtocolJson.encodePayload(ChatSendPayload(payload.clientMessageId, payload.text))
        CommandPayload.Leave -> MessageType.LEAVE to ProtocolJson.encodePayload(LeavePayload("USER"))
    }

    private fun toEvent(envelope: Envelope): SessionEvent? = runCatching {
        when (envelope.type) {
            MessageType.SNAPSHOT -> {
                val p = envelope.decodePayload<SnapshotPayload>()
                SessionEvent.Snapshot(
                    room = p.room,
                    session = p.session,
                    playback = p.playback,
                    queue = p.queue,
                    chat = p.chat,
                    role = p.yourRole,
                    serverTime = p.serverTime,
                    revision = p.revision,
                )
            }
            MessageType.ROOM_STATE -> SessionEvent.RoomState(envelope.decodePayload<RoomStatePayload>().room)
            MessageType.SESSION_STARTED -> {
                val p = envelope.decodePayload<SessionStartedPayload>()
                SessionEvent.SessionStarted(p.session, p.playback, p.queue)
            }
            MessageType.SESSION_CLOSED -> {
                val p = envelope.decodePayload<SessionClosedPayload>()
                SessionEvent.SessionClosed(p.sessionId, p.reason)
            }
            MessageType.PRESENCE -> {
                val p = envelope.decodePayload<PresencePayload>()
                SessionEvent.Presence(p.participants, p.graceExpiresAt)
            }
            MessageType.PLAYBACK_STATE -> SessionEvent.PlaybackChanged(envelope.decodePayload<PlaybackStatePayload>().playback)
            MessageType.QUEUE_STATE -> SessionEvent.QueueChanged(envelope.decodePayload<QueueStatePayload>().queue)
            MessageType.CHAT_MESSAGE -> SessionEvent.ChatReceived(envelope.decodePayload<ChatMessagePayload>().message)
            MessageType.CHAT_ACK -> {
                val p = envelope.decodePayload<ChatAckPayload>()
                SessionEvent.ChatAcknowledged(p.clientMessageId, p.chatSequence)
            }
            MessageType.PLAYBACK_ISSUE -> SessionEvent.PlaybackIssueReported(envelope.decodePayload<PlaybackIssuePayload>().issue)
            MessageType.COMMAND_REJECTED -> {
                val p = envelope.decodePayload<CommandRejectedPayload>()
                SessionEvent.CommandRejected(p.commandId, p.toAppError())
            }
            MessageType.ERROR -> {
                val p = envelope.decodePayload<ErrorPayload>()
                SessionEvent.ServerError(p.toAppError())
            }
            else -> null
        }
    }.getOrNull()

    private fun CommandRejectedPayload.toAppError(): AppError =
        AppError(code.name, code.messageOrFallback(), retryable)

    private fun ErrorPayload.toAppError(): AppError =
        AppError(code.name, message.ifBlank { code.messageOrFallback() }, retryable)

    private fun com.listentogether.protocol.ErrorCode.messageOrFallback(): String = when (this) {
        com.listentogether.protocol.ErrorCode.NOT_MEMBER -> "You are not a member of this room"
        com.listentogether.protocol.ErrorCode.SESSION_MISMATCH -> "The session has ended"
        com.listentogether.protocol.ErrorCode.STALE_REVISION -> "State changed before the command arrived"
        com.listentogether.protocol.ErrorCode.INVALID_PAYLOAD -> "Invalid command"
        com.listentogether.protocol.ErrorCode.RATE_LIMITED -> "Too many requests"
        com.listentogether.protocol.ErrorCode.QUEUE_LIMIT -> "Queue is full"
        com.listentogether.protocol.ErrorCode.CHAT_LIMIT -> "Chat is full"
        com.listentogether.protocol.ErrorCode.UNSUPPORTED -> "Unsupported command"
        com.listentogether.protocol.ErrorCode.PROTOCOL_MISMATCH -> "Update required"
        com.listentogether.protocol.ErrorCode.SLOW_CONSUMER -> "Connection is too slow"
        com.listentogether.protocol.ErrorCode.DUPLICATE_SESSION -> "This room is already open on another device"
        com.listentogether.protocol.ErrorCode.INTERNAL -> "Server error"
    }
}

/** Chat sends are not part of [SyncCommands]; `:communication` uses this direct helper. */
fun SessionTransport.sendChat(
    sessionId: String,
    clientMessageId: String,
    text: String,
    commandId: String = clientMessageId,
): EmittedCommand = send(
    SessionCommand(id = commandId, payload = CommandPayload.ChatSend(clientMessageId, text)),
    sessionId = sessionId,
)
