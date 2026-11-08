package com.listentogether.backend.domain

import com.listentogether.core.model.ChatMessage
import com.listentogether.core.model.ParticipantView
import com.listentogether.core.model.PlaybackSnapshot
import com.listentogether.core.model.PlaybackIssueState
import com.listentogether.core.model.PresenceStatus
import com.listentogether.core.model.QueueItem
import com.listentogether.core.model.QueueState
import com.listentogether.core.model.RepeatMode
import com.listentogether.core.model.Role
import com.listentogether.core.model.RoomStatus
import com.listentogether.core.model.RoomView
import com.listentogether.core.model.SessionStatus
import com.listentogether.core.model.SessionView
import com.listentogether.core.model.Track
import com.listentogether.protocol.ErrorCode
import kotlin.random.Random

data class MemberSlot(
    val participantId: String,
    val role: Role,
    var presence: PresenceStatus = PresenceStatus.LEFT,
    var graceExpiresAt: Long? = null,
)

sealed interface DomainResult<out T> {
    data class Accepted<T>(val value: T) : DomainResult<T>
    data class Rejected(val code: ErrorCode, val message: String) : DomainResult<Nothing>

    fun getOrNull(): T? = (this as? Accepted)?.value
}

fun <T> accepted(value: T): DomainResult<T> = DomainResult.Accepted(value)
fun rejected(code: ErrorCode, message: String): DomainResult<Nothing> = DomainResult.Rejected(code, message)

data class PresenceChange(
    val room: RoomView,
    val sessionStarted: Boolean = false,
    val closedSessionId: String? = null,
)

data class ChatSendOutcome(
    val message: ChatMessage,
    val duplicate: Boolean,
)

