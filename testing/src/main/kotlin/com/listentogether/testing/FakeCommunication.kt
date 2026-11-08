package com.listentogether.testing

import com.listentogether.communication.Announcer
import com.listentogether.communication.SpeechRequest
import com.listentogether.communication.SpeechResult
import com.listentogether.core.common.AppError
import com.listentogether.core.data.AppSettings
import com.listentogether.core.data.SettingsRepository
import com.listentogether.core.data.TtsCursorStore
import com.listentogether.sync.SessionState
import com.listentogether.sync.SessionStateStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Mutable state store for tests that do not need the full SessionEngine reducer. */
class FakeSessionStateStore(
    initial: SessionState = SessionState.Empty,
    private val nowProvider: () -> Long = { 0L },
) : SessionStateStore {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    override fun serverNowMs(): Long = nowProvider()

    fun set(state: SessionState) {
        _state.value = state
    }

    fun update(transform: (SessionState) -> SessionState) {
        _state.value = transform(_state.value)
    }
}

class InMemorySettingsRepository(
    initial: AppSettings = AppSettings(),
    private val nowProvider: () -> Long = { 0L },
) : SettingsRepository {
    private val _settings = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = _settings

    val currentSettings: AppSettings get() = _settings.value

    override suspend fun current(): AppSettings = _settings.value

    override suspend fun setTtsEnabled(enabled: Boolean) {
        _settings.value = _settings.value.copy(ttsEnabled = enabled)
    }

    override suspend fun setTtsSpeechRate(rate: Float) {
        _settings.value = _settings.value.copy(ttsSpeechRate = rate)
    }

    override suspend fun setTtsMuteDuringVoice(mute: Boolean) {
        _settings.value = _settings.value.copy(ttsMuteDuringVoice = mute)
    }

    override suspend fun setMicDefaultOn(enabled: Boolean) {
        _settings.value = _settings.value.copy(micDefaultOn = enabled)
    }

    override suspend fun setLastRoom(roomId: String?, role: String?) {
        _settings.value = _settings.value.copy(lastRoomId = roomId, lastRole = role)
    }

    fun update(settings: AppSettings) {
        _settings.value = settings
    }
}

class InMemoryTtsCursorStore : TtsCursorStore {
    private val sequences = mutableMapOf<String, Long>()
    var purgeCount: Int = 0
        private set

    override suspend fun lastSpokenSequence(sessionId: String): Long = sequences[sessionId] ?: 0

    override suspend fun markSpoken(sessionId: String, sequence: Long) {
        sequences[sessionId] = maxOf(sequences[sessionId] ?: 0, sequence)
    }

    override suspend fun purge() {
        purgeCount++
        sequences.clear()
    }

    fun sequenceFor(sessionId: String): Long = sequences[sessionId] ?: 0
}

/**
 * Recording TTS boundary. In auto mode utterances complete immediately (optionally after a
 * delay). In manual mode [completeNext] drives each utterance, which lets tests prove strict
 * FIFO and non-overlap.
 */
class RecordingAnnouncer(
    availableInitially: Boolean = true,
) : Announcer {
    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _available = MutableStateFlow(availableInitially)
    override val available: StateFlow<Boolean> = _available.asStateFlow()

    private val _errors = MutableSharedFlow<AppError>(extraBufferCapacity = 8)
    override val errors: SharedFlow<AppError> = _errors.asSharedFlow()

    val spoken = mutableListOf<SpeechRequest>()
    var autoComplete: Boolean = true
    var speakDelayMs: Long = 0
    var pauseCount: Int = 0
        private set
    var resumeCount: Int = 0
        private set
    var stopCount: Int = 0
        private set
    var releaseCount: Int = 0
        private set
    var maxConcurrent: Int = 0
        private set

    private var concurrent = 0
    private val pending = ArrayDeque<CompletableDeferred<SpeechResult>>()

    override suspend fun speak(request: SpeechRequest): SpeechResult {
        spoken += request
        concurrent++
        maxConcurrent = maxOf(maxConcurrent, concurrent)
        _speaking.value = true
        try {
            if (autoComplete) {
                if (speakDelayMs > 0) delay(speakDelayMs)
                return SpeechResult.Completed
            }
            val deferred = CompletableDeferred<SpeechResult>()
            pending.addLast(deferred)
            return deferred.await()
        } finally {
            concurrent--
            if (concurrent == 0) _speaking.value = false
        }
    }

    fun completeNext(result: SpeechResult = SpeechResult.Completed) {
        pending.removeFirstOrNull()?.complete(result)
    }

    fun failNext(error: AppError) {
        pending.removeFirstOrNull()?.complete(SpeechResult.Failed(error))
    }

    override fun pause() {
        pauseCount++
        _speaking.value = false
    }

    override fun resume() {
        resumeCount++
        _speaking.value = pending.isNotEmpty()
    }

    override fun stop() {
        stopCount++
        while (pending.isNotEmpty()) pending.removeFirst().complete(SpeechResult.Stopped)
        _speaking.value = false
    }

    override fun release() {
        releaseCount++
        stop()
    }

    fun setAvailable(available: Boolean) {
        _available.value = available
    }
}
