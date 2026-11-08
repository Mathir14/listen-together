package com.listentogether.sync

import com.listentogether.core.common.AppError
import com.listentogether.core.common.Clock
import com.listentogether.core.model.ChatMessage
import com.listentogether.core.model.ConnectionState
import com.listentogether.core.model.ParticipantView
import com.listentogether.core.model.PlaybackIssue
import com.listentogether.core.model.PlaybackSnapshot
import com.listentogether.core.model.PresenceStatus
import com.listentogether.core.model.QueueItem
import com.listentogether.core.model.QueueState
import com.listentogether.core.model.RepeatMode
import com.listentogether.core.model.Role
import com.listentogether.core.model.RoomStatus
import com.listentogether.core.model.RoomView
import com.listentogether.core.model.SessionStatus
import com.listentogether.core.model.SessionView
import com.listentogether.core.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionEngineTest {
    private val track = Track(
        providerTrackId = "vid-1",
        title = "Song One",
        artist = "Artist",
        durationMs = 180_000,
    )
    private val session = SessionView(
        sessionId = "session-1",
        roomId = "room-1",
        status = SessionStatus.ACTIVE,
        createdAt = 1_000,
        participants = listOf("user-1", "user-2"),
    )
    private val room = RoomView(
        roomId = "room-1",
        participants = listOf(
            ParticipantView("user-1", Role.SPEAKER, PresenceStatus.CONNECTED),
            ParticipantView("user-2", Role.LISTENER, PresenceStatus.CONNECTED),
        ),
        activeSessionId = "session-1",
        status = RoomStatus.ACTIVE,
    )

    private fun playback(
        revision: Long,
        isPlaying: Boolean = true,
        positionMs: Long = 10_000,
        updatedAt: Long = 1_000_000,
    ) = PlaybackSnapshot(
        sessionId = "session-1",
        revision = revision,
        currentItemId = "item-1",
        track = track,
        isPlaying = isPlaying,
        positionMs = positionMs,
        updatedAt = updatedAt,
    )

    private fun queue(revision: Long) = QueueState(
        items = listOf(QueueItem("item-1", track, "user-1")),
        currentItemId = "item-1",
        queueRevision = revision,
    )

    private fun snapshotEvent(revision: Long, chat: List<ChatMessage> = emptyList()): SessionEvent.Snapshot =
        SessionEvent.Snapshot(
            room = room,
            session = session,
            playback = playback(revision),
            queue = queue(revision),
            chat = chat,
            role = Role.SPEAKER,
            serverTime = 1_000_000,
            revision = revision,
        )

    private class FakeClock(var now: Long = 1_000_000) : Clock {
        override fun nowMs(): Long = now
        override fun monotonicMs(): Long = now
    }

    private class Sent(
        val command: SessionCommand,
        val sessionId: String?,
        val observedRevision: Long?,
    )

    private class FakeTransport(private val clock: Clock) : SessionTransport {
        private val mutableEvents = MutableSharedFlow<SessionEvent>(replay = 32, extraBufferCapacity = 128)
        override val events: SharedFlow<SessionEvent> = mutableEvents.asSharedFlow()
        private val mutableConnection = MutableStateFlow(ConnectionState.CONNECTED)
        override val connectionState: StateFlow<ConnectionState> = mutableConnection.asStateFlow()
        val sent = mutableListOf<Sent>()
        var accept = true

        override fun connect(roomId: String, token: String, lastSessionId: String?, lastRevision: Long?) = Unit

        override fun send(command: SessionCommand, sessionId: String?, observedRevision: Long?): EmittedCommand {
            sent += Sent(command, sessionId, observedRevision)
            return EmittedCommand(command.id, accept)
        }

        override fun disconnect() = Unit
        override fun serverNowMs(): Long = clock.nowMs()

        fun emit(event: SessionEvent) {
            check(mutableEvents.tryEmit(event))
        }

        fun connection(state: ConnectionState) {
            mutableConnection.value = state
        }
    }

    private class FakeProbe : LocalPlaybackProbe {
        val mutable = MutableStateFlow(LocalPlaybackObservation.Idle)
        override val observation: StateFlow<LocalPlaybackObservation> = mutable.asStateFlow()
    }

    private class AppliedPlayback(
        val playback: PlaybackSnapshot?,
        val origin: Origin,
    )

    private class FakeSink : RemoteStateSink {
        val playbackApplications = mutableListOf<AppliedPlayback>()
        val queueApplications = mutableListOf<QueueState?>()
        var purges = 0

        override suspend fun applyPlayback(playback: PlaybackSnapshot?, serverNowMs: Long, origin: Origin) {
            playbackApplications += AppliedPlayback(playback, origin)
        }

        override suspend fun applyQueue(queue: QueueState?) {
            queueApplications += queue
        }

        override suspend fun purge() {
            purges++
        }
    }

    private fun engine(
        transport: FakeTransport,
        probe: FakeProbe,
        sink: FakeSink,
        scope: kotlinx.coroutines.CoroutineScope,
    ): SessionEngine = SessionEngine(transport, probe, sink, scope).also { it.start() }

    private fun TestScope.engineScope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())

    @Test
    fun `snapshot populates state and applies remote state`() = runTest {
        val transport = FakeTransport(FakeClock())
        val probe = FakeProbe()
        val sink = FakeSink()
        val engine = engine(transport, probe, sink, engineScope())

        transport.emit(snapshotEvent(7))
        advanceUntilIdle()

        with(engine.state.value) {
            assertEquals("room-1", room?.roomId)
            assertEquals("session-1", session?.sessionId)
            assertEquals(7, playback?.revision)
            assertEquals("item-1", queue?.currentItemId)
            assertEquals(Role.SPEAKER, role)
            assertEquals(7, revision)
            assertTrue(hasSnapshot)
        }
        assertEquals(Origin.REMOTE, sink.playbackApplications.last().origin)
        assertEquals(7, sink.queueApplications.last()?.queueRevision)
        assertTrue(transport.sent.isEmpty(), "unexpected commands: ${transport.sent.map { it.command.payload }}")
    }

    @Test
    fun `local play applies optimistically and emits exactly one command`() = runTest {
        val transport = FakeTransport(FakeClock())
        val probe = FakeProbe()
        val sink = FakeSink()
        val engine = engine(transport, probe, sink, engineScope())
        transport.emit(snapshotEvent(3, chat = listOf(chat(1))).let {
            it.copy(playback = playback(3, isPlaying = false))
        })
        advanceUntilIdle()

        engine.play()
        advanceUntilIdle()

        assertEquals(1, transport.sent.size, "commands: ${transport.sent.map { it.command.payload }}")
        assertEquals(CommandPayload.Play, transport.sent.single().command.payload)
        assertEquals("session-1", transport.sent.single().sessionId)
        assertTrue(engine.state.value.isOptimistic)
        assertTrue(engine.state.value.playback?.isPlaying == true)
        assertEquals(Origin.LOCAL_USER, sink.playbackApplications.last().origin)
        assertEquals(1, engine.state.value.pendingCommandIds.size)

        transport.emit(SessionEvent.PlaybackChanged(playback(4, isPlaying = true)))
        advanceUntilIdle()

        assertFalse(engine.state.value.isOptimistic)
        assertTrue(engine.state.value.pendingCommandIds.isEmpty())
        assertEquals(4, engine.state.value.playback?.revision)
        assertEquals(Origin.REMOTE, sink.playbackApplications.last().origin)
    }

    @Test
    fun `remote playback application emits no command`() = runTest {
        val transport = FakeTransport(FakeClock())
        val engine = engine(transport, FakeProbe(), FakeSink(), engineScope())
        transport.emit(snapshotEvent(1))
        advanceUntilIdle()
        transport.sent.clear()

        transport.emit(SessionEvent.PlaybackChanged(playback(2, isPlaying = false)))
        advanceUntilIdle()

        assertTrue(transport.sent.isEmpty(), "unexpected commands: ${transport.sent.map { it.command.payload }}")
        assertFalse(engine.state.value.playback?.isPlaying ?: true)
    }

    @Test
    fun `stale frames are ignored`() = runTest {
        val transport = FakeTransport(FakeClock())
        val engine = engine(transport, FakeProbe(), FakeSink(), engineScope())
        transport.emit(snapshotEvent(9))
        advanceUntilIdle()
        val queueBefore = engine.state.value.queue

        transport.emit(SessionEvent.PlaybackChanged(playback(8, positionMs = 1)))
        transport.emit(SessionEvent.QueueChanged(queue(8)))
        advanceUntilIdle()

        assertEquals(9, engine.state.value.playback?.revision)
        assertEquals(10_000, engine.state.value.playback?.positionMs)
        assertEquals(queueBefore, engine.state.value.queue)
    }

    @Test
    fun `playback and queue sharing a revision are both applied`() = runTest {
        val transport = FakeTransport(FakeClock())
        val engine = engine(transport, FakeProbe(), FakeSink(), engineScope())
        transport.emit(snapshotEvent(1))
        advanceUntilIdle()

        val newTrack = track.copy(providerTrackId = "vid-2", title = "Song Two")
        transport.emit(SessionEvent.PlaybackChanged(playback(2).copy(track = newTrack)))
        transport.emit(
            SessionEvent.QueueChanged(
                QueueState(
                    items = listOf(QueueItem("item-2", newTrack, "user-2")),
                    currentItemId = "item-2",
                    queueRevision = 2,
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals("Song Two", engine.state.value.playback?.track?.title)
        assertEquals("item-2", engine.state.value.queue?.currentItemId)
        assertEquals(2, engine.state.value.queue?.queueRevision)
    }

    @Test
    fun `rejection reverts optimistic playback to authoritative state`() = runTest {
        val transport = FakeTransport(FakeClock())
        val sink = FakeSink()
        val engine = engine(transport, FakeProbe(), sink, engineScope())
        transport.emit(snapshotEvent(3, chat = listOf(chat(1))))
        advanceUntilIdle()

        engine.seek(120_000)
        advanceUntilIdle()
        assertEquals(120_000, engine.state.value.playback?.positionMs)
        val commandId = transport.sent.single().command.id

        transport.emit(
            SessionEvent.CommandRejected(
                commandId,
                AppError("STALE_REVISION", "State changed", retryable = false),
            ),
        )
        advanceUntilIdle()

        assertEquals(10_000, engine.state.value.playback?.positionMs)
        assertFalse(engine.state.value.isOptimistic)
        assertEquals("STALE_REVISION", engine.state.value.lastError?.code)
        assertEquals("item-1", engine.state.value.queue?.currentItemId)
    }

    @Test
    fun `duplicate ended observation sends one track ended command`() = runTest {
        val transport = FakeTransport(FakeClock())
        val probe = FakeProbe()
        val engine = engine(transport, probe, FakeSink(), engineScope())
        transport.emit(snapshotEvent(5))
        advanceUntilIdle()

        probe.mutable.value = LocalPlaybackObservation(
            trackId = "vid-1",
            itemId = "item-1",
            positionMs = 180_000,
            hasEnded = true,
        )
        advanceUntilIdle()
        probe.mutable.value = probe.mutable.value.copy(positionMs = 180_100)
        advanceUntilIdle()

        val ended = transport.sent.filter { it.command.payload is CommandPayload.TrackEnded }
        assertEquals(1, ended.size)
        assertEquals(5, ended.single().observedRevision)
        assertEquals("item-1", (ended.single().command.payload as CommandPayload.TrackEnded).itemId)
    }

    @Test
    fun `session close purges chat queue and playback`() = runTest {
        val transport = FakeTransport(FakeClock())
        val sink = FakeSink()
        val engine = engine(transport, FakeProbe(), sink, engineScope())
        transport.emit(snapshotEvent(4, chat = listOf(chat(1), chat(2))))
        advanceUntilIdle()

        transport.emit(SessionEvent.SessionClosed("session-1", "BOTH_LEFT"))
        advanceUntilIdle()

        with(engine.state.value) {
            assertNull(session)
            assertNull(playback)
            assertNull(queue)
            assertTrue(chat.isEmpty())
            assertEquals("BOTH_LEFT", sessionClosedReason)
        }
        assertEquals(1, sink.purges)
    }

    @Test
    fun `reconnect discards pending commands and restores authoritative state`() = runTest {
        val transport = FakeTransport(FakeClock())
        val sink = FakeSink()
        val engine = engine(transport, FakeProbe(), sink, engineScope())
        transport.emit(snapshotEvent(3))
        advanceUntilIdle()

        engine.pause()
        advanceUntilIdle()
        assertTrue(engine.state.value.pendingCommandIds.isNotEmpty())

        transport.connection(ConnectionState.RECONNECTING)
        advanceUntilIdle()

        assertTrue(engine.state.value.pendingCommandIds.isEmpty())
        assertFalse(engine.state.value.isOptimistic)
        assertTrue(engine.state.value.playback?.isPlaying ?: false)
    }

    @Test
    fun `chat is ordered and deduplicated`() = runTest {
        val transport = FakeTransport(FakeClock())
        val engine = engine(transport, FakeProbe(), FakeSink(), engineScope())
        transport.emit(snapshotEvent(1))
        advanceUntilIdle()

        transport.emit(SessionEvent.ChatReceived(chat(2, "b")))
        transport.emit(SessionEvent.ChatReceived(chat(1, "a")))
        transport.emit(SessionEvent.ChatReceived(chat(2, "b")))
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), engine.state.value.chat.map(ChatMessage::content))
    }

    @Test
    fun `presence replaces participants without changing role`() = runTest {
        val transport = FakeTransport(FakeClock())
        val engine = engine(transport, FakeProbe(), FakeSink(), engineScope())
        transport.emit(snapshotEvent(1))
        advanceUntilIdle()

        transport.emit(
            SessionEvent.Presence(
                listOf(
                    ParticipantView("user-1", Role.SPEAKER, PresenceStatus.CONNECTED),
                    ParticipantView("user-2", Role.LISTENER, PresenceStatus.DISCONNECTED, graceExpiresAt = 9_999),
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(PresenceStatus.DISCONNECTED, engine.state.value.room?.participants?.last()?.status)
        assertEquals(Role.SPEAKER, engine.state.value.role)
    }

    @Test
    fun `playback issue is retained for the UI`() = runTest {
        val transport = FakeTransport(FakeClock())
        val engine = engine(transport, FakeProbe(), FakeSink(), engineScope())
        transport.emit(snapshotEvent(1))
        advanceUntilIdle()

        val issue = PlaybackIssue("user-2", "vid-1", com.listentogether.core.model.PlaybackIssueState.FAILED, "no stream")
        transport.emit(SessionEvent.PlaybackIssueReported(issue))
        advanceUntilIdle()

        assertEquals(issue, engine.state.value.issues["user-2:vid-1"])
    }

    private fun chat(sequence: Long, text: String = "message $sequence") = ChatMessage(
        messageId = "m-$sequence",
        sessionId = "session-1",
        senderId = "user-2",
        content = text,
        createdAt = 1_000 + sequence,
        sequence = sequence,
    )
}
