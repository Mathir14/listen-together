package com.listentogether.service

/**
 * Process-scoped holder allowing [SessionService] and UI components to access the active
 * [SessionRuntime] instantiated by the Application composition root.
 */
object SessionRuntimeHolder {
    @Volatile
    private var runtime: SessionRuntime? = null

    fun set(runtime: SessionRuntime?) {
        this.runtime = runtime
    }

    fun get(): SessionRuntime? = runtime

    fun require(): SessionRuntime =
        runtime ?: throw IllegalStateException("SessionRuntime has not been initialized")
}
