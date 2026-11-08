package com.listentogether.testing

import com.listentogether.core.common.Clock
import com.listentogether.core.model.ConnectionState
import com.listentogether.sync.EmittedCommand
import com.listentogether.sync.SessionCommand
import com.listentogether.sync.SessionEvent
import com.listentogether.sync.SessionTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Network impairments applied to server-to-client frames. */
data class FakeNetworkConditions(
    val latencyMs: Long = 0,
    val jitterMs: Long = 0,
    val lossRate: Double = 0.0,
    val duplicationRate: Double = 0.0,
) {
    init {
        require(latencyMs >= 0) { "latencyMs must be >= 0" }
        require(jitterMs >= 0) { "jitterMs must be >= 0" }
        require(lossRate in 0.0..1.0) { "lossRate must be in [0, 1]" }
        require(duplicationRate in 0.0..1.0) { "duplicationRate must be in [0, 1]" }
    }
}

/**
 * Server side of the in-memory bus. Implementations receive lifecycle callbacks and commands;
 * they can reply through [InMemorySessionBus.sendTo] / [InMemorySessionBus.broadcast].
 */
interface FakeSessionServer {
    fun onConnect(
        client: FakeSessionTransport,
        token: String,
        lastSessionId: String?,
        lastRevision: Long?,
    ) = Unit

    fun onDisconnect(client: FakeSessionTransport) = Unit

    fun onCommand(
        client: FakeSessionTransport,
        command: SessionCommand,
        sessionId: String?,
        observedRevision: Long?,
    ) = Unit
}

/**
 * In-memory replacement for the WebSocket transport fabric (architecture §17). Frames are
 * delivered on [scope] with deterministic jitter/loss/duplication so tests never touch sockets
 * or real time.
 */
class InMemorySessionBus(
    private val scope: CoroutineScope,
    var server: FakeSessionServer? = null,
    seed: Long = DEFAULT_SEED,
) {
    private val random = Random(seed)
    private val attached = LinkedHashSet<FakeSessionTransport>()

    fun attach(client: FakeSessionTransport) {
        attached += client
    }

    fun detach(client: FakeSessionTransport) {
        attached -= client
    }

    fun sendTo(clientId: String, event: SessionEvent) {
        attached.firstOrNull { it.clientId == clientId }?.let { deliver(it, event) }
    }

    fun broadcast(event: SessionEvent, exceptClientId: String? = null) {
        attached.filter { it.clientId != exceptClientId }.forEach { deliver(it, event) }
    }

    internal fun deliver(target: FakeSessionTransport, event: SessionEvent) {
        val network = target.network
        if (network.lossRate > 0 && random.nextDouble() < network.lossRate) return
        val copies = if (network.duplicationRate > 0 && random.nextDouble() < network.duplicationRate) 2 else 1
        repeat(copies) {
            val delayMs = deliveryDelay(network)
            scope.launch {
                if (delayMs > 0) delay(delayMs)
                target.receive(event)
            }
        }
    }

    internal fun dispatch(
        client: FakeSessionTransport,
        command: SessionCommand,
        sessionId: String?,
        observedRevision: Long?,
    ): Boolean {
        if (client !in attached || client.connectionState.value != ConnectionState.CONNECTED) return false
        server?.onCommand(client, command, sessionId, observedRevision)
        return true
    }

    private fun deliveryDelay(network: FakeNetworkConditions): Long {
        if (network.jitterMs == 0L) return network.latencyMs
        val offset = (random.nextDouble() * 2.0 - 1.0) * network.jitterMs / 2.0
        return (network.latencyMs + offset.toLong()).coerceAtLeast(0)
    }

    companion object {
        const val DEFAULT_SEED = 7L
    }
}

/**
 * Client-side fake transport. Each instance models one connected participant and can be given
 * independent network conditions.
 */
class FakeSessionTransport(
    val clientId: String,
    private val bus: InMemorySessionBus,
    private val clock: Clock,
) : SessionTransport {
    private val _events = MutableSharedFlow<SessionEvent>(
        replay = EVENT_REPLAY,
        extraBufferCapacity = EVENT_BUFFER,
    )
    override val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    var network: FakeNetworkConditions = FakeNetworkConditions()
    var connectedRoomId: String? = null
        private set

    val sentCommands = mutableListOf<SessionCommand>()
    val connectCalls = mutableListOf<ConnectCall>()

    data class ConnectCall(
        val roomId: String,
        val token: String,
        val lastSessionId: String?,
        val lastRevision: Long?,
    )

    override fun connect(roomId: String, token: String, lastSessionId: String?, lastRevision: Long?) {
        connectedRoomId = roomId
        connectCalls += ConnectCall(roomId, token, lastSessionId, lastRevision)
        _connectionState.value = ConnectionState.CONNECTING
        bus.attach(this)
        _connectionState.value = ConnectionState.CONNECTED
        bus.server?.onConnect(this, token, lastSessionId, lastRevision)
    }

    override fun send(command: SessionCommand, sessionId: String?, observedRevision: Long?): EmittedCommand {
        val accepted = bus.dispatch(this, command, sessionId, observedRevision)
        if (accepted) sentCommands += command
        return EmittedCommand(command.id, accepted)
    }

    override fun disconnect() {
        if (_connectionState.value == ConnectionState.DISCONNECTED) return
        _connectionState.value = ConnectionState.DISCONNECTED
        connectedRoomId = null
        bus.detach(this)
        bus.server?.onDisconnect(this)
    }

    override fun serverNowMs(): Long =
        (clock as? VirtualClock)?.serverNowMs() ?: clock.nowMs()

    /** Inject a frame as if it arrived from the server. Used by scripted servers/tests. */
    fun receive(event: SessionEvent) {
        _events.tryEmit(event)
    }

    fun simulateConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    companion object {
        private const val EVENT_REPLAY = 64
        private const val EVENT_BUFFER = 256
    }
}

/**
 * A manual server: tests decide which frames each client receives. Commands are recorded so
 * scenarios can assert exactly one outbound intent per local action.
 */
class ManualSessionServer : FakeSessionServer {
    data class ReceivedCommand(
        val clientId: String,
        val command: SessionCommand,
        val sessionId: String?,
        val observedRevision: Long?,
    )

    val commands = mutableListOf<ReceivedCommand>()
    val connectedClients = mutableListOf<String>()
    val disconnectedClients = mutableListOf<String>()

    var onConnectAction: ((FakeSessionTransport, String, String?, Long?) -> Unit)? = null
    var onCommandAction: ((FakeSessionTransport, SessionCommand) -> Unit)? = null

    override fun onConnect(client: FakeSessionTransport, token: String, lastSessionId: String?, lastRevision: Long?) {
        connectedClients += client.clientId
        onConnectAction?.invoke(client, token, lastSessionId, lastRevision)
    }

    override fun onDisconnect(client: FakeSessionTransport) {
        disconnectedClients += client.clientId
    }

    override fun onCommand(
        client: FakeSessionTransport,
        command: SessionCommand,
        sessionId: String?,
        observedRevision: Long?,
    ) {
        commands += ReceivedCommand(client.clientId, command, sessionId, observedRevision)
        onCommandAction?.invoke(client, command)
    }
}
