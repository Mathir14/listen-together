package com.listentogether.service

import com.listentogether.audio.AudioArbiter
import com.listentogether.audio.AudioPolicy
import com.listentogether.communication.ChatRepository
import com.listentogether.communication.TtsCoordinator
import com.listentogether.core.common.AppResult
import com.listentogether.core.model.ConnectionState
import com.listentogether.core.model.PlaybackSnapshot
import com.listentogether.core.model.Role
import com.listentogether.core.model.RoomCredentials
import com.listentogether.core.model.RoomStatus
import com.listentogether.core.model.RoomView
import com.listentogether.core.model.SessionStatus
import com.listentogether.core.model.SessionView
import com.listentogether.core.model.Track
import com.listentogether.core.model.TurnCredentials
import com.listentogether.core.model.VoiceState
import com.listentogether.sync.MutableSessionStateStore
import com.listentogether.sync.SessionEvent
import com.listentogether.testing.FakeSessionTransport
import com.listentogether.testing.InMemorySessionBus
import com.listentogether.testing.InMemorySettingsRepository
import com.listentogether.testing.InMemoryTtsCursorStore
import com.listentogether.testing.MutableLocalPlaybackProbe
import com.listentogether.testing.RecordingAnnouncer
import com.listentogether.testing.RecordingFocusController
import com.listentogether.testing.RecordingRemoteStateSink
import com.listentogether.testing.RecordingRouteController
import com.listentogether.testing.RecordingVoiceTransport
import com.listentogether.testing.VirtualClock
import com.listentogether.voice.VoiceSession
import com.listentogether.voice.VoiceSignal
import com.listentogether.voice.VoiceSignaling
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionServiceTest {

    private object DummyVoiceSignaling : VoiceSignaling {
        private val _incoming = MutableSharedFlow<VoiceSignal>()
        override val incoming: SharedFlow<VoiceSignal> = _incoming
        override fun send(signal: VoiceSignal): Boolean = true
        override fun close() = Unit
    }

    private data class RuntimeHarness(
        val runtime: SessionRuntime,
        val sink: RecordingRemoteStateSink,
        val settingsRepo: InMemorySettingsRepository,
        val transport: FakeSessionTransport,
        val voiceTransport: RecordingVoiceTransport,
    )

    private fun TestScope.createRuntime(
        micPermission: Boolean = true,
    ): RuntimeHarness {
        val clock = VirtualClock(1000L)
        val bus = InMemorySessionBus(backgroundScope)
        val transport = FakeSessionTransport("client-1", bus, clock)
        val sink = RecordingRemoteStateSink()
        val probe = MutableLocalPlaybackProbe()
        val settingsRepo = InMemorySettingsRepository()
        val focusController = RecordingFocusController()
        val routeController = RecordingRouteController()
        val audioArbiter = AudioArbiter(
            focusController = focusController,
            routeController = routeController,
            policy = AudioPolicy(),
            scope = backgroundScope,
        )
        val stateStore = MutableSessionStateStore { clock.monotonicMs() }
        val chat = ChatRepository(
            transport = transport,
            stateStore = stateStore,
            scope = backgroundScope,
        )
        val tts = TtsCoordinator(
            chat = chat,
            announcer = RecordingAnnouncer(),
            audio = audioArbiter,
            stateStore = stateStore,
            settingsRepository = settingsRepo,
            cursorStore = InMemoryTtsCursorStore(),
            scope = backgroundScope,
        )
        val voiceTransport = RecordingVoiceTransport()
        val voiceFactory = {
            VoiceSession(
                transport = voiceTransport,
                signaling = DummyVoiceSignaling,
                turnCredentials = {
                    AppResult.Success(
                        TurnCredentials(
                            username = "user",
                            credential = "pwd",
                            ttlSeconds = 3600L,
                            urls = emptyList(),
                        ),
                    )
                },
                clock = clock,
                scope = backgroundScope,
            )
        }

        val runtime = SessionRuntime(
            scope = backgroundScope,
            settingsRepository = settingsRepo,
            transport = transport,
            probe = probe,
            sink = sink,
            audioArbiter = audioArbiter,
            chat = chat,
            tts = tts,
            voiceFactory = voiceFactory,
            micPermissionGranted = { micPermission },
        )
        return RuntimeHarness(runtime, sink, settingsRepo, transport, voiceTransport)
    }

    @Test
    fun `runtime initial state and service required flag`() = runTest {
        val harness = createRuntime()
        harness.runtime.start()

        assertFalse(harness.runtime.serviceRequired.value)
        assertEquals("Not connected", harness.runtime.serviceNotificationText.value)
        assertFalse(harness.runtime.micTransmitting.value)
        assertTrue(harness.runtime.micMuted.value)
    }

    @Test
    fun `connect sets up credentials and recovery gate blocks autoplay on restore`() = runTest {
        val harness = createRuntime()
        val credentials = RoomCredentials(
            roomId = "room-123",
            participantId = "p-1",
            participantToken = "token-abc",
            role = Role.SPEAKER,
        )

        harness.runtime.restore(credentials)
        assertTrue(harness.runtime.recoveryPending.value)

        val track = Track(
            provider = "youtube_music",
            providerTrackId = "yt-1",
            title = "Song A",
            artist = "Artist A",
            durationMs = 180_000,
        )
        val snapshot = SessionEvent.Snapshot(
            room = RoomView("room-123", emptyList(), activeSessionId = "session-1", status = RoomStatus.ACTIVE),
            session = SessionView("session-1", "room-123", SessionStatus.ACTIVE, 1000L, emptyList()),
            playback = PlaybackSnapshot(
                sessionId = "session-1",
                revision = 1,
                track = track,
                isPlaying = true,
                positionMs = 10_000,
            ),
            queue = null,
            chat = emptyList(),
            role = Role.SPEAKER,
            serverTime = 1000L,
            revision = 1L,
        )

        harness.transport.receive(snapshot)
        runCurrent()

        // Gate should have applied paused playback
        assertTrue(harness.runtime.recoveryPending.value)

        // Resume after recovery opens gate
        harness.runtime.resumeAfterRecovery()
        assertFalse(harness.runtime.recoveryPending.value)
    }

    @Test
    fun `microphone permission denied blocks mic and sets micBlocked`() = runTest {
        val harness = createRuntime(micPermission = false)
        harness.runtime.start()

        harness.runtime.setMicMuted(false)
        assertTrue(harness.runtime.micBlocked.value)
        assertTrue(harness.runtime.micMuted.value)
        assertFalse(harness.runtime.micTransmitting.value)
    }

    @Test
    fun `onMicrophoneUnavailable marks mic blocked and muted`() = runTest {
        val harness = createRuntime(micPermission = true)
        harness.runtime.start()

        harness.runtime.onMicrophoneUnavailable()
        assertTrue(harness.runtime.micBlocked.value)
        assertTrue(harness.runtime.micMuted.value)
    }

    @Test
    fun `notification text changes with room and session state`() = runTest {
        val harness = createRuntime()
        harness.runtime.start()

        assertEquals("Not connected", harness.runtime.serviceNotificationText.value)

        val snapshotWaiting = SessionEvent.Snapshot(
            room = RoomView("room-1", emptyList(), activeSessionId = null, status = RoomStatus.ACTIVE),
            session = null,
            playback = null,
            queue = null,
            chat = emptyList(),
            role = Role.SPEAKER,
            serverTime = 1000L,
            revision = 1L,
        )
        harness.transport.receive(snapshotWaiting)
        runCurrent()
        assertEquals("Waiting for your partner", harness.runtime.serviceNotificationText.value)

        val snapshotActive = SessionEvent.Snapshot(
            room = RoomView("room-1", emptyList(), activeSessionId = "session-1", status = RoomStatus.ACTIVE),
            session = SessionView("session-1", "room-1", SessionStatus.ACTIVE, 1000L, emptyList()),
            playback = null,
            queue = null,
            chat = emptyList(),
            role = Role.SPEAKER,
            serverTime = 1000L,
            revision = 2L,
        )
        harness.transport.receive(snapshotActive)
        runCurrent()
        // Voice is automatically started on active session; initially connecting
        assertEquals("Connecting voice…", harness.runtime.serviceNotificationText.value)

        // When voice connects
        harness.voiceTransport.emitState(VoiceState.CONNECTED)
        runCurrent()
        assertEquals("Voice connected", harness.runtime.serviceNotificationText.value)

        // When speaker unmutes mic
        harness.runtime.setMicMuted(false)
        runCurrent()
        assertEquals("Voice connected · microphone live", harness.runtime.serviceNotificationText.value)
    }

    @Test
    fun `runtimeHolder set and get works`() {
        SessionRuntimeHolder.set(null)
        assertEquals(null, SessionRuntimeHolder.get())
    }
}
