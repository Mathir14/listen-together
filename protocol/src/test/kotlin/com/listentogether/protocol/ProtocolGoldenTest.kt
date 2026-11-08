package com.listentogether.protocol

import com.listentogether.core.model.ChatMessage
import com.listentogether.core.model.ParticipantView
import com.listentogether.core.model.PlaybackSnapshot
import com.listentogether.core.model.PresenceStatus
import com.listentogether.core.model.Role
import com.listentogether.core.model.RoomStatus
import com.listentogether.core.model.RoomView
import com.listentogether.core.model.SessionStatus
import com.listentogether.core.model.SessionView
import com.listentogether.core.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtocolGoldenTest {
    private val helloJson = """
        {"v":1,"kind":"COMMAND","id":"frame-1","type":"HELLO","room_id":"room-1","payload":{"token":"tok","room_id":"room-1","protocol_version":1}}
    """.trimIndent()

    @Test
    fun `hello fixture decodes and round-trips`() {
        val decoded = ProtocolJson.decode(helloJson)
        assertEquals(FrameKind.COMMAND, decoded.kind)
        assertEquals(MessageType.HELLO, decoded.type)
        assertEquals("room-1", decoded.roomId)
        val hello = decoded.decodePayload<HelloPayload>()
        assertEquals("tok", hello.token)
        assertEquals(1, hello.protocolVersion)
        assertEquals(hello, ProtocolJson.payload<HelloPayload>(ProtocolJson.decode(ProtocolJson.encode(decoded))))
    }

    @Test
    fun `unknown additive fields are ignored`() {
        val decoded = ProtocolJson.decode(
            """{"v":1,"kind":"EVENT","id":"e","type":"PONG","room_id":"r","future_field":42,"payload":{"echo":"x"}}""",
        )
        assertEquals("x", decoded.decodePayload<PongPayload>().echo)
        assertEquals(42, decoded.payload["unused"]?.let { 0 } ?: 42)
    }

    @Test
    fun `snapshot payload round-trips fully`() {
        val message = ChatMessage("m1", "s1", "p1", "hello", 1_000, 3)
        val payload = SnapshotPayload(
            room = RoomView(
                roomId = "room-1",
                participants = listOf(
                    ParticipantView("p1", Role.SPEAKER, PresenceStatus.CONNECTED),
                    ParticipantView("p2", Role.LISTENER, PresenceStatus.CONNECTED),
                ),
                activeSessionId = "s1",
                status = RoomStatus.ACTIVE,
            ),
            session = SessionView("s1", "room-1", SessionStatus.ACTIVE, 900, listOf("p1", "p2")),
            playback = PlaybackSnapshot(
                sessionId = "s1",
                revision = 4,
                track = Track(providerTrackId = "vid", title = "Song", artist = "Artist", durationMs = 200_000),
                isPlaying = true,
                positionMs = 12_000,
                updatedAt = 1_000,
            ),
            chat = listOf(message),
            yourRole = Role.SPEAKER,
            serverTime = 2_000,
            revision = 4,
        )
        val frame = eventFrame(MessageType.SNAPSHOT, payload, "room-1", "s1", 4)
        val decoded = ProtocolJson.decode(ProtocolJson.encode(frame)).decodePayload<SnapshotPayload>()
        assertEquals(payload, decoded)
        assertEquals("hello", decoded.chat.single().content)
    }

    @Test
    fun `incompatible protocol version is detected`() {
        val frame = Envelope(version = 2, kind = FrameKind.COMMAND, id = "x", type = MessageType.PING)
        assertFalse(frame.isCompatible)
        assertTrue(frame.copy(version = 1).isCompatible)
    }

    @Test
    fun `command frame carries sequence and room`() {
        val frame = commandFrame(
            type = MessageType.PLAY,
            payload = PongPayload(),
            roomId = "room",
            sessionId = "session",
            clientSeq = 7,
            clientTs = 99,
        )
        assertEquals(FrameKind.COMMAND, frame.kind)
        assertEquals(7, frame.clientSeq)
        assertEquals("room", frame.roomId)
        assertEquals("session", frame.sessionId)
    }
}
