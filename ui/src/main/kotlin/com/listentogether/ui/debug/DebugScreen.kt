package com.listentogether.ui.debug

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DebugScreen(
    viewModel: DebugViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "Synchronization Diagnostics",
            style = MaterialTheme.typography.headlineMedium,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Protocol & Session", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Room ID: ${state.roomId ?: "none"}")
                Text("Session ID: ${state.sessionId ?: "none"}")
                Text("Connection: ${state.connectionState}")
                Text("Role: ${state.role ?: "none"}")
                Text("Authoritative Revision: ${state.revision}")
                Text("Queue Revision: ${state.queueRevision}")
                Text("Playing: ${state.isPlaying}")
                Text("Position: ${state.positionMs} ms")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Voice & Audio Arbitration", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Voice State: ${state.voiceState}")
                Text("Mic Muted: ${state.micMuted}")
                Text("Mic Blocked: ${state.micBlocked}")
                Text("Speaker Muted: ${state.speakerMuted}")
                Text("Music Volume: ${state.audioState.musicVolume}")
                Text("Voice Active: ${state.audioState.voiceActive}")
                Text("TTS Active: ${state.audioState.ttsActive}")
                Text("Focus Granted: ${state.audioState.focusGranted}")
            }
        }
    }
}
