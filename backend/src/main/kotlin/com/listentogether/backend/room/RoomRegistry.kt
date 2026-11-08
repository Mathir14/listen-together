package com.listentogether.backend.room

import com.listentogether.backend.auth.Secrets
import com.listentogether.backend.auth.TokenBinding
import com.listentogether.backend.config.BackendConfig
import com.listentogether.backend.domain.DomainResult
import com.listentogether.backend.domain.RoomAggregate
import com.listentogether.core.common.Clock
import com.listentogether.core.common.logging.Logger
import com.listentogether.core.model.Role
import com.listentogether.core.model.RoomCredentials
import com.listentogether.core.model.RoomView
import com.listentogether.core.model.SessionView
import com.listentogether.protocol.Envelope
import com.listentogether.protocol.FrameKind
import com.listentogether.protocol.LeavePayload
import com.listentogether.protocol.MessageType
import com.listentogether.protocol.ProtocolJson
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope

interface ConnectionSink {
    val roomId: String
    val participantId: String
    fun send(frame: Envelope)
    fun close(reason: String)
}

class RoomRegistry(
    private val config: BackendConfig,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val logger: Logger,
    private val secrets: Secrets = Secrets(),
) : RoomBus {
    private class Seat(
        val participantId: String,
        val role: Role,
        val tokenHash: String,
    )

    private class RoomEntry(
        val aggregate: RoomAggregate,
        val inviteSecretHash: String,
        var inviteUsed: Boolean = false,
    ) {
        val seats = ConcurrentHashMap<Role, Seat>()
        val connections = ConcurrentHashMap<String, ConnectionSink>()
        @Volatile
        var actor: RoomActor? = null
    }

    private val rooms = ConcurrentHashMap<String, RoomEntry>()
    private val tokenBindings = ConcurrentHashMap<String, TokenBinding>()
    private val activeRoomGauge = AtomicInteger(0)

    private fun createEntry(roomId: String, inviteSecretHash: String, aggregate: RoomAggregate): RoomEntry {
        val entry = RoomEntry(aggregate, inviteSecretHash)
        rooms[roomId] = entry
        return entry
    }

    fun createRoom(creatorRole: Role): RoomCredentials {
        val roomId = secrets.randomToken(16)
        val participantId = "user-${secrets.randomToken(8)}"
        val inviteSecret = secrets.randomToken(32)
        val aggregate = RoomAggregate(
            roomId = roomId,
            createdAt = clock.nowMs(),
            newId = { secrets.randomToken(16) },
            queueCap = config.queueCap,
            chatCap = config.chatCap,
            gracePeriodMs = config.gracePeriodMs,
        )
        val member = aggregate.addMember(participantId, creatorRole)
        check(member is DomainResult.Accepted) { "fresh room must accept its first member" }
        val entry = createEntry(roomId, secrets.sha256(inviteSecret), aggregate)
        val token = secrets.randomToken(32)
        val tokenHash = secrets.sha256(token)
        entry.seats[creatorRole] = Seat(participantId, creatorRole, tokenHash)
        tokenBindings[tokenHash] = TokenBinding(roomId, participantId, creatorRole)
        activeRoomGauge.incrementAndGet()
        logger.event(
            "registry.room.created",
            mapOf("room_id" to roomId, "participant_id" to participantId, "role" to creatorRole.name),
        )
        return RoomCredentials(
            roomId = roomId,
            participantId = participantId,
            participantToken = token,
            role = creatorRole,
            inviteSecret = inviteSecret,
        )
    }

    sealed interface JoinOutcome {
        data class Joined(val credentials: RoomCredentials) : JoinOutcome
        data class Rejected(val code: String, val message: String) : JoinOutcome
    }

    fun joinRoom(roomId: String, inviteSecret: String): JoinOutcome {
        val entry = rooms[roomId] ?: return JoinOutcome.Rejected("NOT_MEMBER", "Unknown room")
        if (!secrets.secureEquals(entry.inviteSecretHash, secrets.sha256(inviteSecret))) {
            return JoinOutcome.Rejected("NOT_MEMBER", "Invalid invitation")
        }
        synchronized(entry) {
            if (entry.inviteUsed) return JoinOutcome.Rejected("NOT_MEMBER", "Invitation already used")
            if (entry.seats.size >= 2) return JoinOutcome.Rejected("UNSUPPORTED", "Room is full")
            val creatorRole = entry.seats.keys.firstOrNull() ?: return JoinOutcome.Rejected("INTERNAL", "Corrupt room")
            val role = creatorRole.other
            val participantId = "user-${secrets.randomToken(8)}"
            val member = entry.aggregate.addMember(participantId, role)
            if (member is DomainResult.Rejected) {
                return JoinOutcome.Rejected(member.code.name, member.message)
            }
            val token = secrets.randomToken(32)
            val tokenHash = secrets.sha256(token)
            entry.seats[role] = Seat(participantId, role, tokenHash)
            entry.inviteUsed = true
            tokenBindings[tokenHash] = TokenBinding(roomId, participantId, role)
            logger.event(
                "registry.room.joined",
                mapOf("room_id" to roomId, "participant_id" to participantId, "role" to role.name),
            )
            return JoinOutcome.Joined(
                RoomCredentials(
                    roomId = roomId,
                    participantId = participantId,
                    participantToken = token,
                    role = role,
                ),
            )
        }
    }

    fun authenticate(roomId: String, token: String): TokenBinding? {
        val binding = tokenBindings[secrets.sha256(token)] ?: return null
        return binding.takeIf { it.roomId == roomId }
    }

    fun roomView(roomId: String): RoomView? = rooms[roomId]?.aggregate?.roomView()

    fun sessionView(roomId: String): SessionView? {
        val entry = rooms[roomId] ?: return null
        val session = entry.aggregate.activeSession ?: return null
        return SessionView(
            sessionId = session.sessionId,
            roomId = roomId,
            createdAt = session.createdAt,
            participants = entry.aggregate.slots.map { it.participantId },
        )
    }

    fun roleOf(roomId: String, participantId: String): Role? =
        rooms[roomId]?.aggregate?.participant(participantId)?.role

    fun participantIds(roomId: String): List<String> =
        rooms[roomId]?.aggregate?.slots?.map { it.participantId } ?: emptyList()

    fun connect(binding: TokenBinding, sink: ConnectionSink) {
        val entry = rooms[binding.roomId] ?: run {
            sink.send(errorFrame(binding.roomId, "NOT_MEMBER", "Unknown room"))
            sink.close("not_member")
            return
        }
        val previous = entry.connections.put(binding.participantId, sink)
        if (previous != null && previous !== sink) {
            logger.event(
                "registry.connection.superseded",
                mapOf("room_id" to binding.roomId, "participant_id" to binding.participantId),
            )
            previous.close("superseded")
        }
        val actor = entry.actor ?: RoomActor(
            aggregate = entry.aggregate,
            clock = clock,
            scope = scope,
            bus = this,
            logger = logger,
        ).also { entry.actor = it }
        actor.hello(binding.participantId)
        logger.event(
            "registry.connection.opened",
            mapOf("room_id" to binding.roomId, "participant_id" to binding.participantId),
        )
    }

    fun disconnect(sink: ConnectionSink) {
        val entry = rooms[sink.roomId] ?: return
        val removed = entry.connections.remove(sink.participantId, sink)
        if (removed) {
            entry.actor?.disconnected(sink.participantId)
            logger.event(
                "registry.connection.closed",
                mapOf("room_id" to sink.roomId, "participant_id" to sink.participantId),
            )
        }
    }

    fun leave(roomId: String, participantId: String) {
        val entry = rooms[roomId] ?: return
        val frame = Envelope(
            kind = FrameKind.COMMAND,
            id = secrets.randomToken(8),
            type = MessageType.LEAVE,
            roomId = roomId,
            payload = ProtocolJson.encodePayload(LeavePayload("USER")),
        )
        entry.actor?.submit(participantId, frame)
    }

    fun shutdown() {
        logger.event("registry.shutdown", mapOf("rooms" to rooms.size))
        rooms.values.forEach { entry ->
            entry.actor?.shutdown()
            entry.connections.values.forEach { it.close("server_shutdown") }
        }
    }

    override fun send(participantId: String, frame: Envelope) {
        rooms[frame.roomId]?.connections?.get(participantId)?.send(frame)
    }

    override fun broadcast(roomId: String, frame: Envelope) {
        rooms[roomId]?.connections?.values?.forEach { it.send(frame) }
    }

    fun activeRoomCount(): Int = activeRoomGauge.get()

    fun activeSessionCount(): Int = rooms.values.count { it.aggregate.activeSession != null }

    fun actorFor(roomId: String): RoomActor? = rooms[roomId]?.actor


    private fun errorFrame(roomId: String, code: String, message: String): Envelope = Envelope(
        kind = FrameKind.ERROR,
        id = secrets.randomToken(8),
        type = MessageType.ERROR,
        roomId = roomId,
        payload = ProtocolJson.encodePayload(
            com.listentogether.protocol.ErrorPayload(
                code = com.listentogether.protocol.ErrorCode.valueOf(code),
                message = message,
            ),
        ),
    )
}
