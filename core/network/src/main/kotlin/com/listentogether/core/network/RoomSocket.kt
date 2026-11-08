package com.listentogether.core.network

import com.listentogether.core.common.Clock
import com.listentogether.core.common.ExponentialBackoff
import com.listentogether.core.common.logging.Logger
import com.listentogether.core.model.ConnectionState
import com.listentogether.protocol.Envelope
import com.listentogether.protocol.ErrorCode
import com.listentogether.protocol.ErrorPayload
import com.listentogether.protocol.FrameKind
import com.listentogether.protocol.HelloPayload
import com.listentogether.protocol.MessageType
import com.listentogether.protocol.PingPayload
import com.listentogether.protocol.PROTOCOL_VERSION
import com.listentogether.protocol.ProtocolJson
import com.listentogether.protocol.TimeSyncPayload
import com.listentogether.protocol.TimeSyncResultPayload
import com.listentogether.protocol.decodePayload
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One authenticated WebSocket per process. Reconnection, heartbeat and clock sync are owned here;
 * feature modules consume decoded envelopes through [incoming].
 */
interface ProtocolChannel {
    val incoming: SharedFlow<Envelope>
    val connectionState: StateFlow<ConnectionState>
    fun connect(roomId: String, token: String, lastSessionId: String? = null, lastRevision: Long? = null)
    fun send(frame: Envelope): Boolean
    fun disconnect()
    fun serverNowMs(): Long
}

