package com.listentogether.communication

import com.listentogether.core.common.AppError
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** One utterance handed to the platform TTS engine. Text must never be logged. */
data class SpeechRequest(
    val messageId: String,
    val sessionId: String,
    val sequence: Long,
    val text: String,
    val speechRate: Float = 1f,
)

sealed interface SpeechResult {
    data object Completed : SpeechResult
    data object Stopped : SpeechResult
    data class Failed(val error: AppError) : SpeechResult
}

/**
 * Boundary around the Android TTS engine (architecture §10/§17). Only one [speak] is active at a
 * time; pause stops playback but keeps the utterance pending so [resume] can restart it.
 */
interface Announcer {
    val speaking: StateFlow<Boolean>
    val available: StateFlow<Boolean>

    /** Emits once per distinct engine failure; callers surface it without logging the text. */
    val errors: SharedFlow<AppError>

    suspend fun speak(request: SpeechRequest): SpeechResult
    fun pause()
    fun resume()
    fun stop()
    fun release()
}
