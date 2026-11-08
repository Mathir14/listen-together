package com.listentogether.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.listentogether.core.common.AppResult
import com.listentogether.core.data.SettingsRepository
import com.listentogether.core.model.ConnectionState
import com.listentogether.core.model.Role
import com.listentogether.ui.SessionConnector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    val roomIdInput: String = "",
    val inviteSecretInput: String = "",
    val selectedRole: Role = Role.SPEAKER,
    val lastRoomId: String? = null,
    val lastRole: String? = null,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val isSessionActive: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

class HomeViewModel(
    private val connector: SessionConnector,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            connector.connectionState.collect { conn ->
                _uiState.value = _uiState.value.copy(connectionState = conn)
            }
        }
        viewModelScope.launch {
            connector.isSessionActive.collect { active ->
                _uiState.value = _uiState.value.copy(isSessionActive = active)
            }
        }
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.value = _uiState.value.copy(
                    lastRoomId = settings.lastRoomId,
                    lastRole = settings.lastRole,
                )
            }
        }
    }

    fun onRoomIdChanged(roomId: String) {
        _uiState.value = _uiState.value.copy(roomIdInput = roomId, errorMessage = null)
    }

    fun onInviteSecretChanged(inviteSecret: String) {
        _uiState.value = _uiState.value.copy(inviteSecretInput = inviteSecret, errorMessage = null)
    }

    fun onRoleSelected(role: Role) {
        _uiState.value = _uiState.value.copy(selectedRole = role)
    }

    fun createRoom(onSuccess: (String) -> Unit = {}) {
        val role = _uiState.value.selectedRole
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            when (val result = connector.createRoom(role)) {
                is AppResult.Success -> {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    onSuccess(result.value.roomId)
                }
                is AppResult.Failure -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = result.error.message.ifBlank { "Failed to create room" },
                    )
                }
            }
        }
    }

    fun joinRoom(onSuccess: (String) -> Unit = {}) {
        val roomId = _uiState.value.roomIdInput.trim()
        val inviteSecret = _uiState.value.inviteSecretInput.trim()
        if (roomId.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Please enter a Room ID")
            return
        }
        if (inviteSecret.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Please enter the Invite Secret")
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            when (val result = connector.joinRoom(roomId, inviteSecret)) {
                is AppResult.Success -> {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    onSuccess(result.value.roomId)
                }
                is AppResult.Failure -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = result.error.message.ifBlank { "Failed to join room" },
                    )
                }
            }
        }
    }

    fun rejoinLastRoom(onSuccess: (String) -> Unit = {}) {
        val lastRoom = _uiState.value.lastRoomId ?: return
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            val saved = connector.loadSavedCredentials()
            if (saved != null && saved.roomId == lastRoom) {
                connector.connect(saved, recovery = true)
                _uiState.value = _uiState.value.copy(isLoading = false)
                onSuccess(lastRoom)
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "No saved credentials found for room $lastRoom",
                )
            }
        }
    }
}
