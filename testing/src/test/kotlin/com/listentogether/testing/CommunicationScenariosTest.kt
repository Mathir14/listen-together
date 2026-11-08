package com.listentogether.testing

import com.listentogether.audio.AudioArbiter
import com.listentogether.communication.ChatRepository
import com.listentogether.communication.TtsCoordinator
import com.listentogether.core.common.AppResult
import com.listentogether.core.data.AppSettings
import com.listentogether.core.model.ChatMessage
import com.listentogether.core.model.ParticipantView
import com.listentogether.core.model.PresenceStatus
import com.listentogether.core.model.Role
import com.listentogether.core.model.RoomStatus
import com.listentogether.core.model.RoomView
import com.listentogether.core.model.SessionStatus
import com.listentogether.core.model.SessionView
import com.listentogether.sync.CommandPayload
import com.listentogether.sync.SessionEvent
import com.listentogether.sync.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommunicationScenariosTest {
    private fun TestScope.scope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())

    private fun stateWithChat(
        sessionId: String = "s1",
        chat: List<ChatMessage> = emptyList(),
        role: Role = Role.SPEAKER,
    ) = SessionState(
        room = RoomView(
            roomId = "r1",
            participants = listOf(
                ParticipantView("speaker", Role.SPEAKER, PresenceStatus.CONNECTED),
                ParticipantView("listener", Role.LISTENER, PresenceStatus.CONNECTED),
            ),
            activeSessionId = sessionId,
            status = RoomStatus.ACTIVE,
        ),
        session = SessionView(sessionId, "r1", SessionStatus.ACTIVE, createdAt = 1_000),
        chat = chat,
        role = role,
    )

    private fun message(sequence: Long, text: String = "hello $sequence", sender: String = "listener") =
        ChatMessage(
            messageId = "m$sequence",
            sessionId = "s1",
            senderId = sender,
            content = text,
            createdAt = 1_000 + sequence,
            sequence = sequence,
        )

    private class ChatHarness(
        val scope: CoroutineScope,
        val server: ManualSessionServer,
        val transport: FakeSessionTransport,
        val state: FakeSessionStateStore,
        val repository: ChatRepository,
    )

    private fun TestScope.chatHarness(
        initialChat: List<ChatMessage> = emptyList(),
    ): ChatHarness {
        val scope = scope()
        val clock = VirtualClock()
        val bus = InMemorySessionBus(scope)
        val server = ManualSessionServer()
        bus.server = server
        val transport = FakeSessionTransport("me", bus, clock)
        val state = FakeSessionStateStore(stateWithChat(chat = initialChat))
        transport.connect("r1", "token")
        val repository = ChatRepository(transport, state, scope, retryDelayMs = 1_000)
        repository.start()
        return ChatHarness(scope, server, transport, state, repository)
    }

    @Test
    fun `chat is sorted by sequence and deduped`() = runTest {
        val duplicate = message(2, "two")
        val harness = chatHarness(initialChat = listOf(message(2, "two"), message(1, "one"), duplicate))
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L), harness.repository.messages.value.map(ChatMessage::sequence))
        harness.repository.close()
    }

    @Test
    fun `chat retries the same client message id three times then reports failure`() = runTest {
        val harness = chatHarness()
        advanceUntilIdle()

        assertTrue(harness.repository.send("hello") is AppResult.Success)
        runCurrent()
        val sends = harness.server.commands.mapNotNull { it.command.payload as? CommandPayload.ChatSend }
        assertEquals(1, sends.size)

        advanceTimeBy(1_000)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        advanceUntilIdle()

        val allSends = harness.server.commands.mapNotNull { it.command.payload as? CommandPayload.ChatSend }
        assertEquals(3, allSends.size)
        assertEquals(1, allSends.map(CommandPayload.ChatSend::clientMessageId).distinct().size)
        assertEquals("hello", allSends.first().text)
        assertTrue(harness.repository.pending.value.isEmpty())
        assertEquals("CHAT_DELIVERY_FAILED", harness.repository.lastError.value?.code)
        harness.repository.close()
    }

    @Test
    fun `chat acknowledgement stops retries without producing duplicates`() = runTest {
        val harness = chatHarness()
        advanceUntilIdle()
        harness.repository.send("hi")
        runCurrent()
        val sent = harness.server.commands.single().command.payload as CommandPayload.ChatSend

        harness.transport.receive(SessionEvent.ChatAcknowledged(sent.clientMessageId, 9))
        runCurrent()
        advanceTimeBy(10_000)
        advanceUntilIdle()

        assertEquals(1, harness.server.commands.size)
        assertTrue(harness.repository.pending.value.isEmpty())
        harness.repository.close()
    }

    @Test
    fun `session change drops pending chat and never replays it`() = runTest {
        val harness = chatHarness()
        advanceUntilIdle()
        harness.repository.send("bye")
        runCurrent()
        assertEquals(1, harness.server.commands.size)

        harness.state.set(
            SessionState(
                room = RoomView(roomId = "r1"),
                session = null,
                role = Role.SPEAKER,
            ),
        )
        runCurrent()
        advanceTimeBy(10_000)
        advanceUntilIdle()

        assertEquals(1, harness.server.commands.size)
        assertTrue(harness.repository.pending.value.isEmpty())
        harness.repository.close()
    }

    private class TtsHarness(
        val scope: CoroutineScope,
        val state: FakeSessionStateStore,
        val settings: InMemorySettingsRepository,
        val cursor: InMemoryTtsCursorStore,
        val announcer: RecordingAnnouncer,
        val audio: AudioArbiter,
        val chat: ChatRepository,
        val tts: TtsCoordinator,
    )

    private fun TestScope.ttsHarness(
        initialChat: List<ChatMessage> = emptyList(),
        settings: AppSettings = AppSettings(),
        available: Boolean = true,
    ): TtsHarness {
        val scope = scope()
        val clock = VirtualClock()
        val bus = InMemorySessionBus(scope)
        val server = ManualSessionServer()
        bus.server = server
        val transport = FakeSessionTransport("speaker", bus, clock).apply { connect("r1", "token") }
        val state = FakeSessionStateStore(stateWithChat(chat = initialChat))
        val settingsRepository = InMemorySettingsRepository(settings)
        val cursor = InMemoryTtsCursorStore()
        val announcer = RecordingAnnouncer(availableInitially = available)
        val audio = AudioArbiter(
            focusController = RecordingFocusController(),
            routeController = RecordingRouteController(),
            scope = scope,
        )
        val chat = ChatRepository(transport, state, scope, retryDelayMs = 1_000).also { it.start() }
        val tts = TtsCoordinator(
            chat = chat,
            announcer = announcer,
            audio = audio,
            stateStore = state,
            settingsRepository = settingsRepository,
            cursorStore = cursor,
            scope = scope,
        ).also { it.start() }
        return TtsHarness(scope, state, settingsRepository, cursor, announcer, audio, chat, tts)
    }

    @Test
    fun `listener messages are spoken in chat order and cursor advances`() = runTest {
        val harness = ttsHarness(initialChat = listOf(message(2), message(1)))
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L), harness.announcer.spoken.map { it.sequence })
        assertEquals(2L, harness.cursor.sequenceFor("s1"))
        harness.tts.close()
        harness.chat.close()
    }

    @Test
    fun `tts disabled advances cursor without speaking`() = runTest {
        val harness = ttsHarness(
            initialChat = listOf(message(1), message(2)),
            settings = AppSettings(ttsEnabled = false),
        )
        advanceUntilIdle()

        assertTrue(harness.announcer.spoken.isEmpty())
        assertEquals(2L, harness.cursor.sequenceFor("s1"))
        harness.tts.close()
        harness.chat.close()
    }

    @Test
    fun `voice activity holds tts until the 600 ms idle gate opens`() = runTest {
        val harness = ttsHarness()
        advanceUntilIdle()
        harness.audio.setVoiceActive(true)
        harness.state.update { it.copy(chat = listOf(message(1))) }
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(harness.announcer.spoken.isEmpty())

        harness.audio.setVoiceActive(false)
        advanceTimeBy(599)
        runCurrent()
        assertTrue(harness.announcer.spoken.isEmpty())
        advanceTimeBy(1)
        advanceUntilIdle()

        assertEquals(1, harness.announcer.spoken.size)
        harness.tts.close()
        harness.chat.close()
    }

    @Test
    fun `utterances never overlap and preserve fifo order`() = runTest {
        val harness = ttsHarness(initialChat = listOf(message(1), message(2)))
        harness.announcer.autoComplete = false
        advanceUntilIdle()

        assertEquals(1, harness.announcer.spoken.size)
        assertEquals(1L, harness.announcer.spoken.single().sequence)
        harness.announcer.completeNext()
        runCurrent()
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L), harness.announcer.spoken.map { it.sequence })
        assertEquals(1, harness.announcer.maxConcurrent)
        harness.tts.close()
        harness.chat.close()
    }

    @Test
    fun `duplicate message ids are spoken once`() = runTest {
        val duplicate = message(1)
        val harness = ttsHarness(initialChat = listOf(duplicate, duplicate))
        advanceUntilIdle()

        assertEquals(1, harness.announcer.spoken.size)
        harness.tts.close()
        harness.chat.close()
    }

    @Test
    fun `cursor prevents replay from snapshots on reconnect`() = runTest {
        val harness = ttsHarness()
        harness.cursor.markSpoken("s1", 1)
        harness.state.update { it.copy(chat = listOf(message(1), message(2))) }
        advanceUntilIdle()

        assertEquals(listOf(2L), harness.announcer.spoken.map { it.sequence })
        harness.tts.close()
        harness.chat.close()
    }

    @Test
    fun `session change purges cursor and stops old audio`() = runTest {
        val harness = ttsHarness(initialChat = listOf(message(1)))
        advanceUntilIdle()
        assertEquals(1, harness.announcer.spoken.size)

        harness.state.set(
            stateWithChat(sessionId = "s2", chat = listOf(message(2).copy(sessionId = "s2"))),
        )
        advanceUntilIdle()

        assertTrue(harness.cursor.purgeCount >= 1)
        assertEquals(2L, harness.announcer.spoken.last().sequence)
        harness.tts.close()
        harness.chat.close()
    }
}
