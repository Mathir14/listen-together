package com.listentogether.testing

import com.listentogether.core.model.Role
import com.listentogether.core.model.VoiceState
import com.listentogether.voice.IceConnectionState
import com.listentogether.voice.IceServerConfig
import com.listentogether.voice.VoiceDescriptionType
import com.listentogether.voice.VoiceSignal
import com.listentogether.voice.VoiceSignaling
import com.listentogether.voice.VoiceTransport
import com.listentogether.voice.VoiceTransportEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Recording WebRTC boundary: no native code, fully deterministic. */
class RecordingVoiceTransport : VoiceTransport {
    private val _events = MutableSharedFlow<VoiceTransportEvent>(
        replay = 32,
        extraBufferCapacity = 64,
    )
    override val events: SharedFlow<VoiceTransportEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(VoiceState.IDLE)
    override val state: StateFlow<VoiceState> = _state.asStateFlow()

    var startedRole: Role? = null
        private set
    var startCount: Int = 0
        private set
    var closeCount: Int = 0
        private set
    var restartCount: Int = 0
        private set
    var micMuted: Boolean = true
        private set
    var speakerMuted: Boolean = false
        private set
    val iceServers = mutableListOf<IceServerConfig>()
    val remoteDescriptions = mutableListOf<Pair<VoiceDescriptionType, String>>()
    val remoteCandidates = mutableListOf<Triple<String?, Int, String>>()

    override fun start(role: Role, iceServers: List<IceServerConfig>) {
        startedRole = role
        startCount++
        this.iceServers += iceServers
        _state.value = VoiceState.CONNECTING
        if (role == Role.SPEAKER) {
            _events.tryEmit(
                VoiceTransportEvent.LocalDescription(VoiceDescriptionType.OFFER, "offer-sdp"),
            )
        }
    }

    override fun applyRemoteDescription(type: VoiceDescriptionType, sdp: String) {
        remoteDescriptions += type to sdp
        if (type == VoiceDescriptionType.OFFER && startedRole == Role.LISTENER) {
            _events.tryEmit(
                VoiceTransportEvent.LocalDescription(VoiceDescriptionType.ANSWER, "answer-sdp"),
            )
        }
    }

    override fun addRemoteIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        remoteCandidates += Triple(sdpMid, sdpMLineIndex, candidate)
    }

    override fun setMicMuted(muted: Boolean) {
        micMuted = muted
    }

    override fun setSpeakerMuted(muted: Boolean) {
        speakerMuted = muted
    }

    override fun restartIce() {
        restartCount++
        _events.tryEmit(
            VoiceTransportEvent.LocalDescription(VoiceDescriptionType.OFFER, "restart-sdp"),
        )
    }

    override fun close() {
        closeCount++
        _state.value = VoiceState.IDLE
    }

    fun emit(event: VoiceTransportEvent) {
        _events.tryEmit(event)
    }

    fun emitState(state: VoiceState) {
        _state.value = state
        _events.tryEmit(VoiceTransportEvent.ConnectionChanged(state))
    }

    fun failIce() {
        _events.tryEmit(VoiceTransportEvent.IceStateChanged(IceConnectionState.FAILED))
    }

    fun connectIce() {
        _events.tryEmit(VoiceTransportEvent.IceStateChanged(IceConnectionState.CONNECTED))
    }
}

class RecordingVoiceSignaling : VoiceSignaling {
    private val _incoming = MutableSharedFlow<VoiceSignal>(replay = 16, extraBufferCapacity = 32)
    override val incoming: SharedFlow<VoiceSignal> = _incoming.asSharedFlow()

    val sent = mutableListOf<VoiceSignal>()
    var closed: Boolean = false
        private set

    override fun send(signal: VoiceSignal): Boolean {
        sent += signal
        return true
    }

    override fun close() {
        closed = true
    }

    fun receive(signal: VoiceSignal) {
        _incoming.tryEmit(signal)
    }
}
