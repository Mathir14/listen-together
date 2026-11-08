package com.listentogether.service

import com.listentogether.audio.AudioArbiter
import com.listentogether.audio.AudioEvent
import com.listentogether.audio.AudioState
import com.listentogether.communication.ChatRepository
import com.listentogether.communication.TtsCoordinator
import com.listentogether.communication.TtsState
import com.listentogether.core.common.logging.Logger
import com.listentogether.core.common.logging.NoopLogger
import com.listentogether.core.data.SettingsRepository
import com.listentogether.core.model.ConnectionState
import com.listentogether.core.model.PlaybackSnapshot
import com.listentogether.core.model.QueueState
import com.listentogether.core.model.RepeatMode
import com.listentogether.core.model.Role
import com.listentogether.core.model.RoomCredentials
import com.listentogether.core.model.SessionStatus
import com.listentogether.core.model.Track
import com.listentogether.core.model.VoiceState
import com.listentogether.playback.Media3PlayerProvider
import com.listentogether.sync.LocalPlaybackProbe
import com.listentogether.sync.MutableSessionStateStore
import com.listentogether.sync.Origin
import com.listentogether.sync.RemoteStateSink
import com.listentogether.sync.SessionEngine
import com.listentogether.sync.SessionState
import com.listentogether.sync.SessionTransport
import com.listentogether.voice.VoiceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Process-scoped host for every real-time capability (architecture §13). The Application owns
 * one instance; [SessionService] only hosts it in the foreground and the UI binds to its flows.
 *
 * INV-4 is enforced by construction: the engine applies remote snapshots through [sink] and only
 * the explicit command methods below can produce an outbound frame.
 */
