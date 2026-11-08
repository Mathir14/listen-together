package com.listentogether.voice

import com.listentogether.core.common.AppResult
import com.listentogether.core.common.Clock
import com.listentogether.core.common.logging.Logger
import com.listentogether.core.common.logging.NoopLogger
import com.listentogether.core.model.Role
import com.listentogether.core.model.TurnCredentials
import com.listentogether.core.model.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One-way voice orchestration (architecture §11): fetches ephemeral TURN credentials, wires the
 * WebRTC transport to signaling, applies roles (only the speaker offers), and owns ICE-restart
 * policy. The microphone is muted until explicitly enabled and toggling it never renegotiates.
 */
class VoiceSession(
    private val transport: VoiceTransport,
    private val signaling: VoiceSignaling,
    private val turnCredentials: suspend () -> AppResult<TurnCredentials>,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val logger: Logger = NoopLogger,
    private val listenerIceRestartDelayMs: Long = LISTENER_ICE_RESTART_MS,
) : AutoCloseable {
    val state: StateFlow<VoiceState> = transport.state

    private val _micMuted = MutableStateFlow(true)
    val micMuted: StateFlow<Boolean> = _micMuted.asStateFlow()

    private val _speakerMuted = MutableStateFlow(false)
    val speakerMuted: StateFlow<Boolean> = _speakerMuted.asStateFlow()

    private val jobs = mutableListOf<Job>()
    private var started = false
    private var role: Role = Role.LISTENER
    private var lastIceState: IceConnectionState = IceConnectionState.NEW
    private var restartScheduled = false
    private var restarted = false

    fun start(role: Role) {
        if (started) return
        started = true
        this.role = role
        jobs += scope.launch { signaling.incoming.collect(::handleSignal) }
        jobs += scope.launch { transport.events.collect(::handleTransportEvent) }
        jobs += scope.launch {
            val credentials = runCatching { turnCredentials() }.getOrNull()?.getOrNull()
            if (credentials == null) {
                logger.event("voice.turn.unavailable", mapOf("role" to role.name))
            }
            transport.start(role, iceServers(credentials))
            transport.setMicMuted(true)
            transport.setSpeakerMuted(_speakerMuted.value)
        }
    }

    private suspend fun handleSignal(signal: VoiceSignal) {
        when (signal) {
            is VoiceSignal.Offer -> transport.applyRemoteDescription(VoiceDescriptionType.OFFER, signal.sdp)
            is VoiceSignal.Answer -> transport.applyRemoteDescription(VoiceDescriptionType.ANSWER, signal.sdp)
            is VoiceSignal.Ice -> transport.addRemoteIceCandidate(
                signal.sdpMid,
                signal.sdpMLineIndex,
                signal.candidate,
            )
        }
    }

    private fun handleTransportEvent(event: VoiceTransportEvent) {
        when (event) {
            is VoiceTransportEvent.LocalDescription -> {
                val sent = signaling.send(
                    when (event.type) {
                        VoiceDescriptionType.OFFER -> VoiceSignal.Offer(event.sdp)
                        VoiceDescriptionType.ANSWER -> VoiceSignal.Answer(event.sdp)
                    },
                )
                if (!sent) logger.event("voice.signal.send_failed", mapOf("type" to event.type.name))
            }
            is VoiceTransportEvent.LocalIceCandidate -> {
                signaling.send(
                    VoiceSignal.Ice(event.sdpMid, event.sdpMLineIndex, event.candidate),
                )
            }
            is VoiceTransportEvent.IceStateChanged -> onIceStateChanged(event.state)
            is VoiceTransportEvent.ConnectionChanged -> Unit
        }
    }

    private fun onIceStateChanged(state: IceConnectionState) {
        lastIceState = state
        when (state) {
            IceConnectionState.CONNECTED, IceConnectionState.COMPLETED -> {
                restartScheduled = false
                restarted = false
            }
            IceConnectionState.FAILED -> requestIceRestart()
            else -> Unit
        }
    }

    private fun requestIceRestart() {
        when (role) {
            Role.SPEAKER -> restartIce("speaker_failure")
            Role.LISTENER -> {
                if (restartScheduled) return
                restartScheduled = true
                logger.event("voice.ice.restart_scheduled", mapOf("delay_ms" to listenerIceRestartDelayMs))
                scope.launch {
                    delay(listenerIceRestartDelayMs)
                    restartScheduled = false
                    if (lastIceState == IceConnectionState.FAILED) {
                        restartIce("listener_failure")
                    }
                }
            }
        }
    }

    private fun restartIce(reason: String) {
        if (restarted) return
        restarted = true
        logger.event("voice.ice.restart", mapOf("reason" to reason))
        transport.restartIce()
    }

    fun setMicMuted(muted: Boolean) {
        _micMuted.value = muted
        transport.setMicMuted(muted)
    }

    fun setSpeakerMuted(muted: Boolean) {
        _speakerMuted.value = muted
        transport.setSpeakerMuted(muted)
    }

    private fun iceServers(credentials: TurnCredentials?): List<IceServerConfig> {
        val servers = mutableListOf(IceServerConfig(urls = listOf(DEFAULT_STUN)))
        if (credentials != null) {
            servers += IceServerConfig(
                urls = credentials.urls,
                username = credentials.username,
                credential = credentials.credential,
            )
        }
        return servers
    }

    override fun close() {
        jobs.forEach(Job::cancel)
        jobs.clear()
        transport.close()
        signaling.close()
        started = false
    }

    companion object {
        const val LISTENER_ICE_RESTART_MS = 15_000L
        const val DEFAULT_STUN = "stun:stun.l.google.com:19302"
    }
}
