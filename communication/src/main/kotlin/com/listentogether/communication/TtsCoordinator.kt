package com.listentogether.communication

import com.listentogether.audio.AudioArbiter
import com.listentogether.core.common.AppError
import com.listentogether.core.common.logging.Logger
import com.listentogether.core.common.logging.NoopLogger
import com.listentogether.core.data.AppSettings
import com.listentogether.core.data.SettingsRepository
import com.listentogether.core.data.TtsCursorStore
import com.listentogether.core.model.ChatMessage
import com.listentogether.core.model.Role
import com.listentogether.sync.SessionStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TtsState(
    val enabled: Boolean = true,
    val speaking: Boolean = false,
    val queued: Int = 0,
    val lastError: AppError? = null,
)

/**
 * Automatic listener-chat TTS for the speaker slot (architecture §10). One FIFO utterance at a
 * time, deduped by message id, cursor persisted per session, gated behind voice activity, and
 * never logging chat text.
 */
class TtsCoordinator(
    private val chat: ChatRepository,
    private val announcer: Announcer,
    private val audio: AudioArbiter,
    private val stateStore: SessionStateStore,
    private val settingsRepository: SettingsRepository,
    private val cursorStore: TtsCursorStore,
    private val scope: CoroutineScope,
    private val logger: Logger = NoopLogger,
) : AutoCloseable {
    private val _settings = MutableStateFlow(AppSettings())
    private val _lastError = MutableStateFlow<AppError?>(null)
    private val _queued = MutableStateFlow(0)

    val state: StateFlow<TtsState> = combine(
        _settings,
        announcer.speaking,
        _queued,
        _lastError,
    ) { settings, speaking, queued, error ->
        TtsState(settings.ttsEnabled, speaking, queued, error)
    }.stateIn(scope, SharingStarted.Eagerly, TtsState())

    private val queue = ArrayDeque<ChatMessage>()
    private val queueLock = Any()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val seenMessageIds = LinkedHashSet<String>()
    private val jobs = mutableListOf<Job>()

    private var activeSessionId: String? = null
    private var cursor: Long = 0
    private var started = false
    private var userPaused = false
    private var pausedForVoice = false

    fun start() {
        if (started) return
        started = true
        jobs += scope.launch {
            settingsRepository.settings.collect { settings ->
                val wasEnabled = _settings.value.ttsEnabled
                _settings.value = settings
                if (wasEnabled && !settings.ttsEnabled) onTtsDisabled()
            }
        }
        jobs += scope.launch {
            audio.state.collect { audioState ->
                if (audioState.voiceActive && announcer.speaking.value) {
                    announcer.pause()
                    pausedForVoice = true
                } else if (!audioState.voiceActive && audioState.ttsGateOpen && pausedForVoice && !userPaused) {
                    pausedForVoice = false
                    announcer.resume()
                }
            }
        }
        jobs += scope.launch {
            combine(chat.messages, stateStore.state) { messages, state -> messages to state }
                .collect { (messages, sessionState) ->
                    val sessionId = sessionState.session?.sessionId
                    if (sessionId != activeSessionId) onSessionChanged(sessionId)
                    val role = sessionState.role
                    if (role != Role.SPEAKER) return@collect
                    val rolesById = sessionState.room?.participants
                        ?.associate { it.userId to it.role }
                        .orEmpty()
                    messages.forEach { message ->
                        if (message.sessionId != activeSessionId) return@forEach
                        if (rolesById[message.senderId] != Role.LISTENER) return@forEach
                        if (!seenMessageIds.add(message.messageId)) return@forEach
                        if (message.sequence <= cursor) return@forEach
                        if (!_settings.value.ttsEnabled) {
                            markHandled(message)
                        } else {
                            enqueue(message)
                        }
                    }
                }
        }
        jobs += scope.launch { workerLoop() }
    }

    private suspend fun onSessionChanged(sessionId: String?) {
        val previousSessionId = activeSessionId
        activeSessionId = sessionId
        synchronized(queueLock) { queue.clear() }
        updateQueued()
        seenMessageIds.clear()
        announcer.stop()
        userPaused = false
        pausedForVoice = false
        _lastError.value = null
        cursor = when {
            sessionChanged(previousSessionId, sessionId) -> {
                cursorStore.purge()
                if (sessionId == null) 0 else cursorStore.lastSpokenSequence(sessionId)
            }
            sessionId == null -> 0
            else -> cursorStore.lastSpokenSequence(sessionId)
        }
        signal.trySend(Unit)
    }

    private fun sessionChanged(previousSessionId: String?, sessionId: String?): Boolean =
        previousSessionId != null && previousSessionId != sessionId

    private fun enqueue(message: ChatMessage) {
        synchronized(queueLock) { queue.addLast(message) }
        updateQueued()
        signal.trySend(Unit)
    }

    private suspend fun workerLoop() {
        for (ignored in signal) {
            while (true) {
                val message = synchronized(queueLock) { queue.removeFirstOrNull() } ?: break
                updateQueued()
                if (message.sessionId != activeSessionId) continue
                if (!_settings.value.ttsEnabled) {
                    markHandled(message)
                    continue
                }
                awaitVoiceGate()
                if (message.sessionId != activeSessionId) continue
                if (!_settings.value.ttsEnabled) {
                    markHandled(message)
                    continue
                }
                audio.setTtsActive(true)
                val result = announcer.speak(
                    SpeechRequest(
                        messageId = message.messageId,
                        sessionId = message.sessionId,
                        sequence = message.sequence,
                        text = message.content,
                        speechRate = _settings.value.ttsSpeechRate,
                    ),
                )
                audio.setTtsActive(false)
                when (result) {
                    SpeechResult.Completed -> markHandled(message)
                    SpeechResult.Stopped -> Unit
                    is SpeechResult.Failed -> {
                        // Missing engine/language or a hard engine failure: surface once and
                        // disable TTS automatically so the queue cannot spin.
                        _lastError.value = result.error
                        logger.event(
                            "tts.failed",
                            mapOf("code" to result.error.code, "sequence" to message.sequence),
                        )
                        markHandled(message)
                        settingsRepository.setTtsEnabled(false)
                    }
                }
            }
        }
    }

    private suspend fun awaitVoiceGate() {
        audio.state.first { it.ttsGateOpen }
    }

    private suspend fun markHandled(message: ChatMessage) {
        if (message.sessionId != activeSessionId) return
        if (message.sequence > cursor) {
            cursor = message.sequence
            cursorStore.markSpoken(message.sessionId, message.sequence)
        }
    }

    private fun onTtsDisabled() {
        val dropped = synchronized(queueLock) {
            val copy = queue.toList()
            queue.clear()
            copy
        }
        updateQueued()
        announcer.stop()
        userPaused = false
        pausedForVoice = false
        scope.launch {
            dropped.forEach { markHandled(it) }
            signal.trySend(Unit)
        }
    }

    fun pause() {
        userPaused = true
        announcer.pause()
    }

    fun resume() {
        userPaused = false
        announcer.resume()
    }

    fun stop() {
        userPaused = false
        synchronized(queueLock) { queue.clear() }
        updateQueued()
        announcer.stop()
    }

    private fun updateQueued() {
        _queued.value = synchronized(queueLock) { queue.size }
    }

    override fun close() {
        jobs.forEach(Job::cancel)
        jobs.clear()
        announcer.release()
        synchronized(queueLock) { queue.clear() }
        updateQueued()
        started = false
    }

    companion object {
        const val VOICE_IDLE_GATE_MS = 600L
    }
}
