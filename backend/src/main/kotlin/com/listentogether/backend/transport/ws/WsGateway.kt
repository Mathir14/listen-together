package com.listentogether.backend.transport.ws

import com.listentogether.backend.config.BackendConfig
import com.listentogether.backend.room.ConnectionSink
import com.listentogether.backend.room.RoomRegistry
import com.listentogether.backend.transport.WindowRateLimiter
import com.listentogether.core.common.Clock
import com.listentogether.core.common.logging.Logger
import com.listentogether.protocol.CommandRejectedPayload
import com.listentogether.protocol.Envelope
import com.listentogether.protocol.ErrorCode
import com.listentogether.protocol.ErrorPayload
import com.listentogether.protocol.FrameKind
import com.listentogether.protocol.HelloPayload
import com.listentogether.protocol.MessageType
import com.listentogether.protocol.ProtocolJson
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException

class WsConnection(
    override val roomId: String,
    override val participantId: String,
    private val session: DefaultWebSocketServerSession,
    private val scope: CoroutineScope,
    private val logger: Logger,
    private val onSlowConsumer: (WsConnection) -> Unit,
) : ConnectionSink {
    private val closed = AtomicBoolean(false)

    override fun send(frame: Envelope) {
        if (closed.get()) return
        val result = runCatching { session.outgoing.trySend(Frame.Text(ProtocolJson.encode(frame))) }
        if (result.isFailure || result.getOrNull()?.isFailure == true) {
            onSlowConsumer(this)
        }
    }

    override fun close(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        scope.launch {
            logger.event(
                "ws.connection.close",
                mapOf("room_id" to roomId, "participant_id" to participantId, "reason" to reason),
            )
            runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, reason)) }
        }
    }
}

