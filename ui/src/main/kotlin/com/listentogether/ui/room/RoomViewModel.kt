package com.listentogether.ui.room

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.listentogether.core.data.SettingsRepository
import com.listentogether.core.model.ParticipantView
import com.listentogether.core.model.Role
import com.listentogether.core.model.RoomCredentials
import com.listentogether.sync.SessionStateStore
import com.listentogether.sync.SyncCommands
import com.listentogether.ui.TtsController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RoomUiState(
    val roomId: String? = null,
    val sessionId: String? = null,
    val role: Role? = null,
    val inviteSecret: String? = null,
    val participants: List<ParticipantView> = emptyList(),
    val isSessionActive: Boolean = false,
    val ttsEnabled: Boolean = true,
    val ttsSpeechRate: Float = 1.0f,
)

class RoomViewModel(
    private val store: SessionStateStore,
    private val commands: SyncCommands,
    private val settingsRepository: SettingsRepository,
    private val ttsController: TtsController,
    private val loadCredentials: suspend () -> RoomCredentials? = { null },
) : ViewModel() {

    private val _uiState = MutableStateFlow(RoomUiState())
    val uiState: StateFlow<RoomUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            store.state.collect { session ->
                _uiState.value = _uiState.value.copy(
                    roomId = session.room?.roomId,
                    sessionId = session.session?.sessionId,
                    role = session.role,
                    participants = session.room?.participants ?: emptyList(),
                    isSessionActive = session.isSessionActive,
                )
            }
        }

        viewModelScope.launch {
            val creds = loadCredentials()
            if (creds != null) {
                _uiState.value = _uiState.value.copy(
                    inviteSecret = creds.inviteSecret,
                )
            }
        }

        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.value = _uiState.value.copy(
                    ttsEnabled = settings.ttsEnabled,
                    ttsSpeechRate = settings.ttsSpeechRate,
                )
            }
        }
    }

    fun setTtsEnabled(enabled: Boolean) {
        ttsController.setTtsEnabled(enabled)
    }

    fun setTtsSpeechRate(rate: Float) {
        viewModelScope.launch {
            settingsRepository.setTtsSpeechRate(rate)
        }
    }

    fun leaveRoom() {
        commands.leave()
    }
}
