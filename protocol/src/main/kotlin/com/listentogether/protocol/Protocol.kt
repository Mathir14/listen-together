package com.listentogether.protocol

import com.listentogether.core.model.ChatMessage
import com.listentogether.core.model.PlaybackIssue
import com.listentogether.core.model.PlaybackIssueState
import com.listentogether.core.model.PlaybackSnapshot
import com.listentogether.core.model.QueueItem
import com.listentogether.core.model.QueueState
import com.listentogether.core.model.RepeatMode
import com.listentogether.core.model.Role
import com.listentogether.core.model.RoomView
import com.listentogether.core.model.SessionView
import com.listentogether.core.model.Track
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

const val PROTOCOL_VERSION = 1

@Serializable
enum class FrameKind {
    COMMAND,
    EVENT,
    ACK,
    ERROR,
}

@Serializable
enum class MessageType {
    // client -> server
    HELLO,
    TIME_SYNC,
    PLAY,
    PAUSE,
    SEEK,
    TRACK_SELECT,
    NEXT,
    PREV,
    TRACK_ENDED,
    QUEUE_ADD,
    QUEUE_REMOVE,
    QUEUE_REORDER,
    QUEUE_CLEAR,
    REPEAT_CHANGE,
    SHUFFLE_CHANGE,
    CHAT_SEND,
    VOICE_OFFER,
    VOICE_ANSWER,
    VOICE_ICE,
    PLAYBACK_REPORT,
    LEAVE,
    PING,

    // server -> client
    SNAPSHOT,
    ROOM_STATE,
    SESSION_STARTED,
    SESSION_CLOSED,
    PRESENCE,
    PLAYBACK_STATE,
    QUEUE_STATE,
    CHAT_MESSAGE,
    CHAT_ACK,
    PLAYBACK_ISSUE,
    TIME_SYNC_RESULT,
    ERROR,
    COMMAND_REJECTED,
    PONG,
}

@Serializable
enum class ErrorCode {
    NOT_MEMBER,
    SESSION_MISMATCH,
    STALE_REVISION,
    INVALID_PAYLOAD,
    RATE_LIMITED,
    QUEUE_LIMIT,
    CHAT_LIMIT,
    UNSUPPORTED,
    PROTOCOL_MISMATCH,
    SLOW_CONSUMER,
    DUPLICATE_SESSION,
    INTERNAL,
}

@Serializable
data class Envelope(
    @SerialName("v") val version: Int = PROTOCOL_VERSION,
    val kind: FrameKind,
    val id: String,
    val type: MessageType,
    @SerialName("room_id") val roomId: String = "",
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("sender_id") val senderId: String? = null,
    @SerialName("client_seq") val clientSeq: Long? = null,
    @SerialName("client_ts") val clientTs: Long? = null,
    val revision: Long? = null,
    @SerialName("observed_revision") val observedRevision: Long? = null,
    val payload: JsonObject = JsonObject(emptyMap()),
) {
    val isCompatible: Boolean get() = version == PROTOCOL_VERSION
}

@Serializable
data class HelloPayload(
    val token: String,
    @SerialName("room_id") val roomId: String,
    @SerialName("protocol_version") val protocolVersion: Int = PROTOCOL_VERSION,
    @SerialName("last_session_id") val lastSessionId: String? = null,
    @SerialName("last_revision") val lastRevision: Long? = null,
)

@Serializable
data class TimeSyncPayload(@SerialName("t0") val t0: Long)

@Serializable
data class TimeSyncResultPayload(
    @SerialName("t0") val t0: Long,
    @SerialName("t1") val t1: Long,
    @SerialName("t2") val t2: Long,
)

@Serializable
data class SeekPayload(@SerialName("position_ms") val positionMs: Long)

@Serializable
data class TrackSelectPayload(val track: Track)

@Serializable
data class TrackEndedPayload(
    @SerialName("item_id") val itemId: String,
    @SerialName("position_ms") val positionMs: Long,
)

@Serializable
data class QueueAddPayload(
    val track: Track,
    val index: Int? = null,
)

@Serializable
data class QueueRemovePayload(@SerialName("item_id") val itemId: String)

@Serializable
data class QueueReorderPayload(@SerialName("item_ids") val itemIds: List<String>)

@Serializable
data class RepeatChangePayload(val mode: RepeatMode)

@Serializable
data class ShuffleChangePayload(val enabled: Boolean)

@Serializable
data class ChatSendPayload(
    @SerialName("client_message_id") val clientMessageId: String,
    val text: String,
)

