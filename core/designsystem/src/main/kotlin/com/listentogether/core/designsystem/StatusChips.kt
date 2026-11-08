package com.listentogether.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.listentogether.core.model.Role
import com.listentogether.core.model.VoiceState

@Composable
fun RoleChip(
    role: Role?,
    modifier: Modifier = Modifier,
) {
    if (role == null) return
    val (label, bg) = when (role) {
        Role.SPEAKER -> "Speaker" to MaterialTheme.colorScheme.primaryContainer
        Role.LISTENER -> "Listener" to MaterialTheme.colorScheme.secondaryContainer
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
fun VoiceStatusChip(
    voiceState: VoiceState,
    micMuted: Boolean,
    role: Role?,
    modifier: Modifier = Modifier,
) {
    val (label, bg, textColor) = when {
        voiceState == VoiceState.CONNECTED && !micMuted && role == Role.SPEAKER ->
            Triple("Mic Live", AccentGreen, Color.Black)
        voiceState == VoiceState.CONNECTED && micMuted && role == Role.SPEAKER ->
            Triple("Mic Muted", AccentOrange, Color.Black)
        voiceState == VoiceState.CONNECTED ->
            Triple("Voice Connected", AccentGreen, Color.Black)
        voiceState == VoiceState.CONNECTING ->
            Triple("Voice Connecting", AccentOrange, Color.Black)
        voiceState == VoiceState.RECONNECTING ->
            Triple("Voice Reconnecting", AccentOrange, Color.Black)
        voiceState == VoiceState.FAILED ->
            Triple("Voice Failed", AccentRed, Color.White)
        else ->
            Triple("Voice Idle", Color.Gray.copy(alpha = 0.3f), Color.White)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
        )
    }
}
