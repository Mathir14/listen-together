package com.listentogether.backend.domain

import com.listentogether.core.model.PresenceStatus
import com.listentogether.core.model.RepeatMode
import com.listentogether.core.model.Role
import com.listentogether.core.model.Track
import com.listentogether.protocol.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomDomainTest {
    private var idCounter = 0
    private var now = 1_000L

    private fun room(): RoomAggregate {
        var room: RoomAggregate? = null
        room = RoomAggregate(
            roomId = "room-1",
            createdAt = now,
            newId = { "id-${++idCounter}" },
            queueCap = 3,
            chatCap = 3,
            gracePeriodMs = 60_000,
        )
        room.addMember("alice", Role.SPEAKER)
        room.addMember("bob", Role.LISTENER)
        return room
    }

    private fun track(id: String = "vid-$idCounter", duration: Long = 180_000) =
        Track(providerTrackId = id, title = "Song $id", artist = "Artist", durationMs = duration)

    private fun joinedRoom(): RoomAggregate {
        val room = room()
        room.onPresence("alice", now)
        now += 10
        room.onPresence("bob", now)
        return room
    }

    @Test
    fun `session starts only when both participants are present`() {
        val room = room()
        val first = room.onPresence("alice", now).getOrNull()!!
        assertTrue(!first.sessionStarted)
        assertNull(room.activeSession)

        now += 1
        val second = room.onPresence("bob", now).getOrNull()!!
        assertTrue(second.sessionStarted)
        assertNotNull(room.activeSession)
        assertEquals("ACTIVE", room.roomView().status.name)
    }

    @Test
    fun `play pause seek project the authoritative position`() {
        val room = joinedRoom()
        val session = room.activeSession!!
        val track = track()
        val selectRev = session.selectTrack(track, now, "alice").getOrNull()!!
        assertEquals(1, selectRev)
        assertTrue(session.isPlaying)

        now += 5_000
        session.pause(now).getOrNull()
        assertEquals(5_000, session.positionMs)
        assertEquals(2, session.revision)

        now += 10_000
        session.play(now).getOrNull()
        assertEquals(3, session.revision)
        assertEquals(5_000, session.positionMs)

        session.seek(90_000, now).getOrNull()
        assertEquals(90_000, session.positionMs)
        assertEquals(4, session.revision)
    }

    @Test
    fun `queue add reorder and invalid permutation`() {
        val room = joinedRoom()
        val session = room.activeSession!!
        val first = session.addToQueue(track("a"), null, "alice", now).getOrNull()!!
        val second = session.addToQueue(track("b"), null, "alice", now).getOrNull()!!
        val third = session.addToQueue(track("c"), null, "bob", now).getOrNull()!!

        assertEquals(listOf(first, second, third), session.queue.toList())
        val revisionBefore = session.revision
        val reordered = session.reorderQueue(listOf(third.itemId, first.itemId, second.itemId)).getOrNull()
        assertEquals(revisionBefore + 1, reordered)
        assertEquals(listOf(third.itemId, first.itemId, second.itemId), session.queue.map { it.itemId })

        val rejected = session.reorderQueue(listOf(first.itemId, first.itemId, second.itemId))
        assertTrue(rejected is DomainResult.Rejected)
        assertEquals(revisionBefore + 1, session.revision)
    }

    @Test
    fun `queue limit is enforced`() {
        val room = joinedRoom()
        val session = room.activeSession!!
        repeat(3) { session.addToQueue(track("t$it"), null, "alice", now) }
        val overflow = session.addToQueue(track("overflow"), null, "alice", now)
        assertTrue(overflow is DomainResult.Rejected)
        assertEquals(ErrorCode.QUEUE_LIMIT, (overflow as DomainResult.Rejected).code)
    }

    @Test
    fun `removing current track advances once and bumps one revision`() {
        val room = joinedRoom()
        val session = room.activeSession!!
        session.addToQueue(track("a"), null, "alice", now)
        session.addToQueue(track("b"), null, "alice", now)
        session.selectTrack(track("c"), now, "alice")
        val currentId = session.currentItemId!!
        val revisionBefore = session.revision
        session.removeFromQueue(currentId, now).getOrNull()
        assertEquals(revisionBefore + 1, session.revision)
        assertEquals("b", session.track?.providerTrackId)
        assertTrue(session.isPlaying)
    }

    @Test
    fun `duplicate track ended is rejected without state change`() {
        val room = joinedRoom()
        val session = room.activeSession!!
        session.selectTrack(track("a", duration = 10_000), now, "alice")
        session.addToQueue(track("b", duration = 10_000), null, "alice", now)
        val currentId = session.currentItemId!!
        now += 10_000
        val revision = session.revision
        val accepted = session.trackEnded(currentId, revision, 10_000, now)
        assertTrue(accepted is DomainResult.Accepted)
        assertEquals("b", session.track?.providerTrackId)
        val after = session.revision
        val stale = session.trackEnded(currentId, revision, 10_000, now)
        assertTrue(stale is DomainResult.Rejected)
        assertEquals(after, session.revision)
        assertEquals("b", session.track?.providerTrackId)
    }

    @Test
    fun `repeat one restarts current track on natural end`() {
        val room = joinedRoom()
        val session = room.activeSession!!
        session.selectTrack(track("a", duration = 1_000), now, "alice")
        session.changeRepeat(RepeatMode.ONE)
        val currentId = session.currentItemId!!
        now += 1_000
        val rev = session.revision
        session.trackEnded(currentId, rev, 1_000, now).getOrNull()
        assertEquals(currentId, session.currentItemId)
        assertEquals(0, session.positionMs)
        assertTrue(session.isPlaying)
    }

    @Test
    fun `one participant leaving keeps the session and rejoin resumes it`() {
        val room = joinedRoom()
        val sessionId = room.activeSession!!.sessionId
        room.onLeave("bob", now).getOrNull()
        assertEquals(sessionId, room.activeSession?.sessionId)
        assertEquals("ACTIVE", room.roomView().status.name)

        room.onPresence("bob", now + 1_000).getOrNull()
        assertEquals(sessionId, room.activeSession?.sessionId)
    }

    @Test
    fun `host or creator leaving keeps the session active for listener and rejoin resumes it`() {
        val room = joinedRoom()
        val sessionId = room.activeSession!!.sessionId
        // Alice is the creator (Role.SPEAKER)
        val change = room.onLeave("alice", now).getOrNull()!!
        assertNull(change.closedSessionId, "Session must not close when host leaves while listener remains")
        assertEquals(sessionId, room.activeSession?.sessionId)
        assertEquals("ACTIVE", room.roomView().status.name)
        assertEquals(PresenceStatus.LEFT, room.participant("alice")?.presence)
        assertEquals(PresenceStatus.CONNECTED, room.participant("bob")?.presence)

        // Host rejoins
        val rejoin = room.onPresence("alice", now + 2_000).getOrNull()!!
        assertEquals(sessionId, room.activeSession?.sessionId, "Existing session continues on host rejoin")
        assertEquals(PresenceStatus.CONNECTED, room.participant("alice")?.presence)
    }

    @Test
    fun `both leaving closes session and rejoin creates a new one`() {
        val room = joinedRoom()
        val firstSession = room.activeSession!!.sessionId
        room.onLeave("alice", now).getOrNull()
        val closed = room.onLeave("bob", now).getOrNull()!!
        assertEquals(firstSession, closed.closedSessionId)
        assertNull(room.activeSession)

        room.onPresence("alice", now)
        room.onPresence("bob", now)
        val secondSession = room.activeSession!!.sessionId
        assertNotEquals(firstSession, secondSession)
        assertTrue(room.activeSession!!.chat.isEmpty())
        assertTrue(room.activeSession!!.queue.isEmpty())
    }

    @Test
    fun `disconnect grace keeps session and expiry closes after last participant`() {
        val room = joinedRoom()
        val sessionId = room.activeSession!!.sessionId
        room.onDisconnect("bob", now)
        assertEquals(sessionId, room.activeSession?.sessionId)
        assertEquals(PresenceStatus.DISCONNECTED, room.participant("bob")?.presence)

        val expired = room.expireGrace(now + 60_001).getOrNull()!!
        assertEquals(PresenceStatus.LEFT, room.participant("bob")?.presence)
        assertEquals(sessionId, room.activeSession?.sessionId, "one participant remains")

        room.onDisconnect("alice", now + 60_001)
        val closed = room.expireGrace(now + 120_002).getOrNull()!!
        assertEquals(sessionId, closed.closedSessionId)
        assertNull(room.activeSession)
    }

    @Test
    fun `chat is ordered, deduped and bounded`() {
        val room = joinedRoom()
        val session = room.activeSession!!
        val first = session.chatSend("c1", "hello", "bob", now).getOrNull()!!
        assertEquals(1, first.message.sequence)
        val duplicate = session.chatSend("c1", "hello", "bob", now + 1).getOrNull()!!
        assertTrue(duplicate.duplicate)
        assertEquals(first.message, duplicate.message)
        assertEquals(1, session.chat.size)

        session.chatSend("c2", "two", "bob", now)
        session.chatSend("c3", "three", "bob", now)
        session.chatSend("c4", "four", "bob", now)
        assertEquals(3, session.chat.size)
        assertEquals(listOf(2L, 3L, 4L), session.chat.map { it.sequence })
    }

    @Test
    fun `empty chat and invalid text rejected`() {
        val room = joinedRoom()
        val session = room.activeSession!!
        assertTrue(session.chatSend("c1", "   ", "bob", now) is DomainResult.Rejected)
        assertTrue(session.chatSend("c2", "x".repeat(2_001), "bob", now) is DomainResult.Rejected)
        assertEquals(0, session.chat.size)
    }

    @Test
    fun `shuffle toggling is deterministic and restores order`() {
        val room = joinedRoom()
        val session = room.activeSession!!
        repeat(5) { session.addToQueue(track("t$it"), null, "alice", now) }
        val before = session.queue.map { it.itemId }
        session.changeShuffle(true).getOrNull()
        val shuffled = session.queue.map { it.itemId }
        assertEquals(before.toSet(), shuffled.toSet())
        session.changeShuffle(false).getOrNull()
        assertEquals(before, session.queue.map { it.itemId })
    }
}
