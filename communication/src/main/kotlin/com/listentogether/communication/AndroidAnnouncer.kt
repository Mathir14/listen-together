package com.listentogether.communication

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.listentogether.core.common.AppError
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android [Announcer] over TextToSpeech. Pause stops the engine but retains the pending
 * request; resume restarts that utterance. All callbacks are marshalled through a lock because
 * UtteranceProgressListener is invoked on a binder thread.
 */
class AndroidAnnouncer(
    context: Context,
    private val enginePackage: String? = null,
) : Announcer {
    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _available = MutableStateFlow(false)
    override val available: StateFlow<Boolean> = _available.asStateFlow()

    private val _errors = MutableSharedFlow<AppError>(extraBufferCapacity = 4)
    override val errors: SharedFlow<AppError> = _errors.asSharedFlow()

    private val lock = Any()
    private var tts: TextToSpeech? = null
    private var initialized = false
    private var released = false
    private var paused = false
    private var current: PendingUtterance? = null

    private class PendingUtterance(
        val request: SpeechRequest,
        val utteranceId: String,
        val result: CompletableDeferred<SpeechResult>,
    )

    init {
        tts = if (enginePackage == null) {
            TextToSpeech(context.applicationContext) { status -> onInit(status) }
        } else {
            TextToSpeech(context.applicationContext, { status -> onInit(status) }, enginePackage)
        }
    }

    private fun onInit(status: Int) {
        synchronized(lock) {
            if (released) return
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                initialized = false
                _available.value = false
                _errors.tryEmit(AppError("TTS_UNAVAILABLE", "Text-to-speech is not available"))
                return
            }
            val language = engine.setLanguage(Locale.getDefault())
            if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                initialized = false
                _available.value = false
                _errors.tryEmit(AppError("TTS_LANGUAGE_UNAVAILABLE", "Text-to-speech language is not installed"))
                return
            }
            engine.setOnUtteranceProgressListener(progressListener)
            initialized = true
            _available.value = true
        }
    }

    override suspend fun speak(request: SpeechRequest): SpeechResult {
        val pending = synchronized(lock) {
            if (released || !initialized) {
                return SpeechResult.Failed(AppError("TTS_UNAVAILABLE", "Text-to-speech is not available"))
            }
            current?.let { previous ->
                previous.result.complete(SpeechResult.Stopped)
                tts?.stop()
            }
            paused = false
            PendingUtterance(request, "utt-${UUID.randomUUID()}", CompletableDeferred()).also {
                current = it
                _speaking.value = true
                startLocked(it)
            }
        }
        return pending.result.await()
    }

    private fun startLocked(pending: PendingUtterance) {
        val engine = tts ?: return
        engine.setSpeechRate(pending.request.speechRate.coerceIn(0.5f, 2.5f))
        val status = engine.speak(
            pending.request.text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            pending.utteranceId,
        )
        if (status == TextToSpeech.ERROR) {
            finishLocked(pending, SpeechResult.Failed(AppError("TTS_FAILED", "Text-to-speech failed")))
        }
    }

    override fun pause() {
        synchronized(lock) {
            if (current == null || paused) return
            paused = true
            _speaking.value = false
            tts?.stop()
        }
    }

    override fun resume() {
        synchronized(lock) {
            val pending = current ?: return
            if (!paused) return
            paused = false
            _speaking.value = true
            startLocked(pending)
        }
    }

    override fun stop() {
        synchronized(lock) {
            val pending = current ?: return
            tts?.stop()
            finishLocked(pending, SpeechResult.Stopped)
        }
    }

    override fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            current?.let { pending ->
                current = null
                pending.result.complete(SpeechResult.Stopped)
            }
            _speaking.value = false
            _available.value = false
            tts?.stop()
            tts?.shutdown()
            tts = null
        }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            synchronized(lock) {
                val pending = current ?: return
                if (pending.utteranceId != utteranceId) return
                finishLocked(pending, SpeechResult.Completed)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            onError(utteranceId, TextToSpeech.ERROR)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            synchronized(lock) {
                val pending = current ?: return
                if (pending.utteranceId != utteranceId) return
                finishLocked(
                    pending,
                    SpeechResult.Failed(AppError("TTS_FAILED", "Text-to-speech failed ($errorCode)")),
                )
            }
        }
    }

    private fun finishLocked(pending: PendingUtterance, result: SpeechResult) {
        if (current !== pending) return
        current = null
        paused = false
        _speaking.value = false
        if (result is SpeechResult.Failed) _errors.tryEmit(result.error)
        pending.result.complete(result)
    }
}
