package com.listentogether.voice

import com.listentogether.core.common.logging.Logger
import com.listentogether.core.common.logging.NoopLogger
import com.listentogether.core.network.ProtocolChannel
import com.listentogether.protocol.MessageType
import com.listentogether.protocol.VoiceIcePayload
import com.listentogether.protocol.VoiceSdpPayload
import com.listentogether.protocol.commandFrame
import com.listentogether.protocol.decodePayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Relays SDP/ICE through the single process WebSocket. SDP is never stored or logged. The
 * backend addresses voice frames only to the other slot, so this adapter does not need a peer id.
 */
class ProtocolChannelVoiceSignaling(
    private val channel: ProtocolChannel,
    private val roomId: () -> String?,
    private val sessionId: () -> String?,
    private val scope: CoroutineScope,
    private val logger: Logger = NoopLogger,
) : VoiceSignaling {
    private val _incoming = MutableSharedFlow<VoiceSignal>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<VoiceSignal> = _incoming.asSharedFlow()

    private var job: Job? = scope.launch { collectIncoming() }

    override fun send(signal: VoiceSignal): Boolean {
        val room = roomId() ?: return false
        val frame = when (signal) {
            is VoiceSignal.Offer -> commandFrame(
                type = MessageType.VOICE_OFFER,
                payload = VoiceSdpPayload(signal.sdp),
                roomId = room,
                sessionId = sessionId(),
            )
            is VoiceSignal.Answer -> commandFrame(
                type = MessageType.VOICE_ANSWER,
                payload = VoiceSdpPayload(signal.sdp),
                roomId = room,
                sessionId = sessionId(),
            )
            is VoiceSignal.Ice -> commandFrame(
                type = MessageType.VOICE_ICE,
                payload = VoiceIcePayload(signal.sdpMid, signal.sdpMLineIndex, signal.candidate),
                roomId = room,
                sessionId = sessionId(),
            )
        }
        return channel.send(frame)
    }

    private suspend fun collectIncoming() {
        channel.incoming.collect { envelope ->
            val signal = runCatching {
                when (envelope.type) {
                    MessageType.VOICE_OFFER -> VoiceSignal.Offer(envelope.decodePayload<VoiceSdpPayload>().sdp)
                    MessageType.VOICE_ANSWER -> VoiceSignal.Answer(envelope.decodePayload<VoiceSdpPayload>().sdp)
                    MessageType.VOICE_ICE -> envelope.decodePayload<VoiceIcePayload>().let {
                        VoiceSignal.Ice(it.sdpMid, it.sdpMLineIndex, it.candidate)
                    }
                    else -> null
                }
            }.getOrNull()
            if (signal != null) _incoming.tryEmit(signal)
        }
    }

    override fun close() {
        job?.cancel()
        job = null
    }
}
