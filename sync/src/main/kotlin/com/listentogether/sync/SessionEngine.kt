package com.listentogether.sync

import com.listentogether.core.common.AppError
import com.listentogether.core.common.logging.Logger
import com.listentogether.core.common.logging.NoopLogger
import com.listentogether.core.model.ChatMessage
import com.listentogether.core.model.ConnectionState
import com.listentogether.core.model.PlaybackIssue
import com.listentogether.core.model.PlaybackIssueState
import com.listentogether.core.model.PlaybackSnapshot
import com.listentogether.core.model.QueueState
import com.listentogether.core.model.RepeatMode
import com.listentogether.core.model.RoomStatus
import com.listentogether.core.model.RoomView
import com.listentogether.core.model.SessionStatus
import com.listentogether.core.model.SessionView
import com.listentogether.core.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MutableSessionStateStore(
    private val nowProvider: () -> Long = { 0L },
) : SessionStateStore {
    private val _state = MutableStateFlow(SessionState.Empty)
    override val state: StateFlow<SessionState> = _state.asStateFlow()
    val current: SessionState get() = _state.value

    override fun serverNowMs(): Long = nowProvider()

    internal fun set(state: SessionState) {
        _state.value = state
    }

    internal fun update(transform: (SessionState) -> SessionState) {
        _state.update(transform)
    }
}

/**
 * Server-authoritative session reducer and command facade. All state mutations are serialized by
 * one mutex: frames are applied in arrival order, optimistic local changes are tagged, and every
 * rejection restores the last authoritative snapshot (ADR-017).
 */
