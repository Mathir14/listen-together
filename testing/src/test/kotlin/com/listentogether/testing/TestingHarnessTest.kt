package com.listentogether.testing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TestingHarnessTest {
    private fun TestScope.scope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())

    @Test
    fun `virtual clock advances deterministically and carries a server offset`() {
        val clock = VirtualClock()
        val start = clock.nowMs()
        clock.advanceBy(500)
        clock.serverOffsetMs = 120
        assertEquals(start + 500, clock.nowMs())
        assertEquals(start + 620, clock.serverNowMs())
    }

    @Test
    fun `bus applies latency and duplicates when configured`() = runTest {
        val scope = scope()
        val clock = VirtualClock()
        val bus = InMemorySessionBus(scope)
        val transport = FakeSessionTransport("client", bus, clock).apply {
            network = FakeNetworkConditions(latencyMs = 50, duplicationRate = 1.0)
        }
        transport.connect("room", "token")
        val received = mutableListOf<TestEvent>()
        scope.launch { transport.events.collect { received += TestEvent(it) } }
        advanceUntilIdle()

        bus.deliver(transport, TestEvent.create())
        advanceTimeBy(49)
        assertTrue(received.isEmpty())
        advanceUntilIdle()

        assertEquals(2, received.size)
    }

    @Test
    fun `bus drops every frame at full loss`() = runTest {
        val scope = scope()
        val clock = VirtualClock()
        val bus = InMemorySessionBus(scope)
        val transport = FakeSessionTransport("client", bus, clock).apply {
            network = FakeNetworkConditions(lossRate = 1.0)
        }
        transport.connect("room", "token")
        val received = mutableListOf<TestEvent>()
        scope.launch { transport.events.collect { received += TestEvent(it) } }
        advanceUntilIdle()

        bus.deliver(transport, TestEvent.create())
        advanceUntilIdle()

        assertTrue(received.isEmpty())
    }

    @Test
    fun `simulator runs two clients against one manual server`() = runTest {
        val scope = scope()
        val clock = VirtualClock()
        val server = ManualSessionServer()
        val simulator = SyncSimulator(scope, clock, roomId = "r1", server = server)

        server.onConnectAction = { transport, _, _, _ ->
            val role = if (transport.clientId == "speaker") {
                com.listentogether.core.model.Role.SPEAKER
            } else {
                com.listentogether.core.model.Role.LISTENER
            }
            val session = com.listentogether.core.model.SessionView(
                sessionId = "s1",
                roomId = "r1",
                createdAt = clock.nowMs(),
                participants = listOf("speaker", "listener"),
            )
            transport.receive(
                com.listentogether.sync.SessionEvent.Snapshot(
                    room = com.listentogether.core.model.RoomView(
                        roomId = "r1",
                        participants = emptyList(),
                        activeSessionId = "s1",
                        status = com.listentogether.core.model.RoomStatus.ACTIVE,
                    ),
                    session = session,
                    playback = com.listentogether.core.model.PlaybackSnapshot(
                        sessionId = "s1",
                        revision = 3,
                        currentItemId = "i1",
                        track = fakeTrack("t1"),
                        isPlaying = true,
                        positionMs = 1_000,
                        updatedAt = clock.nowMs(),
                    ),
                    queue = com.listentogether.core.model.QueueState(queueRevision = 3),
                    chat = emptyList(),
                    role = role,
                    serverTime = clock.nowMs(),
                    revision = 3,
                ),
            )
        }

        simulator.connectBoth()
        advanceUntilIdle()

        simulator.clients().forEach { client ->
            assertEquals(3, client.engine.state.value.revision)
            assertEquals("t1", client.sink.lastPlayback?.track?.providerTrackId)
            assertEquals(1, client.sink.playbackApplications.size)
        }
        assertEquals(listOf("speaker", "listener"), server.connectedClients)
        simulator.close()
    }

    private data class TestEvent(val source: com.listentogether.sync.SessionEvent) {
        companion object {
            fun create(): com.listentogether.sync.SessionEvent =
                com.listentogether.sync.SessionEvent.RoomState(
                    com.listentogether.core.model.RoomView(roomId = "r1"),
                )
        }
    }
}
