package com.listentogether.core.common

import kotlinx.serialization.Serializable

/**
 * Error codes shared by transport, sync and UI. Wire-level codes are declared in :protocol and
 * mapped into this type at the boundary.
 */
@Serializable
data class AppError(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
) {
    companion object {
        fun network(message: String = "Network unavailable") = AppError("NETWORK", message, true)
        fun notMember() = AppError("NOT_MEMBER", "You are not a member of this room")
        fun sessionMismatch() = AppError("SESSION_MISMATCH", "The session has ended", true)
        fun protocolMismatch() = AppError("PROTOCOL_MISMATCH", "Update required", false)
        fun invalid(message: String) = AppError("INVALID_PAYLOAD", message, false)
        fun rateLimited() = AppError("RATE_LIMITED", "Too many requests", true)
        fun slowConsumer() = AppError("SLOW_CONSUMER", "Connection is too slow", true)
    }
}

sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>

    fun getOrNull(): T? = when (this) {
        is Success -> value
        is Failure -> null
    }

    fun errorOrNull(): AppError? = when (this) {
        is Success -> null
        is Failure -> error
    }
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(value))
    is AppResult.Failure -> this
}

inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) action(value)
    return this
}

inline fun <T> AppResult<T>.onFailure(action: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) action(error)
    return this
}