class SessionState(
    val sessionId: String,
    val roomId: String,
    val createdAt: Long,
    internal val newId: () -> String,
    private val queueCap: Int = 200,
    private val chatCap: Int = 500,
) {
    var revision: Long = 0
        private set
    var chatSequence: Long = 0
        private set

    val queue: MutableList<QueueItem> = mutableListOf()
    val chat: ArrayDeque<ChatMessage> = ArrayDeque()
    private val chatDedupe: LinkedHashMap<String, Long> = LinkedHashMap()
    private var preShuffleOrder: List<String>? = null

    var currentItemId: String? = null
        private set
    var track: Track? = null
        private set
    var isPlaying: Boolean = false
        private set
    var positionMs: Long = 0
        private set
    var updatedAt: Long = createdAt
        private set
    var repeatMode: RepeatMode = RepeatMode.OFF
        private set
    var shuffleEnabled: Boolean = false
        private set
    var queueRevision: Long = 0
        private set

    val isActive: Boolean get() = true

    fun toView(): SessionView = SessionView(
        sessionId = sessionId,
        roomId = roomId,
        status = SessionStatus.ACTIVE,
        createdAt = createdAt,
        participants = emptyList(),
    )

    fun snapshot(): PlaybackSnapshot = PlaybackSnapshot(
        sessionId = sessionId,
        revision = revision,
        currentItemId = currentItemId,
        track = track,
        isPlaying = isPlaying,
        positionMs = positionMs,
        updatedAt = updatedAt,
        repeatMode = repeatMode,
        shuffleEnabled = shuffleEnabled,
        queueRevision = queueRevision,
    )

    fun queueState(): QueueState = QueueState(
        items = queue.toList(),
        currentItemId = currentItemId,
        queueRevision = queueRevision,
    )

    private fun bump(now: Long): Long {
        revision += 1
        return revision
    }

    private fun projected(now: Long): Long = snapshot().projectedPositionMs(now)

    fun play(now: Long): DomainResult<Long> {
        if (isPlaying) return accepted(revision)
        positionMs = projected(now)
        isPlaying = true
        updatedAt = now
        return accepted(bump(now))
    }

    fun pause(now: Long): DomainResult<Long> {
        if (!isPlaying) return accepted(revision)
        positionMs = projected(now)
        isPlaying = false
        updatedAt = now
        return accepted(bump(now))
    }

    fun seek(positionMs: Long, now: Long): DomainResult<Long> {
        val duration = track?.durationMs ?: 0
        this.positionMs = if (duration > 0) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
        updatedAt = now
        return accepted(bump(now))
    }

    fun selectTrack(track: Track, now: Long, addedBy: String): DomainResult<Long> {
        val item = QueueItem(itemId = newId(), track = track, addedBy = addedBy)
        val currentIndex = queue.indexOfFirst { it.itemId == currentItemId }
        if (currentIndex >= 0) {
            queue[currentIndex] = item
        } else {
            if (queue.size >= queueCap) return rejected(ErrorCode.QUEUE_LIMIT, "Queue limit reached")
            queue += item
        }
        currentItemId = item.itemId
        this.track = track
        positionMs = 0
        updatedAt = now
        isPlaying = true
        queueRevision = bump(now)
        return accepted(revision)
    }

    fun next(now: Long): DomainResult<Long> {
        if (queue.isEmpty()) return rejected(ErrorCode.INVALID_PAYLOAD, "Queue is empty")
        return accepted(advance(now, automatic = false))
    }

    fun previous(now: Long): DomainResult<Long> {
        if (queue.isEmpty()) return rejected(ErrorCode.INVALID_PAYLOAD, "Queue is empty")
        val currentIndex = queue.indexOfFirst { it.itemId == currentItemId }
        if (currentIndex < 0) return accepted(selectIndex(0, now))
        if (projected(now) > PREVIOUS_RESTART_THRESHOLD_MS) {
            positionMs = 0
            updatedAt = now
            return accepted(bump(now))
        }
        var target = currentIndex - 1
        if (target < 0) {
            target = if (repeatMode == RepeatMode.ALL) queue.lastIndex else 0
        }
        return accepted(selectIndex(target, now))
    }

    fun addToQueue(track: Track, index: Int?, addedBy: String, now: Long): DomainResult<QueueItem> {
        if (queue.size >= queueCap) return rejected(ErrorCode.QUEUE_LIMIT, "Queue limit reached")
        val insertion = index ?: queue.size
        if (insertion !in 0..queue.size) return rejected(ErrorCode.INVALID_PAYLOAD, "Queue index out of range")
        val item = QueueItem(itemId = newId(), track = track, addedBy = addedBy)
        queue.add(insertion, item)
        updatedAt = now
        queueRevision = bump(now)
        return accepted(item)
    }

    fun removeFromQueue(itemId: String, now: Long): DomainResult<Long> {
        val index = queue.indexOfFirst { it.itemId == itemId }
        if (index < 0) return rejected(ErrorCode.INVALID_PAYLOAD, "Unknown queue item")
        val wasCurrent = itemId == currentItemId
        queue.removeAt(index)
        if (queue.isEmpty()) {
            currentItemId = null
            track = null
            positionMs = 0
            isPlaying = false
            updatedAt = now
        } else if (wasCurrent) {
            val target = index.coerceIn(0, queue.lastIndex)
            val item = queue[target]
            currentItemId = item.itemId
            track = item.track
            positionMs = 0
            isPlaying = true
            updatedAt = now
        }
        queueRevision = bump(now)
        return accepted(revision)
    }

    fun reorderQueue(itemIds: List<String>): DomainResult<Long> {
        if (itemIds.size != queue.size || itemIds.toSet() != queue.map { it.itemId }.toSet()) {
            return rejected(ErrorCode.INVALID_PAYLOAD, "Reorder must be a permutation of queue items")
        }
        val byId = queue.associateBy { it.itemId }
        val reordered = itemIds.map { byId.getValue(it) }
        queue.clear()
        queue.addAll(reordered)
        queueRevision = bump(updatedAt)
        return accepted(revision)
    }

    fun clearQueue(now: Long): DomainResult<Long> {
        val current = queue.firstOrNull { it.itemId == currentItemId }
        queue.clear()
        if (current != null) queue += current
        queueRevision = bump(now)
        return accepted(revision)
    }

    fun changeRepeat(mode: RepeatMode): DomainResult<Long> {
        repeatMode = mode
        return accepted(bump(updatedAt))
    }

    fun changeShuffle(enabled: Boolean): DomainResult<Long> {
        if (shuffleEnabled == enabled) return accepted(revision)
        val currentIndex = queue.indexOfFirst { it.itemId == currentItemId }
        if (enabled) {
            preShuffleOrder = queue.map { it.itemId }
            val head = if (currentIndex >= 0) queue.take(currentIndex + 1) else emptyList()
            val tail = if (currentIndex >= 0) queue.drop(currentIndex + 1) else queue.toList()
            val shuffled = tail.shuffled(Random(sessionId.hashCode().toLong() xor revision))
            queue.clear()
            queue.addAll(head)
            queue.addAll(shuffled)
        } else {
            val order = preShuffleOrder
            if (order != null) {
                val byId = queue.associateBy { it.itemId }
                val restored = order.mapNotNull { byId[it] } + queue.filter { it.itemId !in order }
                val headCount = if (currentIndex >= 0) currentIndex + 1 else 0
                val head = restored.take(headCount)
                val tail = restored.drop(headCount)
                queue.clear()
                queue.addAll(head)
                queue.addAll(tail)
            }
            preShuffleOrder = null
        }
        shuffleEnabled = enabled
        queueRevision = bump(updatedAt)
        return accepted(revision)
    }

    fun trackEnded(
        itemId: String,
        observedRevision: Long,
        reportedPositionMs: Long,
        now: Long,
    ): DomainResult<Long> {
        if (observedRevision != revision) {
            return rejected(ErrorCode.STALE_REVISION, "Stale revision")
        }
        if (itemId != currentItemId) {
            return rejected(ErrorCode.STALE_REVISION, "Track already changed")
        }
        val duration = track?.durationMs ?: 0
        val projected = if (duration > 0) projected(now) else reportedPositionMs
        if (duration > 0 && projected < duration - END_TOLERANCE_MS) {
            return rejected(ErrorCode.INVALID_PAYLOAD, "Track has not ended")
        }
        if (repeatMode == RepeatMode.ONE) {
            positionMs = 0
            updatedAt = now
            isPlaying = true
            return accepted(bump(now))
        }
        return accepted(advance(now, automatic = true))
    }

    fun chatSend(
        clientMessageId: String,
        text: String,
        senderId: String,
        now: Long,
    ): DomainResult<ChatSendOutcome> {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_CHAT_LENGTH) {
            return rejected(ErrorCode.INVALID_PAYLOAD, "Message must be 1..$MAX_CHAT_LENGTH characters")
        }
        val existing = chatDedupe[clientMessageId]
        if (existing != null) {
            val message = chat.firstOrNull { it.sequence == existing }
                ?: ChatMessage("$sessionId:$existing", sessionId, senderId, trimmed, now, existing)
            return accepted(ChatSendOutcome(message, duplicate = true))
        }
        chatSequence += 1
        val message = ChatMessage(
            messageId = "$sessionId:$chatSequence",
            sessionId = sessionId,
            senderId = senderId,
            content = trimmed,
            createdAt = now,
            sequence = chatSequence,
        )
        chat.addLast(message)
        chatDedupe[clientMessageId] = chatSequence
        while (chat.size > chatCap) chat.removeFirst()
        while (chatDedupe.size > chatCap) {
            val eldest = chatDedupe.keys.first()
            chatDedupe.remove(eldest)
        }
        return accepted(ChatSendOutcome(message, duplicate = false))
    }

    fun reportPlaybackIssue(
        participantId: String,
        trackId: String,
        state: PlaybackIssueState,
        detail: String?,
    ): com.listentogether.core.model.PlaybackIssue = com.listentogether.core.model.PlaybackIssue(
        participantId = participantId,
        trackId = trackId,
        state = state,
        detail = detail,
    )

    private fun advance(now: Long, automatic: Boolean): Long {
        val currentIndex = queue.indexOfFirst { it.itemId == currentItemId }
        if (currentIndex < 0) {
            return selectIndex(0, now)
        }
        if (automatic && repeatMode == RepeatMode.ONE) {
            positionMs = 0
            updatedAt = now
            isPlaying = true
            return bump(now)
        }
        val nextIndex = currentIndex + 1
        if (nextIndex < queue.size) {
            return selectIndex(nextIndex, now)
        }
        if (repeatMode == RepeatMode.ALL && queue.isNotEmpty()) {
            return selectIndex(0, now)
        }
        currentItemId = null
        track = null
        positionMs = 0
        isPlaying = false
        updatedAt = now
        return bump(now)
    }

    private fun selectIndex(index: Int, now: Long): Long {
        val item = queue[index]
        currentItemId = item.itemId
        track = item.track
        positionMs = 0
        updatedAt = now
        isPlaying = true
        return bump(now)
    }

    companion object {
        const val PREVIOUS_RESTART_THRESHOLD_MS = 3_000L
        const val END_TOLERANCE_MS = 1_500L
        const val MAX_CHAT_LENGTH = 2_000
    }
}

