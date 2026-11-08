package com.listentogether.voice

import kotlinx.coroutines.flow.SharedFlow

/** Signaling relay boundary. Production implementation wraps the process WebSocket. */
sealed interface VoiceSignal {
    data class Offer(val sdp: String) : VoiceSignal
    data class Answer(val sdp: String) : VoiceSignal
    data class Ice(val sdpMid: String?, val sdpMLineIndex: Int, val candidate: String) : VoiceSignal
}

interface VoiceSignaling {
    val incoming: SharedFlow<VoiceSignal>
    fun send(signal: VoiceSignal): Boolean
    fun close()
}