@Serializable
data class ChatAckPayload(
    @SerialName("client_message_id") val clientMessageId: String,
    @SerialName("chat_sequence") val chatSequence: Long,
)

@Serializable
data class VoiceSdpPayload(val sdp: String)

@Serializable
data class VoiceIcePayload(
    @SerialName("sdp_mid") val sdpMid: String? = null,
    @SerialName("sdp_mline_index") val sdpMLineIndex: Int = 0,
    val candidate: String,
)

@Serializable
data class PlaybackReportPayload(
    @SerialName("track_id") val trackId: String,
    val state: PlaybackIssueState,
    val detail: String? = null,
)

@Serializable
data class LeavePayload(val reason: String = "USER")

@Serializable
data class SnapshotPayload(
    val room: RoomView,
    val session: SessionView? = null,
    val playback: PlaybackSnapshot? = null,
    val queue: QueueState? = null,
    val chat: List<ChatMessage> = emptyList(),
    @SerialName("your_role") val yourRole: Role,
    @SerialName("server_time") val serverTime: Long,
    val revision: Long = 0,
)

@Serializable
data class RoomStatePayload(val room: RoomView)

@Serializable
data class SessionStartedPayload(
    val session: SessionView,
    val playback: PlaybackSnapshot,
    val queue: QueueState,
)

@Serializable
data class SessionClosedPayload(
    @SerialName("session_id") val sessionId: String,
    val reason: String,
    @SerialName("closed_at") val closedAt: Long,
)

@Serializable
data class PresencePayload(
    val participants: List<com.listentogether.core.model.ParticipantView>,
    @SerialName("grace_expires_at") val graceExpiresAt: Long? = null,
)

@Serializable
data class PlaybackStatePayload(val playback: PlaybackSnapshot)

@Serializable
data class QueueStatePayload(val queue: QueueState)

@Serializable
data class ChatMessagePayload(val message: ChatMessage)

@Serializable
data class PlaybackIssuePayload(val issue: PlaybackIssue)

@Serializable
data class PongPayload(val echo: String? = null)

@Serializable
data class PingPayload(val echo: String? = null)

@Serializable
data class CreateRoomRequest(val role: Role = Role.SPEAKER)

@Serializable
data class JoinRoomRequest(@SerialName("invite_secret") val inviteSecret: String)

@Serializable
data class ApiError(val code: String, val message: String)

@Serializable
data class ErrorPayload(
    val code: ErrorCode,
    val message: String,
    val retryable: Boolean = false,
)

@Serializable
data class CommandRejectedPayload(
    @SerialName("command_id") val commandId: String,
    val code: ErrorCode,
    val retryable: Boolean = false,
)

object ProtocolJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
        isLenient = false
    }

    fun encode(envelope: Envelope): String = json.encodeToString(Envelope.serializer(), envelope)

    fun decode(text: String): Envelope = json.decodeFromString(Envelope.serializer(), text)

    inline fun <reified T> payload(envelope: Envelope): T = json.decodeFromJsonElement(envelope.payload)

    inline fun <reified T> encodePayload(value: T): JsonObject = json.encodeToJsonElement(value) as JsonObject

    fun decodeOrError(text: String): Result<Envelope> = try {
        Result.success(decode(text))
    } catch (error: SerializationException) {
        Result.failure(error)
    }
}

inline fun <reified T> Envelope.decodePayload(): T = ProtocolJson.payload(this)

inline fun <reified T> commandFrame(
    type: MessageType,
    payload: T,
    roomId: String,
    sessionId: String? = null,
    senderId: String? = null,
    clientSeq: Long? = null,
    clientTs: Long? = null,
    observedRevision: Long? = null,
): Envelope = Envelope(
    kind = FrameKind.COMMAND,
    id = com.listentogether.core.common.Ids.newId(),
    type = type,
    roomId = roomId,
    sessionId = sessionId,
    senderId = senderId,
    clientSeq = clientSeq,
    clientTs = clientTs,
    observedRevision = observedRevision,
    payload = ProtocolJson.encodePayload(payload),
)

inline fun <reified T> eventFrame(
    type: MessageType,
    payload: T,
    roomId: String,
    sessionId: String? = null,
    revision: Long? = null,
): Envelope = Envelope(
    kind = FrameKind.EVENT,
    id = com.listentogether.core.common.Ids.newId(),
    type = type,
    roomId = roomId,
    sessionId = sessionId,
    revision = revision,
    payload = ProtocolJson.encodePayload(payload),
)
