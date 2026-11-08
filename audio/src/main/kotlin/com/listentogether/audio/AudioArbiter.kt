package com.listentogether.audio

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One policy object so ducking levels are never scattered (FR-11, T14). */
data class AudioPolicy(
    val idleMusicVolume: Float = 1.0f,
    val voiceMusicVolume: Float = 0.25f,
    val ttsMusicVolume: Float = 0.35f,
    val voiceIdleGateMs: Long = 600L,
)

enum class ExternalFocusChange {
    GAIN,
    LOSS,
    LOSS_TRANSIENT,
    LOSS_TRANSIENT_CAN_DUCK,
}

sealed interface AudioEvent {
    /** Call on external transient loss (e.g. phone call): pause locally and send PAUSE. */
    data object TransientFocusLoss : AudioEvent

    /** Permanent loss: pause but wait for an explicit user resume. */
    data object PermanentFocusLoss : AudioEvent

    data object FocusGained : AudioEvent
}

data class AudioState(
    val musicActive: Boolean = false,
    val voiceActive: Boolean = false,
    val ttsActive: Boolean = false,
    val musicVolume: Float = 1.0f,
    val focusGranted: Boolean = false,
    val communicationRoute: Boolean = false,
    val ttsGateOpen: Boolean = true,
) {
    val ducked: Boolean get() = musicVolume < 1.0f
}

/** Boundary around `AudioManager` focus so arbitration is testable without hardware. */
interface FocusController {
    fun requestFocus(): Boolean
    fun abandonFocus()
}

/** Boundary around audio mode/routing (speaker, Bluetooth, communication mode). */
interface RouteController {
    fun enterCommunicationMode()
    fun exitCommunicationMode()
}

class AudioArbiter(
    private val focusController: FocusController,
    private val routeController: RouteController,
    private val policy: AudioPolicy = AudioPolicy(),
    private var volumeSink: (Float) -> Unit = {},
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    private val _state = MutableStateFlow(AudioState(musicVolume = policy.idleMusicVolume))
    val state: StateFlow<AudioState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<AudioEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<AudioEvent> = _events.asSharedFlow()

    private var voiceIdleJob: Job? = null

    fun setVolumeSink(sink: (Float) -> Unit) = apply { this.volumeSink = sink }

    fun setMusicActive(active: Boolean) {
        val current = _state.value
        if (current.musicActive == active) return
        if (active) {
            val granted = focusController.requestFocus()
            update(current.copy(musicActive = true, focusGranted = granted))
        } else {
            if (current.focusGranted) focusController.abandonFocus()
            update(current.copy(musicActive = false, focusGranted = false))
        }
    }

    fun setVoiceActive(active: Boolean) {
        val current = _state.value
        if (current.voiceActive == active) return
        if (active) {
            voiceIdleJob?.cancel()
            voiceIdleJob = null
            routeController.enterCommunicationMode()
            update(current.copy(voiceActive = true, communicationRoute = true, ttsGateOpen = false))
        } else {
            routeController.exitCommunicationMode()
            update(current.copy(voiceActive = false, communicationRoute = false))
            startVoiceIdleGate()
        }
    }

    fun setTtsActive(active: Boolean) {
        val current = _state.value
        if (current.ttsActive == active) return
        update(current.copy(ttsActive = active))
    }

    fun onFocusChange(change: ExternalFocusChange) {
        when (change) {
            ExternalFocusChange.GAIN -> {
                update(_state.value.copy(focusGranted = true))
                _events.tryEmit(AudioEvent.FocusGained)
            }
            ExternalFocusChange.LOSS -> {
                update(_state.value.copy(focusGranted = false))
                _events.tryEmit(AudioEvent.PermanentFocusLoss)
            }
            ExternalFocusChange.LOSS_TRANSIENT,
            ExternalFocusChange.LOSS_TRANSIENT_CAN_DUCK -> {
                update(_state.value.copy(focusGranted = false))
                _events.tryEmit(AudioEvent.TransientFocusLoss)
            }
        }
    }

    fun release() {
        voiceIdleJob?.cancel()
        voiceIdleJob = null
        if (_state.value.focusGranted) focusController.abandonFocus()
        if (_state.value.communicationRoute) routeController.exitCommunicationMode()
        update(_state.value.copy(musicActive = false, voiceActive = false, ttsActive = false, focusGranted = false, communicationRoute = false, ttsGateOpen = true))
    }

    private fun startVoiceIdleGate() {
        voiceIdleJob?.cancel()
        update(_state.value.copy(ttsGateOpen = false))
        voiceIdleJob = scope.launch {
            delay(policy.voiceIdleGateMs)
            update(_state.value.copy(ttsGateOpen = true))
        }
    }

    private fun update(next: AudioState) {
        val volume = when {
            next.voiceActive -> policy.voiceMusicVolume
            next.ttsActive -> policy.ttsMusicVolume
            else -> policy.idleMusicVolume
        }
        val adjusted = next.copy(musicVolume = volume)
        if (adjusted != _state.value) {
            if (volume != _state.value.musicVolume) volumeSink(volume)
            _state.value = adjusted
        }
    }
}
