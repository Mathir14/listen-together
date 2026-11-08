package com.listentogether.ui

import com.listentogether.core.common.AppResult
import com.listentogether.core.model.ConnectionState
import com.listentogether.core.model.Role
import com.listentogether.core.model.RoomCredentials
import com.listentogether.core.model.VoiceState
import kotlinx.coroutines.flow.StateFlow

interface SessionConnector {
    val connectionState: StateFlow<ConnectionState>
    val isSessionActive: StateFlow<Boolean>
    fun connect(credentials: RoomCredentials, recovery: Boolean = false)

    suspend fun createRoom(role: Role): AppResult<RoomCredentials>
    suspend fun joinRoom(roomId: String, inviteSecret: String): AppResult<RoomCredentials>
    suspend fun loadSavedCredentials(): RoomCredentials?
}

interface VoiceController {
    val voiceState: StateFlow<VoiceState>
    val micMuted: StateFlow<Boolean>
    val micBlocked: StateFlow<Boolean>
    val speakerMuted: StateFlow<Boolean>
    fun setMicMuted(muted: Boolean)
    fun setSpeakerMuted(muted: Boolean)
}

interface TtsController {
    fun setTtsEnabled(enabled: Boolean)
}
