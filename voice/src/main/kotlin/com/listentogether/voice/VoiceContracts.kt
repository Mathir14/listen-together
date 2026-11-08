package com.listentogether.voice

import com.listentogether.core.model.Role
import com.listentogether.core.model.VoiceState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class VoiceDescriptionType { OFFER, ANSWER }

enum class IceConnectionState {
    NEW,
    CHECKING,
    CONNECTED,
    COMPLETED,
    FAILED,
    DISCONNECTED,
    CLOSED,
}

data class IceServerConfig(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

sealed interface VoiceTransportEvent {
    data class LocalDescription(val type: VoiceDescriptionType, val sdp: String) : VoiceTransportEvent
    data class LocalIceCandidate(
        val sdpMid: String?,
        val sdpMLineIndex: Int,
        val candidate: String,
    ) : VoiceTransportEvent

    data class ConnectionChanged(val state: VoiceState) : VoiceTransportEvent
    data class IceStateChanged(val state: IceConnectionState) : VoiceTransportEvent
}

/** Boundary around the WebRTC SDK so signaling/order/role tests never load native code. */
interface VoiceTransport {
    val events: SharedFlow<VoiceTransportEvent>
    val state: StateFlow<VoiceState>

    fun start(role: Role, iceServers: List<IceServerConfig>)
    fun applyRemoteDescription(type: VoiceDescriptionType, sdp: String)
    fun addRemoteIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String)
    fun setMicMuted(muted: Boolean)
    fun setSpeakerMuted(muted: Boolean)
    fun restartIce()
    fun close()
}
