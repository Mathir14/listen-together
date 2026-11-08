package com.listentogether.voice

import android.content.Context
import com.listentogether.core.common.logging.Logger
import com.listentogether.core.common.logging.NoopLogger
import com.listentogether.core.model.Role
import com.listentogether.core.model.VoiceState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Android WebRTC implementation of [VoiceTransport]. The speaker creates one `sendonly`
 * transceiver with a disabled local track (mic off by default); the listener creates one
 * `recvonly` transceiver and never has a microphone. Toggling mute only flips track.enabled, so
 * it never renegotiates.
 */
class WebRtcVoiceTransport(
    private val context: Context,
    private val logger: Logger = NoopLogger,
) : VoiceTransport {
    private val _events = MutableSharedFlow<VoiceTransportEvent>(extraBufferCapacity = 128)
    override val events: SharedFlow<VoiceTransportEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(VoiceState.IDLE)
    override val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val closed = AtomicBoolean(false)
    private var role: Role = Role.LISTENER
    private var factory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localTrack: AudioTrack? = null
    private val remoteTracks = mutableListOf<AudioTrack>()
    private var micMuted = true
    private var speakerMuted = false

    override fun start(role: Role, iceServers: List<IceServerConfig>) {
        if (closed.get() || peerConnection != null) return
        this.role = role
        _state.value = VoiceState.CONNECTING
        try {
            ensureFactory()
            val config = PeerConnection.RTCConfiguration(iceServers.map(::toIceServer)).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                iceTransportsType = PeerConnection.IceTransportsType.ALL
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
            val pc = factory?.createPeerConnection(config, peerConnectionObserver)
                ?: error("PeerConnectionFactory is unavailable")
            peerConnection = pc

            when (role) {
                Role.SPEAKER -> {
                    val source = factory?.createAudioSource(MediaConstraints())
                        ?: error("Audio source is unavailable")
                    audioSource = source
                    val track = factory?.createAudioTrack("listen-together-voice", source)
                        ?: error("Audio track is unavailable")
                    localTrack = track
                    track.setEnabled(!micMuted)
                    pc.addTransceiver(
                        track,
                        RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY),
                    )
                    createOffer()
                }
                Role.LISTENER -> {
                    pc.addTransceiver(
                        MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                        RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
                    )
                }
            }
            logger.event("voice.transport.started", mapOf("role" to role.name))
        } catch (error: Throwable) {
            logger.error("voice.transport.start_failed", error)
            updateState(VoiceState.FAILED)
        }
    }

    override fun applyRemoteDescription(type: VoiceDescriptionType, sdp: String) {
        val pc = peerConnection ?: return
        val description = SessionDescription(
            when (type) {
                VoiceDescriptionType.OFFER -> SessionDescription.Type.OFFER
                VoiceDescriptionType.ANSWER -> SessionDescription.Type.ANSWER
            },
            sdp,
        )
        pc.setRemoteDescription(
            object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    if (type == VoiceDescriptionType.OFFER && role == Role.LISTENER) {
                        createAnswer()
                    }
                }

                override fun onSetFailure(error: String?) {
                    logger.event("voice.sdp.remote_failed", mapOf("type" to type.name, "error" to error.orEmpty()))
                    updateState(VoiceState.FAILED)
                }
            },
            description,
        )
    }

    override fun addRemoteIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        if (candidate.isBlank()) return
        peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    override fun setMicMuted(muted: Boolean) {
        micMuted = muted
        localTrack?.setEnabled(!muted)
    }

    override fun setSpeakerMuted(muted: Boolean) {
        speakerMuted = muted
        remoteTracks.forEach { track ->
            track.setEnabled(!muted)
            track.setVolume(if (muted) 0.0 else 1.0)
        }
    }

    override fun restartIce() {
        val pc = peerConnection ?: return
        pc.restartIce()
        // An ICE restart requires fresh SDP. The speaker normally owns offers; the explicit
        // listener restart path also creates one so a failed path can recover.
        createOffer()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        peerConnection = null
        remoteTracks.forEach { runCatching { it.dispose() } }
        remoteTracks.clear()
        runCatching { localTrack?.dispose() }
        localTrack = null
        runCatching { audioSource?.dispose() }
        audioSource = null
        runCatching { factory?.dispose() }
        factory = null
        runCatching { audioDeviceModule?.release() }
        audioDeviceModule = null
        _state.value = VoiceState.IDLE
    }

    private fun ensureFactory() {
        if (factory != null) return
        if (!initialized.compareAndSet(false, true)) {
            // The native library is initialized once per process; a second factory is still
            // valid for a new session.
        } else {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                    .builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions(),
            )
        }
        val adm = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
            .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
            .createAudioDeviceModule()
        audioDeviceModule = adm
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()
    }

    private fun createOffer() {
        val pc = peerConnection ?: return
        pc.createOffer(
            object : SimpleSdpObserver() {
                override fun onCreateSuccess(description: SessionDescription?) {
                    if (description == null) return
                    pc.setLocalDescription(
                        object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                _events.tryEmit(
                                    VoiceTransportEvent.LocalDescription(
                                        VoiceDescriptionType.OFFER,
                                        description.description,
                                    ),
                                )
                            }

                            override fun onSetFailure(error: String?) {
                                logger.event("voice.sdp.local_failed", mapOf("error" to error.orEmpty()))
                                updateState(VoiceState.FAILED)
                            }
                        },
                        description,
                    )
                }

                override fun onCreateFailure(error: String?) {
                    logger.event("voice.sdp.offer_failed", mapOf("error" to error.orEmpty()))
                    updateState(VoiceState.FAILED)
                }
            },
            MediaConstraints(),
        )
    }

    private fun createAnswer() {
        val pc = peerConnection ?: return
        pc.createAnswer(
            object : SimpleSdpObserver() {
                override fun onCreateSuccess(description: SessionDescription?) {
                    if (description == null) return
                    pc.setLocalDescription(
                        object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                _events.tryEmit(
                                    VoiceTransportEvent.LocalDescription(
                                        VoiceDescriptionType.ANSWER,
                                        description.description,
                                    ),
                                )
                            }

                            override fun onSetFailure(error: String?) {
                                logger.event("voice.sdp.local_failed", mapOf("error" to error.orEmpty()))
                                updateState(VoiceState.FAILED)
                            }
                        },
                        description,
                    )
                }

                override fun onCreateFailure(error: String?) {
                    logger.event("voice.sdp.answer_failed", mapOf("error" to error.orEmpty()))
                    updateState(VoiceState.FAILED)
                }
            },
            MediaConstraints(),
        )
    }

    private fun toIceServer(config: IceServerConfig): PeerConnection.IceServer {
        val builder = PeerConnection.IceServer.builder(config.urls)
        config.username?.let(builder::setUsername)
        config.credential?.let(builder::setPassword)
        return builder.createIceServer()
    }

    private fun updateState(state: VoiceState) {
        if (_state.value == state) return
        _state.value = state
        _events.tryEmit(VoiceTransportEvent.ConnectionChanged(state))
    }

    private val peerConnectionObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            val mapped = when (state) {
                PeerConnection.IceConnectionState.NEW -> IceConnectionState.NEW
                PeerConnection.IceConnectionState.CHECKING -> IceConnectionState.CHECKING
                PeerConnection.IceConnectionState.CONNECTED -> IceConnectionState.CONNECTED
                PeerConnection.IceConnectionState.COMPLETED -> IceConnectionState.COMPLETED
                PeerConnection.IceConnectionState.FAILED -> IceConnectionState.FAILED
                PeerConnection.IceConnectionState.DISCONNECTED -> IceConnectionState.DISCONNECTED
                PeerConnection.IceConnectionState.CLOSED -> IceConnectionState.CLOSED
                null -> IceConnectionState.NEW
            }
            _events.tryEmit(VoiceTransportEvent.IceStateChanged(mapped))
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            val mapped = when (newState) {
                PeerConnection.PeerConnectionState.NEW,
                PeerConnection.PeerConnectionState.CONNECTING,
                -> VoiceState.CONNECTING
                PeerConnection.PeerConnectionState.CONNECTED -> VoiceState.CONNECTED
                PeerConnection.PeerConnectionState.DISCONNECTED -> VoiceState.RECONNECTING
                PeerConnection.PeerConnectionState.FAILED -> VoiceState.FAILED
                PeerConnection.PeerConnectionState.CLOSED -> VoiceState.IDLE
                null -> VoiceState.IDLE
            }
            updateState(mapped)
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit

        override fun onIceCandidate(candidate: IceCandidate?) {
            if (candidate == null) return
            _events.tryEmit(
                VoiceTransportEvent.LocalIceCandidate(
                    candidate.sdpMid,
                    candidate.sdpMLineIndex,
                    candidate.sdp,
                ),
            )
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit

        override fun onAddStream(stream: MediaStream?) = Unit

        override fun onRemoveStream(stream: MediaStream?) = Unit

        override fun onDataChannel(channel: org.webrtc.DataChannel?) = Unit

        override fun onRenegotiationNeeded() = Unit

        override fun onTrack(transceiver: RtpTransceiver?) {
            val track = transceiver?.receiver?.track()
            if (track is AudioTrack) {
                track.setEnabled(!speakerMuted)
                track.setVolume(if (speakerMuted) 0.0 else 1.0)
                remoteTracks += track
            }
        }
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }

    companion object {
        private val initialized = AtomicBoolean(false)
    }
}
