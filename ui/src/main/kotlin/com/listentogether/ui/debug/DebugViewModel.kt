package com.listentogether.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.listentogether.audio.AudioArbiter
import com.listentogether.audio.AudioState
import com.listentogether.core.model.ConnectionState
import com.listentogether.core.model.Role
import com.listentogether.core.model.VoiceState
import com.listentogether.sync.SessionStateStore
import com.listentogether.ui.VoiceController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DebugUiState(
    val roomId: String? = null,
    val sessionId: String? = null,
    val revision: Long = 0L,
    val queueRevision: Long = 0L,
    val role: Role? = null,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val voiceState: VoiceState = VoiceState.IDLE,
    val micMuted: Boolean = true,
    val micBlocked: Boolean = false,
    val speakerMuted: Boolean = false,
    val audioState: AudioState = AudioState(),
)

class DebugViewModel(
    private val store: SessionStateStore,
    private val voiceController: VoiceController,
    private val audioArbiter: AudioArbiter,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DebugUiState())
    val uiState: StateFlow<DebugUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            store.state.collect { session ->
                _uiState.value = _uiState.value.copy(
                    roomId = session.room?.roomId,
                    sessionId = session.session?.sessionId,
                    revision = session.revision,
                    queueRevision = session.queue?.queueRevision ?: 0L,
                    role = session.role,
                    connectionState = session.connection,
                    isPlaying = session.playback?.isPlaying == true,
                    positionMs = session.playback?.positionMs ?: 0L,
                )
            }
        }

        viewModelScope.launch {
            voiceController.voiceState.collect { voice ->
                _uiState.value = _uiState.value.copy(voiceState = voice)
            }
        }

        viewModelScope.launch {
            voiceController.micMuted.collect { muted ->
                _uiState.value = _uiState.value.copy(micMuted = muted)
            }
        }

        viewModelScope.launch {
            voiceController.micBlocked.collect { blocked ->
                _uiState.value = _uiState.value.copy(micBlocked = blocked)
            }
        }

        viewModelScope.launch {
            voiceController.speakerMuted.collect { muted ->
                _uiState.value = _uiState.value.copy(speakerMuted = muted)
            }
        }

        viewModelScope.launch {
            audioArbiter.state.collect { audio ->
                _uiState.value = _uiState.value.copy(audioState = audio)
            }
        }
    }
}
