package com.listentogether.backend.room

import com.listentogether.backend.domain.DomainResult
import com.listentogether.backend.domain.RoomAggregate
import com.listentogether.backend.domain.SessionState
import com.listentogether.core.common.Clock
import com.listentogether.core.common.logging.Logger
import com.listentogether.core.model.QueueState
import com.listentogether.core.model.SessionStatus
import com.listentogether.core.model.SessionView
import com.listentogether.protocol.ChatAckPayload
import com.listentogether.protocol.ChatMessagePayload
import com.listentogether.protocol.ChatSendPayload
import com.listentogether.protocol.CommandRejectedPayload
import com.listentogether.protocol.Envelope
import com.listentogether.protocol.ErrorCode
import com.listentogether.protocol.FrameKind
import com.listentogether.protocol.HelloPayload
import com.listentogether.protocol.LeavePayload
import com.listentogether.protocol.MessageType
import com.listentogether.protocol.PlaybackIssuePayload
import com.listentogether.protocol.PlaybackReportPayload
import com.listentogether.protocol.PlaybackStatePayload
import com.listentogether.protocol.PongPayload
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
import com.listentogether.protocol.TimeSyncPayload
import com.listentogether.protocol.TimeSyncResultPayload
import com.listentogether.protocol.TrackEndedPayload
import com.listentogether.protocol.TrackSelectPayload
import com.listentogether.protocol.VoiceIcePayload
import com.listentogether.protocol.VoiceSdpPayload
import com.listentogether.protocol.eventFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlin.math.min

interface RoomBus {
    fun send(participantId: String, frame: Envelope)
    fun broadcast(roomId: String, frame: Envelope)
}

