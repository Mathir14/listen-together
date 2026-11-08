package com.listentogether.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class Role {
    SPEAKER,
    LISTENER;

    val other: Role get() = if (this == SPEAKER) LISTENER else SPEAKER
}

@Serializable
enum class PresenceStatus {
    CONNECTED,
    DISCONNECTED,
    LEFT,
}

@Serializable
enum class RoomStatus {
    WAITING,
    ACTIVE,
    EMPTY,
    CLOSED,
}

@Serializable
enum class SessionStatus {
    ACTIVE,
    CLOSED,
}

@Serializable
data class ParticipantView(
    @kotlinx.serialization.SerialName("user_id") val userId: String,
    val role: Role,
    val status: PresenceStatus,
    @kotlinx.serialization.SerialName("grace_expires_at") val graceExpiresAt: Long? = null,
)

@Serializable
data class RoomView(
    @kotlinx.serialization.SerialName("room_id") val roomId: String,
    val participants: List<ParticipantView> = emptyList(),
    @kotlinx.serialization.SerialName("active_session_id") val activeSessionId: String? = null,
    val status: RoomStatus = RoomStatus.EMPTY,
)

@Serializable
data class SessionView(
    @kotlinx.serialization.SerialName("session_id") val sessionId: String,
    @kotlinx.serialization.SerialName("room_id") val roomId: String,
    val status: SessionStatus = SessionStatus.ACTIVE,
    @kotlinx.serialization.SerialName("created_at") val createdAt: Long,
    val participants: List<String> = emptyList(),
)

@Serializable
data class Track(
    val provider: String = "youtube_music",
    @kotlinx.serialization.SerialName("provider_track_id") val providerTrackId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    @kotlinx.serialization.SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @kotlinx.serialization.SerialName("duration_ms") val durationMs: Long = 0,
)

@Serializable
data class QueueItem(
    @kotlinx.serialization.SerialName("item_id") val itemId: String,
    val track: Track,
    @kotlinx.serialization.SerialName("added_by") val addedBy: String = "",
)

@Serializable
enum class RepeatMode {
    OFF,
    ONE,
    ALL,
}

@Serializable
data class PlaybackSnapshot(
    @kotlinx.serialization.SerialName("session_id") val sessionId: String,
    val revision: Long = 0,
    @kotlinx.serialization.SerialName("current_item_id") val currentItemId: String? = null,
    val track: Track? = null,
    @kotlinx.serialization.SerialName("is_playing") val isPlaying: Boolean = false,
    @kotlinx.serialization.SerialName("position_ms") val positionMs: Long = 0,
    @kotlinx.serialization.SerialName("updated_at") val updatedAt: Long = 0,
    @kotlinx.serialization.SerialName("repeat_mode") val repeatMode: RepeatMode = RepeatMode.OFF,
    @kotlinx.serialization.SerialName("shuffle_enabled") val shuffleEnabled: Boolean = false,
    @kotlinx.serialization.SerialName("queue_revision") val queueRevision: Long = 0,
) {
    /**
     * Position at [serverNowMs], accounting for elapsed time while playing. Clamped to the
     * track duration when known and never negative.
     */
    fun projectedPositionMs(serverNowMs: Long): Long {
        val elapsed = if (isPlaying) (serverNowMs - updatedAt).coerceAtLeast(0) else 0
        val projected = positionMs + elapsed
        val duration = track?.durationMs ?: 0
        return if (duration > 0) projected.coerceIn(0, duration) else projected.coerceAtLeast(0)
    }
}

@Serializable
data class QueueState(
    val items: List<QueueItem> = emptyList(),
    @kotlinx.serialization.SerialName("current_item_id") val currentItemId: String? = null,
    @kotlinx.serialization.SerialName("queue_revision") val queueRevision: Long = 0,
) {
    fun currentIndex(): Int = items.indexOfFirst { it.itemId == currentItemId }
}

@Serializable
data class ChatMessage(
    @kotlinx.serialization.SerialName("message_id") val messageId: String,
    @kotlinx.serialization.SerialName("session_id") val sessionId: String,
    @kotlinx.serialization.SerialName("sender_id") val senderId: String,
    val content: String,
    @kotlinx.serialization.SerialName("created_at") val createdAt: Long,
    val sequence: Long,
)

@Serializable
enum class PlaybackIssueState {
    DEGRADED,
    FAILED,
}

@Serializable
data class PlaybackIssue(
    @kotlinx.serialization.SerialName("participant_id") val participantId: String,
    @kotlinx.serialization.SerialName("track_id") val trackId: String,
    val state: PlaybackIssueState,
    val detail: String? = null,
)

@Serializable
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED,
}

@Serializable
enum class VoiceState {
    IDLE,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED,
}

@Serializable
enum class PlaybackHealth {
    IDLE,
    BUFFERING,
    READY,
    ENDED,
    FAILED,
}

/**
 * Credentials returned by room creation/join. The participant token is a capability and must be
 * stored only in the Keystore-backed vault.
 */
@Serializable
data class RoomCredentials(
    @kotlinx.serialization.SerialName("room_id") val roomId: String,
    @kotlinx.serialization.SerialName("participant_id") val participantId: String,
    @kotlinx.serialization.SerialName("participant_token") val participantToken: String,
    val role: Role,
    @kotlinx.serialization.SerialName("invite_secret") val inviteSecret: String? = null,
)

@Serializable
data class TurnCredentials(
    val username: String,
    val credential: String,
    @kotlinx.serialization.SerialName("ttl_seconds") val ttlSeconds: Long,
    val urls: List<String>,
)