class SessionRuntime(
    private val scope: CoroutineScope,
    val settingsRepository: SettingsRepository,
    private val transport: SessionTransport,
    probe: LocalPlaybackProbe,
    sink: RemoteStateSink,
    private val audioArbiter: AudioArbiter,
    val chat: ChatRepository,
    val tts: TtsCoordinator,
    private val voiceFactory: () -> VoiceSession,
    private val micPermissionGranted: () -> Boolean = { true },
    val mediaPlayer: Media3PlayerProvider? = null,
    private val logger: Logger = NoopLogger,
    store: MutableSessionStateStore = MutableSessionStateStore { transport.serverNowMs() },
) : AutoCloseable {

    private val recoveryGate = RecoveryGate(sink)

    val engine: SessionEngine = SessionEngine(
        transport = transport,
        probe = probe,
        sink = recoveryGate,
        scope = scope,
        store = store,
        logger = logger,
    )

    val state: StateFlow<SessionState> = engine.state
    val audioState: StateFlow<AudioState> = audioArbiter.state
    val ttsState: StateFlow<TtsState> = tts.state

    private val _voiceState = MutableStateFlow(VoiceState.IDLE)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private val _micMuted = MutableStateFlow(true)
    val micMuted: StateFlow<Boolean> = _micMuted.asStateFlow()

    private val _speakerMuted = MutableStateFlow(false)
    val speakerMuted: StateFlow<Boolean> = _speakerMuted.asStateFlow()

    private val _micBlocked = MutableStateFlow(false)
    val micBlocked: StateFlow<Boolean> = _micBlocked.asStateFlow()

    private val _recoveryPending = MutableStateFlow(false)
    val recoveryPending: StateFlow<Boolean> = _recoveryPending.asStateFlow()

    private val _focusPaused = MutableStateFlow(false)
    val focusPaused: StateFlow<Boolean> = _focusPaused.asStateFlow()

    private val _serviceRequired = MutableStateFlow(false)
    val serviceRequired: StateFlow<Boolean> = _serviceRequired.asStateFlow()

    val serviceNotificationText: StateFlow<String> = combine(state, _voiceState, _micMuted) { session, voice, muted ->
        when {
            session.connection == ConnectionState.CONNECTING -> "Connecting…"
            session.connection == ConnectionState.RECONNECTING -> "Reconnecting…"
            session.connection == ConnectionState.FAILED -> "Connection failed"
            voice == VoiceState.CONNECTED && !muted && session.role == Role.SPEAKER ->
                "Voice connected · microphone live"
            voice == VoiceState.CONNECTED -> "Voice connected"
            voice == VoiceState.CONNECTING -> "Connecting voice…"
            session.isSessionActive -> "Listening together"
            session.sessionClosedReason != null -> "Session ended"
            session.room != null -> "Waiting for your partner"
            else -> "Not connected"
        }
    }.stateIn(scope, SharingStarted.Eagerly, "Not connected")

    val micTransmitting: StateFlow<Boolean> = combine(
        _voiceState,
        _micMuted,
        _micBlocked,
        state,
    ) { voice, muted, blocked, session ->
        session.role == Role.SPEAKER && voice != VoiceState.IDLE && !muted && !blocked
    }.stateIn(scope, SharingStarted.Eagerly, false)

    private val jobs = mutableListOf<Job>()
    private val voiceJobs = mutableListOf<Job>()

    private var voice: VoiceSession? = null
    private var currentRole: Role? = null
    private var started = false
    private var activeSessionId: String? = null
    private var voiceSessionId: String? = null
    private var lastSavedRoomId: String? = null

    fun start() {
        if (started) return
        started = true
        engine.start()
        chat.start()
        tts.start()
        jobs += scope.launch { state.collect(::onSessionState) }
        jobs += scope.launch { audioArbiter.events.collect(::onAudioEvent) }
        jobs += scope.launch {
            combine(state, _voiceState) { session, voiceState ->
                session.isSessionActive || session.playback?.track != null || voiceState != VoiceState.IDLE
            }.distinctUntilChanged().collect { _serviceRequired.value = it }
        }
    }

    /**
     * Restores the last membership after process death. Playback never starts on its own: the
     * recovery gate keeps the local player paused until the user taps resume (architecture §13).
     */
    fun restore(credentials: RoomCredentials) {
        connect(credentials, recovery = true)
    }

    fun connect(credentials: RoomCredentials, recovery: Boolean = false) {
        start()
        recoveryGate.open = !recovery
        _recoveryPending.value = recovery
        if (!recovery) _focusPaused.value = false
        engine.connect(
            roomId = credentials.roomId,
            token = credentials.participantToken,
        )
        logger.event(
            "service.connect",
            mapOf("room_id" to credentials.roomId, "role" to credentials.role.name, "recovery" to recovery),
        )
    }

    fun resumeAfterRecovery() {
        recoveryGate.open = true
        _recoveryPending.value = false
        _focusPaused.value = false
        val snapshot = state.value.playback ?: return
        scope.launch { recoveryGate.applyPlayback(snapshot, engine.serverNowMs(), Origin.SYSTEM) }
    }

    // ---- Playback commands ---------------------------------------------------

    fun play() {
        _focusPaused.value = false
        engine.play()
    }

    fun pause() = engine.pause()
    fun seekTo(positionMs: Long) = engine.seek(positionMs)
    fun selectTrack(track: Track) = engine.selectTrack(track)
    fun next() = engine.next()
    fun previous() = engine.previous()
    fun addToQueue(track: Track, index: Int? = null) = engine.addToQueue(track, index)
    fun removeFromQueue(itemId: String) = engine.removeFromQueue(itemId)
    fun reorderQueue(itemIds: List<String>) = engine.reorderQueue(itemIds)
    fun clearQueue() = engine.clearQueue()
    fun setRepeat(mode: RepeatMode) = engine.setRepeat(mode)
    fun setShuffle(enabled: Boolean) = engine.setShuffle(enabled)

    fun leave() {
        engine.leave()
        tts.stop()
        stopVoice("leave")
    }

    fun setTtsEnabled(enabled: Boolean) {
        scope.launch { settingsRepository.setTtsEnabled(enabled) }
    }

    // ---- Voice ---------------------------------------------------------------

    fun setMicMuted(muted: Boolean) {
        if (!muted && !micPermissionGranted()) {
            onMicrophoneUnavailable()
            return
        }
        _micBlocked.value = false
        applyMicMuted(muted)
    }

    fun setSpeakerMuted(muted: Boolean) {
        _speakerMuted.value = muted
        voice?.setSpeakerMuted(muted)
    }

    /**
     * Called by [SessionService] when Android refuses to promote the foreground service to the
     * microphone type (permission revoked, background start). Degrades to "mic paused".
     */
    fun onMicrophoneUnavailable() {
        _micBlocked.value = true
        applyMicMuted(true)
        logger.event("voice.mic.blocked", emptyMap())
    }

    private fun applyMicMuted(muted: Boolean) {
        _micMuted.value = muted
        voice?.setMicMuted(muted)
    }

    private suspend fun onSessionState(session: SessionState) {
        val sessionId = session.session?.sessionId
        val roomId = session.room?.roomId
        val role = session.role

        if (sessionId != null && session.session?.status == SessionStatus.ACTIVE) {
            if (roomId != null && roomId != lastSavedRoomId) {
                lastSavedRoomId = roomId
                settingsRepository.setLastRoom(roomId, role?.name)
            }
            if (sessionId != voiceSessionId) {
                voiceSessionId = sessionId
                if (role != null) startVoice(role) else stopVoice("no-role")
            }
            if (sessionId != activeSessionId) {
                activeSessionId = sessionId
                _focusPaused.value = false
                logger.event(
                    "service.session.active",
                    mapOf("session_id" to sessionId, "role" to role?.name.orEmpty()),
                )
            }
            if (session.playback?.track == null && session.hasSnapshot) {
                _recoveryPending.value = false
                recoveryGate.open = true
            }
            if (session.playback?.isPlaying == true) _focusPaused.value = false
        } else if (sessionId == null && (activeSessionId != null || voiceSessionId != null)) {
            activeSessionId = null
            voiceSessionId = null
            stopVoice("session-ended")
            tts.stop()
            _recoveryPending.value = false
            recoveryGate.open = true
            if (session.hasSnapshot || session.sessionClosedReason != null) {
                logger.event("service.session.inactive", emptyMap())
            }
        }

        if (session.connection == ConnectionState.FAILED) {
            _recoveryPending.value = false
        }
    }

    private fun startVoice(role: Role) {
        stopVoice("restart")
        currentRole = role
        val next = runCatching { voiceFactory() }.onFailure {
            logger.error("voice.create.failed", it)
        }.getOrNull() ?: return
        voice = next
        _micMuted.value = true
        _speakerMuted.value = false
        _micBlocked.value = false
        voiceJobs += scope.launch { next.state.collect(::onVoiceState) }
        voiceJobs += scope.launch { next.micMuted.collect { _micMuted.value = it } }
        voiceJobs += scope.launch { next.speakerMuted.collect { _speakerMuted.value = it } }
        next.start(role)
        logger.event("voice.start", mapOf("role" to role.name))
    }

    private fun onVoiceState(state: VoiceState) {
        _voiceState.value = state
        audioArbiter.setVoiceActive(state == VoiceState.CONNECTED || state == VoiceState.RECONNECTING)
        if (state == VoiceState.FAILED) {
            logger.event("voice.failed", emptyMap())
        }
    }

    private fun stopVoice(reason: String) {
        voiceJobs.forEach(Job::cancel)
        voiceJobs.clear()
        val previous = voice
        voice = null
        _voiceState.value = VoiceState.IDLE
        audioArbiter.setVoiceActive(false)
        currentRole = null
        _micMuted.value = true
        _speakerMuted.value = false
        _micBlocked.value = false
        if (previous != null) {
            previous.close()
            logger.event("voice.stop", mapOf("reason" to reason))
        }
    }

    // ---- Audio arbitration ---------------------------------------------------

    private fun onAudioEvent(event: AudioEvent) {
        when (event) {
            AudioEvent.TransientFocusLoss -> pauseForFocus(permanent = false)
            AudioEvent.PermanentFocusLoss -> pauseForFocus(permanent = true)
            AudioEvent.FocusGained -> Unit
        }
    }

    private fun pauseForFocus(permanent: Boolean) {
        if (permanent) _focusPaused.value = true
        if (state.value.playback?.isPlaying == true && state.value.isSessionActive) {
            engine.pause()
            logger.event("audio.focus.pause", mapOf("permanent" to permanent))
        }
    }

    override fun close() {
        jobs.forEach(Job::cancel)
        jobs.clear()
        stopVoice("runtime-close")
        tts.close()
        chat.close()
        engine.close()
        audioArbiter.release()
        started = false
    }

    /** Applies the recovery snapshot only after the gate is opened (no surprise autoplay). */
    private class RecoveryGate(private val delegate: RemoteStateSink) : RemoteStateSink {
        @Volatile var open: Boolean = true

        override suspend fun applyPlayback(playback: PlaybackSnapshot?, serverNowMs: Long, origin: Origin) {
            if (!open && origin == Origin.REMOTE) {
                delegate.applyPlayback(playback?.copy(isPlaying = false), serverNowMs, origin)
            } else {
                delegate.applyPlayback(playback, serverNowMs, origin)
            }
        }

        override suspend fun applyQueue(queue: QueueState?) = delegate.applyQueue(queue)
        override suspend fun purge() = delegate.purge()
    }
}