fun Application.configureWebSocket(
    registry: RoomRegistry,
    config: BackendConfig,
    clock: Clock,
    logger: Logger,
    scope: CoroutineScope,
) {
    routing {
        webSocket("/ws") {
            var connection: WsConnection? = null
            try {
                val first = withTimeout(config.handshakeTimeoutMs) { incoming.receive() }
                val text = (first as? Frame.Text)?.readText()
                if (text == null) {
                    closeWithError(this, "INVALID_PAYLOAD", "Text HELLO frame required")
                    return@webSocket
                }
                if (text.toByteArray(Charsets.UTF_8).size > config.maxFrameBytes) {
                    closeWithError(this, "INVALID_PAYLOAD", "Frame too large")
                    return@webSocket
                }
                val helloFrame = try {
                    ProtocolJson.decode(text)
                } catch (error: SerializationException) {
                    closeWithError(this, "INVALID_PAYLOAD", "Malformed envelope")
                    return@webSocket
                }
                if (!helloFrame.isCompatible) {
                    closeWithError(
                        this,
                        "PROTOCOL_MISMATCH",
                        "Server protocol version ${com.listentogether.protocol.PROTOCOL_VERSION} required",
                    )
                    return@webSocket
                }
                if (helloFrame.kind != FrameKind.COMMAND || helloFrame.type != MessageType.HELLO) {
                    closeWithError(this, "INVALID_PAYLOAD", "HELLO must be the first frame")
                    return@webSocket
                }
                val hello = try {
                    ProtocolJson.payload<HelloPayload>(helloFrame)
                } catch (error: SerializationException) {
                    closeWithError(this, "INVALID_PAYLOAD", "Malformed HELLO")
                    return@webSocket
                }
                if (hello.protocolVersion != com.listentogether.protocol.PROTOCOL_VERSION) {
                    closeWithError(this, "PROTOCOL_MISMATCH", "Unsupported protocol version")
                    return@webSocket
                }
                if (hello.roomId != helloFrame.roomId) {
                    closeWithError(this, "INVALID_PAYLOAD", "Room id mismatch")
                    return@webSocket
                }
                val binding = registry.authenticate(hello.roomId, hello.token)
                if (binding == null) {
                    closeWithError(this, "NOT_MEMBER", "Invalid participant token")
                    return@webSocket
                }
                val ws = WsConnection(
                    roomId = binding.roomId,
                    participantId = binding.participantId,
                    session = this,
                    scope = scope,
                    logger = logger,
                    onSlowConsumer = { slow ->
                        logger.event(
                            "ws.slow_consumer",
                            mapOf("room_id" to slow.roomId, "participant_id" to slow.participantId),
                        )
                        registry.disconnect(slow)
                        slow.close("slow_consumer")
                    },
                )
                connection = ws
                registry.connect(binding, ws)

                var lastClientSeq = 0L
                val commandLimiter = WindowRateLimiter(config.maxCommandsPerSecond, 1_000, clock)
                val chatLimiter = WindowRateLimiter(config.maxChatPerMinute, 60_000, clock)

                for (frame in incoming) {
                    if (frame !is Frame.Text) {
                        sendRejected(ws, null, ErrorCode.INVALID_PAYLOAD, "Text frames only")
                        continue
                    }
                    if (frame.readText().toByteArray(Charsets.UTF_8).size > config.maxFrameBytes) {
                        sendRejected(ws, null, ErrorCode.INVALID_PAYLOAD, "Frame too large")
                        ws.close("frame_too_large")
                        break
                    }
                    val envelope = try {
                        ProtocolJson.decode(frame.readText())
                    } catch (error: SerializationException) {
                        sendRejected(ws, null, ErrorCode.INVALID_PAYLOAD, "Malformed envelope")
                        continue
                    }
                    if (!envelope.isCompatible) {
                        sendRejected(ws, envelope, ErrorCode.PROTOCOL_MISMATCH, "Protocol mismatch")
                        ws.close("protocol_mismatch")
                        break
                    }
                    if (envelope.kind != FrameKind.COMMAND) {
                        sendRejected(ws, envelope, ErrorCode.INVALID_PAYLOAD, "Only commands accepted")
                        continue
                    }
                    if (envelope.type == MessageType.HELLO) {
                        sendRejected(ws, envelope, ErrorCode.INVALID_PAYLOAD, "Already authenticated")
                        continue
                    }
                    if (envelope.roomId != binding.roomId) {
                        sendRejected(ws, envelope, ErrorCode.NOT_MEMBER, "Wrong room")
                        continue
                    }
                    val seq = envelope.clientSeq
                    if (seq != null) {
                        if (seq <= lastClientSeq) {
                            sendRejected(ws, envelope, ErrorCode.STALE_REVISION, "Non-increasing client sequence")
                            continue
                        }
                        lastClientSeq = seq
                    }
                    if (!commandLimiter.tryAcquire()) {
                        sendRejected(ws, envelope, ErrorCode.RATE_LIMITED, "Too many commands")
                        continue
                    }
                    if (envelope.type == MessageType.CHAT_SEND && !chatLimiter.tryAcquire()) {
                        sendRejected(ws, envelope, ErrorCode.RATE_LIMITED, "Too many messages")
                        continue
                    }
                    registry.actorFor(binding.roomId)?.submit(binding.participantId, envelope)
                        ?: sendRejected(ws, envelope, ErrorCode.NOT_MEMBER, "Unknown room")
                }
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                logger.event("ws.handshake.timeout")
                runCatching { close(CloseReason(CloseReason.Codes.NORMAL, "handshake_timeout")) }
            } catch (error: Throwable) {
                logger.error("ws.connection.failed", error)
            } finally {
                connection?.let { registry.disconnect(it) }
            }
        }
    }
}

private suspend fun closeWithError(
    session: DefaultWebSocketServerSession,
    code: String,
    message: String,
) {
    val frame = Envelope(
        kind = FrameKind.ERROR,
        id = java.util.UUID.randomUUID().toString(),
        type = MessageType.ERROR,
        payload = ProtocolJson.encodePayload(
            ErrorPayload(ErrorCode.valueOf(code), message, retryable = false),
        ),
    )
    runCatching { session.outgoing.trySend(Frame.Text(ProtocolJson.encode(frame))) }
    runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, code)) }
}

private fun sendRejected(
    connection: WsConnection,
    frame: Envelope?,
    code: ErrorCode,
    message: String,
) {
    val payload = CommandRejectedPayload(
        commandId = frame?.id ?: "unknown",
        code = code,
        retryable = code in setOf(ErrorCode.RATE_LIMITED, ErrorCode.SLOW_CONSUMER, ErrorCode.SESSION_MISMATCH),
    )
    connection.send(
        Envelope(
            kind = FrameKind.ERROR,
            id = frame?.id ?: java.util.UUID.randomUUID().toString(),
            type = MessageType.COMMAND_REJECTED,
            roomId = connection.roomId,
            payload = ProtocolJson.encodePayload(payload),
        ),
    )
}
