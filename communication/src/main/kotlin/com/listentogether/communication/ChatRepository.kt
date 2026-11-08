package com.listentogether.communication

import com.listentogether.core.common.AppError
import com.listentogether.core.common.AppResult
import com.listentogether.core.common.Ids
import com.listentogether.core.model.ChatMessage
import com.listentogether.core.model.SessionStatus
import com.listentogether.sync.CommandPayload
import com.listentogether.sync.SessionCommand
import com.listentogether.sync.SessionEvent
import com.listentogether.sync.SessionStateStore
import com.listentogether.sync.SessionTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Session-scoped chat (architecture §9). Messages are only ever held in memory. Sends are
 * at-least-once: the same `client_message_id` is retried up to [maxAttempts] times while the
 * active session is unchanged, and the server dedupes them.
 */
class ChatRepository(
    private val transport: SessionTransport,
    private val stateStore: SessionStateStore,
    private val scope: CoroutineScope,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
    private val maxAttempts: Int = MAX_ATTEMPTS,
) : AutoCloseable {
    data class PendingMessage(
        val clientMessageId: String,
        val sessionId: String,
        val text: String,
        val attempts: Int = 0,
    )

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _pending = MutableStateFlow<List<PendingMessage>>(emptyList())
    val pending: StateFlow<List<PendingMessage>> = _pending.asStateFlow()

    private val _lastError = MutableStateFlow<AppError?>(null)
    val lastError: StateFlow<AppError?> = _lastError.asStateFlow()

    private val lock = Any()
    private val pendingById = LinkedHashMap<String, PendingMessage>()
    private val retryJobs = mutableMapOf<String, Job>()
    private val jobs = mutableListOf<Job>()

    private var activeSessionId: String? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        jobs += scope.launch {
            stateStore.state.collect { state ->
                val sorted = state.chat
                    .distinctBy(ChatMessage::messageId)
                    .sortedBy(ChatMessage::sequence)
                    .takeLast(MAX_MESSAGES)
                _messages.value = sorted
                val sessionId = state.session?.sessionId
                if (sessionId != activeSessionId) {
                    activeSessionId = sessionId
                    clearPending(AppError.invalid("The session ended before the message was sent"))
                }
            }
        }
        jobs += scope.launch {
            transport.events.collect { event ->
                if (event is SessionEvent.ChatAcknowledged) {
                    acknowledge(event.clientMessageId)
                }
            }
        }
    }

    /**
     * Sends [text] immediately. Retries happen in the background with the same
     * `client_message_id`, so a re-send can never create a duplicate on the server.
     */
    fun send(text: String): AppResult<Unit> {
        val clean = text.trim()
        if (clean.isEmpty()) return AppResult.Failure(AppError.invalid("Message is empty"))
        if (clean.length > MAX_MESSAGE_CHARS) {
            return AppResult.Failure(AppError.invalid("Message is too long"))
        }
        val state = stateStore.state.value
        val session = state.session
        if (session == null || session.status != SessionStatus.ACTIVE) {
            return AppResult.Failure(AppError.invalid("There is no active session"))
        }
        val message = PendingMessage(Ids.newId(), session.sessionId, clean)
        synchronized(lock) {
            pendingById[message.clientMessageId] = message
            publishPending()
        }
        _lastError.value = null
        attempt(message.clientMessageId)
        return AppResult.Success(Unit)
    }

    private fun attempt(clientMessageId: String) {
        val message = synchronized(lock) { pendingById[clientMessageId] } ?: return
        val state = stateStore.state.value
        if (state.session?.sessionId != message.sessionId) {
            drop(clientMessageId, AppError.invalid("The session ended before the message was sent"))
            return
        }
        val next = message.copy(attempts = message.attempts + 1)
        synchronized(lock) {
            if (pendingById[clientMessageId] == null) return
            pendingById[clientMessageId] = next
            retryJobs.remove(clientMessageId)?.cancel()
            publishPending()
        }
        val result = transport.send(
            SessionCommand(payload = CommandPayload.ChatSend(next.clientMessageId, next.text)),
            sessionId = next.sessionId,
        )
        if (!result.accepted) {
            _lastError.value = AppError.network("Chat transport is not connected")
        }
        if (next.attempts >= maxAttempts) {
            drop(clientMessageId, AppError("CHAT_DELIVERY_FAILED", "Message could not be delivered", retryable = true))
            return
        }
        scheduleRetry(clientMessageId)
    }

    private fun scheduleRetry(clientMessageId: String) {
        val job = scope.launch {
            delay(retryDelayMs)
            attempt(clientMessageId)
        }
        synchronized(lock) {
            if (pendingById[clientMessageId] == null) {
                job.cancel()
            } else {
                retryJobs[clientMessageId] = job
            }
        }
    }

    private fun acknowledge(clientMessageId: String) {
        synchronized(lock) {
            if (pendingById.remove(clientMessageId) != null) {
                retryJobs.remove(clientMessageId)?.cancel()
                publishPending()
            }
        }
    }

    private fun drop(clientMessageId: String, error: AppError) {
        synchronized(lock) {
            if (pendingById.remove(clientMessageId) != null) {
                retryJobs.remove(clientMessageId)?.cancel()
                publishPending()
            }
        }
        _lastError.value = error
    }

    private fun clearPending(error: AppError?) {
        synchronized(lock) {
            val hadPending = pendingById.isNotEmpty()
            pendingById.clear()
            retryJobs.values.forEach(Job::cancel)
            retryJobs.clear()
            publishPending()
            if (hadPending && error != null) _lastError.value = error
        }
    }

    private fun publishPending() {
        _pending.value = pendingById.values.toList()
    }

    override fun close() {
        jobs.forEach(Job::cancel)
        jobs.clear()
        synchronized(lock) {
            retryJobs.values.forEach(Job::cancel)
            retryJobs.clear()
            pendingById.clear()
            publishPending()
        }
        started = false
    }

    companion object {
        const val MAX_MESSAGES = 500
        const val MAX_MESSAGE_CHARS = 1_000
        const val RETRY_DELAY_MS = 1_000L
        const val MAX_ATTEMPTS = 3
    }
}