class RoomActor(
    private val aggregate: RoomAggregate,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val bus: RoomBus,
    private val logger: Logger,
) {
    private sealed interface Command {
        data class Hello(val participantId: String) : Command
        data class Frame(val participantId: String, val frame: Envelope) : Command
        data class Disconnected(val participantId: String) : Command
        data object ExpireGrace : Command
        data object Shutdown : Command
    }

    private val inbox = Channel<Command>(Channel.UNLIMITED)
    private var graceJob: Job? = null
    private var stopped = false

    init {
        scope.launch {
            for (command in inbox) {
                try {
                    process(command)
                } catch (error: Throwable) {
                    logger.error("room.command.failed", error, mapOf("room_id" to aggregate.roomId))
                }
            }
        }
    }

    val roomId: String get() = aggregate.roomId

    fun hello(participantId: String) = inbox.trySend(Command.Hello(participantId))

    fun submit(participantId: String, frame: Envelope) = inbox.trySend(Command.Frame(participantId, frame))

    fun disconnected(participantId: String) = inbox.trySend(Command.Disconnected(participantId))

    fun shutdown() = inbox.trySend(Command.Shutdown)

    private suspend fun process(command: Command) {
        when (command) {
            is Command.Hello -> handleHello(command.participantId)
            is Command.Frame -> handleFrame(command.participantId, command.frame)
            is Command.Disconnected -> handleDisconnect(command.participantId)
            Command.ExpireGrace -> handleGraceExpiry()
            Command.Shutdown -> handleShutdown()
        }
    }

    private suspend fun handleHello(participantId: String) {
        if (stopped) return
        val change = aggregate.onPresence(participantId, clock.nowMs()).getOrNull() ?: return
        if (change.sessionStarted) {
            val session = aggregate.activeSession ?: return
            logger.event(
                "room.session.started",
                mapOf("room_id" to aggregate.roomId, "session_id" to session.sessionId),
            )
            broadcast(eventFrame(MessageType.ROOM_STATE, RoomStatePayload(aggregate.roomView()), aggregate.roomId))
            broadcast(presenceFrame())
            broadcast(
                eventFrame(
                    MessageType.SESSION_STARTED,
                    SessionStartedPayload(
                        session = sessionView(session),
                        playback = session.snapshot(),
                        queue = session.queueState(),
                    ),
                    aggregate.roomId,
                    session.sessionId,
                    session.revision,
                ),
            )
        } else {
            broadcast(eventFrame(MessageType.ROOM_STATE, RoomStatePayload(aggregate.roomView()), aggregate.roomId))
            broadcast(presenceFrame())
        }
        bus.send(participantId, snapshotFrame(participantId))
        scheduleGrace()
    }

    private fun handleDisconnect(participantId: String) {
        val change = aggregate.onDisconnect(participantId, clock.nowMs()).getOrNull() ?: return
        logger.event(
            "room.presence.disconnected",
            mapOf("room_id" to aggregate.roomId, "participant_id" to participantId),
        )
        broadcast(eventFrame(MessageType.ROOM_STATE, RoomStatePayload(change.room), aggregate.roomId))
        broadcast(presenceFrame())
        scheduleGrace()
    }

    private fun handleGraceExpiry() {
        val now = clock.nowMs()
        val change = aggregate.expireGrace(now).getOrNull() ?: return
        broadcast(eventFrame(MessageType.ROOM_STATE, RoomStatePayload(change.room), aggregate.roomId))
        broadcast(presenceFrame())
        change.closedSessionId?.let { closedId ->
            logger.event("room.session.closed", mapOf("room_id" to aggregate.roomId, "reason" to "GRACE_EXPIRED"))
            broadcast(
                eventFrame(
                    MessageType.SESSION_CLOSED,
                    SessionClosedPayload(closedId, "GRACE_EXPIRED", now),
                    aggregate.roomId,
                    closedId,
                ),
            )
        }
        scheduleGrace()
    }

    private fun handleShutdown() {
        if (stopped) return
        stopped = true
        val session = aggregate.activeSession
        if (session != null) {
            broadcast(
                eventFrame(
                    MessageType.SESSION_CLOSED,
                    SessionClosedPayload(session.sessionId, "SERVER_SHUTDOWN", clock.nowMs()),
                    aggregate.roomId,
                    session.sessionId,
                ),
            )
        }
        graceJob?.cancel()
        inbox.close()
    }

    private suspend fun handleFrame(participantId: String, frame: Envelope) {
        if (stopped) return
        if (frame.roomId.isNotEmpty() && frame.roomId != aggregate.roomId) {
            reject(participantId, frame, ErrorCode.NOT_MEMBER, "Wrong room")
            return
        }
        when (frame.type) {
            MessageType.PING -> {
                send(participantId, reply(frame, MessageType.PONG, PongPayload(frame.payload["echo"]?.toString())))
            }

            MessageType.TIME_SYNC -> {
                val payload = decode<TimeSyncPayload>(frame) ?: return rejectInvalid(participantId, frame)
                val t1 = clock.nowMs()
                val t2 = clock.nowMs()
                send(
                    participantId,
                    reply(
                        frame,
                        MessageType.TIME_SYNC_RESULT,
                        TimeSyncResultPayload(t0 = payload.t0, t1 = t1, t2 = t2),
                    ),
                )
            }

            MessageType.LEAVE -> {
                val reason = runCatching { ProtocolJson.payload<LeavePayload>(frame).reason }.getOrDefault("USER")
                handleLeave(participantId, reason)
            }

            MessageType.VOICE_OFFER, MessageType.VOICE_ANSWER, MessageType.VOICE_ICE -> {
                val session = aggregate.activeSession ?: return reject(
                    participantId, frame, ErrorCode.SESSION_MISMATCH, "No active session",
                )
                val target = aggregate.slots.firstOrNull { it.participantId != participantId } ?: return
                val payload = when (frame.type) {
                    MessageType.VOICE_ICE -> frame.payload
                    else -> frame.payload
                }
                val relay = Envelope(
                    kind = FrameKind.EVENT,
                    id = frame.id,
                    type = frame.type,
                    roomId = aggregate.roomId,
                    sessionId = session.sessionId,
                    senderId = participantId,
                    revision = session.revision,
                    payload = payload,
                )
                bus.send(target.participantId, relay)
            }

            MessageType.PLAYBACK_REPORT -> {
                val payload = decode<PlaybackReportPayload>(frame) ?: return rejectInvalid(participantId, frame)
                val session = requireSession(participantId, frame) ?: return
                val issue = session.reportPlaybackIssue(participantId, payload.trackId, payload.state, payload.detail)
                broadcast(eventFrame(MessageType.PLAYBACK_ISSUE, PlaybackIssuePayload(issue), aggregate.roomId, session.sessionId))
            }

            else -> handleMutatingCommand(participantId, frame)
        }
    }

    private fun handleLeave(participantId: String, reason: String) {
        val change = aggregate.onLeave(participantId, clock.nowMs()).getOrNull() ?: return
        logger.event(
            "room.participant.left",
            mapOf("room_id" to aggregate.roomId, "participant_id" to participantId, "reason" to reason),
        )
        broadcast(eventFrame(MessageType.ROOM_STATE, RoomStatePayload(change.room), aggregate.roomId))
        broadcast(presenceFrame())
        change.closedSessionId?.let { closedId ->
            logger.event("room.session.closed", mapOf("room_id" to aggregate.roomId, "reason" to "BOTH_LEFT"))
            broadcast(
                eventFrame(
                    MessageType.SESSION_CLOSED,
                    SessionClosedPayload(closedId, "BOTH_LEFT", clock.nowMs()),
                    aggregate.roomId,
                    closedId,
                ),
            )
        }
        scheduleGrace()
    }

    private suspend fun handleMutatingCommand(participantId: String, frame: Envelope) {
        val session = requireSession(participantId, frame) ?: return
        if (frame.sessionId != null && frame.sessionId != session.sessionId) {
            reject(participantId, frame, ErrorCode.SESSION_MISMATCH, "Session mismatch")
            return
        }
        val now = clock.nowMs()
        val result: DomainResult<*>? = when (frame.type) {
            MessageType.PLAY -> session.play(now)
            MessageType.PAUSE -> session.pause(now)
            MessageType.SEEK -> decode<SeekPayload>(frame)?.let { session.seek(it.positionMs, now) }
                ?: return rejectInvalid(participantId, frame)
            MessageType.TRACK_SELECT -> decode<TrackSelectPayload>(frame)?.let {
                session.selectTrack(it.track, now, participantId)
            } ?: return rejectInvalid(participantId, frame)
            MessageType.NEXT -> session.next(now)
            MessageType.PREV -> session.previous(now)
            MessageType.TRACK_ENDED -> {
                val payload = decode<TrackEndedPayload>(frame) ?: return rejectInvalid(participantId, frame)
                session.trackEnded(
                    itemId = payload.itemId,
                    observedRevision = frame.observedRevision ?: -1,
                    reportedPositionMs = payload.positionMs,
                    now = now,
                )
            }
            MessageType.QUEUE_ADD -> decode<QueueAddPayload>(frame)?.let {
                session.addToQueue(it.track, it.index, participantId, now)
            } ?: return rejectInvalid(participantId, frame)
            MessageType.QUEUE_REMOVE -> decode<QueueRemovePayload>(frame)?.let {
                session.removeFromQueue(it.itemId, now)
            } ?: return rejectInvalid(participantId, frame)
            MessageType.QUEUE_REORDER -> decode<QueueReorderPayload>(frame)?.let {
                session.reorderQueue(it.itemIds)
            } ?: return rejectInvalid(participantId, frame)
            MessageType.QUEUE_CLEAR -> session.clearQueue(now)
            MessageType.REPEAT_CHANGE -> decode<RepeatChangePayload>(frame)?.let {
                session.changeRepeat(it.mode)
            } ?: return rejectInvalid(participantId, frame)
            MessageType.SHUFFLE_CHANGE -> decode<ShuffleChangePayload>(frame)?.let {
                session.changeShuffle(it.enabled)
            } ?: return rejectInvalid(participantId, frame)
            MessageType.CHAT_SEND -> {
                val payload = decode<ChatSendPayload>(frame) ?: return rejectInvalid(participantId, frame)
                val outcome = session.chatSend(payload.clientMessageId, payload.text, participantId, now)
                when (outcome) {
                    is DomainResult.Rejected -> {
                        reject(participantId, frame, outcome.code, outcome.message)
                        return
                    }
                    is DomainResult.Accepted -> {
                        if (!outcome.value.duplicate) {
                            broadcast(
                                eventFrame(
                                    MessageType.CHAT_MESSAGE,
                                    ChatMessagePayload(outcome.value.message),
                                    aggregate.roomId,
                                    session.sessionId,
                                ),
                            )
                        }
                        send(
                            participantId,
                            reply(
                                frame,
                                MessageType.CHAT_ACK,
                                ChatAckPayload(payload.clientMessageId, outcome.value.message.sequence),
                            ),
                        )
                        return
                    }
                }
            }
            else -> {
                reject(participantId, frame, ErrorCode.UNSUPPORTED, "Unsupported command")
                return
            }
        }
        when (result) {
            null -> reject(participantId, frame, ErrorCode.INVALID_PAYLOAD, "Invalid payload")
            is DomainResult.Rejected -> reject(participantId, frame, result.code, result.message)
            is DomainResult.Accepted -> {
                broadcast(
                    eventFrame(
                        MessageType.PLAYBACK_STATE,
                        PlaybackStatePayload(session.snapshot()),
                        aggregate.roomId,
                        session.sessionId,
                        session.revision,
                    ),
                )
                broadcast(
                    eventFrame(
                        MessageType.QUEUE_STATE,
                        QueueStatePayload(session.queueState()),
                        aggregate.roomId,
                        session.sessionId,
                        session.revision,
                    ),
                )
            }
        }
    }

    private fun requireSession(participantId: String, frame: Envelope): SessionState? {
        if (aggregate.participant(participantId) == null) {
            reject(participantId, frame, ErrorCode.NOT_MEMBER, "Not a member")
            return null
        }
        val session = aggregate.activeSession
        if (session == null) {
            reject(participantId, frame, ErrorCode.SESSION_MISMATCH, "No active session")
            return null
        }
        return session
    }

    private fun snapshotFrame(participantId: String): Envelope {
        val slot = aggregate.participant(participantId)
        val session = aggregate.activeSession
        val payload = SnapshotPayload(
            room = aggregate.roomView(),
            session = session?.let { sessionView(it) },
            playback = session?.snapshot(),
            queue = session?.queueState(),
            chat = session?.chat?.toList() ?: emptyList(),
            yourRole = slot?.role ?: com.listentogether.core.model.Role.LISTENER,
            serverTime = clock.nowMs(),
            revision = session?.revision ?: 0,
        )
        return eventFrame(
            MessageType.SNAPSHOT,
            payload,
            aggregate.roomId,
            session?.sessionId,
            session?.revision,
        )
    }

    private fun sessionView(session: SessionState): SessionView = SessionView(
        sessionId = session.sessionId,
        roomId = session.roomId,
        status = SessionStatus.ACTIVE,
        createdAt = session.createdAt,
        participants = aggregate.slots.map { it.participantId },
    )

    private fun presenceFrame(): Envelope {
        val room = aggregate.roomView()
        val grace = aggregate.slots.mapNotNull { it.graceExpiresAt }.minOrNull()
        return eventFrame(
            MessageType.PRESENCE,
            PresencePayload(participants = room.participants, graceExpiresAt = grace),
            aggregate.roomId,
        )
    }

    private fun scheduleGrace() {
        graceJob?.cancel()
        val nextDeadline = aggregate.slots
            .filter { it.presence == com.listentogether.core.model.PresenceStatus.DISCONNECTED }
            .mapNotNull { it.graceExpiresAt }
            .minOrNull() ?: return
        val waitMs = (nextDeadline - clock.nowMs()).coerceAtLeast(0)
        graceJob = scope.launch {
            delay(waitMs)
            inbox.trySend(Command.ExpireGrace)
        }
    }

    private fun send(participantId: String, frame: Envelope) = bus.send(participantId, frame)

    private fun broadcast(frame: Envelope) = bus.broadcast(aggregate.roomId, frame)

    private fun reject(participantId: String, frame: Envelope, code: ErrorCode, message: String) {
        val payload = CommandRejectedPayload(
            commandId = frame.id,
            code = code,
            retryable = code in setOf(ErrorCode.RATE_LIMITED, ErrorCode.SLOW_CONSUMER, ErrorCode.SESSION_MISMATCH),
        )
        val envelope = Envelope(
            kind = FrameKind.ERROR,
            id = frame.id,
            type = MessageType.COMMAND_REJECTED,
            roomId = aggregate.roomId,
            sessionId = aggregate.activeSession?.sessionId,
            payload = ProtocolJson.encodePayload(payload),
        )
        send(participantId, envelope)
        logger.event(
            "room.command.rejected",
            mapOf("room_id" to aggregate.roomId, "participant_id" to participantId, "code" to code.name),
        )
    }

    private fun rejectInvalid(participantId: String, frame: Envelope) {
        reject(participantId, frame, ErrorCode.INVALID_PAYLOAD, "Invalid payload")
    }

    private inline fun <reified T> decode(frame: Envelope): T? = try {
        ProtocolJson.payload<T>(frame)
    } catch (error: SerializationException) {
        null
    } catch (error: IllegalArgumentException) {
        null
    }

    private inline fun <reified T> reply(
        command: Envelope,
        type: MessageType,
        payload: T,
    ): Envelope = Envelope(
        kind = FrameKind.EVENT,
        id = command.id,
        type = type,
        roomId = aggregate.roomId,
        sessionId = aggregate.activeSession?.sessionId,
        revision = aggregate.activeSession?.revision,
        payload = ProtocolJson.encodePayload(payload),
    )
}
