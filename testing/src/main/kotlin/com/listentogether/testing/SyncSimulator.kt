package com.listentogether.testing

import com.listentogether.core.model.Role
import com.listentogether.sync.MutableSessionStateStore
import com.listentogether.sync.SessionEngine
import kotlinx.coroutines.CoroutineScope

/**
 * Two-client harness (T10/T12): two [SessionEngine]s, two fake transports and one in-memory bus
 * in a single JVM. Tests inject server frames via [ManualSessionServer] or a richer scripted
 * server without touching a socket or real time.
 */
class SyncSimulator(
    private val scope: CoroutineScope,
    val clock: VirtualClock = VirtualClock(),
    val roomId: String = "room-sim",
    speakerNetwork: FakeNetworkConditions = FakeNetworkConditions(),
    listenerNetwork: FakeNetworkConditions = FakeNetworkConditions(),
    val bus: InMemorySessionBus = InMemorySessionBus(scope, seed = InMemorySessionBus.DEFAULT_SEED),
    val server: FakeSessionServer = ManualSessionServer(),
) : AutoCloseable {
    init {
        bus.server = server
    }

    class Client(
        val name: String,
        val role: Role,
        val transport: FakeSessionTransport,
        val probe: MutableLocalPlaybackProbe,
        val sink: RecordingRemoteStateSink,
        val engine: SessionEngine,
    ) {
        fun connect(token: String = "token-$name") {
            engine.connect(roomId = engine.state.value.room?.roomId ?: DEFAULT_ROOM, token = token)
        }

        fun observation(): com.listentogether.sync.LocalPlaybackObservation = probe.observation.value

        companion object {
            private const val DEFAULT_ROOM = "room-sim"
        }
    }

    val speaker: Client = buildClient("speaker", Role.SPEAKER, speakerNetwork)
    val listener: Client = buildClient("listener", Role.LISTENER, listenerNetwork)

    fun connectBoth() {
        speaker.engine.connect(roomId, "speaker-token")
        listener.engine.connect(roomId, "listener-token")
    }

    fun clients(): List<Client> = listOf(speaker, listener)

    fun client(role: Role): Client = if (role == Role.SPEAKER) speaker else listener

    fun peer(role: Role): Client = if (role == Role.SPEAKER) listener else speaker

    private fun buildClient(
        name: String,
        role: Role,
        network: FakeNetworkConditions,
    ): Client {
        val transport = FakeSessionTransport(
            clientId = name,
            bus = bus,
            clock = clock,
        ).apply { this.network = network }
        val probe = MutableLocalPlaybackProbe()
        val sink = RecordingRemoteStateSink()
        val engine = SessionEngine(
            transport = transport,
            probe = probe,
            sink = sink,
            scope = scope,
            store = MutableSessionStateStore { clock.nowMs() },
        )
        return Client(name, role, transport, probe, sink, engine)
    }

    override fun close() {
        clients().forEach { it.engine.close() }
    }
}
