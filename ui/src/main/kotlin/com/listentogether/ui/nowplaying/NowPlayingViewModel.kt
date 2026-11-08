package com.listentogether.ui.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.listentogether.core.model.ConnectionState
import com.listentogether.core.model.RepeatMode
import com.listentogether.core.model.Role
import com.listentogether.core.model.Track
import com.listentogether.core.model.VoiceState
import com.listentogether.sync.SessionStateStore
import com.listentogether.sync.SyncCommands
import com.listentogether.ui.VoiceController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class NowPlayingUiState(
    val track: Track? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffleEnabled: Boolean = false,
    val role: Role? = null,
    val voiceState: VoiceState = VoiceState.IDLE,
    val micMuted: Boolean = true,
    val micBlocked: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val isSessionActive: Boolean = false,
    val syncRevision: Long = 0L,
)

class NowPlayingViewModel(
    private val store: SessionStateStore,
    private val commands: SyncCommands,
    private val voiceController: VoiceController,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NowPlayingUiState())
    val uiState: StateFlow<NowPlayingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            store.state.collect { session ->
                val playback = session.playback
                _uiState.value = _uiState.value.copy(
                    track = playback?.track,
                    isPlaying = playback?.isPlaying == true,
                    durationMs = playback?.track?.durationMs ?: 0L,
                    repeatMode = playback?.repeatMode ?: RepeatMode.OFF,
                    shuffleEnabled = playback?.shuffleEnabled ?: false,
                    role = session.role,
                    connectionState = session.connection,
                    isSessionActive = session.isSessionActive,
                    syncRevision = session.revision,
                )
                updatePosition()
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
    }

    fun updatePosition() {
        val playback = store.state.value.playback ?: return
        val currentNow = store.serverNowMs()
        val projected = playback.projectedPositionMs(currentNow)
        _uiState.value = _uiState.value.copy(positionMs = projected)
    }

    fun play() = commands.play()
    fun pause() = commands.pause()
    fun togglePlayPause() {
        if (_uiState.value.isPlaying) pause() else play()
    }

    fun seekTo(positionMs: Long) {
        commands.seek(positionMs)
        _uiState.value = _uiState.value.copy(positionMs = positionMs)
    }

    fun next() = commands.next()
    fun previous() = commands.previous()

    fun toggleRepeat() {
        val next = when (_uiState.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        commands.setRepeat(next)
    }

    fun toggleShuffle() {
        commands.setShuffle(!_uiState.value.shuffleEnabled)
    }

    fun toggleMic() {
        voiceController.setMicMuted(!_uiState.value.micMuted)
    }
}
