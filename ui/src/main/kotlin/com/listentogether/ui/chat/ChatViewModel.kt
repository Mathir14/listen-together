package com.listentogether.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.listentogether.communication.ChatRepository
import com.listentogether.communication.TtsCoordinator
import com.listentogether.core.model.ChatMessage
import com.listentogether.core.model.ConnectionState
import com.listentogether.core.model.Role
import com.listentogether.core.model.VoiceState
import com.listentogether.sync.SessionStateStore
import com.listentogether.ui.TtsController
import com.listentogether.ui.VoiceController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val role: Role? = null,
    val voiceState: VoiceState = VoiceState.IDLE,
    val micMuted: Boolean = true,
    val micBlocked: Boolean = false,
    val speakerMuted: Boolean = false,
    val ttsEnabled: Boolean = true,
    val ttsSpeaking: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val isSessionActive: Boolean = false,
)

class ChatViewModel(
    private val chat: ChatRepository,
    private val store: SessionStateStore,
    private val tts: TtsCoordinator,
    private val voiceController: VoiceController,
    private val ttsController: TtsController,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            chat.messages.collect { messages ->
                _uiState.value = _uiState.value.copy(messages = messages)
            }
        }

        viewModelScope.launch {
            store.state.collect { session ->
                _uiState.value = _uiState.value.copy(
                    role = session.role,
                    connectionState = session.connection,
                    isSessionActive = session.isSessionActive,
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
            tts.state.collect { ttsState ->
                _uiState.value = _uiState.value.copy(
                    ttsEnabled = ttsState.enabled,
                    ttsSpeaking = ttsState.speaking,
                )
            }
        }
    }

    fun onTextChanged(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isNotBlank()) {
            chat.send(text)
            _uiState.value = _uiState.value.copy(inputText = "")
        }
    }

    fun toggleMic() {
        voiceController.setMicMuted(!_uiState.value.micMuted)
    }

    fun toggleSpeakerMute() {
        voiceController.setSpeakerMuted(!_uiState.value.speakerMuted)
    }

    fun toggleTts() {
        ttsController.setTtsEnabled(!_uiState.value.ttsEnabled)
    }
}