class RoomSocket(
    private val config: NetworkConfig,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val logger: Logger = com.listentogether.core.common.logging.NoopLogger,
    private val backoff: ExponentialBackoff = ExponentialBackoff(),
    client: HttpClient? = null,
) : ProtocolChannel {
    private val client: HttpClient = client ?: HttpClient(OkHttp) {
        expectSuccess = false
        install(ContentNegotiation) { json(ProtocolJson.json) }
        install(WebSockets)
    }
    private val ownsClient = client == null

    private val _incoming = MutableSharedFlow<Envelope>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val incoming: SharedFlow<Envelope> = _incoming.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val clockSync = ClockSync(clock)
    private val sequence = AtomicLong(0)
    private val session = AtomicReference<DefaultClientWebSocketSession?>(null)
    private val running = AtomicBoolean(false)
    private var loopJob: Job? = null

    @Volatile private var token: String? = null
    @Volatile private var roomId: String? = null
    @Volatile private var lastSessionId: String? = null
    @Volatile private var lastRevision: Long? = null

    override fun connect(roomId: String, token: String, lastSessionId: String?, lastRevision: Long?) {
        synchronized(this) {
            if (running.get() && this.roomId == roomId && this.token == token) return
            disconnectInternal()
            this.roomId = roomId
            this.token = token
            this.lastSessionId = lastSessionId
            this.lastRevision = lastRevision
            running.set(true)
            loopJob = scope.launch { reconnectLoop() }
        }
    }

    override fun send(frame: Envelope): Boolean {
        val session = session.get() ?: return false
        val result = runCatching { session.outgoing.trySend(Frame.Text(ProtocolJson.encode(frame))) }
        return result.getOrNull()?.isSuccess == true
    }

    override fun disconnect() {
        synchronized(this) {
            running.set(false)
            disconnectInternal()
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    override fun serverNowMs(): Long = clockSync.serverNowMs()

    private fun disconnectInternal() {
        loopJob?.cancel()
        loopJob = null
        val session = session.getAndSet(null)
        session?.let { active ->
            scope.launch { runCatching { active.close() } }
        }
    }

    private suspend fun reconnectLoop() {
        var attempt = 0
        while (running.get() && scope.isActive) {
            _connectionState.value = if (attempt == 0) ConnectionState.CONNECTING else ConnectionState.RECONNECTING
            try {
                openSocket()
                attempt = 0
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                logger.error("ws.loop.failed", error)
            }
            if (!running.get()) break
            _connectionState.value = ConnectionState.RECONNECTING
            val delayMs = backoff.delayForAttempt(attempt++)
            logger.event("ws.reconnect.scheduled", mapOf("attempt" to attempt, "delay_ms" to delayMs))
            delay(delayMs)
        }
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private suspend fun openSocket() {
        val currentRoom = roomId ?: return
        val currentToken = token ?: return
        client.webSocket(config.webSocketUrl) {
            if (!running.get()) {
                close()
                return@webSocket
            }
            session.set(this)
            _connectionState.value = ConnectionState.CONNECTED
            logger.event("ws.connected", mapOf("room_id" to currentRoom))

            send(
                Frame.Text(
                    ProtocolJson.encode(
                        Envelope(
                            kind = FrameKind.COMMAND,
                            id = java.util.UUID.randomUUID().toString(),
                            type = MessageType.HELLO,
                            roomId = currentRoom,
                            payload = ProtocolJson.encodePayload(
                                HelloPayload(
                                    token = currentToken,
                                    roomId = currentRoom,
                                    protocolVersion = PROTOCOL_VERSION,
                                    lastSessionId = lastSessionId,
                                    lastRevision = lastRevision,
                                ),
                            ),
                        ),
                    ),
                ),
            )

            val heartbeat = launch {
                while (isActive) {
                    delay(HEARTBEAT_MS)
                    sendPing()
                }
            }
            val timeSync = launch {
                while (isActive) {
                    sendTimeSync()
                    delay(CLOCK_SYNC_INTERVAL_MS)
                }
            }

            var fatal = false
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val envelope = runCatching { ProtocolJson.decode(frame.readText()) }.getOrNull() ?: continue
                    envelope.sessionId?.let { lastSessionId = it }
                    envelope.revision?.let { revision ->
                        val previous = lastRevision
                        if (previous == null || revision > previous) lastRevision = revision
                    }
                    if (envelope.type == MessageType.TIME_SYNC_RESULT) {
                        val payload = runCatching { envelope.decodePayload<TimeSyncResultPayload>() }.getOrNull()
                        if (payload != null) {
                            val receivedAt = clock.monotonicMs()
                            clockSync.addSample(payload.t0, payload.t1, receivedAt)
                        }
                    } else {
                        _incoming.emit(envelope)
                    }
                    if (envelope.kind == FrameKind.ERROR) {
                        val error = runCatching { envelope.decodePayload<ErrorPayload>() }.getOrNull()
                        if (error?.code == ErrorCode.PROTOCOL_MISMATCH || error?.code == ErrorCode.NOT_MEMBER) {
                            fatal = true
                            _connectionState.value = ConnectionState.FAILED
                            break
                        }
                    }
                    if (envelope.type == MessageType.SESSION_CLOSED) {
                        lastSessionId = null
                        lastRevision = null
                    }
                }
            } finally {
                heartbeat.cancel()
                timeSync.cancel()
                session.set(null)
            }
            if (fatal) {
                running.set(false)
                runCatching { close() }
            }
        }
    }

    private fun sendPing() {
        val currentRoom = roomId ?: return
        send(
            Envelope(
                kind = FrameKind.COMMAND,
                id = java.util.UUID.randomUUID().toString(),
                type = MessageType.PING,
                roomId = currentRoom,
                payload = ProtocolJson.encodePayload(PingPayload()),
            ),
        )
    }

    private fun sendTimeSync() {
        val currentRoom = roomId ?: return
        val t0 = clock.monotonicMs()
        send(
            Envelope(
                kind = FrameKind.COMMAND,
                id = java.util.UUID.randomUUID().toString(),
                type = MessageType.TIME_SYNC,
                roomId = currentRoom,
                payload = ProtocolJson.encodePayload(TimeSyncPayload(t0)),
            ),
        )
    }

    fun close() {
        disconnect()
        if (ownsClient) client.close()
    }

    companion object {
        private const val HEARTBEAT_MS = 15_000L
        private const val CLOCK_SYNC_INTERVAL_MS = 30_000L
    }
}
