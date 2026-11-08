package com.listentogether.sync

import com.listentogether.core.common.AppError
import com.listentogether.core.model.ChatMessage
import com.listentogether.core.model.ConnectionState
import com.listentogether.core.model.PlaybackIssue
import com.listentogether.core.model.PlaybackSnapshot
import com.listentogether.core.model.QueueState
import com.listentogether.core.model.Role
import com.listentogether.core.model.RoomView
import com.listentogether.core.model.SessionStatus
import com.listentogether.core.model.SessionView
import com.listentogether.core.model.Track
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the UI and capability modules need to render one shared session. Authoritative
 * fields are only written by [SessionEngine] after server frames arrive; optimistic overlays are
 * marked by [isOptimistic] and always revert to the last authoritative values.
 */
data class SessionState(
    val room: RoomView? = null,
    val session: SessionView? = null,
    val playback: PlaybackSnapshot? = null,
    val queue: QueueState? = null,
    val chat: List<ChatMessage> = emptyList(),
    val role: Role? = null,
    val revision: Long = 0,
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val lastError: AppError? = null,
    val sessionClosedReason: String? = null,
    val pendingCommandIds: Set<String> = emptySet(),
    val issues: Map<String, PlaybackIssue> = emptyMap(),
    val isOptimistic: Boolean = false,
    val hasSnapshot: Boolean = false,
) {
    val isSessionActive: Boolean get() = session?.status == SessionStatus.ACTIVE
    val isWaitingRoom: Boolean get() = room != null && session == null
    val currentTrack: Track? get() = playback?.track
    val currentQueueItemId: String? get() = playback?.currentItemId ?: queue?.currentItemId

    companion object {
        val Empty = SessionState()
    }
}

interface SessionStateStore {
    val state: StateFlow<SessionState>

    /** Monotonic-estimated server epoch milliseconds (offset-corrected). */
    fun serverNowMs(): Long
}

/** Explicit origin so a remote application can never emit a command (INV-4). */
enum class Origin {
    LOCAL_USER,
    REMOTE,
    SYSTEM,
}
