package com.listentogether.backend.room

import com.listentogether.backend.domain.RoomAggregate
import com.listentogether.core.common.Clock
import com.listentogether.core.common.logging.NoopLogger
import com.listentogether.core.model.PresenceStatus
import com.listentogether.core.model.Role
import com.listentogether.protocol.Envelope
import com.listentogether.protocol.FrameKind
import com.listentogether.protocol.MessageType
import com.listentogether.protocol.PlaybackStatePayload
import com.listentogether.protocol.ProtocolJson
import com.listentogether.protocol.SessionClosedPayload
import com.listentogether.protocol.SessionStartedPayload
import com.listentogether.protocol.SnapshotPayload
import com.listentogether.protocol.TrackSelectPayload
import com.listentogether.protocol.commandFrame
import com.listentogether.protocol.decodePayload
import com.listentogether.core.model.Track
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoomActorTest {
    private class TestClock(private val scheduler: TestCoroutineScheduler) : Clock {
        override fun nowMs(): Long = 1_000_000L + scheduler.currentTime
        override fun monotonicMs(): Long = scheduler.currentTime
    }

    private class RecordingBus : RoomBus {
        val direct = mutableListOf<Pair<String, Envelope>>()
        val broadcasts = mutableListOf<Envelope>()

        override fun send(participantId: String, frame: Envelope) {
            direct += participantId to frame
        }

        override fun broadcast(roomId: String, frame: Envelope) {
            broadcasts += frame
        }

        fun lastDirect(participantId: String, type: MessageType): Envelope? =
            direct.lastOrNull { it.first == participantId && it.second.type == type }?.second

        fun lastBroadcast(type: MessageType): Envelope? =
            broadcasts.lastOrNull { it.type == type }
    }

    private fun TestScope.actor(
        bus: RecordingBus,
        room: RoomAggregate,
    ): RoomActor = RoomActor(room, TestClock(testScheduler), backgroundScope, bus, NoopLogger)

    @Test
    fun `hello starts a session and sends a snapshot to the joining participant`() = runTest {
        val bus = RecordingBus()
        val room = RoomAggregate(
            roomId = "r1",
            createdAt = 1,
            newId = { "session-${System.nanoTime()}" },
            gracePeriodMs = 60_000,
        )
        room.addMember("alice", Role.SPEAKER)
        room.addMember("bob", Role.LISTENER)
        val actor = actor(bus, room)

        actor.hello("alice")
        runCurrent()
        assertNotNull(bus.lastDirect("alice", MessageType.SNAPSHOT))
        assertTrue(bus.lastBroadcast(MessageType.SESSION_STARTED) == null)

        actor.hello("bob")
        runCurrent()
        val sessionStarted = bus.lastBroadcast(MessageType.SESSION_STARTED)
        assertNotNull(sessionStarted)
        val payload = sessionStarted.decodePayload<SessionStartedPayload>()
        assertEquals(2, payload.session.participants.size)
        val snapshot = bus.lastDirect("bob", MessageType.SNAPSHOT)!!.decodePayload<SnapshotPayload>()
        assertEquals("ACTIVE", snapshot.room.status.name)
        assertEquals(Role.LISTENER, snapshot.yourRole)
    }

    @Test
    fun `accepted commands broadcast playback state and stale session is rejected`() = runTest {
        val bus = RecordingBus()
        val room = RoomAggregate("r1", 1, newId = { "session-1" }, gracePeriodMs = 60_000)
        room.addMember("alice", Role.SPEAKER)
        room.addMember("bob", Role.LISTENER)
        val actor = actor(bus, room)
        actor.hello("alice"); actor.hello("bob")
        runCurrent()
        val session = room.activeSession!!

        val frame: Envelope = commandFrame(
            type = MessageType.TRACK_SELECT,
            payload = TrackSelectPayload(Track(providerTrackId = "v1", title = "Song", artist = "Artist", durationMs = 10_000)),
            roomId = "r1",
            sessionId = session.sessionId,
            senderId = "alice",
            clientSeq = 1,
        )
        actor.submit("alice", frame)
        runCurrent()
        val state = bus.lastBroadcast(MessageType.PLAYBACK_STATE)!!.decodePayload<PlaybackStatePayload>()
        assertEquals("v1", state.playback.track?.providerTrackId)
        assertTrue(state.playback.isPlaying)

        val stale: Envelope = frame.copy(id = "stale", sessionId = "old-session", clientSeq = 2)
        actor.submit("alice", stale)
        runCurrent()
        val rejected = bus.direct.last { it.first == "alice" }.second
        assertEquals(MessageType.COMMAND_REJECTED, rejected.type)
    }

    @Test
    fun `explicit leaves close the session and broadcast session closed`() = runTest {
        val bus = RecordingBus()
        val room = RoomAggregate("r1", 1, newId = { "session-1" }, gracePeriodMs = 60_000)
        room.addMember("alice", Role.SPEAKER)
        room.addMember("bob", Role.LISTENER)
        val actor = actor(bus, room)
        actor.hello("alice"); actor.hello("bob")
        runCurrent()

        actor.submit("alice", leaveFrame())
        runCurrent()
        assertEquals(MessageType.PRESENCE, bus.lastBroadcast(MessageType.PRESENCE)?.type)
        assertTrue(bus.broadcasts.none { it.type == MessageType.SESSION_CLOSED })

        actor.submit("bob", leaveFrame())
        runCurrent()
        val closed = bus.lastBroadcast(MessageType.SESSION_CLOSED)
        assertNotNull(closed)
        assertEquals("BOTH_LEFT", closed.decodePayload<SessionClosedPayload>().reason)
        assertEquals(null, room.activeSession)
    }

    @Test
    fun `grace timer closes session after both participants stop being present`() = runTest {
        val bus = RecordingBus()
        val room = RoomAggregate("r1", 1, newId = { "session-grace" }, gracePeriodMs = 5_000)
        room.addMember("alice", Role.SPEAKER)
        room.addMember("bob", Role.LISTENER)
        val actor = actor(bus, room)
        actor.hello("alice"); actor.hello("bob")
        runCurrent()

        actor.disconnected("bob")
        runCurrent()
        assertEquals(PresenceStatus.DISCONNECTED, room.participant("bob")?.presence)
        assertEquals("session-grace", room.activeSession?.sessionId)

        actor.disconnected("alice")
        runCurrent()
        advanceTimeBy(5_001)
        runCurrent()

        val closed = bus.lastBroadcast(MessageType.SESSION_CLOSED)
        assertNotNull(closed)
        assertEquals("GRACE_EXPIRED", closed.decodePayload<SessionClosedPayload>().reason)
    }

    private fun leaveFrame(): Envelope = Envelope(
        kind = FrameKind.COMMAND,
        id = "leave-${System.nanoTime()}",
        type = MessageType.LEAVE,
        roomId = "r1",
        payload = ProtocolJson.encodePayload(com.listentogether.protocol.LeavePayload("USER")),
    )
}