class RoomAggregate(
    val roomId: String,
    val createdAt: Long,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
    private val queueCap: Int = 200,
    private val chatCap: Int = 500,
    private val gracePeriodMs: Long = 60_000,
) {
    val slots: MutableList<MemberSlot> = mutableListOf()
    var activeSession: SessionState? = null
        private set
    var lastClosedAt: Long? = null
        private set

    fun addMember(participantId: String, role: Role): DomainResult<MemberSlot> {
        if (slots.size >= 2) return rejected(ErrorCode.UNSUPPORTED, "Room supports exactly two participants")
        if (slots.any { it.role == role }) return rejected(ErrorCode.INVALID_PAYLOAD, "Role already taken")
        val slot = MemberSlot(participantId = participantId, role = role)
        slots += slot
        return accepted(slot)
    }

    fun participantByRole(role: Role): MemberSlot? = slots.firstOrNull { it.role == role }

    fun participant(participantId: String): MemberSlot? = slots.firstOrNull { it.participantId == participantId }

    fun onPresence(participantId: String, now: Long): DomainResult<PresenceChange> {
        val slot = participant(participantId) ?: return rejected(ErrorCode.NOT_MEMBER, "Unknown participant")
        slot.presence = PresenceStatus.CONNECTED
        slot.graceExpiresAt = null
        val bothPresent = slots.size == 2 && slots.all { it.presence == PresenceStatus.CONNECTED }
        var started = false
        if (bothPresent && activeSession == null) {
            activeSession = SessionState(
                sessionId = newId(),
                roomId = roomId,
                createdAt = now,
                newId = newId,
                queueCap = queueCap,
                chatCap = chatCap,
            )
            started = true
        }
        return accepted(PresenceChange(room = roomView(), sessionStarted = started))
    }

    fun onDisconnect(participantId: String, now: Long): DomainResult<PresenceChange> {
        val slot = participant(participantId) ?: return rejected(ErrorCode.NOT_MEMBER, "Unknown participant")
        if (slot.presence == PresenceStatus.CONNECTED) {
            slot.presence = PresenceStatus.DISCONNECTED
            slot.graceExpiresAt = now + gracePeriodMs
        }
        return accepted(PresenceChange(room = roomView()))
    }

    fun onLeave(participantId: String, now: Long): DomainResult<PresenceChange> {
        val slot = participant(participantId) ?: return rejected(ErrorCode.NOT_MEMBER, "Unknown participant")
        slot.presence = PresenceStatus.LEFT
        slot.graceExpiresAt = null
        val closed = closeIfEmpty(now)
        return accepted(PresenceChange(room = roomView(), closedSessionId = closed))
    }

    fun expireDisconnected(now: Long): DomainResult<List<String>> {
        val expired = slots.filter {
            it.presence == PresenceStatus.DISCONNECTED && (it.graceExpiresAt ?: Long.MAX_VALUE) <= now
        }
        expired.forEach {
            it.presence = PresenceStatus.LEFT
            it.graceExpiresAt = null
        }
        return accepted(expired.map { it.participantId })
    }

    fun expireGrace(now: Long): DomainResult<PresenceChange> {
        val expiry = expireDisconnected(now)
        val expired = expiry.getOrNull().orEmpty()
        if (expired.isEmpty()) return accepted(PresenceChange(room = roomView()))
        val closed = closeIfEmpty(now)
        return accepted(PresenceChange(room = roomView(), closedSessionId = closed))
    }

    fun session(): SessionState? = activeSession

    fun requireSession(): DomainResult<SessionState> =
        activeSession?.let { accepted(it) } ?: rejected(ErrorCode.SESSION_MISMATCH, "No active session")

    fun roomView(): RoomView = RoomView(
        roomId = roomId,
        participants = slots.map {
            ParticipantView(
                userId = it.participantId,
                role = it.role,
                status = it.presence,
                graceExpiresAt = it.graceExpiresAt,
            )
        },
        activeSessionId = activeSession?.sessionId,
        status = when {
            activeSession != null -> RoomStatus.ACTIVE
            slots.any { it.presence == PresenceStatus.CONNECTED } -> RoomStatus.WAITING
            else -> RoomStatus.EMPTY
        },
    )

    private fun closeIfEmpty(now: Long): String? {
        if (activeSession == null) return null
        if (slots.none { it.presence != PresenceStatus.LEFT }) {
            val closedId = activeSession?.sessionId
            activeSession = null
            lastClosedAt = now
            return closedId
        }
        return null
    }
}
