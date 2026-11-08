package com.listentogether.voice

import com.listentogether.core.model.ConnectionState
import com.listentogether.core.network.ProtocolChannel
import com.listentogether.protocol.Envelope
import com.listentogether.protocol.FrameKind
import com.listentogether.protocol.MessageType
import com.listentogether.protocol.ProtocolJson
import com.listentogether.protocol.VoiceIcePayload
import com.listentogether.protocol.VoiceSdpPayload
import com.listentogether.protocol.decodePayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProtocolChannelVoiceSignalingTest {
    private class FakeChannel : ProtocolChannel {
        private val _incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 16)
        override val incoming: SharedFlow<Envelope> = _incoming
        override val connectionState: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.CONNECTED)
        val sent = mutableListOf<Envelope>()

        override fun connect(roomId: String, token: String, lastSessionId: String?, lastRevision: Long?) = Unit
        override fun send(frame: Envelope): Boolean {
            sent += frame
            return true
        }

        override fun disconnect() = Unit
        override fun serverNowMs(): Long = 0

        fun receive(frame: Envelope) {
            _incoming.tryEmit(frame)
        }
    }

    private fun TestScope.scope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())

    @Test
    fun `offer and answer encode the addressed voice frames`() = runTest {
        val channel = FakeChannel()
        val signaling = ProtocolChannelVoiceSignaling(
            channel = channel,
            roomId = { "room-1" },
            sessionId = { "session-1" },
            scope = scope(),
        )

        assertTrue(signaling.send(VoiceSignal.Offer("offer-sdp")))
        assertTrue(signaling.send(VoiceSignal.Answer("answer-sdp")))

        assertEquals(MessageType.VOICE_OFFER, channel.sent[0].type)
        assertEquals(FrameKind.COMMAND, channel.sent[0].kind)
        assertEquals("room-1", channel.sent[0].roomId)
        assertEquals("session-1", channel.sent[0].sessionId)
        assertEquals("offer-sdp", channel.sent[0].decodePayload<VoiceSdpPayload>().sdp)
        assertEquals(MessageType.VOICE_ANSWER, channel.sent[1].type)
        signaling.close()
    }

    @Test
    fun `incoming relay maps to typed signals including ice`() = runTest {
        val channel = FakeChannel()
        val signaling = ProtocolChannelVoiceSignaling(
            channel = channel,
            roomId = { "room-1" },
            sessionId = { "session-1" },
            scope = scope(),
        )
        val received = mutableListOf<VoiceSignal>()
        val collectorScope = scope()
        val collector = collectorScope.launch {
            signaling.incoming.collect { received += it }
        }
        advanceUntilIdle()

        channel.receive(
            Envelope(
                kind = FrameKind.EVENT,
                id = "1",
                type = MessageType.VOICE_OFFER,
                roomId = "room-1",
                payload = ProtocolJson.encodePayload(VoiceSdpPayload("remote-offer")),
            ),
        )
        channel.receive(
            Envelope(
                kind = FrameKind.EVENT,
                id = "2",
                type = MessageType.VOICE_ICE,
                roomId = "room-1",
                payload = ProtocolJson.encodePayload(VoiceIcePayload("audio", 0, "candidate-1")),
            ),
        )
        advanceUntilIdle()

        assertEquals(VoiceSignal.Offer("remote-offer"), received[0])
        assertEquals(VoiceSignal.Ice("audio", 0, "candidate-1"), received[1])
        collector.cancel()
        signaling.close()
    }
}