class SessionEngine(
    private val transport: SessionTransport,
    private val probe: LocalPlaybackProbe,
    private val sink: RemoteStateSink,
    private val scope: CoroutineScope,
    private val store: MutableSessionStateStore = MutableSessionStateStore { transport.serverNowMs() },
    private val logger: Logger = NoopLogger,
) : SessionStateStore, SyncCommands, AutoCloseable {

    override val state: StateFlow<SessionState> = store.state
    override fun serverNowMs(): Long = transport.serverNowMs()

    private val mutex = Mutex()
    private val jobs = mutableListOf<Job>()
    private var started = false

    private var lastAppliedRevision = 0L
    private var lastPlaybackRevision = 0L
    private var lastQueueRevision = 0L
    private var authoritativePlayback: PlaybackSnapshot? = null
    private var authoritativeQueue: QueueState? = null
    private var endedReports = mutableSetOf<String>()
    private var issueReports = mutableSetOf<String>()
    private var roomId: String? = null

    fun start() {
        if (started) return
        started = true
        jobs += scope.launch { transport.events.collect { handle(it) } }
        jobs += scope.launch { transport.connectionState.collect { onConnectionChanged(it) } }
        jobs += scope.launch { probe.observation.collect { onObservation(it) } }
    }

    fun connect(
        roomId: String,
        token: String,
        lastSessionId: String? = null,
        lastRevision: Long? = null,
    ) {
        this.roomId = roomId
        store.update { it.copy(sessionClosedReason = null) }
        start()
        transport.connect(
            roomId = roomId,
            token = token,
            lastSessionId = lastSessionId ?: state.value.session?.sessionId,
            lastRevision = lastRevision ?: lastAppliedRevision.takeIf { it > 0 },
        )
    }

    fun disconnect() {
        transport.disconnect()
    }

    override fun close() {
        jobs.forEach(Job::cancel)
        jobs.clear()
        started = false
        transport.disconnect()
    }

    // ---- SyncCommands -------------------------------------------------------

    override fun play() = optimisticPlayback("PLAY") { playback, now ->
        if (playback.isPlaying) null
        else playback.copy(
            isPlaying = true,
            positionMs = playback.projectedPositionMs(now),
            updatedAt = now,
        )
    }

    override fun pause() = optimisticPlayback("PAUSE") { playback, now ->
        if (!playback.isPlaying) null
        else playback.copy(
            isPlaying = false,
            positionMs = playback.projectedPositionMs(now),
            updatedAt = now,
        )
    }

    override fun seek(positionMs: Long) = optimisticPlayback("SEEK") { playback, now ->
        val duration = playback.track?.durationMs ?: 0
        val clamped = if (duration > 0) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
        playback.copy(positionMs = clamped, updatedAt = now)
    }

    override fun selectTrack(track: Track) = optimisticPlayback("TRACK_SELECT") { playback, now ->
        playback.copy(
            track = track,
            isPlaying = true,
            positionMs = 0,
            updatedAt = now,
            repeatMode = playback.repeatMode,
        )
    }

    override fun next() = emitPlaybackCommand("NEXT", CommandPayload.Next)

    override fun previous() = emitPlaybackCommand("PREV", CommandPayload.Previous)

    override fun addToQueue(track: Track, index: Int?) = emitPlaybackCommand(
        "QUEUE_ADD",
        CommandPayload.AddToQueue(track, index),
    )

    override fun removeFromQueue(itemId: String) = emitPlaybackCommand(
        "QUEUE_REMOVE",
        CommandPayload.RemoveFromQueue(itemId),
    )

    override fun reorderQueue(itemIds: List<String>) = emitPlaybackCommand(
        "QUEUE_REORDER",
        CommandPayload.ReorderQueue(itemIds),
    )

    override fun clearQueue() = emitPlaybackCommand("QUEUE_CLEAR", CommandPayload.ClearQueue)

    override fun setRepeat(mode: RepeatMode) = optimisticPlayback("REPEAT_CHANGE") { playback, _ ->
        if (playback.repeatMode == mode) null else playback.copy(repeatMode = mode)
    }

    override fun setShuffle(enabled: Boolean) = optimisticPlayback("SHUFFLE_CHANGE") { playback, _ ->
        if (playback.shuffleEnabled == enabled) null else playback.copy(shuffleEnabled = enabled)
    }

    override fun reportPlaybackIssue(trackId: String, state: PlaybackIssueState, detail: String?) {
        scope.launch {
            mutex.withLock {
                val session = this@SessionEngine.state.value.session ?: return@withLock
                emit(CommandPayload.ReportPlaybackIssue(trackId, state, detail), session.sessionId)
            }
        }
    }

    override fun leave() {
        val command = SessionCommand(payload = CommandPayload.Leave)
        scope.launch {
            mutex.withLock {
                state.value.session?.let { session ->
                    transport.send(command, sessionId = session.sessionId)
                }
                clearSessionLocally("LEFT")
                awaitSink { purge() }
            }
            // Give the outbound frame a moment to flush before closing the socket.
            delay(150)
            transport.disconnect()
        }
    }

    // ---- Command emission ---------------------------------------------------

    private fun optimisticPlayback(
        label: String,
        transform: (PlaybackSnapshot, Long) -> PlaybackSnapshot?,
    ) {
        scope.launch {
            mutex.withLock {
                val current = state.value
                val session = activeSessionOrNull(label) ?: return@withLock
                val base = authoritativePlayback ?: current.playback ?: return@withLock
                val optimistic = transform(base, serverNowMs()) ?: return@withLock
                store.update { it.copy(playback = optimistic, isOptimistic = true) }
                awaitSink {
                    applyPlayback(optimistic, serverNowMs(), Origin.LOCAL_USER)
                }
                emit(
                    when (label) {
                        "PLAY" -> CommandPayload.Play
                        "PAUSE" -> CommandPayload.Pause
                        "SEEK" -> CommandPayload.Seek(optimistic.positionMs)
                        "TRACK_SELECT" -> CommandPayload.SelectTrack(optimistic.track ?: base.track ?: return@withLock)
                        "REPEAT_CHANGE" -> CommandPayload.ChangeRepeat(optimistic.repeatMode)
                        "SHUFFLE_CHANGE" -> CommandPayload.ChangeShuffle(optimistic.shuffleEnabled)
                        else -> return@withLock
                    },
                    session.sessionId,
                )
            }
        }
    }

    private fun emitPlaybackCommand(label: String, payload: CommandPayload) {
        scope.launch {
            mutex.withLock {
                val session = activeSessionOrNull(label) ?: return@withLock
                emit(payload, session.sessionId)
            }
        }
    }

    private fun emit(payload: CommandPayload, sessionId: String) {
        val result = transport.send(SessionCommand(payload = payload), sessionId = sessionId)
        if (result.accepted) {
            store.update { it.copy(pendingCommandIds = it.pendingCommandIds + result.commandId) }
            logger.event("sync.command.sent", mapOf("command" to payload.describe(), "command_id" to result.commandId))
        } else {
            store.update { it.copy(lastError = AppError.network("Not connected to the room")) }
            scope.launch { mutex.withLock { revertOptimistic() } }
        }
    }

    private fun activeSessionOrNull(label: String): SessionView? {
        val state = state.value
        val session = state.session
        if (session == null || session.status != SessionStatus.ACTIVE) {
            store.update { it.copy(lastError = AppError.invalid("There is no active session")) }
            logger.event("sync.command.rejected_locally", mapOf("command" to label, "reason" to "NO_SESSION"))
            return null
        }
        return session
    }

    private fun CommandPayload.describe(): String = when (this) {
        CommandPayload.Play -> "PLAY"
        CommandPayload.Pause -> "PAUSE"
        is CommandPayload.Seek -> "SEEK"
        is CommandPayload.SelectTrack -> "TRACK_SELECT"
        CommandPayload.Next -> "NEXT"
        CommandPayload.Previous -> "PREV"
        is CommandPayload.TrackEnded -> "TRACK_ENDED"
        is CommandPayload.AddToQueue -> "QUEUE_ADD"
        is CommandPayload.RemoveFromQueue -> "QUEUE_REMOVE"
        is CommandPayload.ReorderQueue -> "QUEUE_REORDER"
        CommandPayload.ClearQueue -> "QUEUE_CLEAR"
        is CommandPayload.ChangeRepeat -> "REPEAT_CHANGE"
        is CommandPayload.ChangeShuffle -> "SHUFFLE_CHANGE"
        is CommandPayload.ReportPlaybackIssue -> "PLAYBACK_REPORT"
        is CommandPayload.ChatSend -> "CHAT_SEND"
        CommandPayload.Leave -> "LEAVE"
    }

    // ---- Frame handling -----------------------------------------------------

    private suspend fun handle(event: SessionEvent) {
        mutex.withLock {
            when (event) {
                is SessionEvent.Snapshot -> applySnapshot(event)
                is SessionEvent.RoomState -> store.update { it.copy(room = event.room) }
                is SessionEvent.SessionStarted -> applySessionStarted(event)
                is SessionEvent.SessionClosed -> applySessionClosed(event)
                is SessionEvent.Presence -> applyPresence(event)
                is SessionEvent.PlaybackChanged -> applyPlaybackState(event.playback)
                is SessionEvent.QueueChanged -> applyQueueState(event.queue)
                is SessionEvent.ChatReceived -> applyChat(event.message)
                is SessionEvent.ChatAcknowledged -> Unit
                is SessionEvent.PlaybackIssueReported -> applyIssue(event.issue)
                is SessionEvent.CommandRejected -> applyRejection(event)
                is SessionEvent.ServerError -> store.update { it.copy(lastError = event.error) }
            }
        }
    }

    private suspend fun applySnapshot(event: SessionEvent.Snapshot) {
        val previous = state.value
        val previousSessionId = previous.session?.sessionId
        val incomingSessionId = event.session?.sessionId
        val sessionChanged = previousSessionId != null && previousSessionId != incomingSessionId
        val ended = previousSessionId != null && incomingSessionId == null
        val stale = incomingSessionId != null &&
            incomingSessionId == previousSessionId &&
            event.revision < lastAppliedRevision &&
            previous.hasSnapshot

        if (sessionChanged || ended) {
            logger.event("sync.session.changed", mapOf("previous" to previousSessionId, "incoming" to incomingSessionId))
            clearSessionLocally(if (ended) "SESSION_ENDED" else "SESSION_CHANGED")
        }
        if (stale) {
            store.update {
                it.copy(room = event.room, role = event.role, connection = transport.connectionState.value, hasSnapshot = true)
            }
            return
        }

        val revision = maxOf(event.revision, event.playback?.revision ?: 0, event.queue?.queueRevision ?: 0)
        lastAppliedRevision = revision
        lastPlaybackRevision = event.playback?.revision ?: 0
        lastQueueRevision = event.queue?.queueRevision ?: 0
        authoritativePlayback = event.playback
        authoritativeQueue = event.queue
        endedReports.clear()
        issueReports.clear()

        store.update {
            it.copy(
                room = event.room,
                session = event.session,
                playback = event.playback,
                queue = event.queue,
                chat = event.chat.sortedBy(ChatMessage::sequence),
                role = event.role,
                revision = revision,
                pendingCommandIds = emptySet(),
                isOptimistic = false,
                sessionClosedReason = if (event.session == null) it.sessionClosedReason else null,
                hasSnapshot = true,
                issues = emptyMap(),
            )
        }
        applyAuthoritativeToSink()
        maybeReportEnded()
    }

    private suspend fun applySessionStarted(event: SessionEvent.SessionStarted) {
        val previous = state.value.session?.sessionId
        if (previous != null && previous != event.session.sessionId) {
            clearSessionLocally("SESSION_CHANGED")
        }
        lastAppliedRevision = maxOf(event.playback.revision, event.queue.queueRevision)
        lastPlaybackRevision = event.playback.revision
        lastQueueRevision = event.queue.queueRevision
        authoritativePlayback = event.playback
        authoritativeQueue = event.queue
        endedReports.clear()
        issueReports.clear()
        store.update {
            it.copy(
                session = event.session,
                playback = event.playback,
                queue = event.queue,
                chat = if (previous == event.session.sessionId) it.chat else emptyList(),
                revision = lastAppliedRevision,
                pendingCommandIds = emptySet(),
                isOptimistic = false,
                sessionClosedReason = null,
                issues = emptyMap(),
            )
        }
        applyAuthoritativeToSink()
    }

    private suspend fun applySessionClosed(event: SessionEvent.SessionClosed) {
        val current = state.value.session
        if (current != null && current.sessionId != event.sessionId) return
        logger.event("sync.session.closed", mapOf("session_id" to event.sessionId, "reason" to event.reason))
        clearSessionLocally(event.reason)
        awaitSink { purge() }
        endedReports.clear()
        issueReports.clear()
    }

    private fun applyPresence(event: SessionEvent.Presence) {
        val room = state.value.room ?: return
        store.update { it.copy(room = room.copy(participants = event.participants)) }
    }

    private suspend fun applyPlaybackState(playback: PlaybackSnapshot) {
        val currentSession = state.value.session
        if (currentSession != null && currentSession.sessionId != playback.sessionId) return
        if (playback.revision <= lastPlaybackRevision) return
        lastPlaybackRevision = playback.revision
        lastAppliedRevision = maxOf(lastAppliedRevision, playback.revision)
        authoritativePlayback = playback
        store.update {
            it.copy(
                playback = playback,
                revision = maxOf(it.revision, playback.revision),
                isOptimistic = false,
                pendingCommandIds = emptySet(),
            )
        }
        awaitSink { applyPlayback(playback, serverNowMs(), Origin.REMOTE) }
    }

    private suspend fun applyQueueState(queue: QueueState) {
        if (queue.queueRevision <= lastQueueRevision) return
        lastQueueRevision = queue.queueRevision
        lastAppliedRevision = maxOf(lastAppliedRevision, queue.queueRevision)
        authoritativeQueue = queue
        store.update {
            it.copy(
                queue = queue,
                revision = maxOf(it.revision, queue.queueRevision),
                isOptimistic = false,
                pendingCommandIds = emptySet(),
            )
        }
        awaitSink { applyQueue(queue) }
    }

    private fun applyChat(message: ChatMessage) {
        store.update { current ->
            if (current.chat.any { it.messageId == message.messageId }) current
            else current.copy(chat = (current.chat + message).sortedBy(ChatMessage::sequence).takeLast(MAX_CHAT))
        }
    }

    private fun applyIssue(issue: PlaybackIssue) {
        store.update {
            it.copy(issues = it.issues + ("${issue.participantId}:${issue.trackId}" to issue))
        }
    }

    private suspend fun applyRejection(event: SessionEvent.CommandRejected) {
        logger.event("sync.command.rejected", mapOf("command_id" to event.commandId, "code" to event.error.code))
        store.update {
            it.copy(
                pendingCommandIds = it.pendingCommandIds - event.commandId,
                lastError = event.error,
                isOptimistic = false,
            )
        }
        if (event.error.code == "SESSION_MISMATCH") {
            clearSessionLocally("SESSION_ENDED")
            awaitSink { purge() }
        } else {
            revertOptimistic()
        }
    }

    private suspend fun revertOptimistic() {
        store.update {
            it.copy(
                playback = authoritativePlayback,
                queue = authoritativeQueue,
                isOptimistic = false,
            )
        }
        applyAuthoritativeToSink()
    }

    private suspend fun applyAuthoritativeToSink() {
        val now = serverNowMs()
        awaitSink { applyPlayback(authoritativePlayback, now, Origin.REMOTE) }
        awaitSink { applyQueue(authoritativeQueue) }
    }

    private suspend fun awaitSink(block: suspend RemoteStateSink.() -> Unit) {
        try {
            sink.block()
        } catch (error: Throwable) {
            logger.error("sync.sink.failed", error)
            store.update { it.copy(lastError = AppError("LOCAL_PLAYBACK", "Local playback failed", retryable = true)) }
        }
    }

    private fun clearSessionLocally(reason: String) {
        lastAppliedRevision = 0
        lastPlaybackRevision = 0
        lastQueueRevision = 0
        authoritativePlayback = null
        authoritativeQueue = null
        endedReports.clear()
        issueReports.clear()
        store.update {
            it.copy(
                session = null,
                playback = null,
                queue = null,
                chat = emptyList(),
                revision = 0,
                pendingCommandIds = emptySet(),
                isOptimistic = false,
                issues = emptyMap(),
                sessionClosedReason = reason,
            )
        }
    }

    // ---- Local player observation ------------------------------------------

    private suspend fun onObservation(observation: LocalPlaybackObservation) {
        mutex.withLock {
            if (observation.hasFailed && observation.trackId != null) {
                reportLocalFailure(observation)
            }
            if (observation.hasEnded) {
                maybeReportEnded(observation)
            }
        }
    }

    private suspend fun reportLocalFailure(observation: LocalPlaybackObservation) {
        val session = state.value.session ?: return
        val trackId = observation.trackId ?: return
        val key = "${session.sessionId}:$trackId"
        if (!issueReports.add(key)) return
        val accepted = transport.send(
            SessionCommand(
                payload = CommandPayload.ReportPlaybackIssue(
                    trackId = trackId,
                    state = PlaybackIssueState.FAILED,
                    detail = observation.failureDetail,
                ),
            ),
            sessionId = session.sessionId,
        ).accepted
        if (!accepted) issueReports.remove(key)
    }

    private suspend fun maybeReportEnded(observation: LocalPlaybackObservation = probe.observation.value) {
        if (!observation.hasEnded) return
        val session = state.value.session ?: return
        if (session.status != SessionStatus.ACTIVE) return
        val playback = authoritativePlayback ?: return
        val itemId = playback.currentItemId ?: return
        if (observation.itemId != null && observation.itemId != itemId) return
        val playbackTrack = playback.track
        if (observation.trackId != null && playbackTrack != null &&
            observation.trackId != playbackTrack.providerTrackId
        ) {
            return
        }
        val key = "${session.sessionId}:$itemId:${playback.revision}"
        if (key in endedReports) return
        val accepted = transport.send(
            SessionCommand(
                payload = CommandPayload.TrackEnded(itemId, observation.positionMs),
            ),
            sessionId = session.sessionId,
            observedRevision = playback.revision,
        ).accepted
        if (accepted) endedReports.add(key)
    }

    private fun onConnectionChanged(connection: ConnectionState) {
        val previous = state.value.connection
        store.update { it.copy(connection = connection) }
        if (connection == ConnectionState.RECONNECTING || connection == ConnectionState.DISCONNECTED) {
            // ADR-016: pending playback commands are never replayed. Chat retries are owned by
            // :communication and use the same client_message_id.
            if (state.value.pendingCommandIds.isNotEmpty() || state.value.isOptimistic) {
                scope.launch {
                    mutex.withLock {
                        store.update { it.copy(pendingCommandIds = emptySet(), isOptimistic = false) }
                        revertOptimistic()
                    }
                }
            }
        }
        if (connection == ConnectionState.CONNECTED && previous != ConnectionState.CONNECTED) {
            logger.event("sync.connected", mapOf("room_id" to roomId.orEmpty()))
        }
    }

    companion object {
        private const val MAX_CHAT = 500
    }
}
